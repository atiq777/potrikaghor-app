package com.potrikaghor.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
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
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) PotrikaGhor")
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

    fun parseHtml(html: String, url: String, fallbackTitle: String, source: String): CleanArticle {
        if (html.isEmpty()) {
            return CleanArticle(
                title = fallbackTitle.ifEmpty { "সংবাদ" },
                source = source,
                url = url,
                paragraphs = listOf("ইন্টারনেট সংযোগ না থাকায় অথবা পৃষ্ঠাটি লোড হতে সমস্যা হওয়ায় পূর্ণ লেখাটি আনা সম্ভব হয়নি। আপনি মূল ওয়েবসাইটে পড়তে পারেন।"),
                plainText = fallbackTitle
            )
        }

        // 1. Extract Title
        var title = ""
        val ogTitleMat = Pattern.compile("<meta[^>]*property=\"og:title\"[^>]*content=\"([^\"]*)\"", Pattern.CASE_INSENSITIVE).matcher(html)
        if (ogTitleMat.find()) {
            title = unescape(ogTitleMat.group(1) ?: "")
        }
        if (title.isEmpty()) {
            val titleMat = Pattern.compile("<title>(.*?)</title>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL).matcher(html)
            if (titleMat.find()) {
                title = unescape(titleMat.group(1) ?: "")
            }
        }
        if (title.isEmpty()) title = fallbackTitle

        // 2. Extract Lead Image
        var leadImage: String? = null
        val ogImgMat = Pattern.compile("<meta[^>]*property=\"og:image\"[^>]*content=\"([^\"]*)\"", Pattern.CASE_INSENSITIVE).matcher(html)
        if (ogImgMat.find()) {
            val img = ogImgMat.group(1)
            if (!img.isNullOrEmpty() && (img.startsWith("http://") || img.startsWith("https://"))) {
                leadImage = img
            }
        }

        // 3. Remove script, style, nav, header, footer, noscript
        var clean = html
            .replace(Regex("<script[^>]*>.*?</script>", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("<style[^>]*>.*?</style>", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("<nav[^>]*>.*?</nav>", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("<header[^>]*>.*?</header>", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("<footer[^>]*>.*?</footer", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("<aside[^>]*>.*?</aside>", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("<iframe[^>]*>.*?</iframe>", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), " ")

        // 4. Extract paragraphs
        val paragraphs = mutableListOf<String>()
        val pMat = Pattern.compile("<p[^>]*>(.*?)</p>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL).matcher(clean)
        while (pMat.find()) {
            val rawP = pMat.group(1) ?: continue
            val text = unescape(stripTags(rawP)).trim()
            // Ignore tiny footer snippets or ad labels
            if (text.length > 25 && !text.contains("All Rights Reserved", ignoreCase = true) && !text.contains("কপিরাইট", ignoreCase = true)) {
                paragraphs.add(text)
            }
        }

        if (paragraphs.isEmpty()) {
            // fallback: find div text
            paragraphs.add(title)
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

    private fun stripTags(s: String): String = s.replace(Regex("<[^>]*>"), "")

    private fun unescape(s: String): String {
        return s.replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .trim()
    }
}
