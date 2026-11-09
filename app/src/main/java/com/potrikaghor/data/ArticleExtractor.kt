package com.potrikaghor.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset
import java.util.Locale
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

data class BoundedHtmlReadResult(
    val html: String,
    val bytesRead: Int,
    val wasTruncated: Boolean,
    val extendedReadUsed: Boolean
)

/**
 * Coordinates article extraction lifecycle per URL:
 * - Prevents duplicate WebView/HTTP extractions for the same URL once substantive content is obtained.
 * - Prevents concurrent duplicate live-DOM extractions for the same URL.
 * - Invalidates stale extraction and summary results when the active URL changes.
 */
class ArticleExtractionCoordinator {
    private val lock = Any()
    private var activeUrl: String = ""
    private var requestVersion: Int = 0
    private val succeededUrls = mutableSetOf<String>()
    private val inFlightLiveDomUrls = mutableSetOf<String>()

    fun onUrlChanged(newUrl: String): Int = synchronized(lock) {
        val normalized = newUrl.trim()
        if (normalized != activeUrl) {
            activeUrl = normalized
            requestVersion++
            inFlightLiveDomUrls.clear()
        }
        requestVersion
    }

    fun currentVersion(): Int = synchronized(lock) { requestVersion }

    fun currentUrl(): String = synchronized(lock) { activeUrl }

    fun isCurrent(version: Int, url: String): Boolean = synchronized(lock) {
        version == requestVersion && url.trim() == activeUrl
    }

    fun hasSubstantiveResultFor(url: String): Boolean = synchronized(lock) {
        succeededUrls.contains(url.trim())
    }

    fun markSucceeded(url: String) = synchronized(lock) {
        val norm = url.trim()
        if (norm.isNotEmpty()) {
            succeededUrls.add(norm)
            inFlightLiveDomUrls.remove(norm)
        }
    }

    fun tryStartLiveDomExtraction(url: String, force: Boolean = false): Boolean = synchronized(lock) {
        val norm = url.trim()
        if (norm.isEmpty() || norm.contains("news.google.com")) return false
        if (!force && succeededUrls.contains(norm)) return false
        if (!force && inFlightLiveDomUrls.contains(norm)) return false
        inFlightLiveDomUrls.add(norm)
        true
    }

    fun finishLiveDomExtraction(url: String) = synchronized(lock) {
        inFlightLiveDomUrls.remove(url.trim())
    }

    fun reset() = synchronized(lock) {
        activeUrl = ""
        requestVersion++
        succeededUrls.clear()
        inFlightLiveDomUrls.clear()
    }
}

class ArticleExtractor {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val lenientJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    companion object {
        const val PLACEHOLDER_LOADING = "মূল পত্রিকার পাতা থেকে বিস্তারিত সংবাদ লোড হচ্ছে..."
        const val PLACEHOLDER_WEBVIEW = "এই সংবাদের পূর্ণাঙ্গ পাঠ্য সরাসরি ওয়েবসাইট ভিউতে পড়ুন। উপরের আইকনে ট্যাপ করে ওয়েবসাইট ভিউ ও রিডার মোড পরিবর্তন করতে পারবেন।"
        const val PLACEHOLDER_OFFLINE = "ইন্টারনেট সংযোগ না থাকায় অথবা পৃষ্ঠাটি লোড হতে সমস্যা হওয়ায় পূর্ণ লেখাটি আনা সম্ভব হয়নি। আপনি মূল ওয়েবসাইটে পড়তে পারেন।"

        const val DEFAULT_MAX_HTML_BYTES = 384 * 1024
        const val DEFAULT_TRUNCATED_EXTENSION_BYTES = 256 * 1024
        const val HARD_MAX_HTML_BYTES = 768 * 1024
        const val DEFAULT_EXTRACTION_TIMEOUT_MS = 12_000L
        const val MAX_BOUNDED_DOM_HTML_CHARS = 180_000

        /**
         * JavaScript snippet that extracts live rendered article title, lead image, and visible
         * paragraphs directly from the WebView DOM with bounded node/character traversal.
         */
        val LIVE_DOM_EXTRACTION_JS: String = """
            (function() {
                try {
                    var title = '';
                    var ogTitle = document.querySelector('meta[property="og:title"]');
                    if (ogTitle && ogTitle.content) title = ogTitle.content.trim();
                    if (!title || title.toLowerCase().indexOf('google news') !== -1) {
                        var h1 = document.querySelector('article h1, main h1, h1');
                        if (h1 && h1.innerText) title = h1.innerText.trim();
                    }
                    if (!title) title = (document.title || '').trim();

                    var image = '';
                    var ogImg = document.querySelector('meta[property="og:image"], meta[name="twitter:image"]');
                    if (ogImg && ogImg.content) image = ogImg.content.trim();
                    if (!image) {
                        var artImg = document.querySelector('article img[src^="http"], main img[src^="http"]');
                        if (artImg && artImg.src) image = artImg.src.trim();
                    }

                    var paras = [];
                    var seen = {};
                    var totalChars = 0;
                    var maxChars = 45000;
                    var maxParas = 65;

                    function addText(txt) {
                        if (!txt || paras.length >= maxParas || totalChars >= maxChars) return;
                        var normalized = txt
                            .replace(/&lt;(\/?(?:p|div|br|li|blockquote|h[1-6])\b[^&]*?)&gt;/gi, '\n')
                            .replace(/<\/?(?:p|div|br|li|blockquote|h[1-6])\b[^>]*>/gi, '\n')
                            .replace(/<\/?[a-zA-Z][a-zA-Z0-9]*\b[^>]*>/g, '');
                        var lines = normalized.split(/\n+/);
                        for (var i = 0; i < lines.length; i++) {
                            if (paras.length >= maxParas || totalChars >= maxChars) break;
                            var clean = lines[i].replace(/\s+/g, ' ').trim();
                            if (clean.length > 28 && !seen[clean]) {
                                seen[clean] = true;
                                paras.push(clean);
                                totalChars += clean.length;
                            }
                        }
                    }

                    // 1. JSON-LD articleBody if present in DOM
                    var ldScripts = document.querySelectorAll('script[type="application/ld+json"]');
                    for (var s = 0; s < ldScripts.length && s < 8; s++) {
                        try {
                            var rawLd = ldScripts[s].textContent || '';
                            if (rawLd.length > 0 && rawLd.length < 250000) {
                                var data = JSON.parse(rawLd);
                                var items = Array.isArray(data) ? data : (data['@graph'] ? data['@graph'] : [data]);
                                for (var k = 0; k < items.length; k++) {
                                    if (items[k] && items[k].articleBody) {
                                        addText(items[k].articleBody);
                                    }
                                }
                            }
                        } catch (e) {}
                    }

                    // 2. Newspaper-specific and semantic article containers
                    var selectors = [
                        '.story-element-text',
                        '[itemprop="articleBody"] p',
                        '[itemprop="articleBody"]',
                        'article p',
                        '.article-content p',
                        '.entry-content p',
                        '.post-content p',
                        '.news-content p',
                        '.details-content p',
                        '.dtl_content p',
                        '.news-details p',
                        'main p',
                        'p'
                    ];
                    for (var j = 0; j < selectors.length; j++) {
                        var nodes = document.querySelectorAll(selectors[j]);
                        var limit = Math.min(nodes.length, 120);
                        for (var n = 0; n < limit; n++) {
                            var el = nodes[n];
                            if (el.closest && el.closest('nav, header, footer, aside, .related-news, .comments, script, style')) continue;
                            addText(el.innerText || el.textContent || '');
                            if (paras.length >= maxParas || totalChars >= maxChars) break;
                        }
                        if (paras.length >= 4) break;
                    }

                    // 3. Bounded fallback to article or main innerText if paragraphs are still sparse
                    if (paras.length < 2) {
                        var mainContainer = document.querySelector('article, [itemprop="articleBody"], main');
                        if (mainContainer && mainContainer.innerText) {
                            addText(mainContainer.innerText.substring(0, 35000));
                        }
                    }

                    return JSON.stringify({
                        title: title,
                        image: image,
                        url: window.location.href || '',
                        paragraphs: paras
                    });
                } catch (err) {
                    return JSON.stringify({ error: err.toString() });
                }
            })();
        """.trimIndent()

