package com.potrikaghor.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
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

    companion object {
        const val PLACEHOLDER_LOADING = "মূল পত্রিকার পাতা থেকে বিস্তারিত সংবাদ লোড হচ্ছে..."
        const val PLACEHOLDER_WEBVIEW = "এই সংবাদের পূর্ণাঙ্গ পাঠ্য সরাসরি ওয়েবসাইট ভিউতে পড়ুন। উপরের আইকনে ট্যাপ করে ওয়েবসাইট ভিউ ও রিডার মোড পরিবর্তন করতে পারবেন।"
        const val PLACEHOLDER_OFFLINE = "ইন্টারনেট সংযোগ না থাকায় অথবা পৃষ্ঠাটি লোড হতে সমস্যা হওয়ায় পূর্ণ লেখাটি আনা সম্ভব হয়নি। আপনি মূল ওয়েবসাইটে পড়তে পারেন।"

        /**
         * JavaScript snippet that extracts live rendered article title, lead image, and visible
         * paragraphs directly from the WebView DOM (works with SPA/React newspapers like Prothom Alo,
         * Daily Star, Jugantor, Kalbela, Somoy, etc.).
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
                    function addText(txt) {
                        if (!txt) return;
                        var lines = txt.split(/\n+/);
                        for (var i = 0; i < lines.length; i++) {
                            var clean = lines[i].replace(/\s+/g, ' ').trim();
                            if (clean.length > 28 && !seen[clean]) {
                                seen[clean] = true;
                                paras.push(clean);
                            }
                        }
                    }

                    // 1. JSON-LD articleBody if present in DOM
                    var ldScripts = document.querySelectorAll('script[type="application/ld+json"]');
                    for (var s = 0; s < ldScripts.length; s++) {
                        try {
                            var data = JSON.parse(ldScripts[s].textContent || '{}');
                            var items = Array.isArray(data) ? data : (data['@graph'] ? data['@graph'] : [data]);
                            for (var k = 0; k < items.length; k++) {
                                if (items[k] && items[k].articleBody) {
                                    addText(items[k].articleBody);
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
                        for (var n = 0; n < nodes.length; n++) {
                            var el = nodes[n];
                            if (el.closest && el.closest('nav, header, footer, aside, .related-news, .comments, script, style')) continue;
                            addText(el.innerText || el.textContent || '');
                        }
                        if (paras.length >= 4) break;
                    }

                    // 3. Fallback to article or main innerText if paragraphs are still sparse
                    if (paras.length < 2) {
                        var mainContainer = document.querySelector('article, [itemprop="articleBody"], main');
                        if (mainContainer && mainContainer.innerText) {
                            addText(mainContainer.innerText);
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
    }

    suspend fun extractArticle(url: String, fallbackTitle: String = "", source: String = ""): CleanArticle =
        withContext(Dispatchers.IO) {
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

    fun decodeJsEvaluatedString(jsResult: String?): String {
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

    fun parseLiveDomJson(
        rawJsResult: String?,
        url: String,
        fallbackTitle: String,
        source: String
    ): CleanArticle? {
        val jsonStr = decodeJsEvaluatedString(rawJsResult)
        if (jsonStr.isBlank()) return null
        return try {
            val obj = JSONObject(jsonStr)
            if (obj.has("error")) return null

            val safeFallbackTitle = fallbackTitle.takeIf { !isGenericGoogleTitle(it) }.orEmpty()
            val rawTitle = obj.optString("title", "").trim()
            val resolvedTitle = rawTitle
                .takeIf { it.isNotEmpty() && !isGenericGoogleTitle(it) }
                ?: safeFallbackTitle.ifEmpty { source.ifEmpty { "সংবাদ" } }

            val rawImg = obj.optString("image", "").trim()
            val leadImage = rawImg.takeIf {
                (it.startsWith("http://") || it.startsWith("https://")) && !isGenericGoogleImage(it)
            }

            val resolvedUrl = obj.optString("url", "").takeIf { it.startsWith("http") } ?: url

            val paragraphs = mutableListOf<String>()
            val arr: JSONArray? = obj.optJSONArray("paragraphs")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val p = unescape(arr.optString(i, "")).trim()
                    if (isValidParagraph(p) && !paragraphs.contains(p)) {
                        paragraphs.add(p)
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

        // Split sentences on Bengali Danda (।), question/exclamation marks, newlines, or English period followed by space
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

        // Extract significant keywords from the headline to score sentence relevance
        val headlineWords = headline
            .split(Regex("[\\s,।.!?'\"“”‘’\\-:;()]+"))
            .map { it.trim().lowercase() }
            .filter { it.length >= 3 }
            .toSet()

        data class ScoredSentence(val index: Int, val text: String, val score: Double)

        val scored = rawSentences.mapIndexed { idx, sentence ->
            var score = 0.0
            // Lead sentences in journalism carry the core 5W1H facts
            if (idx == 0) score += 4.0
            else if (idx == 1) score += 2.5
            else if (idx <= 4) score += 1.5

            // Keyword overlap with headline
            val lower = sentence.lowercase()
            headlineWords.forEach { w ->
                if (lower.contains(w)) score += 1.8
            }

            // Sentences containing numbers/dates/statistics often hold concrete facts
            if (sentence.contains(Regex("[0-9০-৯]+"))) {
                score += 1.0
            }

            // Prefer well-formed medium-length sentences
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
        // Strip trailing newspaper/source suffix after " - ", " | ", " – ", " — "
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

        // Normalize Assamese Unicode lookalikes occasionally present in Bengali feeds
        s = s.replace('\u09F0', '\u09B0')
            .replace('\u09F1', '\u09AC')

        // Convert zero-width space and non-breaking spaces to a standard space so words never fuse
        s = s.replace("\u200B", " ")
            .replace("\u00A0", " ")
            .replace("\u202F", " ")
            .replace("\u2007", " ")

        // Preserve ZWJ (\u200D) only inside valid Bengali Ra+Ya-phala (র‍্য, e.g. র‍্যাব, র‍্যাঙ্কিং)
        // and preserve ZWNJ (\u200C) only immediately after hasanta (\u09CD); remove stray invisible marks
        s = s.replace(Regex("(?<!\u09B0\u09CD)\u200D|\u200D(?!\u09AF)"), "")
            .replace(Regex("(?<!\u09CD)\u200C"), "")
            .replace("\u00AD", "")
            .replace("\u2060", "")
            .replace("\uFEFF", "")
            .replace("\u200E", "")
            .replace("\u200F", "")

        // Collapse accidental duplicate hasanta or nukta combining marks
        s = s.replace(Regex("\u09CD{2,}"), "\u09CD")
            .replace(Regex("\u09BC{2,}"), "\u09BC")

        // Strip raw URLs
        s = s.replace(Regex("https?://\\S+"), " ")
            .replace(Regex("www\\.\\S+"), " ")

        // Strip parenthetical/bracketed photo or agency credits that interrupt speech flow
        s = s.replace(Regex("\\((?:ছবি|ফাইল ছবি|সংগৃহীত|ভিডিও|সূত্র|প্রতিনিধি)[^)]*\\)"), " ")
            .replace(Regex("\\[(?:ছবি|ফাইল ছবি|সংগৃহীত|ভিডিও|সূত্র)[^]]*]"), " ")

        // Normalize Bengali title abbreviations written with colon (e.g. "মো:", "ড:", "ডা:") to period
        // and convert ASCII colon used as Bengali visarga inside Bengali words (e.g. "দু:খ" -> "দুঃখ")
        s = s.replace(
            Regex("(?:^|(?<=[\\s(\\[,«\"'“‘]))(ড|ডা|মো|মি|প্রা|ইঞ্জি|অ্যাড|লে|জে|ব্রি):(?=\\s*[\u0980-\u09FF])"),
            "$1. "
        )
        s = s.replace(Regex("(?<=[\u0980-\u09FF]):(?=[\u0980-\u09FF])"), "ঃ")
            .replace("বি.দ্র.", "বিশেষ দ্রষ্টব্য, ")

        // Ensure a space after common Bengali abbreviations so they do not merge with the following name
        s = s.replace(
            Regex("(?:^|(?<=[\\s(\\[,]))(ড|ডা|মো|মি|প্রা|ইঞ্জি|অ্যাড|লে|জে|ব্রি)\\.(?=[\u0980-\u09FF])"),
            "$1. "
        )

        // Preserve number/date ranges with en-dash/em-dash (e.g. ১০–১৫ -> ১০-১৫) before converting clause dashes
        s = s.replace(Regex("(?<=[0-9০-৯])\\s*[–—]\\s*(?=[0-9০-৯])"), "-")

        // Convert clause dashes and vertical bars into a natural comma pause
        s = s.replace(Regex("\\s*[—–|]+\\s*"), ", ")
            .replace(Regex("\\s+--\\s+"), ", ")

        // Separate hyphenated or slashed Bengali compound words so adjacent words do not slur together
        s = s.replace(Regex("(?<=[\u0980-\u09FF])-(?=[\u0980-\u09FF])"), " ")
            .replace(Regex("(?<=[\u0980-\u09FF])\\s*/\\s*(?=[\u0980-\u09FF])"), ", ")

        // Convert remaining parenthetical/bracketed phrases into comma boundaries so words do not blend across "(" or ")"
        s = s.replace(Regex("\\s*[(\\[]([^)\\]]+)[)\\]]\\s*"), ", $1, ")

        // Keep Bengali internal elision apostrophe smooth (e.g. দু’জন -> দুজন, দু’টি -> দুটি)
        s = s.replace(Regex("(?<=\u09A6\u09C1)[’'](?=[\u0980-\u09FF])"), "")

        // Replace remaining quotation marks and decorative symbols with a space so adjacent words never fuse
        s = s.replace(Regex("[•▪►»*#_~<>\"'“”‘’«]+"), " ")

        // Ensure clean word boundaries between adjacent Bengali and Latin/English scripts and after '%'
        s = s.replace(Regex("(?<=[\u0980-\u09FF])(?=[A-Za-z])"), " ")
            .replace(Regex("(?<=[A-Za-z])(?=[\u0980-\u09FF])"), " ")
            .replace(Regex("%(?=[\\p{L}\\p{N}\u0980-\u09FF])"), "% ")

        // Remove accidental spaces before punctuation
        s = s.replace(Regex("\\s+([।?!,;:])"), "$1")

        // Ensure a space follows sentence/clause punctuation without breaking numbers (১,০০,০০০) or times (১০:৩০)
        s = s.replace(Regex("([।?!;])(?=[\\p{L}\\p{N}\u0980-\u09FF])"), "$1 ")
            .replace(Regex("([,:])(?=[\\p{L}\u0980-\u09FF])"), "$1 ")
            .replace(Regex("(?<![0-9০-৯])([,:])(?=[0-9০-৯])"), "$1 ")

        // Collapse repeated punctuation and clean up comma-before-danda artifacts
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

        // 4. Remove script, style, nav, header, footer, noscript and extract <p> and story-element blocks
        val clean = html
            .replace(Regex("<script[^>]*>.*?</script>", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("<style[^>]*>.*?</style>", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("<nav[^>]*>.*?</nav>", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("<header[^>]*>.*?</header>", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("<footer[^>]*>.*?</footer", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("<aside[^>]*>.*?</aside>", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("<iframe[^>]*>.*?</iframe>", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), " ")

        val pMat = Pattern.compile("<(?:p|div[^>]*class=[\"'][^\"']*story-element-text[^\"']*[\"'])[^>]*>(.*?)</(?:p|div)>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL).matcher(clean)
        while (pMat.find()) {
            val rawP = pMat.group(1) ?: continue
            val text = unescape(stripTags(rawP)).replace(Regex("\\s+"), " ").trim()
            if (isValidParagraph(text) && !paragraphs.contains(text)) {
                paragraphs.add(text)
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
