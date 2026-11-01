package com.potrikaghor.data

import android.util.Base64
import android.util.Log
import com.potrikaghor.model.Coverage
import com.potrikaghor.model.NewsItem
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

data class DirectRssFeedSpec(
    val sourceBn: String,
    val sourceEn: String,
    val feedUrl: String,
    val baseUrl: String,
    val isIntl: Boolean = false,
    val defaultCategory: String = "all"
)

data class ImagePipelineDiagnostic(
    val title: String,
    val source: String,
    val originalArticleUrl: String,
    val rssImageUrl: String?,
    val extractedImageUrl: String?,
    val resolvedPublisherUrl: String?,
    val isHttp200Valid: Boolean,
    val httpStatusDetail: String,
    val loadStatus: String
)

class NewsRepository {
    companion object {
        const val DIAG_TAG = "PotrikaImageDiag"
        const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        const val IMAGE_ACCEPT_HEADER = "image/webp,image/png,image/jpeg,image/*;q=0.8,*/*;q=0.5"

        fun logDiag(message: String) {
            try {
                Log.d(DIAG_TAG, message)
            } catch (_: Throwable) {
                println("[$DIAG_TAG] $message")
            }
        }

        fun logArticleImagePipeline(diag: ImagePipelineDiagnostic) {
            val sb = StringBuilder()
            sb.append("ARTICLE_IMAGE_DIAG | ")
            sb.append("title=\"${diag.title.take(65)}\" | ")
            sb.append("source=\"${diag.source}\" | ")
            sb.append("originalUrl=\"${diag.originalArticleUrl}\" | ")
            sb.append("rssImageUrl=\"${diag.rssImageUrl ?: "NONE"}\" | ")
            sb.append("extractedImageUrl=\"${diag.extractedImageUrl ?: "NONE"}\" | ")
            sb.append("resolvedPublisherUrl=\"${diag.resolvedPublisherUrl ?: "NONE"}\" | ")
            sb.append("http200Valid=${diag.isHttp200Valid} (${diag.httpStatusDetail}) | ")
            sb.append("loadStatus=\"${diag.loadStatus}\"")
            logDiag(sb.toString())
        }

        /**
         * Curated direct publisher RSS/Atom feeds.
         * Direct feeds with embedded images (<media:content>, <media:thumbnail>, <enclosure>, <img>)
         * are prioritized first so cards receive verified publisher image URLs directly from RSS XML.
         */
        val DIRECT_PUBLISHER_FEEDS = listOf(
            // Bangla / Domestic feeds with verified embedded RSS images
            DirectRssFeedSpec(
                sourceBn = "প্রথম আলো",
                sourceEn = "Prothom Alo",
                feedUrl = "https://www.prothomalo.com/feed/",
                baseUrl = "https://www.prothomalo.com",
                isIntl = false
            ),
            DirectRssFeedSpec(
                sourceBn = "বাংলা ট্রিবিউন",
                sourceEn = "Bangla Tribune",
                feedUrl = "https://www.banglatribune.com/feed/",
                baseUrl = "https://www.banglatribune.com",
                isIntl = false
            ),
            DirectRssFeedSpec(
                sourceBn = "দেশ রূপান্তর",
                sourceEn = "Desh Rupantor",
                feedUrl = "https://www.deshrupantor.com/feed/",
                baseUrl = "https://www.deshrupantor.com",
                isIntl = false
            ),
            DirectRssFeedSpec(
                sourceBn = "আজকের পত্রিকা",
                sourceEn = "Ajker Patrika",
                feedUrl = "https://www.ajkerpatrika.com/feed",
                baseUrl = "https://www.ajkerpatrika.com",
                isIntl = false
            ),
            DirectRssFeedSpec(
                sourceBn = "দৈনিক আমার দেশ",
                sourceEn = "Dainik Amar Desh",
                feedUrl = "https://dailyamardesh.com/feed",
                baseUrl = "https://dailyamardesh.com",
                isIntl = false
            ),
            DirectRssFeedSpec(
                sourceBn = "সারাবাংলা",
                sourceEn = "Sarabangla",
                feedUrl = "https://sarabangla.net/feed/",
                baseUrl = "https://sarabangla.net",
                isIntl = false
            ),
            DirectRssFeedSpec(
                sourceBn = "বিবিসি বাংলা",
                sourceEn = "BBC Bangla",
                feedUrl = "https://feeds.bbci.co.uk/bengali/rss.xml",
                baseUrl = "https://www.bbc.com/bengali",
                isIntl = false
            ),
            DirectRssFeedSpec(
                sourceBn = "এবিপি আনন্দ",
                sourceEn = "ABP Ananda",
                feedUrl = "https://bengali.abplive.com/home/feed",
                baseUrl = "https://bengali.abplive.com",
                isIntl = false
            ),
            DirectRssFeedSpec(
                sourceBn = "টিভি৯ বাংলা",
                sourceEn = "TV9 Bangla",
                feedUrl = "https://tv9bangla.com/feed",
                baseUrl = "https://tv9bangla.com",
                isIntl = false
            ),
            DirectRssFeedSpec(
                sourceBn = "দ্য ডেইলি স্টার",
                sourceEn = "The Daily Star",
                feedUrl = "https://www.thedailystar.net/rss.xml",
                baseUrl = "https://www.thedailystar.net",
                isIntl = false
            ),
            // Additional Bangla feeds (text RSS; OG image available for Channel i / DW Bangla)
            DirectRssFeedSpec(
                sourceBn = "ডিডব্লিউ বাংলা",
                sourceEn = "DW Bangla",
                feedUrl = "https://rss.dw.com/xml/rss-ben-all",
                baseUrl = "https://www.dw.com/bn",
                isIntl = false
            ),
            DirectRssFeedSpec(
                sourceBn = "চ্যানেল আই",
                sourceEn = "Channel i",
                feedUrl = "https://www.channelionline.com/feed/",
                baseUrl = "https://www.channelionline.com",
                isIntl = false
            ),
            DirectRssFeedSpec(
                sourceBn = "জাগো নিউজ ২৪",
                sourceEn = "Jago News 24",
                feedUrl = "https://www.jagonews24.com/rss/rss.xml",
                baseUrl = "https://www.jagonews24.com",
                isIntl = false
            ),
            // International feeds with verified embedded RSS images
            DirectRssFeedSpec(
                sourceBn = "বিবিসি নিউজ",
                sourceEn = "BBC News",
                feedUrl = "https://feeds.bbci.co.uk/news/rss.xml",
                baseUrl = "https://www.bbc.com/news",
                isIntl = true
            ),
            DirectRssFeedSpec(
                sourceBn = "দ্য গার্ডিয়ান",
                sourceEn = "The Guardian",
                feedUrl = "https://www.theguardian.com/world/rss",
                baseUrl = "https://www.theguardian.com",
                isIntl = true
            ),
            DirectRssFeedSpec(
                sourceBn = "দ্য নিউ ইয়র্ক টাইমস",
                sourceEn = "The New York Times",
                feedUrl = "https://rss.nytimes.com/services/xml/rss/nyt/World.xml",
                baseUrl = "https://www.nytimes.com",
                isIntl = true
            ),
            DirectRssFeedSpec(
                sourceBn = "এনডিটিভি",
                sourceEn = "NDTV",
                feedUrl = "https://feeds.feedburner.com/ndtvnews-top-stories",
                baseUrl = "https://www.ndtv.com",
                isIntl = true
            ),
            DirectRssFeedSpec(
                sourceBn = "টাইমস অব ইন্ডিয়া",
                sourceEn = "The Times of India",
                feedUrl = "https://timesofindia.indiatimes.com/rssfeedstopstories.cms",
                baseUrl = "https://timesofindia.indiatimes.com",
                isIntl = true
            ),
            DirectRssFeedSpec(
                sourceBn = "ইএসপিএন ক্রিকইনফো",
                sourceEn = "ESPNcricinfo",
                feedUrl = "https://www.espncricinfo.com/rss/content/story/feeds/0.xml",
                baseUrl = "https://www.espncricinfo.com",
                isIntl = true,
                defaultCategory = "sports"
            ),
            DirectRssFeedSpec(
                sourceBn = "দ্য ভার্জ",
                sourceEn = "The Verge",
                feedUrl = "https://www.theverge.com/rss/index.xml",
                baseUrl = "https://www.theverge.com",
                isIntl = true,
                defaultCategory = "tech"
            ),
            DirectRssFeedSpec(
                sourceBn = "আল জাজিরা",
                sourceEn = "Al Jazeera",
                feedUrl = "https://www.aljazeera.com/xml/rss/all.xml",
                baseUrl = "https://www.aljazeera.com",
                isIntl = true
            )
        )
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(14, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val fastFeedClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    val imageHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(9, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .addInterceptor { chain ->
            val orig = chain.request()
            val req = orig.newBuilder()
                .header("User-Agent", BROWSER_USER_AGENT)
                .header("Accept", IMAGE_ACCEPT_HEADER)
                .build()
            chain.proceed(req)
        }
        .build()

    // Thread-safe caches for resolved article images and HTTP 200 verification
    private val articleImageCache = ConcurrentHashMap<String, String>()
    private val failedImageCache = ConcurrentHashMap.newKeySet<String>()
    private val verifiedHttp200Cache = ConcurrentHashMap<String, Pair<Boolean, String>>()
    private val inFlightLookups = ConcurrentHashMap<String, Deferred<String?>>()
    private val imageSemaphore = Semaphore(6)

    // In-memory cache for direct publisher RSS feeds (TTL: 3 minutes)
    @Volatile
    private var cachedDirectDomesticItems: List<NewsItem> = emptyList()
    @Volatile
    private var cachedDirectDomesticTimestamp: Long = 0L

    @Volatile
    private var cachedDirectIntlItems: List<NewsItem> = emptyList()
    @Volatile
    private var cachedDirectIntlTimestamp: Long = 0L

    private val directFeedCacheTtlMs = 3 * 60 * 1000L

    private fun parseDate(str: String): Long? {
        val trimmed = str.trim()
        if (trimmed.isEmpty()) return null

        try {
            return ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME)
                .toInstant()
                .toEpochMilli()
        } catch (_: Exception) {}

        try {
            return Instant.parse(trimmed).toEpochMilli()
        } catch (_: Exception) {}

        try {
            return ZonedDateTime.parse(trimmed, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                .toInstant()
                .toEpochMilli()
        } catch (_: Exception) {}

        val patterns = listOf(
            "EEE, dd MMM yyyy HH:mm:ss Z",
            "EEE, d MMM yyyy HH:mm:ss Z",
            "EEE, dd MMM yyyy HH:mm:ss z",
            "EEE, dd MMM yy HH:mm:ss Z",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'"
        )
        for (p in patterns) {
            try {
                val sdf = SimpleDateFormat(p, Locale.ENGLISH).apply {
                    timeZone = TimeZone.getTimeZone("GMT")
                }
                val d = sdf.parse(trimmed)
                if (d != null) return d.time
            } catch (_: Exception) {}
        }
        return null
    }

    fun getCachedArticleImage(item: NewsItem, fallbackItems: List<NewsItem> = emptyList()): String? {
        if (!item.imageUrl.isNullOrBlank() && isValidArticleImageUrl(item.imageUrl, item.link)) {
            return item.imageUrl
        }
        articleImageCache[cacheKeyFor(item)]?.let { return it }
        for (alt in fallbackItems) {
            if (!alt.imageUrl.isNullOrBlank() && isValidArticleImageUrl(alt.imageUrl, alt.link)) {
                return alt.imageUrl
            }
            articleImageCache[cacheKeyFor(alt)]?.let { return it }
        }
        return null
    }

    fun isLookupFailed(item: NewsItem, fallbackItems: List<NewsItem> = emptyList()): Boolean {
        if (!failedImageCache.contains(cacheKeyFor(item))) return false
        return fallbackItems.all { failedImageCache.contains(cacheKeyFor(it)) }
    }

    private fun cacheKeyFor(item: NewsItem): String {
        return item.link.ifBlank { "${item.source}:${item.title}" }
    }

    suspend fun fetchNews(
        query: String = "",
        intl: Boolean = false,
        section: String = ""
    ): List<NewsItem> = withContext(Dispatchers.IO) {
        val q = query.trim()
        val isIntlQuery = intl || q == "আন্তর্জাতিক"

        coroutineScope {
            // 1. Fetch direct publisher RSS feeds (which embed real <media:content>, <media:thumbnail>, <enclosure>, <img>)
            val directDeferred = async {
                fetchDirectPublisherFeeds(isIntl = isIntlQuery)
            }

            // 2. Fetch Google News RSS feed for broad multi-newspaper aggregation
            val googleDeferred = async {
                try {
                    fetchGoogleNewsRss(query = q, intl = intl, section = section)
                } catch (e: Exception) {
                    logDiag("Google News RSS fetch error for query='$q': ${e.message}")
                    emptyList()
                }
            }

            val directItems = directDeferred.await()
            val googleItems = googleDeferred.await()

            // Filter direct items by category/search query if one was specified
            val filteredDirect = filterDirectItemsByQuery(directItems, q, section, isIntlQuery)

            // Cross-match Google News RSS items with direct publisher RSS items by headline similarity
            // so Google News items covering the same event inherit verified RSS image URLs!
            val enrichedGoogleItems = enrichGoogleItemsWithDirectImages(googleItems, directItems)

            val combined = mergeDirectAndGoogleItems(filteredDirect, enrichedGoogleItems)
            if (combined.isEmpty() && googleItems.isEmpty() && directItems.isEmpty()) {
                throw Exception("No news items fetched")
            }

            val finalItems = if (combined.isNotEmpty()) combined else directItems
            logFeedSummaryDiagnostics(q, finalItems)
            finalItems
        }
    }

    private suspend fun fetchDirectPublisherFeeds(isIntl: Boolean): List<NewsItem> = coroutineScope {
        val now = System.currentTimeMillis()
        if (isIntl) {
            if (cachedDirectIntlItems.isNotEmpty() && (now - cachedDirectIntlTimestamp) < directFeedCacheTtlMs) {
                return@coroutineScope cachedDirectIntlItems
            }
        } else {
            if (cachedDirectDomesticItems.isNotEmpty() && (now - cachedDirectDomesticTimestamp) < directFeedCacheTtlMs) {
                return@coroutineScope cachedDirectDomesticItems
            }
        }

        val feedsToFetch = if (isIntl) {
            DIRECT_PUBLISHER_FEEDS.filter { it.isIntl || it.sourceBn == "বিবিসি বাংলা" || it.sourceBn == "ডিডব্লিউ বাংলা" }
        } else {
            DIRECT_PUBLISHER_FEEDS.filter { !it.isIntl }
        }

        val deferreds = feedsToFetch.map { spec ->
            async(Dispatchers.IO) {
                fetchSingleDirectFeed(spec)
            }
        }

        val fetched = deferreds.awaitAll().flatten()
        if (fetched.isNotEmpty()) {
            if (isIntl) {
                cachedDirectIntlItems = fetched
                cachedDirectIntlTimestamp = now
            } else {
                cachedDirectDomesticItems = fetched
                cachedDirectDomesticTimestamp = now
            }
        }
        fetched
    }

    private fun fetchSingleDirectFeed(spec: DirectRssFeedSpec): List<NewsItem> {
        val req = Request.Builder()
            .url(spec.feedUrl)
            .header("User-Agent", BROWSER_USER_AGENT)
            .header("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml, */*")
            .build()

        return try {
            fastFeedClient.newCall(req).execute().use { res ->
                if (!res.isSuccessful) {
                    logDiag("DIRECT_FEED_FAIL | source=\"${spec.sourceBn}\" | url=\"${spec.feedUrl}\" | HTTP ${res.code}")
                    return emptyList()
                }
                val xml = readBoundedBody(res, 350_000)
                val parsed = parseXml(
                    xml = xml,
                    defaultSource = spec.sourceBn,
                    defaultSourceUrl = spec.baseUrl,
                    defaultCategoryId = spec.defaultCategory
                )
                val withImgCount = parsed.count { !it.imageUrl.isNullOrBlank() }
                logDiag(
                    "DIRECT_FEED_OK | source=\"${spec.sourceBn}\" | totalItems=${parsed.size} | " +
                        "itemsWithRssImage=$withImgCount | sampleImage=\"${parsed.firstOrNull { !it.imageUrl.isNullOrBlank() }?.imageUrl ?: "NONE"}\""
                )
                parsed
            }
        } catch (e: Exception) {
            logDiag("DIRECT_FEED_ERROR | source=\"${spec.sourceBn}\" | url=\"${spec.feedUrl}\" | error=\"${e.message}\"")
            emptyList()
        }
    }

    private fun fetchGoogleNewsRss(
        query: String,
        intl: Boolean,
        section: String
    ): List<NewsItem> {
        val q = query.trim()
        val useEnglishEdition = intl && (q.isEmpty() || q.none { it in '\u0980'..'\u09FF' })
        val editionHl = if (useEnglishEdition) "en-US" else "bn"
        val editionGl = if (useEnglishEdition) "US" else "BD"
        val editionCeid = if (useEnglishEdition) "US:en" else "BD:bn"

        val urlBuilder = if (intl && section.isNotEmpty()) {
            "https://news.google.com/rss/headlines/section/topic/$section".toHttpUrl().newBuilder()
        } else if (q.isEmpty()) {
            "https://news.google.com/rss".toHttpUrl().newBuilder()
        } else {
            "https://news.google.com/rss/search".toHttpUrl().newBuilder().addQueryParameter("q", q)
        }

        urlBuilder.addQueryParameter("hl", editionHl)
        urlBuilder.addQueryParameter("gl", editionGl)
        urlBuilder.addQueryParameter("ceid", editionCeid)

        val request = Request.Builder()
            .url(urlBuilder.build())
            .header("User-Agent", BROWSER_USER_AGENT)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
            val body = response.body?.string() ?: ""
            return parseXml(body)
        }
    }

    private fun filterDirectItemsByQuery(
        directItems: List<NewsItem>,
        query: String,
        section: String,
        isIntl: Boolean
    ): List<NewsItem> {
        val q = query.trim()
        if (q.isEmpty() && section.isEmpty()) {
            return directItems
        }
        if (q == "বাংলাদেশ" && !isIntl) {
            return directItems
        }
        if (q == "আন্তর্জাতিক" && isIntl) {
            return directItems
        }

        val keywords = expandedQueryKeywords(q, section)
        if (keywords.isEmpty()) return directItems

        val matched = directItems.filter { item ->
            val haystack = "${item.title} ${item.snippet} ${item.categoryId}".lowercase(Locale.ROOT)
            keywords.any { kw -> haystack.contains(kw.lowercase(Locale.ROOT)) }
        }

        // If a specific custom search query matched items, return them; if it's a broad home category
        // with few keyword hits, still include matched items.
        return matched
    }

    private fun expandedQueryKeywords(query: String, section: String): List<String> {
        val key = (query.ifEmpty { section }).lowercase(Locale.ROOT)
        return when {
            key.contains("রাজনীতি") -> listOf("রাজনীতি", "দল", "নির্বাচন", "সরকার", "বিএনপি", "আওয়ামী", "জামায়াত", "সংসদ", "উপদেষ্টা", "মন্ত্রী", "সমাবেশ")
            key.contains("খেলা") || key.contains("ক্রিকেট") || key == "sports" ->
                listOf("খেলা", "ক্রিকেট", "ফুটবল", "ম্যাচ", "গোল", "রানের", "উইকেট", "বিসিবি", "ফিফা", "লিগ", "টেস্ট", "ওয়ানডে", "টি-টোয়েন্টি", "sport", "cricket", "football")
            key.contains("অর্থনীতি") || key.contains("ব্যবসা") || key == "business" ->
                listOf("অর্থনীতি", "ব্যাংক", "শেয়ার", "বাজার", "টাকা", "ডলার", "রপ্তানি", "আমদানি", "মূল্যস্ফীতি", "বাণিজ্য", "শিল্প", "গার্মেন্টস", "পোশাক", "কর", "ঋণ", "business", "economy", "market")
            key.contains("প্রযুক্তি") || key == "technology" ->
                listOf("প্রযুক্তি", "এআই", "ইন্টারনেট", "স্মার্টফোন", "গুগল", "অ্যাপল", "মেটা", "সাইবার", "ডিজিটাল", "প্রযুক্তির", "সফটওয়্যার", "tech", "ai", "apple", "google", "software")
            key.contains("বিনোদন") || key == "entertainment" ->
                listOf("বিনোদন", "সিনেমা", "চলচ্চিত্র", "নাটক", "গান", "অভিনেতা", "অভিনেত্রী", "বলিউড", "হলিউড", "ঢালিউড", "তারকা", "শাকিব", "গায়ক")
            key.contains("বিজ্ঞান") || key == "science" ->
                listOf("বিজ্ঞান", "গবেষণা", "মহাকাশ", "নাসা", "জলবায়ু", "উদ্ভাবন", "চাঁদ", "গ্রহ", "বিজ্ঞানী", "science", "space", "climate")
            key.contains("চাকরি") ->
                listOf("চাকরি", "নিয়োগ", "বিসিএস", "পরীক্ষা", "পিএসসি", "পদ", "বিজ্ঞপ্তি", "কর্মসংস্থান")
            key.contains("স্বাস্থ্য") || key == "health" ->
                listOf("স্বাস্থ্য", "হাসপাতাল", "চিকিৎসা", "রোগ", "ডেঙ্গু", "ওষুধ", "চিকিৎসক", "ডাক্তার", "স্বাস্থ্যসেবা", "health", "medical")
            key.contains("শিক্ষা") ->
                listOf("শিক্ষা", "স্কুল", "কলেজ", "বিশ্ববিদ্যালয়", "শিক্ষার্থী", "পরীক্ষা", "এইচএসসি", "এসএসসি", "ভর্তি", "শিক্ষক", "ক্যাম্পাস")
            key.contains("আবহাওয়া") ->
                listOf("আবহাওয়া", "বৃষ্টি", "ঘূর্ণিঝড়", "তাপমাত্রা", "গরম", "শীত", "বন্যা", "ঝড়", "লঘুচাপ")
            key.contains("আন্তর্জাতিক") || key == "world" ->
                listOf("বিশ্ব", "আন্তর্জাতিক", "ভারত", "যুক্তরাষ্ট্র", "ট্রাম্প", "রাশিয়া", "ইউক্রেন", "ইসরায়েল", "গাজা", "ইরান", "চীন", "পাকিস্তান", "যুক্তরাজ্য", "ইউরোপ")
            else -> query.split(Regex("\\s+")).filter { it.length >= 2 }
        }
    }

    /**
     * Enriches Google News RSS items with verified RSS image URLs when the same news story
     * is present in any direct publisher RSS feed.
     */
    private fun enrichGoogleItemsWithDirectImages(
        googleItems: List<NewsItem>,
        directItems: List<NewsItem>
    ): List<NewsItem> {
        val directWithImages = directItems.filter { !it.imageUrl.isNullOrBlank() }
        if (directWithImages.isEmpty() || googleItems.isEmpty()) return googleItems

        val tokenizedDirect = directWithImages.map { it to tokens(it.title) }

        return googleItems.map { gItem ->
            val gTokens = tokens(gItem.title)
            var matchedImage: String? = gItem.imageUrl

            if (matchedImage.isNullOrBlank() && gTokens.size >= 3) {
                for ((dItem, dTokens) in tokenizedDirect) {
                    if (dTokens.size >= 3 && jaccard(gTokens, dTokens) >= 0.32) {
                        matchedImage = dItem.imageUrl
                        break
                    }
                }
            }

            val enrichedRelated = gItem.related.map { rel ->
                if (!rel.imageUrl.isNullOrBlank()) {
                    rel
                } else {
                    val rTokens = tokens(rel.title)
                    val relMatch = if (rTokens.size >= 3) {
                        tokenizedDirect.firstOrNull { (_, dTokens) ->
                            dTokens.size >= 3 && jaccard(rTokens, dTokens) >= 0.32
                        }?.first?.imageUrl
                    } else null
                    if (relMatch != null) {
                        articleImageCache[cacheKeyFor(rel)] = relMatch
                        rel.copy(imageUrl = relMatch)
                    } else {
                        rel
                    }
                }
            }

            if (matchedImage == null) {
                matchedImage = enrichedRelated.firstOrNull { !it.imageUrl.isNullOrBlank() }?.imageUrl
            }

            if (!matchedImage.isNullOrBlank()) {
                articleImageCache[cacheKeyFor(gItem)] = matchedImage
                if (gItem.link.isNotBlank()) {
                    articleImageCache[gItem.link] = matchedImage
                }
                gItem.copy(imageUrl = matchedImage, related = enrichedRelated)
            } else {
                gItem.copy(related = enrichedRelated)
            }
        }
    }

    /**
     * Merges direct publisher RSS items and Google News RSS items:
     * - Interleaves across different newspapers so one newspaper doesn't monopolize the top list.
     * - Prioritizes items that have a valid RSS-provided image URL so the top briefing and smart feed
     *   cards display real article thumbnails immediately.
     */
    private fun mergeDirectAndGoogleItems(
        directItems: List<NewsItem>,
        googleItems: List<NewsItem>
    ): List<NewsItem> {
        // Group direct items by source and interleave them round-robin by recency so all sources appear evenly
        val directBySource = directItems
            .sortedByDescending { it.time ?: 0L }
            .groupBy { it.source }
            .values
            .map { it.iterator() }

        val interleavedDirect = mutableListOf<NewsItem>()
        var anyAdded = true
        while (anyAdded) {
            anyAdded = false
            for (iter in directBySource) {
                if (iter.hasNext()) {
                    interleavedDirect.add(iter.next())
                    anyAdded = true
                }
            }
        }

        val allCombined = interleavedDirect + googleItems
        val seenLinks = mutableSetOf<String>()
        val deduplicated = mutableListOf<NewsItem>()

        for (item in allCombined) {
            val normLink = item.link.trim()
            if (normLink.isEmpty() || !seenLinks.add(normLink)) continue
            deduplicated.add(item)
        }

        val withRssImage = deduplicated.filter { !it.imageUrl.isNullOrBlank() }
        val withoutRssImage = deduplicated.filter { it.imageUrl.isNullOrBlank() }

        return withRssImage + withoutRssImage
    }

    private fun logFeedSummaryDiagnostics(query: String, items: List<NewsItem>) {
        val total = items.size
        val withImage = items.count { !it.imageUrl.isNullOrBlank() }
        val bySourceWithImg = items.filter { !it.imageUrl.isNullOrBlank() }
            .groupingBy { it.source }
            .eachCount()
        val bySourceNoImg = items.filter { it.imageUrl.isNullOrBlank() }
            .groupingBy { it.source }
            .eachCount()

        logDiag(
            "FEED_SUMMARY | query=\"$query\" | total=$total | withDirectRssImage=$withImage | " +
                "sourcesWithImages=$bySourceWithImg | sourcesWithoutRssImages=$bySourceNoImg"
        )
    }

    /**
     * Parses both RSS 2.0 (<item>) and Atom (<entry>) XML feeds in a namespace-tolerant manner.
     */
    fun parseXml(
        xml: String,
        defaultSource: String = "",
        defaultSourceUrl: String = "",
        defaultCategoryId: String = "all"
    ): List<NewsItem> {
        val out = mutableListOf<NewsItem>()
        val entryPattern = Pattern.compile("<(?:item|entry)(?:\\s[^>]*)?>(.*?)</(?:item|entry)>", Pattern.DOTALL or Pattern.CASE_INSENSITIVE)
        val matcher = entryPattern.matcher(xml)

        while (matcher.find()) {
            val block = matcher.group(1) ?: continue
            var title = stripTags(extractTagNamespaceAgnostic(block, "title")).trim()
            val link = extractItemLink(block, defaultSourceUrl)
            val pub = extractTagNamespaceAgnostic(block, "pubDate")
                .ifEmpty { extractTagNamespaceAgnostic(block, "published") }
                .ifEmpty { extractTagNamespaceAgnostic(block, "updated") }
                .ifEmpty { extractTagNamespaceAgnostic(block, "date") }

            val sourcePattern = Pattern.compile("<source[^>]*url=[\"']([^\"']*)[\"'][^>]*>(.*?)</source>", Pattern.DOTALL or Pattern.CASE_INSENSITIVE)
            val sm = sourcePattern.matcher(block)
            val sourceUrl: String
            val source: String
            if (sm.find()) {
                sourceUrl = sm.group(1)?.trim().orEmpty().ifEmpty { defaultSourceUrl }
                source = unescape(stripTags(sm.group(2) ?: "")).ifEmpty { defaultSource }
            } else {
                sourceUrl = defaultSourceUrl
                source = defaultSource
            }

            val suffix = " - $source"
            if (source.isNotEmpty() && title.endsWith(suffix)) {
                title = title.substring(0, title.length - suffix.length).trim()
            }

            if (title.isEmpty() || link.isEmpty()) continue

            val desc = extractTagNamespaceAgnostic(block, "description")
                .ifEmpty { extractTagNamespaceAgnostic(block, "summary") }
            val contentEncoded = extractTagNamespaceAgnostic(block, "encoded")
                .ifEmpty { extractTagNamespaceAgnostic(block, "content") }

            val categories = extractCategories(block)
            val categoryText = if (categories.isNotEmpty()) categories.joinToString(" ") else defaultCategoryId
            val plainSnippet = stripTags(desc).take(220).trim()

            val effectiveBaseUrl = when {
                !link.contains("news.google.com") && link.startsWith("http") -> link
                sourceUrl.isNotBlank() -> sourceUrl
                else -> defaultSourceUrl
            }

            val rssImage = extractRssItemImage(
                block = block,
                desc = desc,
                contentEncoded = contentEncoded,
                articleLink = link,
                baseUrl = effectiveBaseUrl
            ) ?: articleImageCache[link]

            if (!rssImage.isNullOrBlank()) {
                articleImageCache[link] = rssImage
            }

            val related = mutableListOf<NewsItem>()
            if (link.contains("news.google.com")) {
                val liPattern = Pattern.compile("<li[^>]*>(.*?)</li>", Pattern.DOTALL)
                val liMatcher = liPattern.matcher(desc)

                while (liMatcher.find()) {
                    val li = liMatcher.group(1) ?: continue
                    val aPat = Pattern.compile("<a[^>]*href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>", Pattern.DOTALL)
                    val fPat = Pattern.compile("<font[^>]*>(.*?)</font>", Pattern.DOTALL)
                    val am = aPat.matcher(li)
                    val fm = fPat.matcher(li)
                    if (am.find() && fm.find()) {
                        val rs = unescape(stripTags(fm.group(1) ?: ""))
                        var rt = unescape(stripTags(am.group(2) ?: ""))
                        val suf = " - $rs"
                        if (rt.endsWith(suf)) {
                            rt = rt.substring(0, rt.length - suf.length)
                        }
                        val rLink = unescape(am.group(1) ?: "")
                        if (rt.isNotEmpty() && rs.isNotEmpty() && rLink.isNotEmpty()) {
                            related.add(
                                NewsItem(
                                    title = rt,
                                    source = rs,
                                    sourceUrl = "",
                                    link = rLink,
                                    time = parseDate(pub),
                                    imageUrl = articleImageCache[rLink]
                                )
                            )
                        }
                    }
                }
            }

            val resolvedSource = if (source.isEmpty()) "অজানা উৎস" else source
            val newsItem = NewsItem(
                title = title,
                source = resolvedSource,
                sourceUrl = sourceUrl,
                link = link,
                time = parseDate(pub),
                categoryId = categoryText,
                related = related,
                snippet = plainSnippet,
                imageUrl = rssImage
            )

            if (!rssImage.isNullOrBlank()) {
                articleImageCache[cacheKeyFor(newsItem)] = rssImage
            }

            logArticleImagePipeline(
                ImagePipelineDiagnostic(
                    title = title,
                    source = resolvedSource,
                    originalArticleUrl = link,
                    rssImageUrl = rssImage,
                    extractedImageUrl = rssImage,
                    resolvedPublisherUrl = if (!link.contains("news.google.com")) link else null,
                    isHttp200Valid = !rssImage.isNullOrBlank(),
                    httpStatusDetail = if (!rssImage.isNullOrBlank()) "RSS_DIRECT_IMAGE" else "NO_IMAGE_IN_RSS_XML",
                    loadStatus = if (!rssImage.isNullOrBlank()) "READY_FOR_COIL" else "NEEDS_FALLBACK_OR_LOGO"
                )
            )

            out.add(newsItem)
        }
        return out
    }

    private fun extractItemLink(block: String, baseUrl: String): String {
        // 1. Standard <link>https://...</link>
        val rawLink = extractTagNamespaceAgnostic(block, "link").trim()
        if (rawLink.startsWith("http://") || rawLink.startsWith("https://")) {
            return rawLink
        }

        // 2. Atom <link rel="alternate" href="..." /> or <link href="..." />
        val atomAltMat = Pattern.compile(
            "<(?:[A-Za-z0-9_-]+:)?link\\b[^>]*rel=[\"']alternate[\"'][^>]*href=[\"']([^\"']+)[\"']",
            Pattern.CASE_INSENSITIVE
        ).matcher(block)
        if (atomAltMat.find()) {
            val href = resolveRelativeUrl(unescape(atomAltMat.group(1) ?: ""), baseUrl)
            if (href.startsWith("http")) return href
        }

        val atomHrefMat = Pattern.compile(
            "<(?:[A-Za-z0-9_-]+:)?link\\b[^>]*href=[\"']([^\"']+)[\"']",
            Pattern.CASE_INSENSITIVE
        ).matcher(block)
        while (atomHrefMat.find()) {
            val tagFull = atomHrefMat.group(0) ?: ""
            if (tagFull.contains("rel=\"self\"", ignoreCase = true) ||
                tagFull.contains("rel='self'", ignoreCase = true)
            ) continue
            val href = resolveRelativeUrl(unescape(atomHrefMat.group(1) ?: ""), baseUrl)
            if (href.startsWith("http")) return href
        }

        // 3. Fallback to <guid isPermaLink="true">https://...</guid>
        val guid = extractTagNamespaceAgnostic(block, "guid").trim()
        if (guid.startsWith("http://") || guid.startsWith("https://")) {
            return guid
        }

        return if (rawLink.isNotEmpty()) resolveRelativeUrl(rawLink, baseUrl) else ""
    }

    private fun extractCategories(block: String): List<String> {
        val cats = mutableListOf<String>()
        val m = Pattern.compile(
            "<(?:[A-Za-z0-9_-]+:)?category\\b[^>]*>(.*?)</(?:[A-Za-z0-9_-]+:)?category>",
            Pattern.DOTALL or Pattern.CASE_INSENSITIVE
        ).matcher(block)
        while (m.find()) {
            val c = stripTags(unescape(m.group(1) ?: "")).trim()
            if (c.isNotEmpty()) cats.add(c)
        }
        val termMat = Pattern.compile(
            "<(?:[A-Za-z0-9_-]+:)?category\\b[^>]*term=[\"']([^\"']+)[\"']",
            Pattern.CASE_INSENSITIVE
        ).matcher(block)
        while (termMat.find()) {
            val c = unescape(termMat.group(1) ?: "").trim()
            if (c.isNotEmpty()) cats.add(c)
        }
        return cats
    }

    /**
     * Extracts article thumbnail/featured image from RSS/Atom XML tags in strict priority order:
     * 1. <*:content> (media:content, mrss:content, etc.)
     * 2. <*:thumbnail> (media:thumbnail, etc.)
     * 3. <*:enclosure> with image MIME type or image URL
     * 4. <*:image>, <*:featuredImage>, <*:coverImage>
     * 5. <img> tags inside <*:encoded>, <*:content>, <*:description>, <*:summary>
     *
     * Handles arbitrary XML namespace prefixes, resolves relative URLs against [articleLink]/[baseUrl],
     * upgrades low-res CDN sizes (such as BBC 240px -> 640px), and strictly validates image URLs.
     */
    fun extractRssItemImage(
        block: String,
        desc: String,
        contentEncoded: String,
        articleLink: String,
        baseUrl: String
    ): String? {
        val resolveBase = articleLink.takeIf { it.startsWith("http") && !it.contains("news.google.com") }
            ?: baseUrl

        // 1. <*:content ... url="..." /> (if multiple sizes exist, prefer the widest valid image)
        val mediaContentTagPat = Pattern.compile(
            "<(?:[A-Za-z0-9_-]+:)?content\\b([^>]*)>",
            Pattern.CASE_INSENSITIVE
        )
        val mcMatcher = mediaContentTagPat.matcher(block)
        var bestMediaContentUrl: String? = null
        var bestMediaContentWidth = -1

        while (mcMatcher.find()) {
            val attrs = mcMatcher.group(1) ?: continue
            val typeAttr = extractAttr(attrs, "type")?.lowercase(Locale.ROOT).orEmpty()
            val mediumAttr = extractAttr(attrs, "medium")?.lowercase(Locale.ROOT).orEmpty()
            if (typeAttr.startsWith("video/") || typeAttr.startsWith("audio/") ||
                mediumAttr == "video" || mediumAttr == "audio"
            ) {
                continue
            }
            val rawUrl = extractAttr(attrs, "url") ?: extractAttr(attrs, "src") ?: continue
            val normalized = normalizeAndResolveImageUrl(rawUrl, resolveBase)
            if (isValidArticleImageUrl(normalized, articleLink)) {
                val width = extractAttr(attrs, "width")?.toIntOrNull() ?: 0
                if (width >= bestMediaContentWidth) {
                    bestMediaContentWidth = width
                    bestMediaContentUrl = normalized
                }
            }
        }
        if (bestMediaContentUrl != null) return bestMediaContentUrl

        // 2. <*:thumbnail ... url="..." />
        val mediaThumbTagPat = Pattern.compile(
            "<(?:[A-Za-z0-9_-]+:)?thumbnail\\b([^>]*)>",
            Pattern.CASE_INSENSITIVE
        )
        val mtMatcher = mediaThumbTagPat.matcher(block)
        var bestThumbUrl: String? = null
        var bestThumbWidth = -1

        while (mtMatcher.find()) {
            val attrs = mtMatcher.group(1) ?: continue
            val rawUrl = extractAttr(attrs, "url") ?: extractAttr(attrs, "src") ?: continue
            val normalized = normalizeAndResolveImageUrl(rawUrl, resolveBase)
            if (isValidArticleImageUrl(normalized, articleLink)) {
                val width = extractAttr(attrs, "width")?.toIntOrNull() ?: 0
                if (width >= bestThumbWidth) {
                    bestThumbWidth = width
                    bestThumbUrl = normalized
                }
            }
        }
        if (bestThumbUrl != null) return bestThumbUrl

        // 3. <*:enclosure ... url="..." />
        val enclosureTagPat = Pattern.compile(
            "<(?:[A-Za-z0-9_-]+:)?enclosure\\b([^>]*)>",
            Pattern.CASE_INSENSITIVE
        )
        val encMatcher = enclosureTagPat.matcher(block)
        while (encMatcher.find()) {
            val attrs = encMatcher.group(1) ?: continue
            val typeAttr = extractAttr(attrs, "type")?.lowercase(Locale.ROOT).orEmpty()
            if (typeAttr.isNotEmpty() && !typeAttr.startsWith("image/")) continue
            val rawUrl = extractAttr(attrs, "url") ?: extractAttr(attrs, "href") ?: continue
            val normalized = normalizeAndResolveImageUrl(rawUrl, resolveBase)
            if (isValidArticleImageUrl(normalized, articleLink)) {
                return normalized
            }
        }

        // 4. <*:image>, <*:featuredImage>, <*:coverImage> tags
        val imageTagPatterns = listOf(
            Pattern.compile("<(?:[A-Za-z0-9_-]+:)?(?:image|featuredImage|coverImage|leadImage)\\b[^>]*>\\s*<(?:[A-Za-z0-9_-]+:)?url[^>]*>(.*?)</(?:[A-Za-z0-9_-]+:)?url>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL),
            Pattern.compile("<(?:[A-Za-z0-9_-]+:)?(?:image|featuredImage|coverImage|leadImage)\\b[^>]*url=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE),
            Pattern.compile("<(?:[A-Za-z0-9_-]+:)?(?:image|featuredImage|coverImage|leadImage)\\b[^>]*>(https?://[^<\\s]+)</(?:[A-Za-z0-9_-]+:)?(?:image|featuredImage|coverImage|leadImage)>", Pattern.CASE_INSENSITIVE)
        )
        for (pat in imageTagPatterns) {
            val m = pat.matcher(block)
            while (m.find()) {
                val normalized = normalizeAndResolveImageUrl(m.group(1) ?: "", resolveBase)
                if (isValidArticleImageUrl(normalized, articleLink)) {
                    return normalized
                }
            }
        }

        // 5. <img> tags inside content:encoded, content, description, or raw block
        val imgTagPat = Pattern.compile("<img\\b([^>]+)>", Pattern.CASE_INSENSITIVE)
        for (htmlPart in listOf(contentEncoded, desc, unescape(block))) {
            if (htmlPart.isEmpty()) continue
            val m = imgTagPat.matcher(htmlPart)
            while (m.find()) {
                val attrs = m.group(1) ?: continue
                // Skip explicit 1x1 or tiny icon dimensions if declared in HTML attributes
                val w = extractAttr(attrs, "width")?.toIntOrNull()
                val h = extractAttr(attrs, "height")?.toIntOrNull()
                if ((w != null && w in 1..48) || (h != null && h in 1..48)) continue

                val candidates = listOfNotNull(
                    extractAttr(attrs, "data-src"),
                    extractAttr(attrs, "data-lazy-src"),
                    extractAttr(attrs, "data-original"),
                    extractAttr(attrs, "src"),
                    extractFirstSrcsetUrl(extractAttr(attrs, "srcset"))
                )
                for (rawCandidate in candidates) {
                    val normalized = normalizeAndResolveImageUrl(rawCandidate, resolveBase)
                    if (isValidArticleImageUrl(normalized, articleLink)) {
                        return normalized
                    }
                }
            }
        }

        return null
    }

    private fun extractAttr(attrs: String, name: String): String? {
        val pat = Pattern.compile(
            "\\b$name\\s*=\\s*(?:[\"']([^\"']+)[\"']|([^\\s>]+))",
            Pattern.CASE_INSENSITIVE
        )
        val m = pat.matcher(attrs)
        if (!m.find()) return null
        return unescape(m.group(1) ?: m.group(2) ?: "").trim().takeIf { it.isNotEmpty() }
    }

    private fun extractFirstSrcsetUrl(srcset: String?): String? {
        if (srcset.isNullOrBlank()) return null
        return srcset.split(",")
            .map { it.trim().substringBefore(" ").trim() }
            .firstOrNull { it.isNotEmpty() }
    }

    /**
     * Resolves relative URLs against [baseUrl], unescapes XML/HTML entities (&amp;, &#038;),
     * encodes unescaped spaces in paths, and upgrades low-res CDN thumbnails.
     */
    fun normalizeAndResolveImageUrl(rawUrl: String, baseUrl: String): String {
        val unescaped = unescape(rawUrl)
            .replace("&#038;", "&")
            .replace("\\/", "/")
            .trim()
        if (unescaped.isEmpty() || unescaped.startsWith("data:")) return ""

        val resolved = resolveRelativeUrl(unescaped, baseUrl)
            .replace(" ", "%20")

        // Upgrade BBC 240px thumbnails to sharp 640px thumbnails
        if (resolved.contains("ichef.bbci.co.uk/")) {
            return resolved
                .replace("/ws/240/", "/ws/640/")
                .replace("/standard/240/", "/standard/640/")
        }

        return resolved
    }

    /**
     * Resolves the cover/featured image for [item]:
     * 1. Uses RSS-provided [NewsItem.imageUrl] first if available and valid.
     * 2. Uses RSS-provided [NewsItem.imageUrl] from sibling coverage items ([fallbackItems] / [item.related]) if available.
     * 3. Only if no RSS image exists and the article has a direct publisher URL (non-Google News), checks OG meta tags.
     * 4. Validates the resolved URL with an HTTP 200 / image Content-Type check and logs full diagnostics.
     */
    suspend fun resolveArticleImage(
        item: NewsItem,
        fallbackItems: List<NewsItem> = emptyList()
    ): String? = withContext(Dispatchers.IO) {
        val key = cacheKeyFor(item)

        // 1. Check direct RSS image on primary item
        if (!item.imageUrl.isNullOrBlank() && isValidArticleImageUrl(item.imageUrl, item.link)) {
            val (httpOk, httpDetail) = verifyImageHttp200(item.imageUrl)
            logArticleImagePipeline(
                ImagePipelineDiagnostic(
                    title = item.title,
                    source = item.source,
                    originalArticleUrl = item.link,
                    rssImageUrl = item.imageUrl,
                    extractedImageUrl = item.imageUrl,
                    resolvedPublisherUrl = item.link.takeIf { !it.contains("news.google.com") },
                    isHttp200Valid = httpOk,
                    httpStatusDetail = httpDetail,
                    loadStatus = if (httpOk) "RSS_IMAGE_HTTP_200_VERIFIED" else "RSS_IMAGE_HTTP_FAILED"
                )
            )
            if (httpOk) {
                articleImageCache[key] = item.imageUrl
                return@withContext item.imageUrl
            }
        }

        // 2. Check in-memory cache
        articleImageCache[key]?.let { cached ->
            val (httpOk, _) = verifyImageHttp200(cached)
            if (httpOk) return@withContext cached
        }

        // 3. Check sibling coverage items' RSS images
        val allFallbacks = (fallbackItems + item.related).distinctBy { cacheKeyFor(it) }
        for (alt in allFallbacks) {
            val altRssImg = alt.imageUrl ?: articleImageCache[cacheKeyFor(alt)]
            if (!altRssImg.isNullOrBlank() && isValidArticleImageUrl(altRssImg, alt.link)) {
                val (httpOk, httpDetail) = verifyImageHttp200(altRssImg)
                logArticleImagePipeline(
                    ImagePipelineDiagnostic(
                        title = item.title,
                        source = item.source,
                        originalArticleUrl = item.link,
                        rssImageUrl = item.imageUrl,
                        extractedImageUrl = altRssImg,
                        resolvedPublisherUrl = alt.link.takeIf { !it.contains("news.google.com") },
                        isHttp200Valid = httpOk,
                        httpStatusDetail = "SIBLING_RSS_IMAGE ($httpDetail)",
                        loadStatus = if (httpOk) "SIBLING_RSS_IMAGE_VERIFIED" else "SIBLING_RSS_IMAGE_FAILED"
                    )
                )
                if (httpOk) {
                    articleImageCache[key] = altRssImg
                    return@withContext altRssImg
                }
            }
        }

        if (failedImageCache.contains(key)) return@withContext null

        // 4. Secondary fallback for direct publisher URLs (e.g. DW Bangla, Al Jazeera, Channel i)
        val resolved = resolveSingleItemSecondaryImage(item)
        if (!resolved.isNullOrBlank()) {
            articleImageCache[key] = resolved
            return@withContext resolved
        }

        for (alt in allFallbacks.take(2)) {
            val altResolved = resolveSingleItemSecondaryImage(alt)
            if (!altResolved.isNullOrBlank()) {
                articleImageCache[key] = altResolved
                return@withContext altResolved
            }
        }

        failedImageCache.add(key)
        logArticleImagePipeline(
            ImagePipelineDiagnostic(
                title = item.title,
                source = item.source,
                originalArticleUrl = item.link,
                rssImageUrl = item.imageUrl,
                extractedImageUrl = null,
                resolvedPublisherUrl = item.link.takeIf { !it.contains("news.google.com") },
                isHttp200Valid = false,
                httpStatusDetail = "NO_VALID_IMAGE_FOUND",
                loadStatus = "FALLBACK_TO_SOURCE_LOGO"
            )
        )
        null
    }

    private suspend fun resolveSingleItemSecondaryImage(item: NewsItem): String? = coroutineScope {
        val key = cacheKeyFor(item)
        articleImageCache[key]?.let { return@coroutineScope it }
        if (failedImageCache.contains(key)) return@coroutineScope null

        val deferred = inFlightLookups.getOrPut(key) {
            async(Dispatchers.IO) {
                imageSemaphore.withPermit {
                    try {
                        articleImageCache[key]?.let { return@withPermit it }
                        val link = item.link.trim()
                        var publisherUrl: String? = null

                        if (link.startsWith("http") && !link.contains("news.google.com")) {
                            publisherUrl = link
                        } else if (link.contains("news.google.com")) {
                            // Only attempt fast local base64 decoding (no network scraping of google.com/articles to avoid HTTP 429)
                            val articleId = extractGoogleNewsArticleId(link)
                            if (!articleId.isNullOrEmpty()) {
                                publisherUrl = tryDecodeBase64Url(articleId)
                            }
                        }

                        var extractedOg: String? = null
                        if (!publisherUrl.isNullOrBlank() && !publisherUrl.contains("news.google.com")) {
                            extractedOg = extractOgImageFromUrl(publisherUrl)
                        }

                        if (!extractedOg.isNullOrBlank() && isValidArticleImageUrl(extractedOg, publisherUrl ?: link)) {
                            val (httpOk, httpDetail) = verifyImageHttp200(extractedOg)
                            logArticleImagePipeline(
                                ImagePipelineDiagnostic(
                                    title = item.title,
                                    source = item.source,
                                    originalArticleUrl = link,
                                    rssImageUrl = item.imageUrl,
                                    extractedImageUrl = extractedOg,
                                    resolvedPublisherUrl = publisherUrl,
                                    isHttp200Valid = httpOk,
                                    httpStatusDetail = httpDetail,
                                    loadStatus = if (httpOk) "OG_IMAGE_HTTP_200_VERIFIED" else "OG_IMAGE_HTTP_FAILED"
                                )
                            )
                            if (httpOk) {
                                articleImageCache[key] = extractedOg
                                if (link.isNotBlank()) articleImageCache[link] = extractedOg
                                return@withPermit extractedOg
                            }
                        }

                        failedImageCache.add(key)
                        null
                    } catch (e: Exception) {
                        logDiag("SECONDARY_IMAGE_LOOKUP_ERROR | title=\"${item.title.take(40)}\" | error=\"${e.message}\"")
                        failedImageCache.add(key)
                        null
                    } finally {
                        inFlightLookups.remove(key)
                    }
                }
            }
        }

        deferred.await()
    }

    /**
     * Verifies that [imageUrl] responds with HTTP 200/206 and a valid image Content-Type.
     * Caches the result so each URL is checked at most once.
     */
    fun verifyImageHttp200(imageUrl: String): Pair<Boolean, String> {
        verifiedHttp200Cache[imageUrl]?.let { return it }

        val req = Request.Builder()
            .url(imageUrl)
            .header("User-Agent", BROWSER_USER_AGENT)
            .header("Accept", IMAGE_ACCEPT_HEADER)
            .header("Range", "bytes=0-1023")
            .get()
            .build()

        val result = try {
            imageHttpClient.newCall(req).execute().use { res ->
                val code = res.code
                val contentType = (res.header("Content-Type") ?: "").lowercase(Locale.ROOT)
                val isSuccessCode = code in 200..299
                val isImageContent = contentType.startsWith("image/") ||
                    contentType.contains("octet-stream") ||
                    (contentType.isEmpty() && isSuccessCode)
                val isNotHtml = !contentType.contains("text/html") && !contentType.contains("application/xhtml")
                val valid = isSuccessCode && isImageContent && isNotHtml
                valid to "HTTP $code Content-Type=${contentType.ifEmpty { "unknown" }}"
            }
        } catch (e: Exception) {
            false to "EXCEPTION: ${e.javaClass.simpleName}: ${e.message}"
        }

        verifiedHttp200Cache[imageUrl] = result
        return result
    }

    private fun extractGoogleNewsArticleId(url: String): String? {
        val m = Pattern.compile("(?:articles|read)/([A-Za-z0-9_-]+)").matcher(url)
        return if (m.find()) m.group(1) else null
    }

    private fun tryDecodeBase64Url(encodedId: String): String? {
        return try {
            val bytes = Base64.decode(encodedId, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            val raw = String(bytes, Charsets.ISO_8859_1)
            val start = raw.indexOf("http")
            if (start == -1) return null
            val sub = raw.substring(start)
            val urlChars = StringBuilder()
            for (ch in sub) {
                if (ch.code in 33..126) {
                    urlChars.append(ch)
                } else {
                    break
                }
            }
            val candidate = urlChars.toString()
            if ((candidate.startsWith("http://") || candidate.startsWith("https://")) &&
                !candidate.contains("google.com") &&
                candidate.contains(".")
            ) {
                candidate
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Fetches the article HTML (<head> section) for direct publisher URLs and extracts og:image / twitter:image.
     */
    private fun extractOgImageFromUrl(articleUrl: String): String? {
        val req = Request.Builder()
            .url(articleUrl)
            .header("User-Agent", BROWSER_USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .build()

        val (html, resolvedBaseUrl) = try {
            fastFeedClient.newCall(req).execute().use { res ->
                if (!res.isSuccessful) {
                    logDiag("OG_FETCH_HTTP_FAIL | url=\"$articleUrl\" | HTTP ${res.code}")
                    return null
                }
                readBoundedBody(res, 96_000) to res.request.url.toString()
            }
        } catch (e: Exception) {
            logDiag("OG_FETCH_ERROR | url=\"$articleUrl\" | error=\"${e.message}\"")
            return null
        }

        if (html.isEmpty()) return null

        val metaPatterns = listOf(
            Pattern.compile("<meta[^>]+property=[\"']og:image(?::secure_url|:url)?[\"'][^>]+content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE),
            Pattern.compile("<meta[^>]+content=[\"']([^\"']+)[\"'][^>]+property=[\"']og:image(?::secure_url|:url)?[\"']", Pattern.CASE_INSENSITIVE),
            Pattern.compile("<meta[^>]+(?:name|property)=[\"']twitter:image(?::src)?[\"'][^>]+content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE),
            Pattern.compile("<meta[^>]+content=[\"']([^\"']+)[\"'][^>]+(?:name|property)=[\"']twitter:image(?::src)?[\"']", Pattern.CASE_INSENSITIVE),
            Pattern.compile("<meta[^>]+itemprop=[\"']image[\"'][^>]+content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE),
            Pattern.compile("<link[^>]+rel=[\"']image_src[\"'][^>]+href=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\"image\"\\s*:\\s*\\{[^}]*\"url\"\\s*:\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\"image\"\\s*:\\s*\\[\\s*\"(https?://[^\"]+)\"", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\"image\"\\s*:\\s*\"(https?://[^\"]+)\"", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\"thumbnailUrl\"\\s*:\\s*\"(https?://[^\"]+)\"", Pattern.CASE_INSENSITIVE)
        )

        for (pat in metaPatterns) {
            val m = pat.matcher(html)
            while (m.find()) {
                val rawImg = m.group(1) ?: continue
                val fullUrl = normalizeAndResolveImageUrl(rawImg, resolvedBaseUrl)
                if (isValidArticleImageUrl(fullUrl, resolvedBaseUrl)) {
                    return fullUrl
                }
            }
        }

        return null
    }

    private fun readBoundedBody(response: okhttp3.Response, maxBytes: Int): String {
        val source = response.body?.source() ?: return ""
        source.request(maxBytes.toLong())
        val buffer = source.buffer.clone()
        val size = buffer.size.coerceAtMost(maxBytes.toLong())
        return buffer.readString(size, Charsets.UTF_8)
    }

    fun resolveRelativeUrl(candidate: String, baseUrl: String): String {
        val trimmed = candidate.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
        if (trimmed.startsWith("//")) return "https:$trimmed"
        if (baseUrl.isBlank()) return trimmed
        return try {
            val safeCandidate = trimmed.replace(" ", "%20")
            URI(baseUrl).resolve(safeCandidate).toString()
        } catch (_: Exception) {
            if (baseUrl.endsWith("/") && trimmed.startsWith("/")) {
                baseUrl.dropLast(1) + trimmed
            } else if (!baseUrl.endsWith("/") && !trimmed.startsWith("/")) {
                "$baseUrl/$trimmed"
            } else {
                baseUrl + trimmed
            }
        }
    }

    /**
     * Validates candidate image URLs before assigning to [NewsItem.imageUrl] or passing to Coil.
     * Rejects empty/invalid URLs, article webpage URLs, Google News redirect URLs, tracking pixels,
     * CMS emojis, theme badges, and favicons.
     */
    fun isValidArticleImageUrl(url: String?, articleUrl: String = ""): Boolean {
        if (url.isNullOrBlank()) return false
        val u = url.trim()
        if (!u.startsWith("http://") && !u.startsWith("https://")) return false
        if (articleUrl.isNotBlank() && u.equals(articleUrl.trim(), ignoreCase = true)) return false

        val lower = u.lowercase(Locale.ROOT)

        // Reject non-URL MIME strings (e.g. "image/jpeg") or malformed hosts
        val host = try {
            URI(u.replace(" ", "%20")).host?.lowercase(Locale.ROOT) ?: ""
        } catch (_: Exception) {
            ""
        }
        if (host.isEmpty() || !host.contains(".")) return false

        // Reject Google News redirect URLs, favicons, branding, trackers, CMS emojis, and theme badges
        val rejectedSubstrings = listOf(
            "news.google.com",
            "google.com/url",
            "googleusercontent.com/j6_cofbogxhri9i",
            "gstatic.com/images/branding",
            "gstatic.com/kpui",
            "google.com/logos",
            "google.com/s2/favicons",
            "s.w.org/images/core/emoji",
            "/wp-includes/images/smilies",
            "gravatar.com/avatar",
            "/themes/custom/",
            "google_preferred_source_badge",
            "google_news.svg",
            "feedburner.com/~ff",
            "feedburner.com/~r",
            "feeds.feedburner.com/~",
            "doubleclick.net",
            "google-analytics.com",
            "googletagmanager.com",
            "facebook.com/tr",
            "1x1",
            "spacer.gif",
            "pixel.gif",
            "pixel.png",
            "blank.gif",
            "clear.gif",
            "favicon.ico",
            "/favicon"
        )
        if (rejectedSubstrings.any { lower.contains(it) }) return false

        val pathPart = lower.substringBefore("?").substringBefore("#")
        if (pathPart.endsWith(".svg") ||
            pathPart.endsWith(".ico") ||
            pathPart.endsWith(".html") ||
            pathPart.endsWith(".htm") ||
            pathPart.endsWith(".php") ||
            pathPart.endsWith(".aspx") ||
            pathPart.endsWith(".jsp") ||
            pathPart.endsWith(".xml") ||
            pathPart.endsWith(".rss") ||
            pathPart.endsWith(".json")
        ) {
            return false
        }

        // Accept URLs with explicit image extensions or known image/media CDN paths/hosts
        val hasImageExtension = pathPart.endsWith(".jpg") ||
            pathPart.endsWith(".jpeg") ||
            pathPart.endsWith(".png") ||
            pathPart.endsWith(".webp") ||
            pathPart.endsWith(".avif") ||
            pathPart.endsWith(".gif") ||
            pathPart.endsWith(".cms")

        val isKnownImageHostOrPath = host.startsWith("media.") ||
            host.startsWith("images.") ||
            host.startsWith("cdn.") ||
            host.startsWith("ichef.") ||
            host.startsWith("static") ||
            host.contains("guim.co.uk") ||
            host.contains("ndtvimg.com") ||
            host.contains("toiimg.com") ||
            host.contains("imgci.com") ||
            host.contains("abplive.com") ||
            lower.contains("/uploads/") ||
            lower.contains("/images/") ||
            lower.contains("/media/") ||
            lower.contains("/photo/") ||
            lower.contains("/files/styles/") ||
            lower.contains("format=jpg") ||
            lower.contains("format=webp") ||
            lower.contains("format=png")

        return hasImageExtension || isKnownImageHostOrPath
    }

    /**
     * Namespace-agnostic tag extractor: matches both `<tag>...</tag>` and `<prefix:tag>...</prefix:tag>`.
     */
    private fun extractTagNamespaceAgnostic(block: String, tag: String): String {
        val pattern = Pattern.compile(
            "<(?:[A-Za-z0-9_-]+:)?$tag(?:\\s[^>]*)?>(.*?)</(?:[A-Za-z0-9_-]+:)?$tag>",
            Pattern.DOTALL or Pattern.CASE_INSENSITIVE
        )
        val m = pattern.matcher(block)
        return if (m.find()) unescape(m.group(1) ?: "") else ""
    }

    private fun stripTags(s: String): String = s.replace(Regex("<[^>]*>"), "")

    private fun unescape(raw: String): String {
        var s = raw.replace(Regex("<!\\[CDATA\\[(.*?)\\]\\]>", RegexOption.DOT_MATCHES_ALL)) {
            it.groupValues[1]
        }
        s = s.replace(Regex("&#(x?)([0-9a-fA-F]+);")) { m ->
            val isHex = m.groupValues[1].equals("x", ignoreCase = true)
            val code = m.groupValues[2].toIntOrNull(if (isHex) 16 else 10)
            if (code != null) code.toChar().toString() else m.value
        }
        return s.replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#039;", "'")
            .replace("&#038;", "&")
            .replace("&amp;", "&")
            .trim()
    }

    suspend fun fetchCoverage(intl: Boolean): List<Coverage> = coroutineScope {
        val topics = if (intl) {
            listOf("", "WORLD", "BUSINESS", "TECHNOLOGY", "SPORTS", "SCIENCE", "HEALTH", "ENTERTAINMENT")
        } else {
            listOf("", "রাজনীতি", "ক্রিকেট", "অর্থনীতি", "বিনোদন", "প্রযুক্তি", "শিক্ষা", "আবহাওয়া")
        }

        val deferreds = topics.map { topic ->
            async {
                try {
                    if (intl) {
                        fetchNews(intl = true, section = topic)
                    } else {
                        fetchNews(query = topic)
                    }
                } catch (_: Exception) {
                    emptyList()
                }
            }
        }

        val allItems = deferreds.awaitAll().flatten()
        if (allItems.isEmpty()) throw Exception("No news items fetched")
        buildCoverage(allItems)
    }

    private class Group {
        val items = mutableListOf<NewsItem>()
        val tokens = mutableListOf<Set<String>>()
    }

    private fun tokens(s: String): Set<String> {
        return s.lowercase(Locale.ROOT)
            .split(Regex("[^\\p{L}\\p{M}\\p{N}]+"))
            .filter { it.length >= 2 }
            .toSet()
    }

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val inter = a.count { b.contains(it) }
        return inter.toDouble() / (a.size + b.size - inter).toDouble()
    }

    fun buildCoverage(all: List<NewsItem>): List<Coverage> {
        val groups = mutableListOf<Group>()
        val byTitle = mutableMapOf<String, Group>()

        for (n in all) {
            val tk = tokens(n.title)
            var hit = byTitle[n.title]
            if (hit == null && tk.size >= 3) {
                for (g in groups) {
                    if (g.tokens.any { t -> t.size >= 3 && jaccard(t, tk) >= 0.45 }) {
                        hit = g
                        break
                    }
                }
            }

            if (hit == null) {
                hit = Group()
                groups.add(hit)
            }

            hit.items.add(n)
            hit.items.addAll(n.related)
            hit.tokens.add(tk)
            byTitle[n.title] = hit
            for (r in n.related) {
                if (!byTitle.containsKey(r.title)) {
                    byTitle[r.title] = hit
                }
            }
        }

        val out = mutableListOf<Coverage>()
        for (g in groups) {
            // Order items so items with a verified RSS imageUrl and sourceUrl come first in the coverage group
            val ordered = g.items.sortedWith(
                compareByDescending<NewsItem> { !it.imageUrl.isNullOrBlank() }
                    .thenByDescending { it.sourceUrl.isNotEmpty() }
            )
            val groupImageUrl = ordered.firstOrNull { !it.imageUrl.isNullOrBlank() }?.imageUrl

            val seen = mutableSetOf<String>()
            val uniq = mutableListOf<NewsItem>()

            for (it in ordered) {
                val k = it.source.trim().lowercase(Locale.ROOT)
                if (k.isEmpty() || k == "অজানা উৎস") continue
                if (seen.add(k)) {
                    val itemWithImg = if (it.imageUrl.isNullOrBlank() && !groupImageUrl.isNullOrBlank()) {
                        it.copy(imageUrl = groupImageUrl)
                    } else {
                        it
                    }
                    uniq.add(itemWithImg)
                }
            }

            if (uniq.isNotEmpty()) {
                out.add(Coverage(uniq))
            }
        }

        return out.sortedWith(
            compareByDescending<Coverage> { cov ->
                cov.items.any { !it.imageUrl.isNullOrBlank() }
            }.thenByDescending { cov ->
                cov.count > 1
            }.thenByDescending { cov ->
                cov.count
            }.thenByDescending { cov ->
                cov.latest ?: 0L
            }
        )
    }

    suspend fun checkUrl(url: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val clientCheck = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        val req = Request.Builder()
            .url(url)
            .header("User-Agent", BROWSER_USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
            .build()

        try {
            clientCheck.newCall(req).execute().use { res ->
                val code = res.code
                if (code in 200..399) {
                    true to "ঠিক আছে (${code})"
                } else {
                    false to "ত্রুটি (স্ট্যাটাস $code)"
                }
            }
        } catch (e: Exception) {
            false to "খোলা যায়নি (${e.message ?: "টাইমআউট"})"
        }
    }
}