        /**
         * Bounded HTML fallback JS that strips heavy non-content nodes inside JS first and caps the
         * returned markup to 180 KB so multi-megabyte outerHTML never crosses the JNI/Main-Thread bridge.
         */
        val BOUNDED_ARTICLE_HTML_JS: String = """
            (function() {
                try {
                    var root = document.querySelector('article, [itemprop="articleBody"], main') || document.body;
                    if (!root) return '';
                    var clone = root.cloneNode(true);
                    var noisy = clone.querySelectorAll('script, style, svg, nav, footer, header, iframe, noscript, aside');
                    for (var i = 0; i < noisy.length; i++) {
                        if (noisy[i].parentNode) noisy[i].parentNode.removeChild(noisy[i]);
                    }
                    var headTitle = '<title>' + (document.title || '') + '</title>';
                    var html = headTitle + (clone.innerHTML || '');
                    return html.length > 180000 ? html.substring(0, 180000) : html;
                } catch (e) {
                    return '';
                }
            })();
        """.trimIndent()
    }

    suspend fun extractArticle(
        url: String,
        fallbackTitle: String = "",
        source: String = "",
        maxHtmlBytes: Int = DEFAULT_MAX_HTML_BYTES,
        fallbackExtensionBytes: Int = DEFAULT_TRUNCATED_EXTENSION_BYTES,
        timeoutMs: Long = DEFAULT_EXTRACTION_TIMEOUT_MS,
        callFactory: ((Request) -> Call)? = null
    ): CleanArticle = withContext(Dispatchers.IO) {
        if (url.contains("news.google.com")) {
            val cleanTitle = fallbackTitle.ifEmpty { source.ifEmpty { "সংবাদ" } }
            return@withContext CleanArticle(
                title = cleanTitle,
                source = source,
                url = url,
                leadImageUrl = null,
                paragraphs = listOf(
                    cleanTitle,
                    PLACEHOLDER_LOADING
                ),
                plainText = cleanTitle
            )
        }

        val req = try {
            Request.Builder()
                .url(url)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                )
                .header("Accept", "text/html,application/xhtml+xml")
                .build()
        } catch (_: Exception) {
            return@withContext parseHtml("", url, fallbackTitle, source)
        }

        val outerJob = coroutineContext[Job]
        val html = try {
            withTimeoutOrNull(timeoutMs) {
                val timeoutJob = coroutineContext[Job]
                val call = (callFactory ?: { r -> client.newCall(r) }).invoke(req)
                executeCancellableExtractionCall(call, timeoutJob) { res ->
                    if (!res.isSuccessful) return@executeCancellableExtractionCall ""
                    val body = res.body ?: return@executeCancellableExtractionCall ""
                    val charset = try {
                        body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
                    } catch (_: Exception) {
                        Charsets.UTF_8
                    }
                    readBoundedHtmlStream(
                        inputStream = body.byteStream(),
                        charset = charset,
                        maxBytes = maxHtmlBytes,
                        fallbackExtensionBytes = fallbackExtensionBytes
                    ).html
                }
            } ?: ""
        } catch (e: CancellationException) {
            if (outerJob != null && !outerJob.isActive) {
                throw e
            }
            ""
        } catch (_: Exception) {
            ""
        }

