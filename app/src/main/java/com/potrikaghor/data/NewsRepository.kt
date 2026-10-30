package com.potrikaghor.data

import com.potrikaghor.model.Coverage
import com.potrikaghor.model.NewsItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class NewsRepository {
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val rfc822DateFormats = listOf(
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.ENGLISH).apply {
            timeZone = TimeZone.getTimeZone("GMT")
        },
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH),
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ENGLISH).apply {
            timeZone = TimeZone.getTimeZone("GMT")
        }
    )

    private fun parseDate(str: String): Long? {
        if (str.isEmpty()) return null
        for (fmt in rfc822DateFormats) {
            try {
                val d = fmt.parse(str.trim())
                if (d != null) return d.time
            } catch (_: Exception) {}
        }
        return null
    }

    suspend fun fetchNews(
        query: String = "",
        intl: Boolean = false,
        section: String = ""
    ): List<NewsItem> = withContext(Dispatchers.IO) {
        val q = query.trim()
        val editionHl = if (intl) "en-US" else "bn"
        val editionGl = if (intl) "US" else "BD"
        val editionCeid = if (intl) "US:en" else "BD:bn"

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
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) PotrikaGhor")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
            val body = response.body?.string() ?: ""
            parseXml(body)
        }
    }

    private fun parseXml(xml: String): List<NewsItem> {
        val out = mutableListOf<NewsItem>()
        val itemPattern = Pattern.compile("<item>(.*?)</item>", Pattern.DOTALL)
        val matcher = itemPattern.matcher(xml)

        while (matcher.find()) {
            val block = matcher.group(1) ?: continue
            var title = extractTag(block, "title")
            val link = extractTag(block, "link")
            val pub = extractTag(block, "pubDate")

            val sourcePattern = Pattern.compile("<source[^>]*url=\"([^\"]*)\"[^>]*>(.*?)</source>", Pattern.DOTALL)
            val sm = sourcePattern.matcher(block)
            val sourceUrl: String
            val source: String
            if (sm.find()) {
                sourceUrl = sm.group(1) ?: ""
                source = unescape(sm.group(2) ?: "")
            } else {
                sourceUrl = ""
                source = ""
            }

            val suffix = " - $source"
            if (source.isNotEmpty() && title.endsWith(suffix)) {
                title = title.substring(0, title.length - suffix.length)
            }

            if (title.isEmpty() || link.isEmpty()) continue

            val related = mutableListOf<NewsItem>()
            val desc = extractTag(block, "description")
            val liPattern = Pattern.compile("<li[^>]*>(.*?)</li>", Pattern.DOTALL)
            val liMatcher = liPattern.matcher(desc)

            while (liMatcher.find()) {
                val li = liMatcher.group(1) ?: continue
                val aPat = Pattern.compile("<a[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", Pattern.DOTALL)
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
                                time = parseDate(pub)
                            )
                        )
                    }
                }
            }

            out.add(
                NewsItem(
                    title = title,
                    source = if (source.isEmpty()) "অজানা উৎস" else source,
                    sourceUrl = sourceUrl,
                    link = link,
                    time = parseDate(pub),
                    related = related
                )
            )
        }
        return out
    }

    private fun extractTag(block: String, tag: String): String {
        val pattern = Pattern.compile("<$tag[^>]*>(.*?)</$tag>", Pattern.DOTALL)
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
                    if (g.tokens.any { t -> t.size >= 3 && jaccard(t, tk) >= 0.55 }) {
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
            val ordered = g.items.filter { it.sourceUrl.isNotEmpty() } + g.items.filter { it.sourceUrl.isEmpty() }
            val seen = mutableSetOf<String>()
            val uniq = mutableListOf<NewsItem>()

            for (it in ordered) {
                val k = it.source.trim().lowercase(Locale.ROOT)
                if (k.isEmpty() || k == "অজানা উৎস") continue
                if (seen.add(k)) {
                    uniq.add(it)
                }
            }

            if (uniq.isNotEmpty()) {
                out.add(Coverage(uniq))
            }
        }

        return out.sortedWith { a, b ->
            val c = b.count.compareTo(a.count)
            if (c != 0) c
            else {
                val ta = a.latest ?: 0L
                val tb = b.latest ?: 0L
                tb.compareTo(ta)
            }
        }
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
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) PotrikaGhor")
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
