package com.potrikaghor.data

import android.util.Base64
import android.util.Log
import com.potrikaghor.BuildConfig
import com.potrikaghor.model.Coverage
import com.potrikaghor.model.HomeFeedMode
import com.potrikaghor.model.NewsItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.net.URI
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern
import kotlin.math.abs
import kotlin.math.pow

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

data class CachedCoverageResult(
    val cacheKey: String,
    val intl: Boolean,
    val filterQuery: String,
    val coverageList: List<Coverage>,
    val manyCoverage: List<Coverage>,
    val fewCoverage: List<Coverage>,
    val originalItems: List<NewsItem>,
    val fetchedAtMs: Long
) {
    fun isExpired(
        nowMs: Long = System.currentTimeMillis(),
        ttlMs: Long = NewsRepository.COVERAGE_CACHE_TTL_MS
    ): Boolean {
        return (nowMs - fetchedAtMs) > ttlMs
    }
}

data class CoverageScrollPosition(
    val firstVisibleItemIndex: Int = 0,
    val firstVisibleItemScrollOffset: Int = 0
)

class CoveragePersistentStateHolder {
    @Volatile
    var isIntl: Boolean = false
    @Volatile
    var selectedTopic: String = ""
    @Volatile
    var isLoading: Boolean = false
    @Volatile
    var isRefreshing: Boolean = false
    @Volatile
    var errorMsg: String? = null
    @Volatile
    var lastSuccessfulResult: CachedCoverageResult? = null
    @Volatile
    var lastSuccessfulFetchTimeMs: Long = 0L
    @Volatile
    var returningFromReader: Boolean = false

    private val scrollPositionsByKey = ConcurrentHashMap<String, CoverageScrollPosition>()
    private val lastResultsByKey = ConcurrentHashMap<String, CachedCoverageResult>()

    fun recordSuccessfulResult(result: CachedCoverageResult) {
        lastSuccessfulResult = result
        lastSuccessfulFetchTimeMs = result.fetchedAtMs
        lastResultsByKey[result.cacheKey] = result
    }

    fun getLastSuccessfulResult(cacheKey: String): CachedCoverageResult? {
        return lastResultsByKey[cacheKey] ?: lastSuccessfulResult?.takeIf { it.cacheKey == cacheKey }
    }

    fun getScrollPosition(cacheKey: String): CoverageScrollPosition {
        return scrollPositionsByKey[cacheKey] ?: CoverageScrollPosition()
    }

    fun saveScrollPosition(cacheKey: String, index: Int, offset: Int) {
        scrollPositionsByKey[cacheKey] = CoverageScrollPosition(
            firstVisibleItemIndex = index.coerceAtLeast(0),
            firstVisibleItemScrollOffset = offset.coerceAtLeast(0)
        )
    }

    fun markNavigatingToReader() {
        returningFromReader = true
    }

    fun consumeReturningFromReader(): Boolean {
        val wasReturning = returningFromReader
        returningFromReader = false
        return wasReturning
    }
}

data class CachedHomeFeedResult(
    val cacheKey: String,
    val categoryKey: String,
    val feedMode: HomeFeedMode,
    val favoritesSignature: String,
    val newsItems: List<NewsItem>,
    val coverageList: List<Coverage>,
    val fetchedAtMs: Long
) {
    fun isExpired(
        nowMs: Long = System.currentTimeMillis(),
        ttlMs: Long = NewsRepository.HOME_FEED_CACHE_TTL_MS
    ): Boolean {
        return (nowMs - fetchedAtMs) > ttlMs
    }
}

data class HomeFeedScrollPosition(
    val firstVisibleItemIndex: Int = 0,
    val firstVisibleItemScrollOffset: Int = 0
)

class HomeFeedPersistentStateHolder(
    private val maxCacheEntries: Int = NewsRepository.MAX_HOME_FEED_CACHE_ENTRIES
) {
    @Volatile
    var selectedCategoryIndex: Int = 0
    @Volatile
    var selectedLocalRegionId: String = ""
    @Volatile
    var lastSuccessfulFetchTimeMs: Long = 0L
    @Volatile
    var returningFromReader: Boolean = false

    private val cacheLock = Any()
    private val processedResultsByKey = object : LinkedHashMap<String, CachedHomeFeedResult>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedHomeFeedResult>?): Boolean {
            return size > maxCacheEntries
        }
    }
    private val rawCategoryItemsByKey = object : LinkedHashMap<String, Pair<List<NewsItem>, Long>>(24, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<List<NewsItem>, Long>>?): Boolean {
            return size > maxCacheEntries
        }
    }
    private val scrollPositionsByKey = ConcurrentHashMap<String, HomeFeedScrollPosition>()
    private val seenItemKeysByCategory = ConcurrentHashMap<String, MutableSet<String>>()

    fun scrollStateKey(categoryKey: String, feedMode: HomeFeedMode): String {
        val normCat = categoryKey.trim().lowercase(Locale.ROOT).ifEmpty { "top" }
        return "cat=$normCat|mode=${feedMode.name}"
    }

    fun getScrollPosition(categoryKey: String, feedMode: HomeFeedMode): HomeFeedScrollPosition {
        return scrollPositionsByKey[scrollStateKey(categoryKey, feedMode)] ?: HomeFeedScrollPosition()
    }

    fun saveScrollPosition(categoryKey: String, feedMode: HomeFeedMode, index: Int, offset: Int) {
        scrollPositionsByKey[scrollStateKey(categoryKey, feedMode)] = HomeFeedScrollPosition(
            firstVisibleItemIndex = index.coerceAtLeast(0),
            firstVisibleItemScrollOffset = offset.coerceAtLeast(0)
        )
    }

    fun getSeenItemKeys(categoryKey: String): MutableSet<String> {
        val normCat = categoryKey.trim().lowercase(Locale.ROOT).ifEmpty { "top" }
        return seenItemKeysByCategory.computeIfAbsent(normCat) {
            ConcurrentHashMap.newKeySet()
        }
    }

    fun getOrCreateSeenKeys(categoryKey: String): MutableSet<String> = getSeenItemKeys(categoryKey)

    fun recordSuccessfulResult(result: CachedHomeFeedResult) {
        if (result.newsItems.isEmpty()) return
        synchronized(cacheLock) {
            processedResultsByKey[result.cacheKey] = result
            rawCategoryItemsByKey[result.categoryKey] = result.newsItems to result.fetchedAtMs
            lastSuccessfulFetchTimeMs = result.fetchedAtMs
        }
    }

    fun recordRawCategoryItems(categoryKey: String, items: List<NewsItem>, fetchedAtMs: Long) {
        if (items.isEmpty()) return
        val normCat = categoryKey.trim().lowercase(Locale.ROOT).ifEmpty { "top" }
        synchronized(cacheLock) {
            rawCategoryItemsByKey[normCat] = items to fetchedAtMs
            if (fetchedAtMs > lastSuccessfulFetchTimeMs) {
                lastSuccessfulFetchTimeMs = fetchedAtMs
            }
        }
    }

    fun getProcessedResult(cacheKey: String): CachedHomeFeedResult? {
        return synchronized(cacheLock) {
            processedResultsByKey[cacheKey]
        }
    }

    fun getAnyCachedResultForCategoryAndMode(
        categoryKey: String,
        feedMode: HomeFeedMode
    ): CachedHomeFeedResult? {
        val normCat = categoryKey.trim().lowercase(Locale.ROOT).ifEmpty { "top" }
        return synchronized(cacheLock) {
            processedResultsByKey.values.lastOrNull {
                it.categoryKey == normCat && it.feedMode == feedMode
            } ?: processedResultsByKey.values.lastOrNull {
                it.categoryKey == normCat
            }
        }
    }

    fun getRawCategoryItems(categoryKey: String): Pair<List<NewsItem>, Long>? {
        val normCat = categoryKey.trim().lowercase(Locale.ROOT).ifEmpty { "top" }
        return synchronized(cacheLock) {
            rawCategoryItemsByKey[normCat]
        }
    }

    fun processedCacheSize(): Int = synchronized(cacheLock) { processedResultsByKey.size }

    fun markNavigatingToReader() {
        returningFromReader = true
    }

    fun consumeReturningFromReader(): Boolean {
        val wasReturning = returningFromReader
        returningFromReader = false
        return wasReturning
    }
}

