package com.potrikaghor.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONTokener
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

data class CleanArticle(
    val title: String,
    val source: String,
    val url: String,
    val leadImageUrl: String? = null,
    val paragraphs: List<String>,
    val plainText: String
)

class ArticleExtractor {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun extractArticle(url: String, fallbackTitle: String = "", source: String = ""): CleanArticle =
        withContext(Dispatchers.IO) {
            if (url.contains("news.google.com")) {
                // Google News URLs require client-side JS redirection in WebView.
                // Return clean fallback until WebView resolves the publisher page DOM.
                val cleanTitle = fallbackTitle.ifEmpty { source.ifEmpty { "সংবাদ" } }
                return@withContext CleanArticle(
                    title = cleanTitle,
                    source = source,
                    url = url,
                    leadImageUrl = null,
                    paragraphs = listOf(
                        cleanTitle,
                        "মূল পত্রিকার পাতা থেকে বিস্তারিত সংবাদ লোড হচ্ছে..."
                    ),
                    plainText = cleanTitle
                )
            }

            val req = Request.Builder()
                .url(url)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                )
                .header("Accept", "text/html,application/xhtml+xml")
                .build()

            val html = try {
                client.newCall(req).execute().use { res ->
                    if (res.isSuccessful) res.body?.string() ?: "" else ""
                }
            } catch (_: Exception) {
                ""
            }