        ensureActive()
        withContext(Dispatchers.Default) {
            parseHtml(html, url, fallbackTitle, source)
        }
    }

    internal fun <T> executeCancellableExtractionCall(
        call: Call,
        job: Job?,
        block: (Response) -> T
    ): T {
        val cancelWatcher = if (job != null) {
            Job(parent = job).apply {
                invokeOnCompletion { cause ->
                    if (cause != null) {
                        try {
                            call.cancel()
                        } catch (_: Throwable) {}
                    }
                }
            }
        } else {
            null
        }

        try {
            if (job != null && !job.isActive) {
                call.cancel()
                throw CancellationException("Extraction job already cancelled")
            }
            val response = call.execute()
            response.use { res ->
                return block(res)
            }
        } catch (e: IOException) {
            if (call.isCanceled() || (job != null && !job.isActive)) {
                throw CancellationException("OkHttp extraction call cancelled", e)
            }
            throw e
        } finally {
            cancelWatcher?.cancel()
        }
    }

    /**
     * Streaming bounded HTML reader:
     * - Reads in 8 KB chunks up to [maxBytes] (never calling unbounded ResponseBody.string()).
     * - If the stream is truncated at [maxBytes] AND the initial chunk does not yet contain closing
     *   article/main tags or enough paragraph markers (e.g., pages with oversized <head>/inline scripts),
     *   adaptively extends reading by up to [fallbackExtensionBytes] (capped at [HARD_MAX_HTML_BYTES]),
     *   stopping early as soon as </article> or </main> is reached.
     */
    fun readBoundedHtmlStream(
        inputStream: InputStream,
        charset: Charset = Charsets.UTF_8,
        maxBytes: Int = DEFAULT_MAX_HTML_BYTES,
        fallbackExtensionBytes: Int = DEFAULT_TRUNCATED_EXTENSION_BYTES
    ): BoundedHtmlReadResult {
        val safePrimaryLimit = maxBytes.coerceIn(16 * 1024, HARD_MAX_HTML_BYTES)
        val safeExtension = fallbackExtensionBytes.coerceIn(0, HARD_MAX_HTML_BYTES - safePrimaryLimit)
        val buffer = ByteArray(8 * 1024)
        val out = ByteArrayOutputStream(minOf(safePrimaryLimit, 64 * 1024))

        var totalRead = 0
        var truncated = false
        var extendedUsed = false

        inputStream.use { stream ->
            while (totalRead < safePrimaryLimit) {
                if (Thread.currentThread().isInterrupted) {
                    throw CancellationException("Interrupted while reading HTML stream")
                }
                val toRead = minOf(buffer.size, safePrimaryLimit - totalRead)
                val read = stream.read(buffer, 0, toRead)
                if (read == -1) {
                    return BoundedHtmlReadResult(
                        html = out.toString(charset.name()),
                        bytesRead = totalRead,
                        wasTruncated = false,
                        extendedReadUsed = false
                    )
                }
                out.write(buffer, 0, read)
                totalRead += read
            }

            // Peek next byte to check whether stream actually continues beyond safePrimaryLimit
            val nextByte = stream.read()
            if (nextByte == -1) {
                return BoundedHtmlReadResult(
                    html = out.toString(charset.name()),
                    bytesRead = totalRead,
                    wasTruncated = false,
                    extendedReadUsed = false
                )
            }

            truncated = true
            val initialHtml = out.toString(charset.name())

            // Adaptive fallback: if the first safePrimaryLimit bytes had no </article> or </main>
            // and fewer than 3 <p> tags (e.g., large inline script/style header), read up to safeExtension more bytes
            if (safeExtension > 0 && needsExtendedStreamRead(initialHtml)) {
                extendedUsed = true
                out.write(nextByte)
                totalRead += 1
                val hardLimit = safePrimaryLimit + safeExtension
                while (totalRead < hardLimit) {
                    if (Thread.currentThread().isInterrupted) {
                        throw CancellationException("Interrupted during extended HTML stream read")
                    }
                    val toRead = minOf(buffer.size, hardLimit - totalRead)
                    val read = stream.read(buffer, 0, toRead)
                    if (read == -1) {
                        truncated = false
                        break
                    }
                    out.write(buffer, 0, read)
                    totalRead += read

                    // Check periodically if we have now captured </article> or </main>
                    if (totalRead - safePrimaryLimit >= 32 * 1024) {
                        val currentStr = out.toString(charset.name())
                        if (!needsExtendedStreamRead(currentStr)) {
                            break
                        }
                    }
                }
            }
        }

        return BoundedHtmlReadResult(
            html = out.toString(charset.name()),
            bytesRead = totalRead,
            wasTruncated = truncated,
            extendedReadUsed = extendedUsed
        )
    }

    private fun needsExtendedStreamRead(partialHtml: String): Boolean {
        val lower = partialHtml.lowercase(Locale.ROOT)
        if (lower.contains("</article>") || lower.contains("</main>")) return false
        if (lower.contains("\"articlebody\"")) return false
        var pCount = 0
        var idx = 0
        while (idx < lower.length && pCount < 3) {
            val found = lower.indexOf("<p", idx)
            if (found == -1) break
            val nextChar = lower.getOrNull(found + 2)
            if (nextChar == '>' || nextChar == ' ' || nextChar == '\t' || nextChar == '\n' || nextChar == '\r') {
                pCount++
            }
            idx = found + 2
        }
        return pCount < 3
    }

    fun decodeJsEvaluatedString(jsResult: String?): String {
        if (jsResult.isNullOrBlank() || jsResult == "null") return ""
        val trimmed = jsResult.trim()
        return try {
            val elem = lenientJson.parseToJsonElement(trimmed)
            if (elem is JsonPrimitive) elem.content else trimmed
        } catch (_: Exception) {
            trimmed.trim('"')
                .replace("\\u003C", "<")
                .replace("\\u003E", ">")
                .replace("\\n", "\n")
                .replace("\\\"", "\"")
        }
    }

    fun parseLiveDomJson(
        rawJsResult: String?,
        url: String,
        fallbackTitle: String,
        source: String
    ): CleanArticle? {
        val jsonStr = decodeJsEvaluatedString(rawJsResult)
        if (jsonStr.isBlank()) return null
        return try {
            val obj = lenientJson.parseToJsonElement(jsonStr) as? JsonObject ?: return null
            if (obj.containsKey("error")) return null

            val safeFallbackTitle = fallbackTitle.takeIf { !isGenericGoogleTitle(it) }.orEmpty()
            val rawTitle = (obj["title"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            val cleanedRawTitle = cleanInlineTextLinear(rawTitle)
            val resolvedTitle = cleanedRawTitle
                .takeIf { it.isNotEmpty() && !isGenericGoogleTitle(it) }
                ?: safeFallbackTitle.ifEmpty { source.ifEmpty { "সংবাদ" } }

            val rawImg = (obj["image"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            val leadImage = rawImg.takeIf {
                (it.startsWith("http://") || it.startsWith("https://")) && !isGenericGoogleImage(it)
            }

            val resolvedUrl = (obj["url"] as? JsonPrimitive)?.contentOrNull?.trim()
                ?.takeIf { it.startsWith("http") } ?: url

            val paragraphs = mutableListOf<String>()
            val seen = mutableSetOf<String>()
            val arr = obj["paragraphs"] as? JsonArray
            if (arr != null) {
                for (elem in arr) {
                    val rawP = (elem as? JsonPrimitive)?.contentOrNull.orEmpty()
                    for (p in extractCleanParagraphs(rawP)) {
                        if (isValidParagraph(p) && seen.add(p)) {
                            paragraphs.add(p)
                        }
                    }
                }
            }

            if (paragraphs.isEmpty()) return null

            CleanArticle(
                title = resolvedTitle,
                source = source,
                url = resolvedUrl,
                leadImageUrl = leadImage,
                paragraphs = paragraphs,
                plainText = paragraphs.joinToString("\n\n")
            )
        } catch (_: Exception) {
            null
        }
    }

    fun hasSubstantiveContent(article: CleanArticle?): Boolean {
        if (article == null) return false
        val realParagraphs = article.paragraphs.filter { p ->
            !isPlaceholderText(p) && p != article.title && p.length > 30
        }
        return realParagraphs.isNotEmpty()
    }

    fun isPlaceholderText(text: String): Boolean {
        val t = text.trim()
        return t == PLACEHOLDER_LOADING ||
            t == PLACEHOLDER_WEBVIEW ||
            t == PLACEHOLDER_OFFLINE ||
            t.contains("বিস্তারিত সংবাদ লোড হচ্ছে") ||
            t.contains("পূর্ণাঙ্গ পাঠ্য সরাসরি ওয়েবসাইট ভিউতে পড়ুন") ||
            t.contains("পূর্ণ লেখাটি আনা সম্ভব হয়নি")
    }

    /**
     * Background-safe coroutine wrapper for [generateSmartSummary] that guarantees execution on
     * [Dispatchers.Default] (or the specified background [dispatcher]).
     */
    suspend fun generateSmartSummaryAsync(
        article: CleanArticle?,
        fallbackTitle: String,
        dispatcher: CoroutineDispatcher = Dispatchers.Default
    ): List<String> = withContext(dispatcher) {
        generateSmartSummary(article, fallbackTitle)
    }

    /**
     * Generates a factual, extractive 3-5 bullet summary from the article's real paragraphs.
     * Supports both Bengali (।) and English (.) sentences and ranks sentences by keyword
     * relevance and information density.
     */
    fun generateSmartSummary(article: CleanArticle?, fallbackTitle: String): List<String> {
        val headline = article?.title
            ?.takeIf { it.isNotBlank() && !isGenericGoogleTitle(it) }
            ?: fallbackTitle.trim()

        val realParagraphs = article?.paragraphs
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() && !isPlaceholderText(it) && !isGenericGoogleTitle(it) }
            .orEmpty()

        val bodyText = realParagraphs.joinToString("\n")
        if (bodyText.length < 80) {
            return if (headline.isNotBlank()) {
                listOf(headline)
            } else {
                listOf("সংবাদটির মূল অংশ লোড হচ্ছে, অনুগ্রহ করে কয়েক মুহূর্ত অপেক্ষা করুন।")
            }
        }

        val rawSentences = bodyText
            .split(Regex("(?<=[।!?])\\s+|(?<=\\.)\\s+(?=[A-Z0-9\u0980-\u09FF])|\n+"))
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { s ->
                s.length in 30..320 &&
                    !isPlaceholderText(s) &&
                    !s.equals(headline, ignoreCase = true)
            }
            .distinct()

        if (rawSentences.isEmpty()) {
            return realParagraphs.take(3).map { if (it.length > 220) it.take(217) + "..." else it }
        }

        if (rawSentences.size <= 4) {
            return rawSentences
        }

        val headlineWords = headline
            .split(Regex("[\\s,।.!?'\"“”‘’\\-:;()]+"))
            .map { it.trim().lowercase() }
            .filter { it.length >= 3 }
            .toSet()

        data class ScoredSentence(val index: Int, val text: String, val score: Double)

        val scored = rawSentences.mapIndexed { idx, sentence ->
            var score = 0.0
            if (idx == 0) score += 4.0
            else if (idx == 1) score += 2.5
            else if (idx <= 4) score += 1.5

            val lower = sentence.lowercase()
            headlineWords.forEach { w ->
                if (lower.contains(w)) score += 1.8
            }

            if (sentence.contains(Regex("[0-9০-৯]+"))) {
                score += 1.0
            }

            if (sentence.length in 55..200) {
                score += 1.0
            }

            ScoredSentence(idx, sentence, score)
        }

        return scored
            .sortedByDescending { it.score }
            .take(4)
            .sortedBy { it.index }
            .map { it.text }
    }

    /**
     * Builds clean, natural text for Text-to-Speech (TTS), filtering out UI placeholders,
     * normalizing Bengali punctuation/spacing, and preserving paragraph breaks ("\n\n")
     * for natural newsreader pauses.
     */
    fun buildTtsSpeechText(article: CleanArticle?, fallbackTitle: String): String {
        val rawHeadline = article?.title
            ?.takeIf { it.isNotBlank() && !isGenericGoogleTitle(it) }
            ?: fallbackTitle.trim()

        val headline = cleanHeadlineForTts(rawHeadline, article?.source.orEmpty())

        val cleanedParagraphs = article?.paragraphs
            ?.map { it.trim() }
            ?.filter {
                it.isNotBlank() &&
                    !isPlaceholderText(it) &&
                    !isGenericGoogleTitle(it) &&
                    !it.equals(rawHeadline, ignoreCase = true) &&
                    !it.equals(headline, ignoreCase = true) &&
                    !isTtsBoilerplateLine(it)
            }
            ?.map { cleanSegmentForTts(it) }
            ?.filter { it.length > 10 && !it.equals(headline, ignoreCase = true) }
            ?.distinct()
            .orEmpty()

        return buildString {
            if (headline.isNotBlank()) {
                append(ensureSentenceEnding(headline))
            }
            if (cleanedParagraphs.isNotEmpty()) {
                if (isNotEmpty()) {
                    append("\n\n")
                }
                append(cleanedParagraphs.joinToString("\n\n") { ensureSentenceEnding(it) })
            }
        }.trim()
    }

    private fun cleanHeadlineForTts(rawHeadline: String, sourceName: String): String {
        var h = rawHeadline.trim()
        if (h.isEmpty()) return ""
        val suffixSplit = h.split(Regex("\\s+[|\\-–—]\\s+"))
        if (suffixSplit.size > 1) {
            val lastPart = suffixSplit.last().trim()
            if (lastPart.length <= 28 || (sourceName.isNotBlank() && lastPart.contains(sourceName, ignoreCase = true))) {
                h = suffixSplit.dropLast(1).joinToString(" - ").trim()
            }
        }
        return cleanSegmentForTts(h)
    }

    private fun isTtsBoilerplateLine(text: String): Boolean {
        val t = text.trim()
        return t.startsWith("আরও পড়ুন") ||
            t.startsWith("আরও পড়ুন") ||
            t.startsWith("এ সম্পর্কিত আরও") ||
            t.startsWith("বিস্তারিত ভিডিওতে") ||
            t.startsWith("ভিডিও দেখুন") ||
            t.startsWith("গুগল নিউজে") ||
            t.startsWith("ফেইসবুকে ফলো") ||
            t.startsWith("বিজ্ঞাপন")
    }

    private fun cleanSegmentForTts(input: String): String {
        if (input.isBlank()) return ""
        var s = try {
            java.text.Normalizer.normalize(input, java.text.Normalizer.Form.NFC)
        } catch (_: Exception) {
            input
        }

        s = s.replace('\u09F0', '\u09B0')
            .replace('\u09F1', '\u09AC')

        s = s.replace("\u200B", " ")
            .replace("\u00A0", " ")
            .replace("\u202F", " ")
            .replace("\u2007", " ")

        s = s.replace(Regex("(?<!\u09B0\u09CD)\u200D|\u200D(?!\u09AF)"), "")
            .replace(Regex("(?<!\u09CD)\u200C"), "")
            .replace("\u00AD", "")
            .replace("\u2060", "")
            .replace("\uFEFF", "")
            .replace("\u200E", "")
            .replace("\u200F", "")

        s = s.replace(Regex("\u09CD{2,}"), "\u09CD")
            .replace(Regex("\u09BC{2,}"), "\u09BC")

        s = s.replace(Regex("https?://\\S+"), " ")
            .replace(Regex("www\\.\\S+"), " ")

        s = s.replace(Regex("\\((?:ছবি|ফাইল ছবি|সংগৃহীত|ভিডিও|সূত্র|প্রতিনিধি)[^)]*\\)"), " ")
            .replace(Regex("\\[(?:ছবি|ফাইল ছবি|সংগৃহীত|ভিডিও|সূত্র)[^]]*]"), " ")

        s = s.replace(
            Regex("(?:^|(?<=[\\s(\\[,«\"'“‘]))(ড|ডা|মো|মি|প্রা|ইঞ্জি|অ্যাড|লে|জে|ব্রি):(?=\\s*[\u0980-\u09FF])"),
            "$1. "
        )
        s = s.replace(Regex("(?<=[\u0980-\u09FF]):(?=[\u0980-\u09FF])"), "ঃ")
            .replace("বি.দ্র.", "বিশেষ দ্রষ্টব্য, ")

        s = s.replace(
            Regex("(?:^|(?<=[\\s(\\[,]))(ড|ডা|মো|মি|প্রা|ইঞ্জি|অ্যাড|লে|জে|ব্রি)\\.(?=[\u0980-\u09FF])"),
            "$1. "
        )

        s = s.replace(Regex("(?<=[0-9০-৯])\\s*[–—]\\s*(?=[0-9০-৯])"), "-")

        s = s.replace(Regex("\\s*[—–|]+\\s*"), ", ")
            .replace(Regex("\\s+--\\s+"), ", ")

        s = s.replace(Regex("(?<=[\u0980-\u09FF])-(?=[\u0980-\u09FF])"), " ")
            .replace(Regex("(?<=[\u0980-\u09FF])\\s*/\\s*(?=[\u0980-\u09FF])"), ", ")

        s = s.replace(Regex("\\s*[(\\[]([^)\\]]+)[)\\]]\\s*"), ", $1, ")

        s = s.replace(Regex("(?<=\u09A6\u09C1)[’'](?=[\u0980-\u09FF])"), "")

        s = s.replace(Regex("[•▪►»*#_~<>\"'“”‘’«]+"), " ")

        s = s.replace(Regex("(?<=[\u0980-\u09FF])(?=[A-Za-z])"), " ")
            .replace(Regex("(?<=[A-Za-z])(?=[\u0980-\u09FF])"), " ")
            .replace(Regex("%(?=[\\p{L}\\p{N}\u0980-\u09FF])"), "% ")

        s = s.replace(Regex("\\s+([।?!,;:])"), "$1")

        s = s.replace(Regex("([।?!;])(?=[\\p{L}\\p{N}\u0980-\u09FF])"), "$1 ")
            .replace(Regex("([,:])(?=[\\p{L}\u0980-\u09FF])"), "$1 ")
            .replace(Regex("(?<![0-9০-৯])([,:])(?=[0-9০-৯])"), "$1 ")

        s = s.replace(Regex("।{2,}"), "।")
            .replace(Regex("\\.{2,}"), "। ")
            .replace(Regex(",(\\s*,)+"), ", ")
            .replace(Regex(",\\s*([।.!?])"), "$1")
            .replace(Regex("^,\\s*"), "")

        return s.replace(Regex("[ \\t]+"), " ").trim()
    }

    private fun ensureSentenceEnding(text: String): String {
        val t = text.trim()
        if (t.isEmpty()) return ""
        return if (t.endsWith("।") || t.endsWith(".") || t.endsWith("?") || t.endsWith("!")) {
            t
        } else {
            val hasBengali = Regex("[\u0980-\u09FF]").containsMatchIn(t)
            if (hasBengali) "$t।" else "$t."
        }
    }

    /**
     * Parses HTML into a [CleanArticle] without any catastrophic-backtracking regex or
     * full-document DOT_MATCHES_ALL regex replacements:
     * 1. Extracts title & lead image from bounded <head> / meta tags or JSON-LD.
     * 2. Parses <script type="application/ld+json"> blocks via [lenientJson] ([JsonObject]/[JsonArray]).
     * 3. Falls back to single-pass linear noise removal ([stripNoiseBlocksLinear]) and linear
     *    paragraph/story-element extraction ([extractParagraphsLinear]), plus meta description fallback
     *    if the HTML stream was truncated.
     */
    fun parseHtml(html: String, url: String, fallbackTitle: String, source: String): CleanArticle {
        val safeFallbackTitle = fallbackTitle.takeIf { !isGenericGoogleTitle(it) }.orEmpty()

        if (html.isEmpty()) {
            val resolvedTitle = safeFallbackTitle.ifEmpty { source.ifEmpty { "সংবাদ" } }
            return CleanArticle(
                title = resolvedTitle,
                source = source,
                url = url,
                paragraphs = listOf(PLACEHOLDER_OFFLINE),
                plainText = resolvedTitle
            )
        }

        // Bound meta-tag search to the head/top window (first 96 KB) for speed
        val headWindow = if (html.length > 96_000) html.substring(0, 96_000) else html

        // 1. Extract Title
        var title = extractMetaContent(headWindow, "property", "og:title")
            .ifEmpty { extractMetaContent(headWindow, "name", "twitter:title") }

        if (title.isEmpty() || isGenericGoogleTitle(title)) {
            val h1Candidate = extractFirstTagTextLinear(html, "h1")
            if (h1Candidate.length > 10 && !isGenericGoogleTitle(h1Candidate)) {
                title = h1Candidate
            }
        }
        if (title.isEmpty() || isGenericGoogleTitle(title)) {
            val titleCandidate = extractFirstTagTextLinear(headWindow, "title")
            if (!isGenericGoogleTitle(titleCandidate)) {
                title = titleCandidate
            }
        }

        // 2. Extract Lead Image (ignoring Google News / generic icons)
        var leadImage: String? = extractMetaContent(headWindow, "property", "og:image")
            .takeIf { (it.startsWith("http://") || it.startsWith("https://")) && !isGenericGoogleImage(it) }
        if (leadImage == null) {
            leadImage = extractMetaContent(headWindow, "name", "twitter:image")
                .takeIf { (it.startsWith("http://") || it.startsWith("https://")) && !isGenericGoogleImage(it) }
        }

        // 3. Extract JSON-LD articleBody / headline / image using kotlinx.serialization.json.Json
        val paragraphs = mutableListOf<String>()
        val seenParagraphs = mutableSetOf<String>()
        val jsonLdExtracted = extractFromJsonLdBlocks(html)
        if ((title.isEmpty() || isGenericGoogleTitle(title)) && jsonLdExtracted.headline.isNotEmpty()) {
            val cand = cleanInlineTextLinear(jsonLdExtracted.headline)
            if (!isGenericGoogleTitle(cand)) {
                title = cand
            }
        }
        if (leadImage == null && jsonLdExtracted.imageUrl != null) {
            val img = jsonLdExtracted.imageUrl
            if ((img.startsWith("http://") || img.startsWith("https://")) && !isGenericGoogleImage(img)) {
                leadImage = img
            }
        }
        for (rawBody in jsonLdExtracted.articleBodies) {
            for (p in extractCleanParagraphs(rawBody)) {
                if (isValidParagraph(p) && seenParagraphs.add(p)) {
                    paragraphs.add(p)
                }
            }
        }

        if (title.isEmpty() || isGenericGoogleTitle(title)) {
            title = safeFallbackTitle.ifEmpty { source.ifEmpty { "সংবাদ" } }
        }

        // 4. Single-pass linear removal of script/style/nav/header/footer/aside/iframe/comment noise
        // and linear extraction of <p> and story-element-text blocks
        val cleanHtml = stripNoiseBlocksLinear(html)
        extractParagraphsLinear(cleanHtml, paragraphs, seenParagraphs)

        // 5. Fallback for truncated or minimal HTML: use JSON-LD description or meta description if substantive
        if (paragraphs.isEmpty()) {
            val metaDesc = extractMetaContent(headWindow, "property", "og:description")
                .ifEmpty { extractMetaContent(headWindow, "name", "description") }
                .ifEmpty { jsonLdExtracted.description }
            for (cleanDesc in extractCleanParagraphs(metaDesc)) {
                if (isValidParagraph(cleanDesc) && seenParagraphs.add(cleanDesc)) {
                    paragraphs.add(cleanDesc)
                }
            }
        }

        if (paragraphs.isEmpty()) {
            if (safeFallbackTitle.isNotEmpty()) {
                paragraphs.add(safeFallbackTitle)
            }
            paragraphs.add(PLACEHOLDER_WEBVIEW)
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

    private data class JsonLdExtractionResult(
        val headline: String = "",
        val imageUrl: String? = null,
        val description: String = "",
        val articleBodies: List<String> = emptyList()
    )

    private fun extractFromJsonLdBlocks(html: String): JsonLdExtractionResult {
        val blocks = findJsonLdScriptBlocksLinear(html)
        if (blocks.isEmpty()) return JsonLdExtractionResult()

        var headline = ""
        var imageUrl: String? = null
        var description = ""
        val bodies = mutableListOf<String>()

        fun optPrimString(obj: JsonObject, key: String): String {
            return (obj[key] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        }

        fun visitNode(node: JsonElement?) {
            when (node) {
                is JsonObject -> {
                    if (headline.isEmpty()) {
                        val h = optPrimString(node, "headline")
                            .ifEmpty { optPrimString(node, "name") }
                        if (h.length > 8 && !isGenericGoogleTitle(h)) {
                            headline = h
                        }
                    }
                    if (description.isEmpty()) {
                        val d = optPrimString(node, "description")
                        if (d.length > 25) {
                            description = d
                        }
                    }
                    if (imageUrl == null && node.containsKey("image")) {
                        imageUrl = extractImageUrlFromJsonValue(node["image"])
                    }
                    val body = optPrimString(node, "articleBody")
                    if (body.isNotEmpty()) {
                        bodies.add(body)
                    }
                    if (node.containsKey("@graph")) {
                        visitNode(node["@graph"])
                    }
                    if (node.containsKey("mainEntity")) {
                        visitNode(node["mainEntity"])
                    }
                    if (node.containsKey("hasPart")) {
                        visitNode(node["hasPart"])
                    }
                }
                is JsonArray -> {
                    for (elem in node) {
                        visitNode(elem)
                    }
                }
                else -> {}
            }
        }

        for (rawBlock in blocks) {
            val cleaned = rawBlock.trim()
                .removePrefix("<!--")
                .removeSuffix("-->")
                .trim()
            if (cleaned.isEmpty()) continue

            val parsed = tryParseJsonElement(cleaned)
                ?: tryParseJsonElement(sanitizeUnescapedJsonControlChars(cleaned))
            if (parsed != null) {
                visitNode(parsed)
            }
        }

        return JsonLdExtractionResult(
            headline = headline,
            imageUrl = imageUrl,
            description = description,
            articleBodies = bodies
        )
    }

    private fun tryParseJsonElement(rawJson: String): JsonElement? {
        return try {
            lenientJson.parseToJsonElement(rawJson)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Sanitizes raw unescaped control characters (\n, \r, \t) that reside *inside* JSON string literals
     * without using any catastrophic regex.
     */
    private fun sanitizeUnescapedJsonControlChars(raw: String): String {
        val sb = StringBuilder(raw.length)
        var inQuotes = false
        var escaped = false
        for (i in 0 until raw.length) {
            val ch = raw[i]
            if (escaped) {
                sb.append(ch)
                escaped = false
                continue
            }
            if (ch == '\\') {
                sb.append(ch)
                if (inQuotes) escaped = true
                continue
            }
            if (ch == '"') {
                inQuotes = !inQuotes
                sb.append(ch)
                continue
            }
            if (inQuotes && (ch == '\n' || ch == '\r' || ch == '\t')) {
                when (ch) {
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append(" ")
                    '\t' -> sb.append(" ")
                }
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun extractImageUrlFromJsonValue(value: JsonElement?): String? {
        return when (value) {
            is JsonPrimitive -> value.contentOrNull?.trim()?.takeIf {
                it.startsWith("http://") || it.startsWith("https://")
            }
            is JsonObject -> {
                val u = (value["url"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                    .ifEmpty { (value["contentUrl"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty() }
                u.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            }
            is JsonArray -> {
                for (elem in value) {
                    val candidate = extractImageUrlFromJsonValue(elem)
                    if (candidate != null) return candidate
                }
                null
            }
            else -> null
        }
    }

    /**
     * Finds `<script ... type="application/ld+json" ...>...</script>` blocks in O(N) time
     * using indexOf without regular expressions.
     */
    private fun findJsonLdScriptBlocksLinear(html: String): List<String> {
        val results = mutableListOf<String>()
        var searchFrom = 0
        val len = html.length
        while (searchFrom < len && results.size < 12) {
            val scriptStart = html.indexOf("<script", searchFrom, ignoreCase = true)
            if (scriptStart == -1) break
            val openTagEnd = html.indexOf('>', scriptStart + 7)
            if (openTagEnd == -1) break

            val openTag = html.substring(scriptStart, openTagEnd + 1)
            val isJsonLd = openTag.contains("application/ld+json", ignoreCase = true)
            val closeScript = html.indexOf("</script", openTagEnd + 1, ignoreCase = true)

            if (isJsonLd) {
                val contentEnd = if (closeScript != -1) closeScript else len
                if (contentEnd > openTagEnd + 1) {
                    val body = html.substring(openTagEnd + 1, contentEnd)
                    if (body.length <= 250_000) {
                        results.add(body)
                    }
                }
            }

            if (closeScript == -1) break
            val closeTagEnd = html.indexOf('>', closeScript + 8)
            searchFrom = if (closeTagEnd != -1) closeTagEnd + 1 else closeScript + 8
        }
        return results
    }

    /**
     * Single-pass O(N) noise remover that strips comments and `<script>`, `<style>`, `<nav>`,
     * `<header>`, `<footer>`, `<aside>`, `<iframe>`, `<noscript>`, `<svg>`, `<form>`, `<button>`,
     * `<select>`, `<title>`, `<h1>` blocks without allocating multiple full-document copies or
     * risking regex backtracking on unclosed tags.
     */
    internal fun stripNoiseBlocksLinear(html: String): String {
        val len = html.length
        val sb = StringBuilder(minOf(len, 128 * 1024))
        var i = 0
        val noiseTags = arrayOf(
            "script", "style", "nav", "header", "footer", "aside",
            "iframe", "noscript", "svg", "form", "button", "select", "title", "h1"
        )

        while (i < len) {
            val lt = html.indexOf('<', i)
            if (lt == -1) {
                sb.append(html, i, len)
                break
            }
            if (lt > i) {
                sb.append(html, i, lt)
            }

            // Check HTML comment <!-- ... -->
            if (html.startsWith("<!--", lt)) {
                val commentEnd = html.indexOf("-->", lt + 4)
                if (commentEnd == -1) break
                sb.append(' ')
                i = commentEnd + 3
                continue
            }

            // Check whether this opening tag matches any noise block tag
            var matchedNoiseTag: String? = null
            for (tag in noiseTags) {
                val tagLen = tag.length
                if (lt + 1 + tagLen < len && html.regionMatches(lt + 1, tag, 0, tagLen, ignoreCase = true)) {
                    val boundaryCh = html[lt + 1 + tagLen]
                    if (boundaryCh == '>' || boundaryCh == ' ' || boundaryCh == '\t' || boundaryCh == '\n' || boundaryCh == '\r' || boundaryCh == '/') {
                        matchedNoiseTag = tag
                        break
                    }
                }
            }

            if (matchedNoiseTag != null) {
                val openEnd = html.indexOf('>', lt + 1 + matchedNoiseTag.length)
                if (openEnd == -1) break
                // Self-closing e.g. <iframe ... />
                if (html[openEnd - 1] == '/') {
                    i = openEnd + 1
                    continue
                }
                val closeMarker = "</$matchedNoiseTag"
                val closeIdx = html.indexOf(closeMarker, openEnd + 1, ignoreCase = true)
                if (closeIdx == -1) {
                    // Truncated noise block at the end of bounded stream: stop cleanly without backtracking
                    break
                }
                val closeEnd = html.indexOf('>', closeIdx + closeMarker.length)
                sb.append(' ')
                i = if (closeEnd != -1) closeEnd + 1 else len
            } else {
                sb.append('<')
                i = lt + 1
            }
        }
        return sb.toString()
    }

    /**
     * Linearly extracts clean paragraphs from [cleanHtml], splitting `<p>`, `</p>`, `<div>`, `</div>`,
     * `<br>`, `<br/>` and other structural tags into separate paragraphs, stripping inline and
     * malformed HTML tags, decoding HTML entities, and preserving valid `<` and `>` in prose.
     */
    private fun extractParagraphsLinear(
        cleanHtml: String,
        outParagraphs: MutableList<String>,
        seen: MutableSet<String>
    ) {
        val isFullHtmlDocument = cleanHtml.contains("<html", ignoreCase = true) ||
            cleanHtml.contains("<body", ignoreCase = true)

        val extracted = extractCleanParagraphs(
            rawInput = cleanHtml,
            allowShortNonPTags = !isFullHtmlDocument,
            alreadyStrippedNoise = true
        )
        for (p in extracted) {
            if (seen.add(p)) {
                outParagraphs.add(p)
            }
        }
    }

    private data class MatchedHtmlTag(
        val tagName: String,
        val endIndex: Int,
        val isBlockBreak: Boolean,
        val isClosing: Boolean
    )

    private val knownHtmlTags = setOf(
        "p", "div", "br", "hr", "span", "a", "strong", "b", "em", "i", "u", "s",
        "strike", "del", "ins", "font", "small", "big", "sub", "sup", "mark",
        "blockquote", "q", "cite", "code", "pre", "ul", "ol", "li", "dl", "dt", "dd",
        "h1", "h2", "h3", "h4", "h5", "h6", "article", "section", "main", "aside",
        "header", "footer", "nav", "figure", "figcaption", "img", "picture", "source",
        "video", "audio", "iframe", "table", "thead", "tbody", "tfoot", "tr", "td", "th",
        "caption", "time", "abbr", "address", "details", "summary", "wbr", "ruby", "rt",
        "rp", "bdi", "bdo", "data", "dfn", "kbd", "samp", "var", "html", "head", "body",
        "meta", "link", "style", "script", "noscript", "svg", "path", "form", "input",
        "button", "select", "option", "label", "center"
    )

    private val blockBreakHtmlTags = setOf(
        "p", "div", "br", "hr", "li", "blockquote", "pre",
        "h1", "h2", "h3", "h4", "h5", "h6",
        "article", "section", "main", "figure", "figcaption",
        "table", "tr", "ul", "ol", "dl", "dt", "dd", "header", "footer", "aside", "nav"
    )

    private val structuralMalformedAllowedTags = setOf(
        "p", "div", "br", "hr", "li", "blockquote", "span", "strong", "em", "article", "section"
    )

    /**
     * Public linear-time paragraph extractor that converts HTML or mixed HTML/plain-text input
     * into a list of clean, separated paragraphs with zero HTML tag leakage.
     */
    fun extractCleanParagraphs(
        rawInput: String,
        allowShortNonPTags: Boolean = true,
        alreadyStrippedNoise: Boolean = false
    ): List<String> {
        if (rawInput.isBlank()) return emptyList()

        val noiseFree = if (alreadyStrippedNoise) rawInput else stripNoiseBlocksLinear(rawInput)
        val decoded = decodeHtmlEntitiesLinear(noiseFree)
        val len = decoded.length
        if (len == 0) return emptyList()

        val result = mutableListOf<String>()
        val currentParagraph = StringBuilder(minOf(len, 512))
        var inOpenPTag = false
        var segmentTouchedPTag = false
        var hadAnyHtmlTag = false

        fun flushParagraph() {
            if (currentParagraph.isEmpty()) {
                segmentTouchedPTag = false
                return
            }
            val text = collapseWhitespaceLinear(currentParagraph)
            currentParagraph.setLength(0)
            val fromP = segmentTouchedPTag
            segmentTouchedPTag = false
            if (text.isEmpty()) return
            val minLen = if (allowShortNonPTags || fromP) 8 else 26
            if (isValidParagraph(text, minLen = minLen)) {
                result.add(text)
            }
        }

        var i = 0
        while (i < len) {
            val ch = decoded[i]

            if (ch == '\r' || ch == '\n') {
                var nextIdx = i + 1
                var newlineCount = 1
                if (ch == '\r' && nextIdx < len && decoded[nextIdx] == '\n') {
                    nextIdx++
                }
                while (nextIdx < len && (decoded[nextIdx] == '\n' || decoded[nextIdx] == '\r' || decoded[nextIdx] == ' ' || decoded[nextIdx] == '\t')) {
                    if (decoded[nextIdx] == '\n') newlineCount++
                    nextIdx++
                }
                if (!inOpenPTag || newlineCount >= 2) {
                    flushParagraph()
                } else {
                    currentParagraph.append(' ')
                }
                i = nextIdx
                continue
            }

            if (ch == '<') {
                val matched = tryMatchHtmlTagLinear(decoded, i)
                if (matched != null) {
                    hadAnyHtmlTag = true
                    if (matched.tagName == "p") {
                        segmentTouchedPTag = true
                    }
                    if (matched.isBlockBreak) {
                        flushParagraph()
                        if (matched.tagName == "p" && !matched.isClosing) {
                            inOpenPTag = true
                            segmentTouchedPTag = true
                        } else if (matched.tagName == "p" && matched.isClosing) {
                            inOpenPTag = false
                        } else if (matched.isClosing && matched.tagName in blockBreakHtmlTags) {
                            inOpenPTag = false
                        }
                    } else if (matched.tagName == "td" || matched.tagName == "th") {
                        currentParagraph.append(' ')
                    }
                    i = matched.endIndex
                    continue
                } else if (i == len - 1 && hadAnyHtmlTag) {
                    // Ignore a lone trailing '<' truncated at the very end of an HTML stream
                    break
                }
            }

            currentParagraph.append(ch)
            i++
        }

        flushParagraph()
        return result
    }

    /**
     * Attempts to match an HTML tag (complete or malformed/incomplete) starting at [ltIndex] (`'<'`).
     * Returns `null` if `'<'` is a normal character in prose (e.g., `"৫ < ১০"`, `"x < y"`, `"<বাংলা>"`).
     */
    private fun tryMatchHtmlTagLinear(s: String, ltIndex: Int): MatchedHtmlTag? {
        val len = s.length
        if (ltIndex + 1 >= len) return null

        // 1. HTML comment <!-- ... -->
        if (s.startsWith("<!--", ltIndex)) {
            val endComment = s.indexOf("-->", ltIndex + 4)
            val endPos = if (endComment != -1) endComment + 3 else len
            return MatchedHtmlTag("!--", endPos, isBlockBreak = false, isClosing = false)
        }

        // 2. <!DOCTYPE ...> or <![CDATA[...]]>
        if (s[ltIndex + 1] == '!' || s[ltIndex + 1] == '?') {
            val gt = s.indexOf('>', ltIndex + 2)
            if (gt != -1 && gt - ltIndex <= 512) {
                return MatchedHtmlTag("!decl", gt + 1, isBlockBreak = false, isClosing = false)
            }
            return null
        }

        var cursor = ltIndex + 1
        var isClosing = false
        if (s[cursor] == '/') {
            isClosing = true
            cursor++
            if (cursor >= len) {
                // Truncated "</" at the very end of string
                return MatchedHtmlTag("", len, isBlockBreak = false, isClosing = true)
            }
        }

        val firstCh = s[cursor]
        if (!isAsciiLetter(firstCh)) {
            return null
        }

        var nameEnd = cursor + 1
        while (nameEnd < len && nameEnd - cursor < 20 && isAsciiLetterOrDigit(s[nameEnd])) {
            nameEnd++
        }

        val tagName = s.substring(cursor, nameEnd).lowercase(Locale.ROOT)
        if (tagName !in knownHtmlTags) {
            return null
        }

        val isBlock = tagName in blockBreakHtmlTags

        // Case A: Truncated right after tag name at EOF (e.g. "</p" or "<p")
        if (nameEnd == len) {
            return MatchedHtmlTag(tagName, len, isBlock, isClosing)
        }

        val nextCh = s[nameEnd]

        // Case B: Immediate '>' (e.g. "<p>", "</p>", "<div>", "</div>", "<br>")
        if (nextCh == '>') {
            return MatchedHtmlTag(tagName, nameEnd + 1, isBlock, isClosing)
        }

        // Case C: Self-closing '/' (e.g. "<br/>", "<hr/>", or truncated "<br/")
        if (nextCh == '/') {
            if (nameEnd + 1 == len) {
                return MatchedHtmlTag(tagName, len, isBlock, isClosing)
            }
            if (s[nameEnd + 1] == '>') {
                return MatchedHtmlTag(tagName, nameEnd + 2, isBlock, isClosing)
            }
            if (s[nameEnd + 1] == '<') {
                return MatchedHtmlTag(tagName, nameEnd + 1, isBlock, isClosing)
            }
        }

        // Case D: Immediately followed by another '<' (malformed "<p<p>" or "</p<p>")
        if (nextCh == '<') {
            return MatchedHtmlTag(tagName, nameEnd, isBlock, isClosing)
        }

        // Case E: Followed by whitespace (e.g. "<p class=\"...\">", "<br />", "</p >", or malformed "<p class=\"...\"")
        if (isAsciiWhitespace(nextCh)) {
            var p = skipAsciiWhitespace(s, nameEnd)
            if (p == len || s[p] == '<') {
                return MatchedHtmlTag(tagName, p, isBlock, isClosing)
            }
            if (s[p] == '>') {
                return MatchedHtmlTag(tagName, p + 1, isBlock, isClosing)
            }
            if (s[p] == '/' && (p + 1 == len || s[p + 1] == '>')) {
                val endPos = if (p + 1 < len) p + 2 else len
                return MatchedHtmlTag(tagName, endPos, isBlock, isClosing)
            }

            if (isClosing) {
                // Malformed closing tag like "</p 텍스트" without '>'
                return MatchedHtmlTag(tagName, p, isBlock, isClosing = true)
            }

            // Scan zero or more HTML attributes: attrName(=("..."|'...'|unquoted))?
            var attrCount = 0
            var lastValidAttrEnd = p
            val maxScan = minOf(len, ltIndex + 2048)

            while (p < maxScan) {
                p = skipAsciiWhitespace(s, p)
                if (p >= maxScan) break
                val c = s[p]
                if (c == '>') {
                    return MatchedHtmlTag(tagName, p + 1, isBlock, isClosing = false)
                }
                if (c == '/' && (p + 1 == len || (p + 1 < len && s[p + 1] == '>'))) {
                    val endPos = if (p + 1 < len) p + 2 else len
                    return MatchedHtmlTag(tagName, endPos, isBlock, isClosing = false)
                }
                if (c == '<') {
                    return if (attrCount > 0 || tagName in structuralMalformedAllowedTags) {
                        MatchedHtmlTag(tagName, p, isBlock, isClosing = false)
                    } else {
                        null
                    }
                }
                if (!isAsciiAttrStart(c)) {
                    break
                }

                var attrNameEnd = p + 1
                while (attrNameEnd < maxScan && isAsciiAttrChar(s[attrNameEnd])) {
                    attrNameEnd++
                }
                var afterName = skipAsciiWhitespace(s, attrNameEnd)
                if (afterName < maxScan && s[afterName] == '=') {
                    var valStart = skipAsciiWhitespace(s, afterName + 1)
                    if (valStart >= maxScan) {
                        attrCount++
                        lastValidAttrEnd = maxScan
                        p = maxScan
                        break
                    }
                    val quote = s[valStart]
                    if (quote == '"' || quote == '\'') {
                        var qEnd = valStart + 1
                        while (qEnd < maxScan && s[qEnd] != quote) {
                            qEnd++
                        }
                        p = if (qEnd < maxScan) qEnd + 1 else maxScan
                        attrCount++
                        lastValidAttrEnd = skipAsciiWhitespace(s, p)
                    } else {
                        var uEnd = valStart
                        while (uEnd < maxScan && !isAsciiWhitespace(s[uEnd]) && s[uEnd] != '>' && s[uEnd] != '<') {
                            uEnd++
                        }
                        p = uEnd
                        attrCount++
                        lastValidAttrEnd = skipAsciiWhitespace(s, p)
                    }
                } else {
                    // Boolean attribute (e.g. disabled, itemscope) only if followed by '>', '/', '=', or another ASCII attr
                    p = afterName
                    if (p == len || s[p] == '>' || s[p] == '/') {
                        attrCount++
                        lastValidAttrEnd = p
                    } else {
                        break
                    }
                }
            }

            if (p < len && s[p] == '>') {
                return MatchedHtmlTag(tagName, p + 1, isBlock, isClosing = false)
            }
            if (attrCount > 0) {
                return MatchedHtmlTag(tagName, lastValidAttrEnd, isBlock, isClosing = false)
            }
            if (tagName in structuralMalformedAllowedTags && (tagName.length >= 2 || hasMatchingCloseTagAhead(s, nameEnd, tagName))) {
                return MatchedHtmlTag(tagName, skipAsciiWhitespace(s, nameEnd), isBlock, isClosing = false)
            }
        }

        return null
    }

    private fun hasMatchingCloseTagAhead(s: String, fromIndex: Int, tagName: String): Boolean {
        val marker = "</$tagName"
        val idx = s.indexOf(marker, fromIndex, ignoreCase = true)
        if (idx == -1) return false
        val after = idx + marker.length
        return after == s.length || s[after] == '>' || isAsciiWhitespace(s[after]) || s[after] == '<'
    }

    private fun isAsciiLetter(c: Char): Boolean =
        (c in 'a'..'z') || (c in 'A'..'Z')

    private fun isAsciiLetterOrDigit(c: Char): Boolean =
        isAsciiLetter(c) || (c in '0'..'9')

    private fun isAsciiAttrStart(c: Char): Boolean =
        isAsciiLetter(c) || c == '_' || c == ':'

    private fun isAsciiAttrChar(c: Char): Boolean =
        isAsciiLetterOrDigit(c) || c == '-' || c == '_' || c == ':' || c == '.'

    private fun isAsciiWhitespace(c: Char): Boolean =
        c == ' ' || c == '\t' || c == '\n' || c == '\r'

    private fun skipAsciiWhitespace(s: String, start: Int): Int {
        var i = start
        while (i < s.length && isAsciiWhitespace(s[i])) {
            i++
        }
        return i
    }

    private fun collapseWhitespaceLinear(cs: CharSequence): String {
        val sb = StringBuilder(cs.length)
        var lastWasSpace = true
        for (i in 0 until cs.length) {
            val c = cs[i]
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u00A0') {
                if (!lastWasSpace) {
                    sb.append(' ')
                    lastWasSpace = true
                }
            } else {
                sb.append(c)
                lastWasSpace = false
            }
        }
        if (sb.isNotEmpty() && sb[sb.length - 1] == ' ') {
            sb.setLength(sb.length - 1)
        }
        return sb.toString()
    }

    private fun cleanInlineTextLinear(raw: String): String {
        if (raw.isBlank()) return ""
        val decoded = decodeHtmlEntitiesLinear(raw)
        val stripped = stripTagsLinear(decoded)
        return collapseWhitespaceLinear(stripped)
    }

    private fun extractMetaContent(headHtml: String, attrName: String, attrValue: String): String {
        var idx = 0
        val len = headHtml.length
        while (idx < len) {
            val metaStart = headHtml.indexOf("<meta", idx, ignoreCase = true)
            if (metaStart == -1) break
            val metaEnd = headHtml.indexOf('>', metaStart + 5)
            if (metaEnd == -1) break
            val tag = headHtml.substring(metaStart, metaEnd + 1)
            val matchedAttr = extractTagAttributeValue(tag, attrName)
            if (matchedAttr.equals(attrValue, ignoreCase = true)) {
                val content = extractTagAttributeValue(tag, "content")
                if (content.isNotEmpty()) {
                    return cleanInlineTextLinear(content)
                }
            }
            idx = metaEnd + 1
        }
        return ""
    }

    private fun extractTagAttributeValue(tag: String, attrName: String): String {
        val pat = Pattern.compile("\\b$attrName\\s*=\\s*([\"'])(.*?)\\1", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
        val m = pat.matcher(tag)
        return if (m.find()) m.group(2)?.trim().orEmpty() else ""
    }

    private fun extractFirstTagTextLinear(html: String, tagName: String): String {
        val openMarker = "<$tagName"
        var searchFrom = 0
        while (searchFrom < html.length) {
            val start = html.indexOf(openMarker, searchFrom, ignoreCase = true)
            if (start == -1) return ""
            val afterTag = html.getOrNull(start + openMarker.length) ?: return ""
            if (afterTag != '>' && afterTag != ' ' && afterTag != '\t' && afterTag != '\n' && afterTag != '\r') {
                searchFrom = start + openMarker.length
                continue
            }
            val openEnd = html.indexOf('>', start + openMarker.length)
            if (openEnd == -1) return ""
            val closeIdx = html.indexOf("</$tagName", openEnd + 1, ignoreCase = true)
            if (closeIdx == -1) return ""
            val raw = html.substring(openEnd + 1, closeIdx)
            val text = cleanInlineTextLinear(raw)
            if (text.isNotEmpty()) return text
            searchFrom = closeIdx + tagName.length + 3
        }
        return ""
    }

    private fun stripTagsLinear(s: String): String {
        if (s.indexOf('<') == -1) return s
        val sb = StringBuilder(s.length)
        var i = 0
        val len = s.length
        while (i < len) {
            val ch = s[i]
            if (ch == '<') {
                val matched = tryMatchHtmlTagLinear(s, i)
                if (matched != null) {
                    if (matched.isBlockBreak || matched.tagName == "td" || matched.tagName == "th") {
                        sb.append(' ')
                    }
                    i = matched.endIndex
                    continue
                }
            }
            sb.append(ch)
            i++
        }
        return sb.toString()
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

    private fun isValidParagraph(text: String, minLen: Int = 8): Boolean {
        if (text.length < minLen) return false
        if (text.length > 35 && !text.contains(' ')) return false
        var hasLetter = false
        for (i in 0 until text.length) {
            val c = text[i]
            if (Character.isLetter(c) || c in '\u0980'..'\u09FF') {
                hasLetter = true
                break
            }
        }
        if (!hasLetter) return false
        if (isGenericGoogleTitle(text)) return false
        if (isPlaceholderText(text)) return false
        val lower = text.lowercase()
        if (lower.contains("all rights reserved") ||
            lower.contains("কপিরাইট") ||
            lower.contains("google news") ||
            lower.contains("enable javascript") ||
            lower.contains("privacy policy") ||
            lower.contains("terms of use")
        ) {
            return false
        }
        return true
    }

    internal fun decodeHtmlEntitiesLinear(s: String): String {
        if (s.indexOf('&') == -1) return s
        val pass1 = decodeHtmlEntitiesSinglePass(s)
        return if (pass1.indexOf('&') != -1 && pass1 != s) {
            decodeHtmlEntitiesSinglePass(pass1)
        } else {
            pass1
        }
    }

    private fun decodeHtmlEntitiesSinglePass(s: String): String {
        val len = s.length
        val sb = StringBuilder(len)
        var i = 0
        while (i < len) {
            val ch = s[i]
            if (ch == '&') {
                val semi = s.indexOf(';', i + 1)
                if (semi != -1 && semi - i <= 12) {
                    val entity = s.substring(i + 1, semi)
                    val decodedEntity = resolveHtmlEntity(entity)
                    if (decodedEntity != null) {
                        sb.append(decodedEntity)
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

    private fun resolveHtmlEntity(entity: String): String? {
        if (entity.isEmpty()) return null
        if (entity[0] == '#') {
            val codePoint = try {
                if (entity.length > 2 && (entity[1] == 'x' || entity[1] == 'X')) {
                    entity.substring(2).toInt(16)
                } else {
                    entity.substring(1).toInt(10)
                }
            } catch (_: NumberFormatException) {
                return null
            }
            return when (codePoint) {
                160 -> " "
                8216 -> "‘"
                8217 -> "’"
                8220 -> "“"
                8221 -> "”"
                8211 -> "–"
                8212 -> "—"
                in 32..0x10FFFF -> try {
                    String(Character.toChars(codePoint))
                } catch (_: IllegalArgumentException) {
                    null
                }
                else -> null
            }
        }
        return when (entity.lowercase(Locale.ROOT)) {
            "nbsp" -> " "
            "amp" -> "&"
            "lt" -> "<"
            "gt" -> ">"
            "quot" -> "\""
            "apos", "#39" -> "'"
            "lsquo" -> "‘"
            "rsquo" -> "’"
            "ldquo" -> "“"
            "rdquo" -> "”"
            "ndash" -> "–"
            "mdash" -> "—"
            "hellip" -> "…"
            "bull" -> "•"
            "copy" -> "©"
            "reg" -> "®"
            "trade" -> "™"
            "zwnj" -> "\u200C"
            "zwj" -> "\u200D"
            else -> null
        }
    }

    private fun unescape(s: String): String {
        return decodeHtmlEntitiesLinear(s).trim()
    }
}