class NewsRepository {
    companion object {
        const val DIAG_TAG = "PotrikaImageDiag"
        const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        const val IMAGE_ACCEPT_HEADER = "image/webp,image/png,image/jpeg,image/*;q=0.8,*/*;q=0.5"

        fun logDiag(message: String) {
            if (BuildConfig.DEBUG) {
                try {
                    Log.d(DIAG_TAG, message)
                } catch (_: Throwable) {
                    println("[$DIAG_TAG] $message")
                }
            }
        }

        fun logArticleImagePipeline(diag: ImagePipelineDiagnostic) {
            if (!BuildConfig.DEBUG) return
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

        const val FUTURE_TIMESTAMP_TOLERANCE_MS = 10 * 60 * 1000L
        const val FEED_STALE_THRESHOLD_MS = 72 * 3600 * 1000L
        const val PINNED_STALE_ITEM_MAX_AGE_MS = 7 * 24 * 3600 * 1000L
        const val DUPLICATE_TIME_WINDOW_MS = 12 * 3600 * 1000L
        const val PER_FEED_TIMEOUT_MS = 10_000L
        const val DIRECT_FEED_BATCH_DEADLINE_MS = 13_000L
        const val GOOGLE_FEED_TIMEOUT_MS = 10_000L
        const val DIRECT_FEED_MAX_RESPONSE_BYTES = 1_200_000
        const val GOOGLE_FEED_MAX_RESPONSE_BYTES = 500_000
        const val COVERAGE_CACHE_TTL_MS = 3 * 60 * 1000L
        const val HOME_FEED_CACHE_TTL_MS = 3 * 60 * 1000L
        const val MAX_HOME_FEED_CACHE_ENTRIES = 24

        /**
         * Curated direct publisher RSS/Atom feeds.
         * Excludes stale/abandoned feeds (such as The Daily Star root /rss.xml frozen in June 2026
         * and CNN World RSS frozen in April 2023).
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
                feedUrl = "https://www.dailyamardesh.com/feed",
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
                feedUrl = "https://www.channelionline.com/rss.xml",
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
            // 6 New Live-Verified Bangla / Domestic Official Feeds
            DirectRssFeedSpec(
                sourceBn = "রাইজিংবিডি",
                sourceEn = "RisingBD",
                feedUrl = "https://www.risingbd.com/rss/rss.xml",
                baseUrl = "https://www.risingbd.com",
                isIntl = false
            ),
            DirectRssFeedSpec(
                sourceBn = "সংবাদ প্রতিদিন",
                sourceEn = "Sangbad Pratidin",
                feedUrl = "https://www.sangbadpratidin.in/feed/",
                baseUrl = "https://www.sangbadpratidin.in",
                isIntl = false
            ),
            DirectRssFeedSpec(
                sourceBn = "জুমবাংলা",
                sourceEn = "ZoomBangla",
                feedUrl = "https://inews.zoombangla.com/feed/",
                baseUrl = "https://inews.zoombangla.com",
                isIntl = false
            ),
            DirectRssFeedSpec(
                sourceBn = "অর্থসূচক",
                sourceEn = "Arthosuchak",
                feedUrl = "https://www.arthosuchak.com/feed/",
                baseUrl = "https://www.arthosuchak.com",
                isIntl = false,
                defaultCategory = "biz"
            ),
            DirectRssFeedSpec(
                sourceBn = "দৈনিক আজাদী",
                sourceEn = "Daily Azadi (Chattogram)",
                feedUrl = "https://dainikazadi.net/feed/",
                baseUrl = "https://dainikazadi.net",
                isIntl = false
            ),
            DirectRssFeedSpec(
                sourceBn = "দৈনিক পূর্বকোণ",
                sourceEn = "Purbokone (Chattogram)",
                feedUrl = "https://dainikpurbokone.net/feed/",
                baseUrl = "https://www.dainikpurbokone.net",
                isIntl = false
            ),
            // Existing International feeds with verified embedded RSS images
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
            ),
            // 9 New Live-Verified International Feeds (CNN 2023 & ambiguous EST feeds excluded)
            DirectRssFeedSpec(
                sourceBn = "ফ্রান্স ২৪",
                sourceEn = "France 24",
                feedUrl = "https://www.france24.com/en/rss",
                baseUrl = "https://www.france24.com",
                isIntl = true
            ),
            DirectRssFeedSpec(
                sourceBn = "জাতিসংঘ সংবাদ",
                sourceEn = "UN News",
                feedUrl = "https://news.un.org/feed/subscribe/en/news/all/rss.xml",
                baseUrl = "https://news.un.org",
                isIntl = true
            ),
            DirectRssFeedSpec(
                sourceBn = "ডয়চে ভেলে (ইংরেজি)",
                sourceEn = "DW English",
                feedUrl = "https://rss.dw.com/xml/rss-en-all",
                baseUrl = "https://www.dw.com/en",
                isIntl = true
            ),
            DirectRssFeedSpec(
                sourceBn = "স্কাই নিউজ",
                sourceEn = "Sky News",
                feedUrl = "https://feeds.skynews.com/feeds/rss/world.xml",
                baseUrl = "https://news.sky.com",
                isIntl = true
            ),
            DirectRssFeedSpec(
                sourceBn = "এবিসি নিউজ",
                sourceEn = "ABC News",
                feedUrl = "https://abcnews.com/abcnews/internationalheadlines",
                baseUrl = "https://abcnews.go.com",
                isIntl = true
            ),
            DirectRssFeedSpec(
                sourceBn = "দ্য হিন্দু",
                sourceEn = "The Hindu",
                feedUrl = "https://www.thehindu.com/news/international/feeder/default.rss",
                baseUrl = "https://www.thehindu.com",
                isIntl = true
            ),
            DirectRssFeedSpec(
                sourceBn = "সাউথ চায়না মর্নিং পোস্ট",
                sourceEn = "SCMP",
                feedUrl = "https://www.scmp.com/rss/91/feed/",
                baseUrl = "https://www.scmp.com",
                isIntl = true
            ),
            DirectRssFeedSpec(
                sourceBn = "টেকক্রাঞ্চ",
                sourceEn = "TechCrunch",
                feedUrl = "https://techcrunch.com/feed/",
                baseUrl = "https://techcrunch.com",
                isIntl = true,
                defaultCategory = "tech"
            ),
            DirectRssFeedSpec(
                sourceBn = "আর্স টেকনিকা",
                sourceEn = "Ars Technica",
                feedUrl = "https://feeds.arstechnica.com/arstechnica/index",
                baseUrl = "https://arstechnica.com",
                isIntl = true,
                defaultCategory = "tech"
            )
        )
    }

    internal val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    internal val fastFeedClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(7, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    val imageHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(9, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
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
    private val directFeedSemaphore = Semaphore(6)
    private val currentFeedJobHolder = ThreadLocal<Job?>()

    private class SharedInFlightRequest<T>(
        val deferred: Deferred<T>,
        var activeWaiters: Int = 0
    )

    private val inFlightLock = Any()
    private val sharedFetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var inFlightDomesticDirectBatch: SharedInFlightRequest<List<NewsItem>>? = null
    private var inFlightIntlDirectBatch: SharedInFlightRequest<List<NewsItem>>? = null
    private val inFlightNewsRequests = ConcurrentHashMap<String, SharedInFlightRequest<List<NewsItem>>>()

    private val _initialHomeLoadCompleted = MutableStateFlow(false)
    val initialHomeLoadCompleted: StateFlow<Boolean> = _initialHomeLoadCompleted.asStateFlow()

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
    private val categoryFeedMemoryCache = ConcurrentHashMap<String, List<NewsItem>>()

    // Processed Coverage Memory Cache & Persistent State Holder (survives Reader & Tab navigation)
    val coverageStateHolder = CoveragePersistentStateHolder()
    private val coverageMemoryCache = ConcurrentHashMap<String, CachedCoverageResult>()
    private val inFlightCoverageRequests = ConcurrentHashMap<String, SharedInFlightRequest<CachedCoverageResult>>()
    internal val coverageFetchExecutionCounter = AtomicInteger(0)
    internal val coverageBuildExecutionCounter = AtomicInteger(0)

    // Processed Home Feed Memory Cache & Persistent State Holder (survives Reader & Tab navigation)
    val homeFeedStateHolder = HomeFeedPersistentStateHolder()
    private val inFlightHomeFeedRequests = ConcurrentHashMap<String, SharedInFlightRequest<CachedHomeFeedResult>>()
    internal val homeFeedFetchExecutionCounter = AtomicInteger(0)
    internal val homeFeedBuildExecutionCounter = AtomicInteger(0)

    @Volatile
    var dataSaverMode: Boolean = false

    fun markInitialHomeLoadCompleted() {
        _initialHomeLoadCompleted.value = true
    }

    suspend fun awaitInitialHomeLoadBeforeBackgroundWork(maxWaitMs: Long = 20_000L) {
        if (_initialHomeLoadCompleted.value) return
        withTimeoutOrNull(maxWaitMs) {
            _initialHomeLoadCompleted.first { it }
        }
    }

    internal fun availableDirectFeedPermits(): Int = directFeedSemaphore.availablePermits

    /**
     * Executes a blocking OkHttp [Call] while binding its lifecycle to [job] (defaulting to the
     * active feed coroutine job) so coroutine timeout or cancellation immediately invokes
     * [Call.cancel] while [Call.execute] is still blocked, and always closes the [Response].
     */
    internal fun <T> executeCancellableCall(
        call: Call,
        job: Job? = currentFeedJobHolder.get(),
        block: (Response) -> T
    ): T {
        val cancelWatcher = if (job != null) Job(parent = job) else null
        val completionHandle = cancelWatcher?.invokeOnCompletion { cause ->
            if (cause != null) {
                call.cancel()
            }
        }
        try {
            if (job?.isCancelled == true) {
                call.cancel()
                throw CancellationException("HTTP call cancelled before execution")
            }
            return call.execute().use { response ->
                block(response)
            }
        } catch (e: java.io.IOException) {
            if (job?.isCancelled == true || call.isCanceled()) {
                throw CancellationException("HTTP call cancelled", e)
            }
            throw e
        } finally {
            completionHandle?.dispose()
            cancelWatcher?.cancel()
        }
    }

    fun getMemoryCachedCategoryFeed(categoryId: String): List<NewsItem> {
        val normKey = normalizeHomeCategoryKey(categoryId)
        return categoryFeedMemoryCache[normKey]
            ?: homeFeedStateHolder.getRawCategoryItems(normKey)?.first
            ?: emptyList()
    }

    fun putMemoryCachedCategoryFeed(categoryId: String, items: List<NewsItem>) {
        if (items.isNotEmpty()) {
            val normKey = normalizeHomeCategoryKey(categoryId)
            categoryFeedMemoryCache[normKey] = items
            homeFeedStateHolder.recordRawCategoryItems(normKey, items, System.currentTimeMillis())
        }
    }

    fun normalizeHomeCategoryKey(categoryId: String, localRegionId: String = ""): String {
        val cleanCat = categoryId.trim().lowercase(Locale.ROOT).ifEmpty { "top" }
        val cleanReg = localRegionId.trim().lowercase(Locale.ROOT)
        return if (cleanCat == "local" && cleanReg.isNotEmpty()) {
            "local_$cleanReg"
        } else {
            cleanCat
        }
    }

    fun computeFavoritesSignature(
        favoriteUrls: Collection<String>
    ): String = computeFavoritesSignature(HomeFeedMode.TOP_STORIES, favoriteUrls.toSet())

    fun computeFavoritesSignature(
        feedMode: HomeFeedMode,
        favoriteUrls: Set<String>
    ): String {
        if (feedMode == HomeFeedMode.LATEST_NEWS || favoriteUrls.isEmpty()) {
            return "none"
        }
        return favoriteUrls
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }
            .sorted()
            .joinToString(",")
            .ifEmpty { "none" }
    }

    fun homeFeedCacheKey(
        categoryKey: String,
        feedMode: HomeFeedMode,
        favoriteUrls: Set<String> = emptySet()
    ): String = homeFeedCacheKey(
        categoryKey = categoryKey,
        localRegionId = "",
        feedMode = feedMode,
        favoriteUrls = favoriteUrls
    )

    fun homeFeedCacheKey(
        categoryKey: String,
        localRegionId: String = "",
        feedMode: HomeFeedMode = HomeFeedMode.TOP_STORIES,
        favoriteUrls: Set<String> = emptySet()
    ): String {
        val normCat = normalizeHomeCategoryKey(categoryKey, localRegionId)
        val favSig = computeFavoritesSignature(feedMode, favoriteUrls)
        return "cat=$normCat|mode=${feedMode.name}|favs=$favSig"
    }

    /**
     * Returns the processed [CachedHomeFeedResult] for the given category, local region, feed mode,
     * and favorites signature.
     * IMPORTANT: TTL expiry NEVER deletes or evicts cached results; stale entries remain available
     * for immediate Frame-1 rendering and Stale-While-Revalidate fallback.
     */
    fun getCachedHomeFeedResult(
        categoryKey: String,
        feedMode: HomeFeedMode,
        favoriteUrls: Set<String> = emptySet()
    ): CachedHomeFeedResult? = getCachedHomeFeedResult(
        categoryKey = categoryKey,
        localRegionId = "",
        feedMode = feedMode,
        favoriteUrls = favoriteUrls
    )

    fun getCachedHomeFeedResult(
        categoryKey: String,
        localRegionId: String = "",
        feedMode: HomeFeedMode = HomeFeedMode.TOP_STORIES,
        favoriteUrls: Set<String> = emptySet()
    ): CachedHomeFeedResult? {
        val key = homeFeedCacheKey(categoryKey, localRegionId, feedMode, favoriteUrls)
        return homeFeedStateHolder.getProcessedResult(key)
    }

    fun isHomeFeedCacheFresh(
        categoryKey: String,
        feedMode: HomeFeedMode,
        favoriteUrls: Set<String> = emptySet(),
        nowMs: Long = System.currentTimeMillis(),
        ttlMs: Long = HOME_FEED_CACHE_TTL_MS
    ): Boolean = isHomeFeedCacheFresh(
        categoryKey = categoryKey,
        localRegionId = "",
        feedMode = feedMode,
        favoriteUrls = favoriteUrls,
        nowMs = nowMs,
        ttlMs = ttlMs
    )

    fun isHomeFeedCacheFresh(
        categoryKey: String,
        localRegionId: String = "",
        feedMode: HomeFeedMode = HomeFeedMode.TOP_STORIES,
        favoriteUrls: Set<String> = emptySet(),
        nowMs: Long = System.currentTimeMillis(),
        ttlMs: Long = HOME_FEED_CACHE_TTL_MS
    ): Boolean {
        val cached = getCachedHomeFeedResult(categoryKey, localRegionId, feedMode, favoriteUrls) ?: return false
        return !cached.isExpired(nowMs, ttlMs)
    }

    fun hasFreshRawCategoryFeed(
        categoryKey: String,
        localRegionId: String = "",
        nowMs: Long = System.currentTimeMillis(),
        ttlMs: Long = HOME_FEED_CACHE_TTL_MS
    ): Boolean {
        val normCat = normalizeHomeCategoryKey(categoryKey, localRegionId)
        val rawPair = homeFeedStateHolder.getRawCategoryItems(normCat) ?: return false
        return rawPair.first.isNotEmpty() && (nowMs - rawPair.second) <= ttlMs
    }

    fun putCachedHomeFeedResult(result: CachedHomeFeedResult) {
        if (result.newsItems.isNotEmpty()) {
            categoryFeedMemoryCache[result.categoryKey] = result.newsItems
            homeFeedStateHolder.recordSuccessfulResult(result)
        }
    }

    internal suspend fun buildHomeFeedCoverageOnDefault(
        items: List<NewsItem>,
        feedMode: HomeFeedMode,
        favoriteUrls: Set<String>,
        nowMs: Long = System.currentTimeMillis()
    ): List<Coverage> = withContext(Dispatchers.Default) {
        homeFeedBuildExecutionCounter.incrementAndGet()
        if (items.isEmpty()) return@withContext emptyList()
        try {
            when (feedMode) {
                HomeFeedMode.TOP_STORIES -> buildTopStories(all = items, favoriteUrls = favoriteUrls, nowMs = nowMs)
                HomeFeedMode.LATEST_NEWS -> buildLatestNews(all = items, nowMs = nowMs)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            items.map { Coverage(listOf(it)) }
        }
    }

    /**
     * Fetches or restores the processed Home Feed ([CachedHomeFeedResult]) with:
     * - 3-minute TTL processed memory cache (never deleting stale entries on TTL expiry)
     * - Reuse of fresh raw category items when switching [feedMode] or [favoriteUrls] within TTL
     *   (avoiding unnecessary Google News / RSS network fetches)
     * - Shared in-flight request deduplication with cancellation safety
     * - Clustering strictly on [Dispatchers.Default] and network on [Dispatchers.IO]
     */
    suspend fun fetchHomeFeedCached(
        categoryKey: String,
        localRegionId: String = "",
        query: String = "",
        intl: Boolean = false,
        feedMode: HomeFeedMode = HomeFeedMode.TOP_STORIES,
        favoriteUrls: Set<String> = emptySet(),
        forceRefresh: Boolean = false,
        nowMs: Long = System.currentTimeMillis(),
        ttlMs: Long = HOME_FEED_CACHE_TTL_MS,
        newsFetcher: (suspend (String, Boolean, Boolean) -> List<NewsItem>)? = null
    ): CachedHomeFeedResult = withContext(Dispatchers.IO) {
        val normCat = normalizeHomeCategoryKey(categoryKey, localRegionId)
        val favSig = computeFavoritesSignature(feedMode, favoriteUrls)
        val key = homeFeedCacheKey(normCat, "", feedMode, favoriteUrls)

        if (!forceRefresh) {
            val cached = homeFeedStateHolder.getProcessedResult(key)
            if (cached != null && !cached.isExpired(nowMs, ttlMs)) {
                return@withContext cached
            }
        }

        val requestMapKey = if (forceRefresh) "$key|force" else key
        val shared: SharedInFlightRequest<CachedHomeFeedResult> = synchronized(inFlightLock) {
            if (!forceRefresh) {
                val cached = homeFeedStateHolder.getProcessedResult(key)
                if (cached != null && !cached.isExpired(nowMs, ttlMs)) {
                    return@withContext cached
                }
            }
            val existing = inFlightHomeFeedRequests[requestMapKey]
            if (existing != null && existing.deferred.isActive) {
                existing.activeWaiters++
                existing
            } else {
                lateinit var created: SharedInFlightRequest<CachedHomeFeedResult>
                val deferred = sharedFetchScope.async(start = CoroutineStart.LAZY) {
                    try {
                        performFetchHomeFeed(
                            normCat = normCat,
                            cacheKey = key,
                            favSig = favSig,
                            query = query,
                            intl = intl,
                            feedMode = feedMode,
                            favoriteUrls = favoriteUrls,
                            forceRefresh = forceRefresh,
                            nowMs = nowMs,
                            ttlMs = ttlMs,
                            newsFetcher = newsFetcher
                        )
                    } finally {
                        synchronized(inFlightLock) {
                            if (inFlightHomeFeedRequests[requestMapKey] === created) {
                                inFlightHomeFeedRequests.remove(requestMapKey)
                            }
                        }
                    }
                }
                created = SharedInFlightRequest(deferred = deferred, activeWaiters = 1)
                inFlightHomeFeedRequests[requestMapKey] = created
                created
            }
        }

        shared.deferred.start()
        var cancelled = false
        try {
            shared.deferred.await()
        } catch (e: CancellationException) {
            cancelled = true
            val shouldCancelUnderlying = synchronized(inFlightLock) {
                shared.activeWaiters--
                if (shared.activeWaiters <= 0) {
                    if (inFlightHomeFeedRequests[requestMapKey] === shared) {
                        inFlightHomeFeedRequests.remove(requestMapKey)
                    }
                    true
                } else {
                    false
                }
            }
            if (shouldCancelUnderlying) {
                withContext(NonCancellable) {
                    shared.deferred.cancelAndJoin()
                }
            }
            throw e
        } finally {
            if (!cancelled) {
                synchronized(inFlightLock) {
                    shared.activeWaiters = (shared.activeWaiters - 1).coerceAtLeast(0)
                }
            }
        }
    }

    private suspend fun performFetchHomeFeed(
        normCat: String,
        cacheKey: String,
        favSig: String,
        query: String,
        intl: Boolean,
        feedMode: HomeFeedMode,
        favoriteUrls: Set<String>,
        forceRefresh: Boolean,
        nowMs: Long,
        ttlMs: Long,
        newsFetcher: (suspend (String, Boolean, Boolean) -> List<NewsItem>)?
    ): CachedHomeFeedResult {
        val rawPair = homeFeedStateHolder.getRawCategoryItems(normCat)
        val hasFreshRaw = !forceRefresh &&
            rawPair != null &&
            rawPair.first.isNotEmpty() &&
            (nowMs - rawPair.second) <= ttlMs

        val (items, effectiveFetchedAtMs) = if (hasFreshRaw && rawPair != null) {
            rawPair.first to rawPair.second
        } else {
            homeFeedFetchExecutionCounter.incrementAndGet()
            val fetched = if (newsFetcher != null) {
                newsFetcher(query, intl, forceRefresh)
            } else {
                fetchNews(query = query, intl = intl, forceRefresh = forceRefresh)
            }
            if (fetched.isEmpty()) {
                throw java.io.IOException("No news items fetched for $normCat")
            }
            fetched to nowMs
        }

        val builtCoverage = buildHomeFeedCoverageOnDefault(
            items = items,
            feedMode = feedMode,
            favoriteUrls = favoriteUrls,
            nowMs = effectiveFetchedAtMs
        )

        val result = CachedHomeFeedResult(
            cacheKey = cacheKey,
            categoryKey = normCat,
            feedMode = feedMode,
            favoritesSignature = favSig,
            newsItems = items,
            coverageList = builtCoverage,
            fetchedAtMs = effectiveFetchedAtMs
        )
        putCachedHomeFeedResult(result)
        return result
    }

    /**
     * API 24-compatible date parser using fresh [SimpleDateFormat] instances per call.
     * - Supports '+06:00' and '-05:00' colon offsets in both RFC-1123 and ISO-8601 strings.
     * - Rejects ambiguous timezone abbreviations (such as EST, EDT, CST, PST, IST, BST) as null.
     * - Rejects timestamps without an explicit timezone indicator as null.
     * - Rejects untrusted future timestamps exceeding [nowMs] + 10 minutes as null.
     */
    fun parseDate(str: String, nowMs: Long = System.currentTimeMillis()): Long? {
        val raw = str.trim().replace(Regex("\\s+"), " ")
        if (raw.isEmpty()) return null

        // Normalize trailing ISO colon offset "+06:00" / "-05:00" -> "+0600" / "-0500"
        var normalized = raw.replace(Regex("([+-]\\d{2}):(\\d{2})$"), "$1$2")

        // Normalize ISO-8601 trailing 'Z' / 'z' (after digits) to "+0000"
        if (normalized.matches(Regex(".*\\d[Zz]$"))) {
            normalized = normalized.dropLast(1) + "+0000"
        }

        // Normalize explicit unambiguous UTC labels (" UTC", " UT") to " GMT"
        normalized = normalized.replace(Regex("\\s+(?:UTC|UT)$", RegexOption.IGNORE_CASE), " GMT")

        // Validate that the string ends with an unambiguous timezone:
        // either a numeric offset ([+-]HHmm with HH in 00..14 and mm in 00..59) or " GMT".
        val numericTzMatch = Regex("([+-])(\\d{2})(\\d{2})$").find(normalized)
        val hasValidTimezone = if (numericTzMatch != null) {
            val hh = numericTzMatch.groupValues[2].toIntOrNull() ?: 99
            val mm = numericTzMatch.groupValues[3].toIntOrNull() ?: 99
            hh in 0..14 && mm in 0..59
        } else {
            normalized.endsWith(" GMT", ignoreCase = true)
        }

        if (!hasValidTimezone) {
            return null
        }

        val patterns = listOf(
            "EEE, dd MMM yyyy HH:mm:ss Z",
            "EEE, d MMM yyyy HH:mm:ss Z",
            "EEE, dd MMM yyyy HH:mm Z",
            "EEE, d MMM yyyy HH:mm Z",
            "EEE, dd MMM yyyy HH:mm:ss z",
            "EEE, d MMM yyyy HH:mm:ss z",
            "EEE, dd MMM yy HH:mm:ss Z",
            "EEE, d MMM yy HH:mm:ss Z",
            "EEE, dd MMM yy HH:mm:ss z",
            "EEE, d MMM yy HH:mm:ss z",
            "dd MMM yyyy HH:mm:ss Z",
            "d MMM yyyy HH:mm:ss Z",
            "dd MMM yyyy HH:mm:ss z",
            "d MMM yyyy HH:mm:ss z",
            "yyyy-MM-dd'T'HH:mm:ss.SSSZ",
            "yyyy-MM-dd'T'HH:mm:ss.SSZ",
            "yyyy-MM-dd'T'HH:mm:ss.SZ",
            "yyyy-MM-dd'T'HH:mm:ssZ",
            "yyyy-MM-dd'T'HH:mmZ",
            "yyyy-MM-dd HH:mm:ss Z",
            "yyyy-MM-dd HH:mm:ss z"
        )

        for (pattern in patterns) {
            try {
                val sdf = SimpleDateFormat(pattern, Locale.US).apply {
                    isLenient = false
                    timeZone = TimeZone.getTimeZone("GMT")
                }
                val pos = ParsePosition(0)
                val parsedDate = sdf.parse(normalized, pos)
                if (parsedDate != null && pos.index == normalized.length) {
                    val epochMs = parsedDate.time
                    if (epochMs <= 0L) return null
                    if (epochMs > nowMs + FUTURE_TIMESTAMP_TOLERANCE_MS) {
                        return null
                    }
                    return epochMs
                }
            } catch (_: Throwable) {
                // Safe across all Android API levels (24+)
            }
        }
        return null
    }

    /**
     * Feed-level stale check: returns true if the feed has timestamped items and its newest
     * valid timestamp is older than [maxFeedStaleAgeMs] (default 72 hours).
     */
    fun isFeedStale(
        items: List<NewsItem>,
        nowMs: Long = System.currentTimeMillis(),
        maxFeedStaleAgeMs: Long = FEED_STALE_THRESHOLD_MS
    ): Boolean {
        val newest = items.mapNotNull { it.time }.maxOrNull() ?: return false
        return (nowMs - newest) > maxFeedStaleAgeMs
    }

    /**
     * Article-level pinned stale item filter for direct publisher RSS feeds:
     * When a direct publisher feed contains fresh articles (within 72 hours of [nowMs]),
     * filters out individual pinned old articles older than 7 days (e.g. DW Bangla's March 2026
     * pinned item at index 0). Never deletes items if the entire feed is older or offline.
     */
    fun filterPinnedStaleDirectItems(
        items: List<NewsItem>,
        nowMs: Long = System.currentTimeMillis()
    ): List<NewsItem> {
        if (items.size <= 1) return items
        val hasFreshItems = items.any { it.time != null && (nowMs - it.time) in 0..FEED_STALE_THRESHOLD_MS }
        if (!hasFreshItems) return items
        return items.filter { item ->
            val t = item.time ?: return@filter true
            (nowMs - t) <= PINNED_STALE_ITEM_MAX_AGE_MS
        }
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
        section: String = "",
        forceRefresh: Boolean = false
    ): List<NewsItem> = fetchNewsInternal(
        query = query,
        intl = intl,
        section = section,
        forceRefresh = forceRefresh
    )

    internal suspend fun fetchNewsInternal(
        query: String = "",
        intl: Boolean = false,
        section: String = "",
        forceRefresh: Boolean = false,
        directFeedFetcher: (DirectRssFeedSpec) -> List<NewsItem> = ::fetchSingleDirectFeed,
        googleRssFetcher: (String, Boolean, String) -> List<NewsItem> = ::fetchGoogleNewsRss,
        perFeedTimeoutMs: Long = PER_FEED_TIMEOUT_MS,
        batchDeadlineMs: Long = DIRECT_FEED_BATCH_DEADLINE_MS,
        googleFeedTimeoutMs: Long = GOOGLE_FEED_TIMEOUT_MS
    ): List<NewsItem> = withContext(Dispatchers.IO) {
        val q = query.trim()
        val sec = section.trim()
        val requestKey = "q=$q|intl=$intl|sec=$sec"

        if (forceRefresh) {
            return@withContext performFetchNews(
                q = q,
                intl = intl,
                section = sec,
                forceRefresh = true,
                directFeedFetcher = directFeedFetcher,
                googleRssFetcher = googleRssFetcher,
                perFeedTimeoutMs = perFeedTimeoutMs,
                batchDeadlineMs = batchDeadlineMs,
                googleFeedTimeoutMs = googleFeedTimeoutMs
            )
        }

        val shared: SharedInFlightRequest<List<NewsItem>> = synchronized(inFlightLock) {
            val existing = inFlightNewsRequests[requestKey]
            if (existing != null && existing.deferred.isActive) {
                existing.activeWaiters++
                existing
            } else {
                lateinit var created: SharedInFlightRequest<List<NewsItem>>
                val deferred = sharedFetchScope.async(start = CoroutineStart.LAZY) {
                    try {
                        performFetchNews(
                            q = q,
                            intl = intl,
                            section = sec,
                            forceRefresh = false,
                            directFeedFetcher = directFeedFetcher,
                            googleRssFetcher = googleRssFetcher,
                            perFeedTimeoutMs = perFeedTimeoutMs,
                            batchDeadlineMs = batchDeadlineMs,
                            googleFeedTimeoutMs = googleFeedTimeoutMs
                        )
                    } finally {
                        synchronized(inFlightLock) {
                            if (inFlightNewsRequests[requestKey] === created) {
                                inFlightNewsRequests.remove(requestKey)
                            }
                        }
                    }
                }
                created = SharedInFlightRequest(deferred = deferred, activeWaiters = 1)
                inFlightNewsRequests[requestKey] = created
                created
            }
        }

        shared.deferred.start()
        var cancelled = false
        try {
            shared.deferred.await()
        } catch (e: CancellationException) {
            cancelled = true
            val shouldCancelUnderlying = synchronized(inFlightLock) {
                shared.activeWaiters--
                if (shared.activeWaiters <= 0) {
                    if (inFlightNewsRequests[requestKey] === shared) {
                        inFlightNewsRequests.remove(requestKey)
                    }
                    true
                } else {
                    false
                }
            }
            if (shouldCancelUnderlying) {
                withContext(NonCancellable) {
                    shared.deferred.cancelAndJoin()
                }
            }
            throw e
        } finally {
            if (!cancelled) {
                synchronized(inFlightLock) {
                    shared.activeWaiters = (shared.activeWaiters - 1).coerceAtLeast(0)
                }
            }
        }
    }

    private suspend fun performFetchNews(
        q: String,
        intl: Boolean,
        section: String,
        forceRefresh: Boolean,
        directFeedFetcher: (DirectRssFeedSpec) -> List<NewsItem>,
        googleRssFetcher: (String, Boolean, String) -> List<NewsItem>,
        perFeedTimeoutMs: Long,
        batchDeadlineMs: Long,
        googleFeedTimeoutMs: Long
    ): List<NewsItem> = coroutineScope {
        val isIntlQuery = intl || q == "আন্তর্জাতিক"

        // 1. Fetch direct publisher RSS feeds (bounded per-feed and batch deadline, partial results preserved)
        val directDeferred = async {
            try {
                fetchDirectPublisherFeeds(
                    isIntl = isIntlQuery,
                    forceRefresh = forceRefresh,
                    fetcher = directFeedFetcher,
                    perFeedTimeoutMs = perFeedTimeoutMs,
                    batchDeadlineMs = batchDeadlineMs
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logDiag("Direct publisher feeds error for query='$q': ${e.message}")
                emptyList()
            }
        }

        // 2. Fetch Google News RSS feed with bounded timeout and cancellable Call execution
        val googleDeferred = async {
            try {
                withTimeoutOrNull(googleFeedTimeoutMs) {
                    val googleJob = coroutineContext[Job]
                    runInterruptible(Dispatchers.IO) {
                        currentFeedJobHolder.set(googleJob)
                        try {
                            googleRssFetcher(q, intl, section)
                        } finally {
                            currentFeedJobHolder.remove()
                        }
                    }
                } ?: run {
                    logDiag("Google News RSS timeout for query='$q'")
                    emptyList()
                }
            } catch (e: CancellationException) {
                throw e
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

    internal suspend fun fetchDirectPublisherFeeds(
        isIntl: Boolean,
        forceRefresh: Boolean = false,
        fetcher: (DirectRssFeedSpec) -> List<NewsItem> = ::fetchSingleDirectFeed,
        perFeedTimeoutMs: Long = PER_FEED_TIMEOUT_MS,
        batchDeadlineMs: Long = DIRECT_FEED_BATCH_DEADLINE_MS
    ): List<NewsItem> = coroutineScope {
        val now = System.currentTimeMillis()
        if (!forceRefresh) {
            if (isIntl) {
                if (cachedDirectIntlItems.isNotEmpty() && (now - cachedDirectIntlTimestamp) < directFeedCacheTtlMs) {
                    return@coroutineScope cachedDirectIntlItems
                }
            } else {
                if (cachedDirectDomesticItems.isNotEmpty() && (now - cachedDirectDomesticTimestamp) < directFeedCacheTtlMs) {
                    return@coroutineScope cachedDirectDomesticItems
                }
            }
        }

        if (forceRefresh) {
            return@coroutineScope executeDirectPublisherFeedsBatch(
                isIntl = isIntl,
                fetcher = fetcher,
                perFeedTimeoutMs = perFeedTimeoutMs,
                batchDeadlineMs = batchDeadlineMs
            )
        }

        val shared: SharedInFlightRequest<List<NewsItem>> = synchronized(inFlightLock) {
            val checkNow = System.currentTimeMillis()
            if (isIntl) {
                if (cachedDirectIntlItems.isNotEmpty() && (checkNow - cachedDirectIntlTimestamp) < directFeedCacheTtlMs) {
                    return@coroutineScope cachedDirectIntlItems
                }
                val existing = inFlightIntlDirectBatch
                if (existing != null && existing.deferred.isActive) {
                    existing.activeWaiters++
                    existing
                } else {
                    lateinit var created: SharedInFlightRequest<List<NewsItem>>
                    val deferred = sharedFetchScope.async(start = CoroutineStart.LAZY) {
                        try {
                            executeDirectPublisherFeedsBatch(
                                isIntl = true,
                                fetcher = fetcher,
                                perFeedTimeoutMs = perFeedTimeoutMs,
                                batchDeadlineMs = batchDeadlineMs
                            )
                        } finally {
                            synchronized(inFlightLock) {
                                if (inFlightIntlDirectBatch === created) {
                                    inFlightIntlDirectBatch = null
                                }
                            }
                        }
                    }
                    created = SharedInFlightRequest(deferred = deferred, activeWaiters = 1)
                    inFlightIntlDirectBatch = created
                    created
                }
            } else {
                if (cachedDirectDomesticItems.isNotEmpty() && (checkNow - cachedDirectDomesticTimestamp) < directFeedCacheTtlMs) {
                    return@coroutineScope cachedDirectDomesticItems
                }
                val existing = inFlightDomesticDirectBatch
                if (existing != null && existing.deferred.isActive) {
                    existing.activeWaiters++
                    existing
                } else {
                    lateinit var created: SharedInFlightRequest<List<NewsItem>>
                    val deferred = sharedFetchScope.async(start = CoroutineStart.LAZY) {
                        try {
                            executeDirectPublisherFeedsBatch(
                                isIntl = false,
                                fetcher = fetcher,
                                perFeedTimeoutMs = perFeedTimeoutMs,
                                batchDeadlineMs = batchDeadlineMs
                            )
                        } finally {
                            synchronized(inFlightLock) {
                                if (inFlightDomesticDirectBatch === created) {
                                    inFlightDomesticDirectBatch = null
                                }
                            }
                        }
                    }
                    created = SharedInFlightRequest(deferred = deferred, activeWaiters = 1)
                    inFlightDomesticDirectBatch = created
                    created
                }
            }
        }

        shared.deferred.start()
        var cancelled = false
        try {
            shared.deferred.await()
        } catch (e: CancellationException) {
            cancelled = true
            val shouldCancelUnderlying = synchronized(inFlightLock) {
                shared.activeWaiters--
                if (shared.activeWaiters <= 0) {
                    if (isIntl) {
                        if (inFlightIntlDirectBatch === shared) inFlightIntlDirectBatch = null
                    } else {
                        if (inFlightDomesticDirectBatch === shared) inFlightDomesticDirectBatch = null
                    }
                    true
                } else {
                    false
                }
            }
            if (shouldCancelUnderlying) {
                withContext(NonCancellable) {
                    shared.deferred.cancelAndJoin()
                }
            }
            throw e
        } finally {
            if (!cancelled) {
                synchronized(inFlightLock) {
                    shared.activeWaiters = (shared.activeWaiters - 1).coerceAtLeast(0)
                }
            }
        }
    }

    private suspend fun executeDirectPublisherFeedsBatch(
        isIntl: Boolean,
        fetcher: (DirectRssFeedSpec) -> List<NewsItem>,
        perFeedTimeoutMs: Long,
        batchDeadlineMs: Long
    ): List<NewsItem> = coroutineScope {
        val now = System.currentTimeMillis()
        val feedsToFetch = if (isIntl) {
            DIRECT_PUBLISHER_FEEDS.filter { it.isIntl || it.sourceBn == "বিবিসি বাংলা" || it.sourceBn == "ডিডব্লিউ বাংলা" }
        } else {
            DIRECT_PUBLISHER_FEEDS.filter { !it.isIntl }
        }

        val completedResults = ConcurrentHashMap<Int, List<NewsItem>>()
        val batchJob = SupervisorJob(coroutineContext[Job])
        val batchScope = CoroutineScope(coroutineContext + batchJob + Dispatchers.IO)

        try {
            val feedJobs = feedsToFetch.mapIndexed { index, spec ->
                batchScope.launch {
                    val items = try {
                        directFeedSemaphore.withPermit {
                            withTimeoutOrNull(perFeedTimeoutMs) {
                                val feedJob = coroutineContext[Job]
                                val rawItems = runInterruptible(Dispatchers.IO) {
                                    currentFeedJobHolder.set(feedJob)
                                    try {
                                        fetcher(spec)
                                    } finally {
                                        currentFeedJobHolder.remove()
                                    }
                                }
                                filterPinnedStaleDirectItems(rawItems, now)
                            } ?: run {
                                logDiag("DIRECT_FEED_TIMEOUT | source=\"${spec.sourceBn}\" | url=\"${spec.feedUrl}\"")
                                emptyList()
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        logDiag("DIRECT_FEED_ERROR | source=\"${spec.sourceBn}\" | url=\"${spec.feedUrl}\" | error=\"${e.message}\"")
                        emptyList()
                    }
                    if (items.isNotEmpty()) {
                        completedResults[index] = items
                    }
                }
            }

            withTimeoutOrNull(batchDeadlineMs) {
                feedJobs.joinAll()
            }
        } finally {
            withContext(NonCancellable) {
                batchJob.cancelAndJoin()
            }
        }

        val fetched = completedResults.entries
            .sortedBy { it.key }
            .flatMap { it.value }

        if (fetched.isNotEmpty()) {
            val finishedAt = System.currentTimeMillis()
            if (isIntl) {
                cachedDirectIntlItems = fetched
                cachedDirectIntlTimestamp = finishedAt
            } else {
                cachedDirectDomesticItems = fetched
                cachedDirectDomesticTimestamp = finishedAt
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
            executeCancellableCall(fastFeedClient.newCall(req)) { res ->
                if (!res.isSuccessful) {
                    logDiag("DIRECT_FEED_FAIL | source=\"${spec.sourceBn}\" | url=\"${spec.feedUrl}\" | HTTP ${res.code}")
                    return@executeCancellableCall emptyList()
                }
                val readResult = readBoundedBodyWithDiagnostics(res, DIRECT_FEED_MAX_RESPONSE_BYTES)
                if (readResult.isTruncated || !readResult.isCompleteXml) {
                    logDiag(
                        "DIRECT_FEED_TRUNCATED_OR_PARTIAL | source=\"${spec.sourceBn}\" | url=\"${spec.feedUrl}\" | " +
                            "bytesRead=${readResult.bytesRead} | maxBytes=$DIRECT_FEED_MAX_RESPONSE_BYTES | " +
                            "isTruncated=${readResult.isTruncated} | isCompleteXml=${readResult.isCompleteXml} | " +
                            "droppedPartialTrailingItem=${readResult.droppedPartialTrailingItem}"
                    )
                }
                val parsed = parseXml(
                    xml = readResult.body,
                    defaultSource = spec.sourceBn,
                    defaultSourceUrl = spec.baseUrl,
                    defaultCategoryId = spec.defaultCategory
                )
                val withImgCount = parsed.count { !it.imageUrl.isNullOrBlank() }
                logDiag(
                    "DIRECT_FEED_OK | source=\"${spec.sourceBn}\" | totalItems=${parsed.size} | " +
                        "bytesRead=${readResult.bytesRead} | isCompleteXml=${readResult.isCompleteXml} | " +
                        "itemsWithRssImage=$withImgCount | sampleImage=\"${parsed.firstOrNull { !it.imageUrl.isNullOrBlank() }?.imageUrl ?: "NONE"}\""
                )
                parsed
            }
        } catch (e: CancellationException) {
            throw e
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

        return executeCancellableCall(client.newCall(request)) { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
            val body = readBoundedBody(response, GOOGLE_FEED_MAX_RESPONSE_BYTES)
            parseXml(body)
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
            else -> query.split(whitespaceRegex).filter { it.length >= 2 }
        }
    }

    /**
     * Enriches Google News RSS items with verified RSS image URLs when the same news story
     * is present in any direct publisher RSS feed.
     * Precomputes normalized title signatures for direct items once per batch to avoid
     * repeated O(N * M) regex tokenization.
     */
    internal fun enrichGoogleItemsWithDirectImages(
        googleItems: List<NewsItem>,
        directItems: List<NewsItem>
    ): List<NewsItem> {
        val directWithImages = directItems.filter { !it.imageUrl.isNullOrBlank() }
        if (directWithImages.isEmpty() || googleItems.isEmpty()) return googleItems

        // Precompute signatures for all direct items with images once before iterating
        val directSignatures = directWithImages.map { dItem ->
            dItem to getOrCreateTitleSignature(dItem.title)
        }

        return googleItems.map { gItem ->
            var matchedImage: String? = gItem.imageUrl

            if (matchedImage.isNullOrBlank()) {
                val gSig = getOrCreateTitleSignature(gItem.title)
                for ((dItem, dSig) in directSignatures) {
                    if (areArticlesDuplicateWithSignatures(gItem.time, gSig, dItem.time, dSig)) {
                        matchedImage = dItem.imageUrl
                        break
                    }
                }
            }

            val enrichedRelated = gItem.related.map { rel ->
                if (!rel.imageUrl.isNullOrBlank()) {
                    rel
                } else {
                    val relSig = getOrCreateTitleSignature(rel.title)
                    val relMatch = directSignatures.firstOrNull { (dItem, dSig) ->
                        areArticlesDuplicateWithSignatures(rel.time, relSig, dItem.time, dSig)
                    }?.first?.imageUrl
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
        // Group direct items by source and interleave them round-robin by recency so all sources appear evenly.
        // Uses effectiveSortTime (verified item time or bounded fallbackTime) and preserves original feedOrderIndex.
        val directBySource = directItems
            .sortedWith(
                compareByDescending<NewsItem> { it.effectiveSortTime ?: Long.MIN_VALUE }
                    .thenByDescending { it.time != null }
                    .thenBy { if (it.feedOrderIndex >= 0) it.feedOrderIndex else Int.MAX_VALUE }
            )
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
     * - Safely excludes any trailing truncated/unclosed <item> or <entry>.
     * - Preserves verified item timestamps in [NewsItem.time] (never substituting `now` for missing dates).
     * - Assigns [NewsItem.feedOrderIndex] and computes bounded neighbor/channel [NewsItem.fallbackTime]
     *   with [NewsItem.isEstimatedTime] = true for date-less items so feed order is preserved without
     *   either dropping date-less items to 0L or promoting old items to the top.
     */
    fun parseXml(
        xml: String,
        defaultSource: String = "",
        defaultSourceUrl: String = "",
        defaultCategoryId: String = "all",
        nowMs: Long = System.currentTimeMillis()
    ): List<NewsItem> {
        if (xml.isBlank()) return emptyList()
        val (safeXml, _) = sanitizeTruncatedOrPartialXml(xml, isTruncated = !hasClosingRootXmlTag(xml))
        if (safeXml.isBlank()) return emptyList()

        val firstItemIdx = findFirstEntryOrItemIndex(safeXml)
        val headerXml = if (firstItemIdx > 0) safeXml.substring(0, firstItemIdx) else ""
        val channelDateStr = if (headerXml.isNotEmpty()) {
            extractTagNamespaceAgnostic(headerXml, "lastBuildDate")
                .ifEmpty { extractTagNamespaceAgnostic(headerXml, "pubDate") }
                .ifEmpty { extractTagNamespaceAgnostic(headerXml, "updated") }
        } else {
            ""
        }
        val channelFallbackMs = parseDate(channelDateStr, nowMs)

        val out = mutableListOf<NewsItem>()
        val entryPattern = Pattern.compile("<(?:item|entry)(?:\\s[^>]*)?>(.*?)</(?:item|entry)>", Pattern.DOTALL or Pattern.CASE_INSENSITIVE)
        val nestedOpenPattern = Pattern.compile("<(?:item|entry)(?:\\s[^>]*)?>", Pattern.CASE_INSENSITIVE)
        val matcher = entryPattern.matcher(safeXml)

        while (matcher.find()) {
            var block = matcher.group(1) ?: continue
            // If an earlier <item>/<entry> was malformed and lacked a closing tag before the next opening tag,
            // strip the unclosed prefix so only the innermost complete item block is parsed.
            val nestedMatcher = nestedOpenPattern.matcher(block)
            var lastNestedEnd = -1
            while (nestedMatcher.find()) {
                lastNestedEnd = nestedMatcher.end()
            }
            if (lastNestedEnd in 1..block.length) {
                block = block.substring(lastNestedEnd)
            }

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

            val parsedItemTime = parseDate(pub, nowMs)

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
                            val relSourceUrl = if (rs.equals(source, ignoreCase = true)) sourceUrl else ""
                            related.add(
                                NewsItem(
                                    title = rt,
                                    source = rs,
                                    sourceUrl = relSourceUrl,
                                    link = rLink,
                                    time = parsedItemTime,
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
                time = parsedItemTime,
                categoryId = categoryText,
                related = related,
                snippet = plainSnippet,
                imageUrl = rssImage,
                fallbackTime = null,
                isEstimatedTime = false,
                feedOrderIndex = out.size
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

        return assignEstimatedFallbackTimes(out, channelFallbackMs, nowMs)
    }

    /**
     * Assigns bounded [NewsItem.fallbackTime] and [NewsItem.isEstimatedTime] = true for items whose
     * item-level [NewsItem.time] is null, while strictly keeping [NewsItem.time] == null:
     * - Never uses `nowMs` when `pubDate` is empty or missing.
     * - When sibling items in the same feed have valid timestamps (e.g. Purbokone or BBC Bangla),
     *   bounds the estimated timestamp to the surrounding dated items in feed order so original
     *   feed order is preserved AND old articles are never promoted to a regenerated channel `lastBuildDate`.
     * - When no items in the feed have item-level dates at all, uses `channelFallbackMs - idx * 1000L`
     *   as a separate estimated fallback date if available.
     */
    private fun assignEstimatedFallbackTimes(
        items: List<NewsItem>,
        channelFallbackMs: Long?,
        nowMs: Long
    ): List<NewsItem> {
        if (items.isEmpty() || items.all { it.time != null }) return items

        return items.mapIndexed { idx, item ->
            if (item.time != null) return@mapIndexed item

            var prevIdx = -1
            var prevTime: Long? = null
            for (p in (idx - 1) downTo 0) {
                val t = items[p].time
                if (t != null) {
                    prevIdx = p
                    prevTime = t
                    break
                }
            }

            var nextIdx = -1
            var nextTime: Long? = null
            for (n in (idx + 1) until items.size) {
                val t = items[n].time
                if (t != null) {
                    nextIdx = n
                    nextTime = t
                    break
                }
            }

            val estimatedMs: Long? = when {
                prevTime != null && nextTime != null -> {
                    val span = (nextIdx - prevIdx).coerceAtLeast(2)
                    val step = (idx - prevIdx).coerceAtLeast(1)
                    if (prevTime >= nextTime) {
                        val diff = prevTime - nextTime
                        if (diff >= span * 1_000L) {
                            prevTime - (diff * step) / span
                        } else {
                            (prevTime - step * 1_000L).coerceAtLeast(nextTime)
                        }
                    } else {
                        (minOf(prevTime, nextTime) - step * 1_000L).coerceAtLeast(1L)
                    }
                }
                prevTime == null && nextTime != null -> {
                    // Leading date-less item before the first dated item (e.g. Purbokone item #1):
                    // Anchor just ahead of nextTime (+1s per step) so it stays ahead of item #2 in feed order
                    // without jumping to a regenerated today's channel <lastBuildDate>.
                    val stepsAhead = (nextIdx - idx).coerceAtLeast(1)
                    val anchoredToNext = nextTime + stepsAhead * 1_000L
                    val upperCap = if (channelFallbackMs != null && channelFallbackMs >= nextTime) {
                        minOf(channelFallbackMs, nowMs + FUTURE_TIMESTAMP_TOLERANCE_MS)
                    } else {
                        maxOf(nextTime + stepsAhead * 1_000L, nowMs)
                    }
                    anchoredToNext.coerceAtMost(upperCap)
                }
                prevTime != null && nextTime == null -> {
                    val stepsBehind = (idx - prevIdx).coerceAtLeast(1)
                    (prevTime - stepsBehind * 1_000L).coerceAtLeast(1L)
                }
                channelFallbackMs != null -> {
                    (channelFallbackMs - idx * 1_000L).coerceAtLeast(1L)
                }
                else -> null
            }

            if (estimatedMs != null) {
                item.copy(
                    fallbackTime = estimatedMs,
                    isEstimatedTime = true
                )
            } else {
                item
            }
        }
    }

    /**
     * Extracts the valid HTML Article URL from an RSS `<item>` or Atom `<entry>` block:
     * - Attribute-order independent parsing (`rel` before `href` or `href` before `rel`).
     * - Prioritizes `rel="alternate"` HTML Article URLs (`text/html`, `application/xhtml+xml`, or untyped).
     * - Strictly rejects `rel="self"`, `rel="enclosure"`, `rel="related"`, `rel="replies"`, `rel="via"`,
     *   and any other non-alternate `rel` values.
     * - Strictly rejects non-HTML MIME types (such as `image/`, `audio/`, `video/`, `application/atom+xml`).
     * - Strictly rejects invalid URLs and image/media/feed asset URLs via [isValidArticleWebUrl].
     */
    internal fun extractItemLink(block: String, baseUrl: String): String {
        // 1. Standard RSS <link>https://...</link> (only when tag has no non-alternate rel attribute)
        val textLinkMat = Pattern.compile(
            "<(?:[A-Za-z0-9_-]+:)?link\\b([^>]*)>(.*?)</(?:[A-Za-z0-9_-]+:)?link>",
            Pattern.DOTALL or Pattern.CASE_INSENSITIVE
        ).matcher(block)
        while (textLinkMat.find()) {
            val attrs = textLinkMat.group(1) ?: ""
            val relAttr = extractAttr(attrs, "rel")?.trim()?.lowercase(Locale.ROOT).orEmpty()
            if (relAttr.isNotEmpty() && relAttr != "alternate") continue
            val typeAttr = extractAttr(attrs, "type")?.trim()?.lowercase(Locale.ROOT).orEmpty()
            if (!isAllowedArticleLinkMimeType(typeAttr)) continue

            val innerRaw = stripTags(unescape(textLinkMat.group(2) ?: "")).trim()
            if (innerRaw.isNotEmpty()) {
                val resolved = resolveRelativeUrl(innerRaw, baseUrl)
                if (isValidArticleWebUrl(resolved)) {
                    return resolved
                }
            }
        }

        // 2. Atom / self-closing / attribute-based <link ... href="..." ...>
        val atomLinkMat = Pattern.compile(
            "<(?:[A-Za-z0-9_-]+:)?link\\b([^>]*)/?>",
            Pattern.CASE_INSENSITIVE
        ).matcher(block)

        var bestExplicitHtmlAlternate: String? = null
        var bestUntypedAlternate: String? = null
        var bestExplicitHtmlDefaultRel: String? = null
        var bestUntypedDefaultRel: String? = null

        while (atomLinkMat.find()) {
            val attrs = atomLinkMat.group(1) ?: continue
            val rawHref = extractAttr(attrs, "href") ?: continue
            val relAttr = extractAttr(attrs, "rel")?.trim()?.lowercase(Locale.ROOT).orEmpty()
            val typeAttr = extractAttr(attrs, "type")?.trim()?.lowercase(Locale.ROOT).orEmpty()

            // Only rel="alternate" or omitted rel (Atom default) may ever be used as an article URL.
            // Explicitly rejects self, enclosure, related, replies, via, edit, hub, etc.
            if (relAttr.isNotEmpty() && relAttr != "alternate") continue
            if (!isAllowedArticleLinkMimeType(typeAttr)) continue

            val resolvedHref = resolveRelativeUrl(rawHref, baseUrl)
            if (!isValidArticleWebUrl(resolvedHref)) continue

            val isExplicitHtml = typeAttr.startsWith("text/html") || typeAttr.startsWith("application/xhtml+xml")
            if (relAttr == "alternate") {
                if (isExplicitHtml) {
                    if (bestExplicitHtmlAlternate == null) bestExplicitHtmlAlternate = resolvedHref
                } else if (bestUntypedAlternate == null) {
                    bestUntypedAlternate = resolvedHref
                }
            } else {
                if (isExplicitHtml) {
                    if (bestExplicitHtmlDefaultRel == null) bestExplicitHtmlDefaultRel = resolvedHref
                } else if (bestUntypedDefaultRel == null) {
                    bestUntypedDefaultRel = resolvedHref
                }
            }
        }

        bestExplicitHtmlAlternate?.let { return it }
        bestUntypedAlternate?.let { return it }
        bestExplicitHtmlDefaultRel?.let { return it }
        bestUntypedDefaultRel?.let { return it }

        // 3. Fallback to <guid> when it is a valid article web URL and not marked isPermaLink="false"
        val guidMat = Pattern.compile(
            "<(?:[A-Za-z0-9_-]+:)?guid\\b([^>]*)>(.*?)</(?:[A-Za-z0-9_-]+:)?guid>",
            Pattern.DOTALL or Pattern.CASE_INSENSITIVE
        ).matcher(block)
        while (guidMat.find()) {
            val attrs = guidMat.group(1) ?: ""
            val isPermaLinkAttr = extractAttr(attrs, "isPermaLink")?.trim()?.lowercase(Locale.ROOT)
            if (isPermaLinkAttr == "false") continue
            val rawGuid = stripTags(unescape(guidMat.group(2) ?: "")).trim()
            if (rawGuid.startsWith("http://") || rawGuid.startsWith("https://")) {
                if (isValidArticleWebUrl(rawGuid)) {
                    return rawGuid
                }
            }
        }

        return ""
    }

    private fun isAllowedArticleLinkMimeType(typeAttrLower: String): Boolean {
        if (typeAttrLower.isEmpty()) return true
        return typeAttrLower.startsWith("text/html") ||
            typeAttrLower.startsWith("application/xhtml+xml")
    }

    /**
     * Validates that a candidate item link is a legitimate HTTP/HTTPS web article URL,
     * rejecting malformed URLs, non-web schemes, and direct image/audio/video/feed file URLs.
     */
    internal fun isValidArticleWebUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val trimmed = url.trim()
        if (!trimmed.startsWith("http://", ignoreCase = true) &&
            !trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return false
        }

        val host = try {
            URI(trimmed.replace(" ", "%20")).host?.lowercase(Locale.ROOT).orEmpty()
        } catch (_: Exception) {
            ""
        }
        if (host.isEmpty() || !host.contains(".")) return false

        val lower = trimmed.lowercase(Locale.ROOT)
        val pathPart = lower.substringBefore("?").substringBefore("#")
        val nonArticleExtensions = listOf(
            ".jpg", ".jpeg", ".png", ".webp", ".gif", ".avif", ".svg", ".ico", ".bmp", ".tiff",
            ".mp3", ".mp4", ".m4a", ".ogg", ".wav", ".webm", ".mkv", ".avi", ".mov",
            ".pdf", ".zip", ".xml", ".rss", ".atom", ".json"
        )
        if (nonArticleExtensions.any { pathPart.endsWith(it) }) {
            return false
        }
        return true
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

    data class BoundedBodyReadResult(
        val body: String,
        val bytesRead: Int,
        val isTruncated: Boolean,
        val isCompleteXml: Boolean,
        val droppedPartialTrailingItem: Boolean
    )

    private fun readBoundedBody(response: okhttp3.Response, maxBytes: Int): String {
        return readBoundedBodyWithDiagnostics(response, maxBytes).body
    }

    internal fun readBoundedBodyWithDiagnostics(
        response: okhttp3.Response,
        maxBytes: Int
    ): BoundedBodyReadResult {
        val safeMax = maxBytes.coerceAtLeast(1024)
        val source = response.body?.source()
            ?: return BoundedBodyReadResult(
                body = "",
                bytesRead = 0,
                isTruncated = false,
                isCompleteXml = false,
                droppedPartialTrailingItem = false
            )
        source.request(safeMax.toLong() + 1L)
        val buffer = source.buffer.clone()
        val isTruncated = buffer.size > safeMax.toLong()
        val sizeToRead = buffer.size.coerceAtMost(safeMax.toLong()).toInt()
        val rawBytes = buffer.readByteArray(sizeToRead.toLong())
        return decodeBoundedXmlBytes(rawBytes, isTruncated)
    }

    internal fun readBoundedBytesWithDiagnostics(
        rawBytes: ByteArray,
        maxBytes: Int
    ): BoundedBodyReadResult {
        val safeMax = maxBytes.coerceAtLeast(64)
        val isTruncated = rawBytes.size > safeMax
        val slice = if (isTruncated) rawBytes.copyOfRange(0, safeMax) else rawBytes
        return decodeBoundedXmlBytes(slice, isTruncated)
    }

    private fun decodeBoundedXmlBytes(
        bytes: ByteArray,
        isTruncated: Boolean
    ): BoundedBodyReadResult {
        if (bytes.isEmpty()) {
            return BoundedBodyReadResult(
                body = "",
                bytesRead = 0,
                isTruncated = isTruncated,
                isCompleteXml = false,
                droppedPartialTrailingItem = false
            )
        }
        val validUtf8Len = if (isTruncated) trimIncompleteTrailingUtf8Bytes(bytes) else bytes.size
        val decoded = String(bytes, 0, validUtf8Len, Charsets.UTF_8)
        val hasRootClose = hasClosingRootXmlTag(decoded)
        val isCompleteXml = !isTruncated && hasRootClose
        val (sanitizedXml, droppedPartial) = sanitizeTruncatedOrPartialXml(decoded, isTruncated || !hasRootClose)
        return BoundedBodyReadResult(
            body = sanitizedXml,
            bytesRead = bytes.size,
            isTruncated = isTruncated,
            isCompleteXml = isCompleteXml,
            droppedPartialTrailingItem = droppedPartial
        )
    }

    private fun trimIncompleteTrailingUtf8Bytes(bytes: ByteArray): Int {
        val len = bytes.size
        if (len == 0) return 0
        var i = len - 1
        var continuationCount = 0
        while (i >= 0 && continuationCount < 3 && (bytes[i].toInt() and 0xC0) == 0x80) {
            continuationCount++
            i--
        }
        if (i < 0) return len
        val lead = bytes[i].toInt() and 0xFF
        val expectedSeqLen = when {
            (lead and 0x80) == 0x00 -> 1
            (lead and 0xE0) == 0xC0 -> 2
            (lead and 0xF0) == 0xE0 -> 3
            (lead and 0xF8) == 0xF0 -> 4
            else -> 1
        }
        val actualSeqLen = continuationCount + 1
        return if (actualSeqLen < expectedSeqLen) i else len
    }

    internal fun hasClosingRootXmlTag(xml: String): Boolean {
        val tail = if (xml.length > 4096) xml.substring(xml.length - 4096) else xml
        val lower = tail.lowercase(Locale.ROOT)
        return lower.contains("</rss>") ||
            lower.contains("</feed>") ||
            lower.contains("</rdf:rdf>") ||
            lower.contains("</rdf>")
    }

    private fun findFirstEntryOrItemIndex(xml: String): Int {
        val m = Pattern.compile("<(?:item|entry)(?:\\s|>)", Pattern.CASE_INSENSITIVE).matcher(xml)
        return if (m.find()) m.start() else -1
    }

    /**
     * Ensures that if an XML response is truncated at the byte limit or lacks a closing root tag,
     * any trailing unclosed `<item>` or `<entry>` fragment after the last complete `</item>` or
     * `</entry>` is stripped so partial items are never parsed as valid items.
     */
    internal fun sanitizeTruncatedOrPartialXml(
        xml: String,
        isTruncated: Boolean
    ): Pair<String, Boolean> {
        if (xml.isEmpty()) return "" to false
        val lastCloseItem = xml.lastIndexOf("</item>", ignoreCase = true)
        val lastCloseEntry = xml.lastIndexOf("</entry>", ignoreCase = true)

        val lastCompleteEnd = when {
            lastCloseItem >= 0 && lastCloseItem >= lastCloseEntry -> lastCloseItem + "</item>".length
            lastCloseEntry >= 0 -> lastCloseEntry + "</entry>".length
            else -> -1
        }

        if (lastCompleteEnd == -1) {
            val hasOpenItem = findFirstEntryOrItemIndex(xml) >= 0
            return if (hasOpenItem) "" to true else xml to false
        }

        val tailAfterLastClose = xml.substring(lastCompleteEnd)
        val hasTrailingUnclosedItem = Pattern.compile(
            "<(?:item|entry)(?:\\s|>|$)",
            Pattern.CASE_INSENSITIVE
        ).matcher(tailAfterLastClose).find()

        if (hasTrailingUnclosedItem) {
            return xml.substring(0, lastCompleteEnd) to true
        }
        if (isTruncated) {
            return xml.substring(0, lastCompleteEnd) to false
        }
        return xml to false
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

    /**
     * Single-pass Android & JVM-safe XML/HTML entity unescaper:
     * - Unwraps `<![CDATA[...]]>` blocks.
     * - Decodes numeric decimal (`&#123;`) and hex (`&#x1F4F0;`) entities with strict Unicode
     *   codepoint validation (rejecting surrogates and out-of-range codepoints).
     * - Decodes named HTML/XML entities (`&nbsp;`, `&ndash;`, `&mdash;`, `&zwnj;`, `&rsquo;`,
     *   `&lsquo;`, `&ldquo;`, `&rdquo;`, `&ntilde;`, `&lt;`, `&gt;`, `&quot;`, `&apos;`, `&amp;`, etc.)
     *   in a single left-to-right pass so double-decoding (e.g. `&amp;nbsp;` or `&#38;lt;`) never occurs.
     */
    internal fun unescape(raw: String): String {
        if (raw.isEmpty()) return ""
        val cdataUnwrapped = if (raw.contains("<![CDATA[")) {
            raw.replace(Regex("<!\\[CDATA\\[(.*?)\\]\\]>", RegexOption.DOT_MATCHES_ALL)) {
                it.groupValues[1]
            }
        } else {
            raw
        }
        if (cdataUnwrapped.indexOf('&') == -1) {
            return cdataUnwrapped.trim()
        }
        return decodeEntitiesSinglePass(cdataUnwrapped).trim()
    }

    private fun decodeEntitiesSinglePass(s: String): String {
        val len = s.length
        val sb = StringBuilder(len)
        var i = 0
        while (i < len) {
            val ch = s[i]
            if (ch == '&') {
                val maxEnd = minOf(len, i + 14)
                var semi = -1
                var k = i + 1
                while (k < maxEnd) {
                    val c = s[k]
                    if (c == ';') {
                        semi = k
                        break
                    }
                    if (c == '&' || c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '<' || c == '>') {
                        break
                    }
                    k++
                }
                if (semi != -1 && semi > i + 1) {
                    val entityBody = s.substring(i + 1, semi)
                    val resolved = resolveSingleXmlOrHtmlEntity(entityBody)
                    if (resolved != null) {
                        sb.append(resolved)
                        i = semi + 1
                        continue
                    }
                }
            }
            sb.append(ch)
            i++
        }
        return sb.toString()
    }

    private fun resolveSingleXmlOrHtmlEntity(entity: String): String? {
        if (entity.isEmpty()) return null
        if (entity[0] == '#') {
            if (entity.length < 2) return null
            val codePoint = try {
                if (entity.length > 2 && (entity[1] == 'x' || entity[1] == 'X')) {
                    entity.substring(2).toInt(16)
                } else {
                    entity.substring(1).toInt(10)
                }
            } catch (_: NumberFormatException) {
                return null
            }
            if (codePoint == 160) return " "
            if (codePoint in 0xD800..0xDFFF) return null
            if (codePoint !in 1..0x10FFFF) return null
            if (codePoint < 32 && codePoint != 9 && codePoint != 10 && codePoint != 13) return null
            return try {
                String(Character.toChars(codePoint))
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        // Case-sensitive check for entities where uppercase/lowercase differ (e.g. Ntilde vs ntilde)
        when (entity) {
            "Ntilde" -> return "Ñ"
            "ntilde" -> return "ñ"
            "Eacute" -> return "É"
            "eacute" -> return "é"
            "Aacute" -> return "Á"
            "aacute" -> return "á"
            "Iacute" -> return "Í"
            "iacute" -> return "í"
            "Oacute" -> return "Ó"
            "oacute" -> return "ó"
            "Uacute" -> return "Ú"
            "uacute" -> return "ú"
            "Uuml" -> return "Ü"
            "uuml" -> return "ü"
            "Ouml" -> return "Ö"
            "ouml" -> return "ö"
            "Ccedil" -> return "Ç"
            "ccedil" -> return "ç"
        }

        return when (entity.lowercase(Locale.ROOT)) {
            "nbsp" -> " "
            "ndash" -> "–"
            "mdash" -> "—"
            "zwnj" -> "\u200C"
            "zwj" -> "\u200D"
            "rsquo" -> "’"
            "lsquo" -> "‘"
            "ldquo" -> "“"
            "rdquo" -> "”"
            "ntilde" -> "ñ"
            "amp" -> "&"
            "lt" -> "<"
            "gt" -> ">"
            "quot" -> "\""
            "apos" -> "'"
            "hellip" -> "…"
            "bull" -> "•"
            "copy" -> "©"
            "reg" -> "®"
            "trade" -> "™"
            "laquo" -> "«"
            "raquo" -> "»"
            "euro" -> "€"
            "pound" -> "£"
            "yen" -> "¥"
            "deg" -> "°"
            "plusmn" -> "±"
            "times" -> "×"
            "divide" -> "÷"
            else -> null
        }
    }

    fun coverageCacheKey(intl: Boolean, filterQuery: String = ""): String {
        val normQuery = filterQuery.trim().lowercase(Locale.ROOT)
        return "intl=$intl|q=$normQuery"
    }

    /**
     * Returns the processed [CachedCoverageResult] for [intl] and [filterQuery] if present in memory.
     * IMPORTANT: TTL expiry NEVER deletes or hides previously cached Coverage results; stale entries
     * remain available for immediate display (Stale-While-Revalidate) and fallback on refresh failure.
     */
    fun getCachedCoverageResult(intl: Boolean, filterQuery: String = ""): CachedCoverageResult? {
        val key = coverageCacheKey(intl, filterQuery)
        return coverageMemoryCache[key] ?: coverageStateHolder.getLastSuccessfulResult(key)
    }

    fun isCoverageCacheFresh(
        intl: Boolean,
        filterQuery: String = "",
        nowMs: Long = System.currentTimeMillis(),
        ttlMs: Long = COVERAGE_CACHE_TTL_MS
    ): Boolean {
        val cached = getCachedCoverageResult(intl, filterQuery) ?: return false
        return !cached.isExpired(nowMs, ttlMs)
    }

    fun putCachedCoverageResult(result: CachedCoverageResult) {
        if (result.coverageList.isNotEmpty()) {
            coverageMemoryCache[result.cacheKey] = result
            coverageStateHolder.recordSuccessfulResult(result)
        }
    }

    suspend fun fetchCoverage(intl: Boolean): List<Coverage> {
        return fetchCoverageCached(intl = intl).coverageList
    }

    suspend fun fetchCoverageCached(
        intl: Boolean,
        filterQuery: String = "",
        forceRefresh: Boolean = false,
        nowMs: Long = System.currentTimeMillis(),
        ttlMs: Long = COVERAGE_CACHE_TTL_MS,
        topicFetcher: (suspend (String, Boolean) -> List<NewsItem>)? = null
    ): CachedCoverageResult = withContext(Dispatchers.IO) {
        val q = filterQuery.trim()
        val key = coverageCacheKey(intl, q)

        if (!forceRefresh) {
            val cached = getCachedCoverageResult(intl, q)
            if (cached != null && !cached.isExpired(nowMs, ttlMs)) {
                coverageStateHolder.recordSuccessfulResult(cached)
                return@withContext cached
            }
        }

        val shared: SharedInFlightRequest<CachedCoverageResult> = synchronized(inFlightLock) {
            val existing = inFlightCoverageRequests[key]
            if (existing != null && existing.deferred.isActive) {
                existing.activeWaiters++
                existing
            } else {
                lateinit var created: SharedInFlightRequest<CachedCoverageResult>
                val deferred = sharedFetchScope.async(start = CoroutineStart.LAZY) {
                    try {
                        performFetchCoverage(
                            intl = intl,
                            filterQuery = q,
                            cacheKey = key,
                            nowMs = nowMs,
                            topicFetcher = topicFetcher
                        )
                    } finally {
                        synchronized(inFlightLock) {
                            if (inFlightCoverageRequests[key] === created) {
                                inFlightCoverageRequests.remove(key)
                            }
                        }
                    }
                }
                created = SharedInFlightRequest(deferred = deferred, activeWaiters = 1)
                inFlightCoverageRequests[key] = created
                created
            }
        }

        shared.deferred.start()
        var cancelled = false
        try {
            shared.deferred.await()
        } catch (e: CancellationException) {
            cancelled = true
            val shouldCancelUnderlying = synchronized(inFlightLock) {
                shared.activeWaiters--
                if (shared.activeWaiters <= 0) {
                    if (inFlightCoverageRequests[key] === shared) {
                        inFlightCoverageRequests.remove(key)
                    }
                    true
                } else {
                    false
                }
            }
            if (shouldCancelUnderlying) {
                withContext(NonCancellable) {
                    shared.deferred.cancelAndJoin()
                }
            }
            throw e
        } finally {
            if (!cancelled) {
                synchronized(inFlightLock) {
                    shared.activeWaiters = (shared.activeWaiters - 1).coerceAtLeast(0)
                }
            }
        }
    }

    private suspend fun performFetchCoverage(
        intl: Boolean,
        filterQuery: String,
        cacheKey: String,
        nowMs: Long,
        topicFetcher: (suspend (String, Boolean) -> List<NewsItem>)?
    ): CachedCoverageResult = coroutineScope {
        coverageFetchExecutionCounter.incrementAndGet()

        val topics = if (filterQuery.isNotEmpty()) {
            listOf(filterQuery)
        } else if (intl) {
            listOf("", "WORLD", "BUSINESS", "TECHNOLOGY", "SPORTS", "SCIENCE", "HEALTH", "ENTERTAINMENT")
        } else {
            listOf("", "রাজনীতি", "ক্রিকেট", "অর্থনীতি", "বিনোদন", "প্রযুক্তি", "শিক্ষা", "আবহাওয়া")
        }

        val deferreds = topics.map { topic ->
            async(Dispatchers.IO) {
                try {
                    if (topicFetcher != null) {
                        topicFetcher(topic, intl)
                    } else if (intl) {
                        fetchNews(intl = true, section = topic)
                    } else {
                        fetchNews(query = topic)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    emptyList()
                }
            }
        }

        val allItems = deferreds.awaitAll().flatten()
        if (allItems.isEmpty()) throw java.io.IOException("No news items fetched")

        val (builtCoverage, many, few) = withContext(Dispatchers.Default) {
            coverageBuildExecutionCounter.incrementAndGet()
            val clusters = buildCoverage(allItems, nowMs)
            val manyList = clusters.filter { it.count >= 3 }
            val fewList = clusters.filter { it.count == 2 }
            Triple(clusters, manyList, fewList)
        }

        val result = CachedCoverageResult(
            cacheKey = cacheKey,
            intl = intl,
            filterQuery = filterQuery,
            coverageList = builtCoverage,
            manyCoverage = many,
            fewCoverage = few,
            originalItems = allItems,
            fetchedAtMs = nowMs
        )
        putCachedCoverageResult(result)
        result
    }

    private class Group {
        val items = mutableListOf<NewsItem>()
    }

    private val builtInPublisherLookup: Map<String, String> by lazy {
        val map = mutableMapOf<String, String>()
        for (paper in PapersData.builtInPapers) {
            val host = extractNormalizedHost(paper.openUrl)
            if (host.isEmpty()) continue
            val bnKey = paper.bn.trim().lowercase(Locale.ROOT)
            val enKey = paper.en.trim().lowercase(Locale.ROOT)
            if (bnKey.isNotEmpty()) map.putIfAbsent(bnKey, host)
            if (enKey.isNotEmpty()) map.putIfAbsent(enKey, host)
            map.putIfAbsent(host, host)
            val rawHost = paper.host.trim().lowercase(Locale.ROOT)
            if (rawHost.isNotEmpty()) map.putIfAbsent(rawHost, host)
        }
        for (spec in DIRECT_PUBLISHER_FEEDS) {
            val host = extractNormalizedHost(spec.baseUrl)
            if (host.isEmpty()) continue
            map.putIfAbsent(spec.sourceBn.trim().lowercase(Locale.ROOT), host)
            map.putIfAbsent(spec.sourceEn.trim().lowercase(Locale.ROOT), host)
            map.putIfAbsent(host, host)
        }
        // Explicit, reviewed publisher aliases observed in Google News RSS <source> and <font> tags
        val verifiedAliases = mapOf(
            "দৈনিক প্রথম আলো" to "prothomalo.com",
            "prothom alo" to "prothomalo.com",
            "jagonews24" to "jagonews24.com",
            "jagonews24.com" to "jagonews24.com",
            "jago news 24" to "jagonews24.com",
            "জাগো নিউজ" to "jagonews24.com",
            "জাগোনিউজ২৪.কম" to "jagonews24.com",
            "দ্য ডেইলি স্টার বাংলা" to "thedailystar.net",
            "the daily star" to "thedailystar.net",
            "daily star" to "thedailystar.net",
            "দৈনিক ইত্তেফাক" to "ittefaq.com.bd",
            "the daily ittefaq" to "ittefaq.com.bd",
            "ইত্তেফাক" to "ittefaq.com.bd",
            "দৈনিক যুগান্তর" to "jugantor.com",
            "দৈনিক কালের কণ্ঠ" to "kalerkantho.com",
            "দৈনিক সমকাল" to "samakal.com",
            "দৈনিক ইনকিলাব" to "dailyinqilab.com",
            "দৈনিক নয়া দিগন্ত" to "dailynayadiganta.com",
            "দৈনিক নয়া দিগন্ত" to "dailynayadiganta.com",
            "নয়া দিগন্ত" to "dailynayadiganta.com",
            "দৈনিক মানবজমিন" to "mzamin.com",
            "দৈনিক জনকণ্ঠ" to "dailyjanakantha.com",
            "দৈনিক ভোরের কাগজ" to "bhorerkagoj.com",
            "দৈনিক কালবেলা" to "kalbela.com",
            "দৈনিক আমাদের সময়" to "amadershomoy.com",
            "দৈনিক যায়যায়দিন" to "jaijaidinbd.com",
            "দৈনিক যায়যায়দিন" to "jaijaidinbd.com",
            "যায়যায়দিন" to "jaijaidinbd.com",
            "আমার দেশ" to "dailyamardesh.com",
            "দৈনিক আমার দেশ" to "dailyamardesh.com",
            "রাইজিংবিডি.কম" to "risingbd.com",
            "risingbd.com" to "risingbd.com",
            "বিডিনিউজ টোয়েন্টিফোর ডটকম" to "bdnews24.com",
            "বিডিনিউজ২৪.কম" to "bdnews24.com",
            "বিডিনিউজ২৪" to "bdnews24.com",
            "bdnews24.com" to "bdnews24.com",
            "banglanews24.com" to "banglanews24.com",
            "বাংলানিউজটোয়েন্টিফোর.কম" to "banglanews24.com",
            "bd-pratidin.com" to "bd-pratidin.com",
            "চ্যানেল আই অনলাইন" to "channelionline.com",
            "channel i online" to "channelionline.com",
            "সময় টিভি" to "somoynews.tv",
            "সময় টিভি" to "somoynews.tv",
            "সময় সংবাদ" to "somoynews.tv",
            "সময় সংবাদ" to "somoynews.tv",
            "somoy tv" to "somoynews.tv",
            "somoy news" to "somoynews.tv",
            "bbc" to "bbc.com",
            "বিবিসি" to "bbc.com",
            "bbc news বাংলা" to "bbc.com",
            "bbc bangla" to "bbc.com",
            "dw.com" to "dw.com",
            "ডয়চে ভেলে" to "dw.com",
            "dhakatimes24" to "dhakatimes24.com",
            "dhakatimes24.com" to "dhakatimes24.com",
            "dhaka times" to "dhakatimes24.com",
            "dhaka times 24" to "dhakatimes24.com",
            "ঢাকা টাইমস" to "dhakatimes24.com",
            "bangladesh sangbad sangstha (bss)" to "bssnews.net",
            "bangladesh sangbad sangstha" to "bssnews.net",
            "বাংলাদেশ সংবাদ সংস্থা (বাসস)" to "bssnews.net",
            "বাংলাদেশ সংবাদ সংস্থা" to "bssnews.net",
            "বাসস" to "bssnews.net",
            "bss" to "bssnews.net",
            "bss news" to "bssnews.net",
            "rtv news" to "rtvonline.com",
            "rtv online" to "rtvonline.com",
            "আরটিভি অনলাইন" to "rtvonline.com",
            "ntv online" to "ntvbd.com",
            "এনটিভি অনলাইন" to "ntvbd.com",
            "channel 24" to "channel24bd.tv",
            "চ্যানেল টুয়েন্টিফোর" to "channel24bd.tv",
            "jamuna television" to "jamuna.tv",
            "যমুনা টেলিভিশন" to "jamuna.tv",
            "ekattor tv" to "ekattor.tv",
            "একাত্তর" to "ekattor.tv",
            "independent television" to "itvbd.com",
            "ইনডিপেনডেন্ট টেলিভিশন" to "itvbd.com",
            "dbc news" to "dbcnews.tv",
            "ডিবিসি" to "dbcnews.tv",
            "dhaka post" to "dhakapost.com",
            "dhakapost.com" to "dhakapost.com",
            "dhaka mail" to "dhakamail.com",
            "dhakamail.com" to "dhakamail.com",
            "barta24" to "barta24.com",
            "barta 24" to "barta24.com",
            "barta24.com" to "barta24.com",
            "বার্তা২৪.কম" to "barta24.com",
            "news18 bengali" to "news18.com",
            "news18" to "news18.com",
            "sarabangla" to "sarabangla.net",
            "sarabangla.net" to "sarabangla.net",
            "সারাবাংলা.নেট" to "sarabangla.net",
            "the daily campus" to "thedailycampus.com",
            "ডেইলি ক্যাম্পাস" to "thedailycampus.com",
            "দৈনিক শিক্ষা" to "dainikshiksha.com",
            "dainikshiksha" to "dainikshiksha.com",
            "প্রবাসীর দিগন্ত" to "probashirdiganta.com",
            "probashir diganta" to "probashirdiganta.com",
            "দৈনিক আজাদী" to "dainikazadi.net",
            "dainik azadi" to "dainikazadi.net",
            "দৈনিক পূর্বকোণ" to "dainikpurbokone.net",
            "dainik purbokone" to "dainikpurbokone.net",
            "জুমবাংলা" to "inews.zoombangla.com",
            "zoombangla" to "inews.zoombangla.com",
            "অর্থসূচক" to "arthosuchak.com",
            "arthosuchak" to "arthosuchak.com",
            "জাতিসংঘ সংবাদ" to "news.un.org",
            "un news" to "news.un.org",
            "এবিসি নিউজ" to "abcnews.go.com",
            "abc news" to "abcnews.go.com",
            "south china morning post" to "scmp.com"
        )
        for ((alias, host) in verifiedAliases) {
            map[alias] = host
        }
        map
    }

    private val verifiedTierPublishers: Set<String> by lazy {
        val set = mutableSetOf<String>()
        for (spec in DIRECT_PUBLISHER_FEEDS) {
            val host = extractNormalizedHost(spec.baseUrl)
            if (host.isNotEmpty()) set.add(host)
        }
        for (paper in PapersData.builtInPapers) {
            if (paper.cat in setOf("bn", "en", "online", "intl", "biz", "sports", "tech", "tv")) {
                val host = extractNormalizedHost(paper.openUrl)
                if (host.isNotEmpty()) set.add(host)
            }
        }
        set
    }

    private fun extractNormalizedHost(url: String): String {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return ""
        return try {
            val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                trimmed
            } else {
                "https://$trimmed"
            }
            val h = URI(withScheme.replace(" ", "%20")).host?.lowercase(Locale.ROOT).orEmpty()
            val noWww = if (h.startsWith("www.")) h.substring(4) else h
            if (noWww.startsWith("bangla.") && noWww != "bangla.abplive.com") {
                noWww.removePrefix("bangla.")
            } else if (noWww.startsWith("en.")) {
                noWww.removePrefix("en.")
            } else {
                noWww
            }
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * Resolves a canonical publisher identifier in strict priority order:
     * 1. Canonical publisher ID extracted from [sourceUrl] (when a valid non-Google domain host is present).
     * 2. Explicit, reviewed alias mapping ([builtInPublisherLookup] from [PapersData.builtInPapers],
     *    [DIRECT_PUBLISHER_FEEDS], and reviewed publisher aliases).
     * 3. Distinct, stable ID (`"raw:<cleanName>"`) for unknown publishers so distinct publishers
     *    are never falsely merged.
     */
    fun canonicalPublisherId(sourceName: String, sourceUrl: String = ""): String {
        // Priority 1: Canonical publisher ID from sourceUrl
        val urlHost = extractNormalizedHost(sourceUrl)
        if (urlHost.isNotEmpty() && !urlHost.contains("google.com")) {
            builtInPublisherLookup[urlHost]?.let { return it }
            return urlHost
        }

        // Priority 2: Explicit, reviewed alias mapping
        val cleanName = sourceName.trim().lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), " ")
        if (cleanName.isNotEmpty()) {
            builtInPublisherLookup[cleanName]?.let { return it }
            if (cleanName.contains(".") && !cleanName.contains(" ")) {
                val hostFromName = extractNormalizedHost(cleanName)
                if (hostFromName.isNotEmpty()) {
                    builtInPublisherLookup[hostFromName]?.let { return it }
                    return hostFromName
                }
            }
        }

        // Priority 3: Distinct stable ID for unknown publishers
        return if (cleanName.isNotEmpty()) "raw:$cleanName" else "raw:unknown"
    }

    private val banglaAndEnStopwords = setOf(
        "থেকে", "নিয়ে", "নিয়ে", "করে", "করা", "করার", "হয়", "হয়", "হবে", "হয়েছে", "হয়েছে",
        "বলেন", "বললেন", "জানালেন", "জানিয়েছেন", "জানিয়েছেন", "দেওয়ার", "দেওয়ার", "পাওয়া", "পাওয়া",
        "গেছে", "জন্য", "সঙ্গে", "কাছে", "মধ্যে", "পরে", "আগে", "আজ", "কাল", "এক", "দুই", "তিন",
        "চার", "পাঁচ", "না", "নয়", "নয়", "নেই", "নাই", "ও", "বা", "এবং", "কিন্তু", "যদি", "তবে",
        "এই", "সেই", "কোনো", "কোন", "আরও", "আরো", "সব", "কী", "কি", "কেন", "কীভাবে", "যা", "যার",
        "তা", "তার", "তাদের", "যাদের", "আমাদের", "নিজের", "হতে", "হলো", "হল", "পারে", "উপর", "ওপর",
        "দিয়ে", "দিয়ে", "থাকা", "থাকে", "গেল", "যায়", "যায়", "শুরু", "শেষ", "নতুন", "বড়", "বড়",
        "the", "and", "for", "with", "from", "that", "this", "are", "was", "were", "has", "have",
        "had", "will", "would", "after", "before", "over", "into", "about", "amid", "says", "said"
    )

    private val protectedBanglaWords = setOf(
        "কাদের", "তাহের", "জাহের", "নাসের", "সাদের", "খায়ের", "খায়ের", "জুবায়ের", "জুবায়ের",
        "আগের", "মেয়ের", "মেয়ের", "দেশের", "শহরের", "দলের", "মাসের", "বছরের", "দিনের", "রাতের"
    )

    private val districtLocations = mapOf(
        "ঢাকা" to "dhaka", "ঢাকায়" to "dhaka", "ঢাকায়" to "dhaka", "ঢাকার" to "dhaka",
        "চট্টগ্রাম" to "chattogram", "চট্টগ্রামে" to "chattogram", "চট্টগ্রামের" to "chattogram",
        "রাজশাহী" to "rajshahi", "রাজশাহীতে" to "rajshahi", "রাজশাহীর" to "rajshahi",
        "খুলনা" to "khulna", "খুলনায়" to "khulna", "খুলনায়" to "khulna", "খুলনার" to "khulna",
        "বরিশাল" to "barishal", "বরিশালে" to "barishal", "বরিশালের" to "barishal",
        "সিলেট" to "sylhet", "সিলেটে" to "sylhet", "সিলেটের" to "sylhet",
        "রংপুর" to "rangpur", "রংপুরে" to "rangpur", "রংপুরের" to "rangpur",
        "ময়মনসিংহ" to "mymensingh", "ময়মনসিংহ" to "mymensingh", "ময়মনসিংহে" to "mymensingh",
        "কুমিল্লা" to "cumilla", "কুমিল্লায়" to "cumilla", "কুমিল্লায়" to "cumilla", "কুমিল্লার" to "cumilla",
        "গাজীপুর" to "gazipur", "গাজীপুরে" to "gazipur", "গাজীপুরের" to "gazipur",
        "নারায়ণগঞ্জ" to "narayanganj", "নারায়ণগঞ্জ" to "narayanganj", "নারায়ণগঞ্জে" to "narayanganj",
        "বগুড়া" to "bogura", "বগুড়া" to "bogura", "বগুড়ায়" to "bogura", "বগুড়ার" to "bogura",
        "কক্সবাজার" to "coxsbazar", "কক্সবাজারে" to "coxsbazar", "কক্সবাজারের" to "coxsbazar",
        "যশোর" to "jashore", "যশোরে" to "jashore", "যশোরের" to "jashore",
        "ফরিদপুর" to "faridpur", "ফরিদপুরে" to "faridpur", "ফরিদপুরের" to "faridpur",
        "দিনাজপুর" to "dinajpur", "দিনাজপুরে" to "dinajpur", "দিনাজপুরের" to "dinajpur",
        "নোয়াখালী" to "noakhali", "নোয়াখালী" to "noakhali", "নোয়াখালীতে" to "noakhali",
        "পাবনা" to "pabna", "পাবনায়" to "pabna", "পাবনায়" to "pabna", "পাবনার" to "pabna",
        "টাঙ্গাইল" to "tangail", "টাঙ্গাইলে" to "tangail", "টাঙ্গাইলের" to "tangail",
        "কুষ্টিয়া" to "kushtia", "কুষ্টিয়া" to "kushtia", "কুষ্টিয়ায়" to "kushtia",
        "সাতক্ষীরা" to "satkhira", "সাতক্ষীরায়" to "satkhira", "সাতক্ষীরায়" to "satkhira", "সাতক্ষীরার" to "satkhira",
        "মাদারীপুর" to "madaripur", "মাদারীপুরে" to "madaripur", "মাদারীপুরের" to "madaripur",
        "লক্ষ্মীপুর" to "lakshmipur", "লক্ষ্মীপুরে" to "lakshmipur",
        "ফেনী" to "feni", "ফেনীতে" to "feni", "ফেনীর" to "feni",
        "ব্রাহ্মণবাড়িয়া" to "brahmanbaria", "ব্রাহ্মণবাড়িয়া" to "brahmanbaria",
        "চাঁদপুর" to "chandpur", "চাঁদপুরে" to "chandpur", "চাঁদপুরের" to "chandpur",
        "কিশোরগঞ্জ" to "kishoreganj", "কিশোরগঞ্জে" to "kishoreganj",
        "নেত্রকোনা" to "netrokona", "নেত্রকোনায়" to "netrokona",
        "শেরপুর" to "sherpur", "শেরপুরে" to "sherpur",
        "জামালপুর" to "jamalpur", "জামালপুরে" to "jamalpur",
        "সিরাজগঞ্জ" to "sirajganj", "সিরাজগঞ্জে" to "sirajganj",
        "নাটোর" to "natore", "নাটোরে" to "natore",
        "নওগাঁ" to "naogaon", "নওগাঁয়" to "naogaon", "নওগাঁয়" to "naogaon",
        "ঝিনাইদহ" to "jhenaidah", "ঝিনাইদহে" to "jhenaidah",
        "বাগেরহাট" to "bagerhat", "বাগেরহাটে" to "bagerhat",
        "চুয়াডাঙ্গা" to "chuadanga", "চুয়াডাঙ্গা" to "chuadanga", "চুয়াডাঙ্গায়" to "chuadanga",
        "ভোলা" to "bhola", "ভোলায়" to "bhola", "ভোলায়" to "bhola",
        "পটুয়াখালী" to "patuakhali", "পটুয়াখালী" to "patuakhali",
        "সুনামগঞ্জ" to "sunamganj", "সুনামগঞ্জে" to "sunamganj",
        "হবিগঞ্জ" to "habiganj", "হবিগঞ্জে" to "habiganj",
        "মৌলভীবাজার" to "moulvibazar", "মৌলভীবাজারে" to "moulvibazar",
        "কুড়িগ্রাম" to "kurigram", "কুড়িগ্রাম" to "kurigram",
        "রাঙামাটি" to "rangamati", "বান্দরবান" to "bandarban", "খাগড়াছড়ি" to "khagrachhari",
        "মুন্সিগঞ্জ" to "munshiganj", "মানিকগঞ্জ" to "manikganj", "নরসিংদী" to "narsingdi",
        "গোপালগঞ্জ" to "gopalganj", "শরীয়তপুর" to "shariatpur", "রাজবাড়ী" to "rajbari"
    )

    private val whitespaceRegex = Regex("\\s+")
    private val nonWordSplitRegex = Regex("[^\\p{L}\\p{M}\\p{N}]+")

    internal data class PrecomputedTitleSignature(
        val normTitle: String,
        val districts: Set<String>,
        val tokens: Set<String>
    )

    private val titleSignatureCache = ConcurrentHashMap<String, PrecomputedTitleSignature>()
    internal val titleTokenizationCounter = AtomicInteger(0)

    internal fun clearTitleSignatureCacheForTest() {
        titleSignatureCache.clear()
        titleTokenizationCounter.set(0)
    }

    internal fun getOrCreateTitleSignature(rawTitle: String): PrecomputedTitleSignature {
        val normTitle = rawTitle.trim().replace(whitespaceRegex, " ")
        if (normTitle.isEmpty()) {
            return PrecomputedTitleSignature("", emptySet(), emptySet())
        }
        titleSignatureCache[normTitle]?.let { return it }
        if (titleSignatureCache.size > 4096) {
            titleSignatureCache.clear()
        }
        return titleSignatureCache.computeIfAbsent(normTitle) { key ->
            titleTokenizationCounter.incrementAndGet()
            val rawWords = key.lowercase(Locale.ROOT)
                .split(nonWordSplitRegex)
                .filter { it.length >= 2 }
            val districts = mutableSetOf<String>()
            val tokens = mutableSetOf<String>()
            for (w in rawWords) {
                districtLocations[w]?.let { districts.add(it) }
                if (w in banglaAndEnStopwords) continue
                val norm = normalizeBanglaToken(w)
                if (norm.length >= 2 && norm !in banglaAndEnStopwords) {
                    tokens.add(norm)
                }
            }
            PrecomputedTitleSignature(normTitle = key, districts = districts, tokens = tokens)
        }
    }

    internal fun normalizeBanglaToken(word: String): String {
        if (word.length < 6 || word in protectedBanglaWords) return word
        val pluralSuffixes = listOf("গুলোকে", "গুলোতে", "গুলোর", "দেরকে", "গুলো", "টিকে", "টির")
        for (suf in pluralSuffixes) {
            if (word.length >= suf.length + 4 && word.endsWith(suf)) {
                return word.dropLast(suf.length)
            }
        }
        // Only strip case suffixes (-কে, -ের) on long words (>= 7 chars) leaving at least 5 chars stem
        if (word.length >= 7) {
            for (suf in listOf("কে", "ের")) {
                if (word.endsWith(suf) && word.length - suf.length >= 5) {
                    return word.dropLast(suf.length)
                }
            }
        }
        return word
    }

    fun meaningfulTokens(s: String): Set<String> {
        return getOrCreateTitleSignature(s).tokens
    }

    private fun extractDistrictEntities(title: String): Set<String> {
        return getOrCreateTitleSignature(title).districts
    }

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val inter = a.count { b.contains(it) }
        return inter.toDouble() / (a.size + b.size - inter).toDouble()
    }

    internal fun areArticlesDuplicateWithSignatures(
        t1: Long?,
        sigA: PrecomputedTitleSignature,
        t2: Long?,
        sigB: PrecomputedTitleSignature
    ): Boolean {
        if (t1 != null && t2 != null && abs(t1 - t2) > DUPLICATE_TIME_WINDOW_MS) {
            return false
        }

        if (sigA.normTitle.isNotEmpty() && sigA.normTitle.equals(sigB.normTitle, ignoreCase = true)) {
            return true
        }

        if (sigA.districts.isNotEmpty() && sigB.districts.isNotEmpty() && sigA.districts.intersect(sigB.districts).isEmpty()) {
            return false
        }

        val tokensA = sigA.tokens
        val tokensB = sigB.tokens
        if (tokensA.size < 4 || tokensB.size < 4) {
            return false
        }

        val inter = tokensA.count { tokensB.contains(it) }
        if (inter < 3) return false

        val jac = inter.toDouble() / (tokensA.size + tokensB.size - inter).toDouble()
        if (inter == 3 && jac < 0.50) return false
        return jac >= 0.42
    }

    /**
     * Conservative 4-Gate Duplicate Detection Rule:
     * Gate 1: Time window <= 12 hours when both items have timestamps.
     * Location Conflict Check: Disjoint district entities are never merged.
     * Gate 2: Both titles must have >= 4 meaningful non-stopword tokens (unless titles are identical).
     * Gate 3: Meaningful token intersection >= 4 (or intersection == 3 with Jaccard >= 0.50).
     * Gate 4: Meaningful token Jaccard similarity >= 0.42.
     */
    fun areArticlesDuplicate(a: NewsItem, b: NewsItem): Boolean {
        val t1 = a.time
        val t2 = b.time
        if (t1 != null && t2 != null && abs(t1 - t2) > DUPLICATE_TIME_WINDOW_MS) {
            return false
        }
        val sigA = getOrCreateTitleSignature(a.title)
        val sigB = getOrCreateTitleSignature(b.title)
        return areArticlesDuplicateWithSignatures(t1, sigA, t2, sigB)
    }

    private fun clusterItemsIntoCoverage(all: List<NewsItem>): List<Coverage> {
        val groups = mutableListOf<Group>()

        for (n in all) {
            var hit: Group? = null
            for (g in groups) {
                val anchor = g.items.firstOrNull()
                if (anchor != null && g.items.any { existing -> areArticlesDuplicate(existing, n) }) {
                    hit = g
                    break
                }
            }

            if (hit == null) {
                hit = Group()
                groups.add(hit)
            }

            hit.items.add(n)
            for (rel in n.related) {
                // Only include related items that are within the valid time window
                val tMain = n.time
                val tRel = rel.time
                if (tMain == null || tRel == null || abs(tMain - tRel) <= DUPLICATE_TIME_WINDOW_MS) {
                    hit.items.add(rel)
                }
            }
        }

        val out = mutableListOf<Coverage>()
        for (g in groups) {
            val groupImageUrl = g.items.firstOrNull { !it.imageUrl.isNullOrBlank() }?.imageUrl

            // Sort candidates so for each canonical publisher we keep its newest timestamped report
            // (with image/sourceUrl as tie-breakers), and order the final cluster with the newest
            // timestamped report first so Coverage.headline is always the latest report.
            val sortedCandidates = g.items.sortedWith(
                compareByDescending<NewsItem> { it.time ?: Long.MIN_VALUE }
                    .thenByDescending { it.effectiveSortTime ?: Long.MIN_VALUE }
                    .thenBy { if (it.feedOrderIndex >= 0) it.feedOrderIndex else Int.MAX_VALUE }
                    .thenByDescending { !it.imageUrl.isNullOrBlank() }
                    .thenByDescending { it.sourceUrl.isNotEmpty() }
            )

            val domainBackedSourceNames = sortedCandidates.mapNotNull { cand ->
                val raw = cand.source.trim()
                if (raw.isEmpty() || raw == "অজানা উৎস") return@mapNotNull null
                val pid = canonicalPublisherId(raw, cand.sourceUrl)
                if (!pid.startsWith("raw:")) {
                    raw.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
                } else {
                    null
                }
            }.toSet()

            val seenPublishers = mutableSetOf<String>()
            val uniq = mutableListOf<NewsItem>()

            for (it in sortedCandidates) {
                val rawSource = it.source.trim()
                if (rawSource.isEmpty() || rawSource == "অজানা উৎস") continue
                val cleanSourceName = rawSource.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
                val pubId = canonicalPublisherId(rawSource, it.sourceUrl)
                if (pubId.startsWith("raw:") && cleanSourceName in domainBackedSourceNames) {
                    continue
                }
                if (seenPublishers.add(pubId)) {
                    val itemWithImg = if (it.imageUrl.isNullOrBlank() && !groupImageUrl.isNullOrBlank()) {
                        it.copy(imageUrl = groupImageUrl)
                    } else {
                        it
                    }
                    uniq.add(itemWithImg)
                }
            }

            if (uniq.isNotEmpty()) {
                val orderedByNewest = uniq.sortedWith(
                    compareByDescending<NewsItem> { it.time ?: Long.MIN_VALUE }
                        .thenByDescending { it.effectiveSortTime ?: Long.MIN_VALUE }
                        .thenBy { if (it.feedOrderIndex >= 0) it.feedOrderIndex else Int.MAX_VALUE }
                        .thenByDescending { !it.imageUrl.isNullOrBlank() }
                        .thenByDescending { it.sourceUrl.isNotEmpty() }
                )
                out.add(Coverage(orderedByNewest))
            }
        }
        return out
    }

    /**
     * Zero-drop source diversity limiter: ensures at most [maxConsecutive] consecutive items
     * from the same canonical publisher appear in a row when other publishers are available,
     * deferring excess items in FIFO order without ever dropping any item.
     */
    fun applyZeroDropSourceDiversity(
        clusters: List<Coverage>,
        maxConsecutive: Int = 2
    ): List<Coverage> {
        if (clusters.size <= maxConsecutive) return clusters
        val remaining = clusters.toMutableList()
        val result = mutableListOf<Coverage>()
        var lastPubId: String? = null
        var streak = 0

        while (remaining.isNotEmpty()) {
            val pickIndex = if (streak >= maxConsecutive && lastPubId != null) {
                remaining.indexOfFirst { cov ->
                    val lead = cov.items.firstOrNull()
                    val pubId = if (lead != null) canonicalPublisherId(lead.source, lead.sourceUrl) else ""
                    pubId != lastPubId
                }.takeIf { it >= 0 } ?: 0
            } else {
                0
            }

            val chosen = remaining.removeAt(pickIndex)
            val chosenLead = chosen.items.firstOrNull()
            val chosenPubId = if (chosenLead != null) canonicalPublisherId(chosenLead.source, chosenLead.sourceUrl) else ""

            if (chosenPubId == lastPubId) {
                streak++
            } else {
                lastPubId = chosenPubId
                streak = 1
            }
            result.add(chosen)
        }
        return result
    }

    /**
     * Builds the Latest News (সর্বশেষ সংবাদ) feed:
     * - Strictly sorted by [Coverage.latest] descending (newest first).
     * - Clusters with null timestamps are placed at the very end.
     * - Does NOT sort or demote by image presence.
     * - Applies zero-drop source diversity (max 2 consecutive from the same canonical publisher).
     */
    fun buildLatestNews(
        all: List<NewsItem>,
        nowMs: Long = System.currentTimeMillis()
    ): List<Coverage> {
        val clusters = clusterItemsIntoCoverage(all)
        val withTime = clusters
            .filter { (it.effectiveSortTime ?: 0L) > 0L }
            .sortedWith(
                compareByDescending<Coverage> { it.effectiveSortTime ?: 0L }
                    .thenByDescending { it.latest != null }
                    .thenBy { cov ->
                        val idx = cov.items.firstOrNull()?.feedOrderIndex ?: -1
                        if (idx >= 0) idx else Int.MAX_VALUE
                    }
            )
        val withoutTime = clusters
            .filter { (it.effectiveSortTime ?: 0L) <= 0L }
            .sortedBy { cov ->
                val idx = cov.items.firstOrNull()?.feedOrderIndex ?: -1
                if (idx >= 0) idx else Int.MAX_VALUE
            }

        val diversifiedWithTime = applyZeroDropSourceDiversity(withTime, maxConsecutive = 2)
        return diversifiedWithTime + withoutTime
    }

    suspend fun buildLatestNewsAsync(
        all: List<NewsItem>,
        nowMs: Long = System.currentTimeMillis()
    ): List<Coverage> = withContext(Dispatchers.Default) {
        buildLatestNews(all, nowMs)
    }

    fun computeTopStoryScore(
        cov: Coverage,
        favoriteCanonicalIds: Set<String> = emptySet(),
        favoriteUrls: Set<String> = emptySet(),
        nowMs: Long = System.currentTimeMillis()
    ): Double {
        val domainBackedNames = cov.items.mapNotNull { cand ->
            val raw = cand.source.trim()
            if (raw.isEmpty() || raw == "অজানা উৎস") return@mapNotNull null
            val pid = canonicalPublisherId(raw, cand.sourceUrl)
            if (!pid.startsWith("raw:")) {
                raw.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
            } else {
                null
            }
        }.toSet()

        val seenPubIds = mutableSetOf<String>()
        var uniquePublisherCount = 0
        for (item in cov.items) {
            val raw = item.source.trim()
            if (raw.isEmpty() || raw == "অজানা উৎস") continue
            val cleanName = raw.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
            val pubId = canonicalPublisherId(raw, item.sourceUrl)
            if (pubId.startsWith("raw:") && cleanName in domainBackedNames) {
                continue
            }
            if (seenPubIds.add(pubId)) {
                uniquePublisherCount++
            }
        }
        val uniquePublishers = uniquePublisherCount.coerceIn(1, 8)

        val hasVerifiedTier = cov.items.any { item ->
            val pubId = canonicalPublisherId(item.source, item.sourceUrl)
            pubId in verifiedTierPublishers
        }
        val tierBonus = if (hasVerifiedTier) 8.0 else 0.0

        val hasFavorite = (favoriteCanonicalIds.isNotEmpty() || favoriteUrls.isNotEmpty()) &&
            cov.items.any { item ->
                val pubId = canonicalPublisherId(item.source, item.sourceUrl)
                pubId in favoriteCanonicalIds || (item.sourceUrl.isNotEmpty() && item.sourceUrl in favoriteUrls)
            }
        val favoriteTieBreaker = if (hasFavorite) 2.0 else 0.0

        val latestMs = cov.effectiveSortTime
        val ageHours = if (latestMs != null && latestMs > 0L) {
            ((nowMs - latestMs).coerceAtLeast(0L).toDouble() / 3_600_000.0).coerceAtLeast(0.25)
        } else {
            48.0
        }

        // Image bonus = 0.0, Keyword bonus = 0.0
        val numerator = (30.0 * uniquePublishers) + tierBonus + favoriteTieBreaker
        val decay = (1.0 + (ageHours / 4.0)).pow(1.25)
        return numerator / decay
    }

    /**
     * Builds the Top Stories (প্রধান সংবাদ) feed:
     * - Ranks clusters by [computeTopStoryScore] (unique canonical publisher count, verified tier bonus,
     *   favorite tie-breaker +2.0, zero image bonus, zero keyword bonus, and time decay).
     */
    fun buildTopStories(
        all: List<NewsItem>,
        favoriteUrls: Set<String> = emptySet(),
        nowMs: Long = System.currentTimeMillis()
    ): List<Coverage> {
        val clusters = clusterItemsIntoCoverage(all)
        val favCanonicalIds = favoriteUrls.mapNotNull { url ->
            extractNormalizedHost(url).takeIf { it.isNotEmpty() }
        }.toSet()

        return clusters.sortedWith(
            compareByDescending<Coverage> { cov ->
                computeTopStoryScore(cov, favCanonicalIds, favoriteUrls, nowMs)
            }.thenByDescending { cov ->
                cov.effectiveSortTime ?: 0L
            }.thenByDescending { cov ->
                cov.latest != null
            }.thenBy { cov ->
                val idx = cov.items.firstOrNull()?.feedOrderIndex ?: -1
                if (idx >= 0) idx else Int.MAX_VALUE
            }
        )
    }

    suspend fun buildTopStoriesAsync(
        all: List<NewsItem>,
        favoriteUrls: Set<String> = emptySet(),
        nowMs: Long = System.currentTimeMillis()
    ): List<Coverage> = withContext(Dispatchers.Default) {
        buildTopStories(all, favoriteUrls, nowMs)
    }

    fun buildCoverage(
        all: List<NewsItem>,
        nowMs: Long = System.currentTimeMillis()
    ): List<Coverage> {
        return buildTopStories(all = all, favoriteUrls = emptySet(), nowMs = nowMs)
    }

    suspend fun buildCoverageAsync(
        all: List<NewsItem>,
        nowMs: Long = System.currentTimeMillis()
    ): List<Coverage> = withContext(Dispatchers.Default) {
        buildCoverage(all, nowMs)
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