            parseHtml(html, url, fallbackTitle, source)
        }

    fun decodeJsEvaluatedHtml(jsResult: String?): String {
        if (jsResult.isNullOrBlank() || jsResult == "null") return ""
        return try {
            JSONTokener(jsResult).nextValue()?.toString() ?: ""
        } catch (_: Exception) {
            jsResult.trim('"')
                .replace("\\u003C", "<")
                .replace("\\u003E", ">")
                .replace("\\n", "\n")
                .replace("\\\"", "\"")
        }
    }

    fun parseHtml(html: String, url: String, fallbackTitle: String, source: String): CleanArticle {
        val safeFallbackTitle = fallbackTitle.takeIf { !isGenericGoogleTitle(it) }.orEmpty()

        if (html.isEmpty()) {
            val resolvedTitle = safeFallbackTitle.ifEmpty { source.ifEmpty { "সংবাদ" } }
            return CleanArticle(
                title = resolvedTitle,
                source = source,
                url = url,
                paragraphs = listOf("ইন্টারনেট সংযোগ না থাকায় অথবা পৃষ্ঠাটি লোড হতে সমস্যা হওয়ায় পূর্ণ লেখাটি আনা সম্ভব হয়নি। আপনি মূল ওয়েবসাইটে পড়তে পারেন।"),
                plainText = resolvedTitle
            )
        }

        // 1. Extract Title
        var title = ""
        val ogTitleMat = Pattern.compile("<meta[^>]*property=[\"']og:title[\"'][^>]*content=[\"']([^\"']*)[\"']", Pattern.CASE_INSENSITIVE).matcher(html)
        if (ogTitleMat.find()) {
            title = unescape(ogTitleMat.group(1) ?: "")
        }
        if (title.isEmpty() || isGenericGoogleTitle(title)) {
            val h1Mat = Pattern.compile("<h1[^>]*>(.*?)</h1>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL).matcher(html)
            if (h1Mat.find()) {
                val candidate = unescape(stripTags(h1Mat.group(1) ?: ""))
                if (candidate.length > 10 && !isGenericGoogleTitle(candidate)) {
                    title = candidate
                }
            }
        }
        if (title.isEmpty() || isGenericGoogleTitle(title)) {
            val titleMat = Pattern.compile("<title>(.*?)</title>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL).matcher(html)
            if (titleMat.find()) {
                val candidate = unescape(titleMat.group(1) ?: "")
                if (!isGenericGoogleTitle(candidate)) {
                    title = candidate
                }
            }
        }
        if (title.isEmpty() || isGenericGoogleTitle(title)) {
            title = safeFallbackTitle.ifEmpty { source.ifEmpty { "সংবাদ" } }
        }

        // 2. Extract Lead Image (ignoring Google News / generic icons)
        var leadImage: String? = null
        val ogImgMat = Pattern.compile("<meta[^>]*property=[\"']og:image[\"'][^>]*content=[\"']([^\"']*)[\"']", Pattern.CASE_INSENSITIVE).matcher(html)
        if (ogImgMat.find()) {
            val img = ogImgMat.group(1)?.trim()
            if (!img.isNullOrEmpty() && (img.startsWith("http://") || img.startsWith("https://")) && !isGenericGoogleImage(img)) {
                leadImage = img
            }
        }
        if (leadImage == null) {
            val twImgMat = Pattern.compile("<meta[^>]*name=[\"']twitter:image[\"'][^>]*content=[\"']([^\"']*)[\"']", Pattern.CASE_INSENSITIVE).matcher(html)
            if (twImgMat.find()) {
                val img = twImgMat.group(1)?.trim()
                if (!img.isNullOrEmpty() && (img.startsWith("http://") || img.startsWith("https://")) && !isGenericGoogleImage(img)) {
                    leadImage = img
                }
            }
        }

        // 3. Extract JSON-LD articleBody if available
        val paragraphs = mutableListOf<String>()
        val articleBodyMat = Pattern.compile("\"articleBody\"\\s*:\\s*\"((?:\\\\\"|[^\"])*)\"").matcher(html)
        while (articleBodyMat.find()) {
            val rawBody = articleBodyMat.group(1) ?: continue
            val decodedBody = unescape(
                rawBody.replace("\\n", "\n")
                    .replace("\\r", " ")
                    .replace("\\\"", "\"")
            )
            decodedBody.split(Regex("\n+"))
                .map { stripTags(it).trim() }
                .filter { isValidParagraph(it) }
                .forEach { p ->
                    if (!paragraphs.contains(p)) paragraphs.add(p)
                }
        }

        // 4. Remove script, style, nav, header, footer, noscript and extract <p> tags
        val clean = html
            .replace(Regex("<script[^>]*>.*?</script>", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("<style[^>]*>.*?</style>", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("<nav[^>]*>.*?</nav>", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("<header[^>]*>.*?</header>", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("<footer[^>]*>.*?</footer", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("<aside[^>]*>.*?</aside>", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("<iframe[^>]*>.*?</iframe>", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), " ")

        val pMat = Pattern.compile("<p[^>]*>(.*?)</p>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL).matcher(clean)
        while (pMat.find()) {
            val rawP = pMat.group(1) ?: continue
            val text = unescape(stripTags(rawP)).trim()
            if (isValidParagraph(text) && !paragraphs.contains(text)) {
                paragraphs.add(text)
            }
        }

        if (paragraphs.isEmpty()) {
            if (safeFallbackTitle.isNotEmpty()) {
                paragraphs.add(safeFallbackTitle)
            }
            paragraphs.add("এই সংবাদের পূর্ণাঙ্গ পাঠ্য সরাসরি ওয়েবসাইট ভিউতে পড়ুন। উপরের আইকনে ট্যাপ করে ওয়েবসাইট ভিউ ও রিডার মোড পরিবর্তন করতে পারবেন।")
        }

        val plainText = paragraphs.joinToString("\n\n")

        return CleanArticle(
            title = title,
            source = source,
            url = url,
            leadImageUrl = leadImage,
            paragraphs = paragraphs,
            plainText = plainText
        )
    }

    private fun isGenericGoogleTitle(text: String): Boolean {
        val t = text.trim()
        return t.equals("Google News", ignoreCase = true) ||
            t.startsWith("Google News -", ignoreCase = true) ||
            t.equals("Google", ignoreCase = true)
    }

    private fun isGenericGoogleImage(url: String): Boolean {
        val u = url.lowercase()
        return u.contains("googleusercontent.com") ||
            u.contains("gstatic.com/ images/branding") ||
            u.contains("gstatic.com") ||
            u.contains("news.google.com") ||
            u.contains("google.com/logos")
    }

    private fun isValidParagraph(text: String): Boolean {
        if (text.length <= 25) return false
        if (isGenericGoogleTitle(text)) return false
        val lower = text.lowercase()
        if (lower.contains("all rights reserved") ||
            lower.contains("কপিরাইট") ||
            lower.contains("google news") ||
            lower.contains("enable javascript") ||
            lower.contains("privacy policy")
        ) {
            return false
        }
        return true
    }

    private fun stripTags(s: String): String = s.replace(Regex("<[^>]*>"), "")

    private fun unescape(s: String): String {
        return s.replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&#8217;", "’")
            .replace("&#8216;", "‘")
            .replace("&#8220;", "“")
            .replace("&#8221;", "”")
            .trim()
    }
}
