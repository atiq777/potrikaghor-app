package com.potrikaghor.ui.screens

import android.content.Intent
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.potrikaghor.data.AppStateRepository
import com.potrikaghor.data.ArticleExtractionCoordinator
import com.potrikaghor.data.ArticleExtractor
import com.potrikaghor.data.CleanArticle
import com.potrikaghor.model.Bookmark
import com.potrikaghor.model.Paper
import com.potrikaghor.ui.components.MotionTokens
import com.potrikaghor.ui.components.PaperAvatar
import com.potrikaghor.ui.components.ShareCardDialog
import com.potrikaghor.ui.components.pressScaleEffect
import com.potrikaghor.ui.components.rememberReducedMotion
import com.potrikaghor.ui.theme.AmoledBackground
import com.potrikaghor.ui.theme.FlagRed
import com.potrikaghor.ui.theme.SepiaBackground
import com.potrikaghor.ui.theme.SepiaText
import com.potrikaghor.ui.util.BengaliUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI
import java.util.Locale
import kotlin.math.abs

/**
 * Throttles WebView progress updates so [ReaderScreen] does not recompose on every 1% progress tick.
 */
internal fun shouldUpdateWebProgress(
    previousProgress: Int,
    newProgress: Int,
    lastUpdateMs: Long,
    nowMs: Long
): Boolean {
    val clamped = newProgress.coerceIn(0, 100)
    if (clamped == previousProgress) return false
    if (previousProgress == 0 && clamped > 0) return true
    if (clamped >= 100 && previousProgress < 100) return true
    if (clamped == 0) return true
    return abs(clamped - previousProgress) >= 15 && (nowMs - lastUpdateMs) >= 180L
}

/**
 * Tracks lazy/on-demand TTS lifecycle so opening [ReaderScreen] never initializes [TextToSpeech]
 * until the user explicitly taps the Listen/Play button.
 */
internal class LazyTtsLifecycleState {
    var initRequestCount: Int = 0
        private set
    var isInitialized: Boolean = false
        private set
    var isInitializing: Boolean = false
        private set
    var isDisposed: Boolean = false
        private set

    fun shouldCreateEngineOnReaderOpen(): Boolean = false

    fun requestEngineInitialization(factory: () -> Unit): Boolean {
        if (isDisposed) return false
        if (isInitialized || isInitializing) return false
        isInitializing = true
        initRequestCount++
        factory()
        return true
    }

    fun onEngineInitResult(success: Boolean) {
        isInitializing = false
        isInitialized = success && !isDisposed
    }

    fun onDispose(cleanup: () -> Unit) {
        if (!isDisposed) {
            isDisposed = true
            isInitializing = false
            isInitialized = false
            cleanup()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    paper: Paper,
    initialUrl: String,
    initialTitle: String = "",
    appRepo: AppStateRepository,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val appState by appRepo.state.collectAsState()
    val extractor = remember { ArticleExtractor() }
    val extractionCoordinator = remember { ArticleExtractionCoordinator() }
    val ttsLifecycle = remember { LazyTtsLifecycleState() }

    val fallbackHeadline = remember(initialTitle, paper.bn) {
        initialTitle.trim().ifEmpty { paper.bn }
    }

    // Open news articles in the user's preferred mode (Clean Reader Mode by default),
    // while direct newspaper homepages (without an article headline) open in Website View.
    var isCleanReaderMode by remember {
        mutableStateOf(if (initialTitle.isNotBlank()) appState.preferCleanReader else false)
    }
    var cleanArticle by remember { mutableStateOf<CleanArticle?>(null) }
    var isExtracting by remember { mutableStateOf(true) }

    var webProgress by remember { mutableIntStateOf(0) }
    var lastProgressUpdateMs by remember { mutableLongStateOf(0L) }
    var currentWebUrl by remember { mutableStateOf(initialUrl) }
    var isRedirectingGoogleNews by remember { mutableStateOf(initialUrl.contains("news.google.com")) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    var liveDomExtractionJob by remember { mutableStateOf<Job?>(null) }
    var delayedPageFinishedJob by remember { mutableStateOf<Job?>(null) }
    var ttsPlaybackJob by remember { mutableStateOf<Job?>(null) }

    var showShareDialog by remember { mutableStateOf(false) }
    var showAiSummary by remember { mutableStateOf(false) }
    var summaryBullets by remember { mutableStateOf<List<String>>(emptyList()) }
    var summaryArticleUrl by remember { mutableStateOf("") }
    var showTtsControls by remember { mutableStateOf(false) }

    // Reader styling options
    var readerFontSize by remember { mutableIntStateOf(appState.articleFontSize) }
    val readerLineHeight by remember { mutableFloatStateOf(appState.articleLineHeight) }
    val readerThemeStyle by remember { mutableStateOf(appState.themeStyle) } // amoled, normal, sepia

    // Lazy / On-Demand TTS state (never initialized until user presses Listen)
    var tts: TextToSpeech? by remember { mutableStateOf(null) }
    var isTtsReady by remember { mutableStateOf(false) }
    var isTtsInitializing by remember { mutableStateOf(false) }
    var isTtsPlaying by remember { mutableStateOf(false) }
    var ttsSpeed by remember { mutableFloatStateOf(1.0f) }
    var pendingTtsArticle by remember { mutableStateOf<CleanArticle?>(null) }
    var pendingTtsAutoPlay by remember { mutableStateOf(false) }

    val isBookmarked = appState.bookmarks.any { it.url == currentWebUrl || it.url == initialUrl }

    // Helper to extract live article content from the currently rendered WebView DOM
    // WebView calls remain strictly on the Main Thread; string decoding and HTML parsing run on Dispatchers.Default.
    fun extractLiveFromWebView(
        wv: WebView?,
        forceRefresh: Boolean = false,
        onComplete: ((CleanArticle?) -> Unit)? = null
    ) {
        if (wv == null) {
            onComplete?.invoke(cleanArticle)
            return
        }
        val liveUrl = (wv.url?.takeIf { it.isNotBlank() } ?: currentWebUrl).trim()
        if (liveUrl.isEmpty() || liveUrl.contains("news.google.com")) {
            onComplete?.invoke(cleanArticle)
            return
        }

        // Skip duplicate extraction if this exact URL already has substantive content
        if (!forceRefresh &&
            extractionCoordinator.hasSubstantiveResultFor(liveUrl) &&
            cleanArticle?.url == liveUrl &&
            extractor.hasSubstantiveContent(cleanArticle)
        ) {
            isExtracting = false
            onComplete?.invoke(cleanArticle)
            return
        }

        if (!extractionCoordinator.tryStartLiveDomExtraction(liveUrl, force = forceRefresh)) {
            onComplete?.invoke(cleanArticle)
            return
        }

        val capturedVersion = extractionCoordinator.currentVersion()

        wv.evaluateJavascript(ArticleExtractor.LIVE_DOM_EXTRACTION_JS) { rawJs ->
            liveDomExtractionJob?.cancel()
            liveDomExtractionJob = scope.launch(Dispatchers.Default) {
                try {
                    val domArticle = extractor.parseLiveDomJson(
                        rawJsResult = rawJs,
                        url = liveUrl,
                        fallbackTitle = fallbackHeadline,
                        source = paper.bn
                    )
                    ensureActive()
                    withContext(Dispatchers.Main) {
                        if (!extractionCoordinator.isCurrent(capturedVersion, liveUrl)) {
                            extractionCoordinator.finishLiveDomExtraction(liveUrl)
                            return@withContext
                        }
                        if (domArticle != null && extractor.hasSubstantiveContent(domArticle)) {
                            extractionCoordinator.markSucceeded(liveUrl)
                            cleanArticle = domArticle
                            isExtracting = false
                            onComplete?.invoke(domArticle)
                        } else {
                            // Bounded fallback: extract only cleaned article/main/body markup (<=180 KB),
                            // NEVER full multi-megabyte document.documentElement.outerHTML, and decode on Default.
                            wv.evaluateJavascript(ArticleExtractor.BOUNDED_ARTICLE_HTML_JS) { rawBoundedHtmlJs ->
                                liveDomExtractionJob = scope.launch(Dispatchers.Default) {
                                    try {
                                        val decodedHtml = extractor.decodeJsEvaluatedString(rawBoundedHtmlJs)
                                        val parsed = if (decodedHtml.length > 120) {
                                            extractor.parseHtml(
                                                html = decodedHtml,
                                                url = liveUrl,
                                                fallbackTitle = fallbackHeadline,
                                                source = paper.bn
                                            )
                                        } else {
                                            null
                                        }
                                        ensureActive()
                                        withContext(Dispatchers.Main) {
                                            if (!extractionCoordinator.isCurrent(capturedVersion, liveUrl)) {
                                                extractionCoordinator.finishLiveDomExtraction(liveUrl)
                                                return@withContext
                                            }
                                            if (parsed != null && (extractor.hasSubstantiveContent(parsed) || cleanArticle == null)) {
                                                if (extractor.hasSubstantiveContent(parsed)) {
                                                    extractionCoordinator.markSucceeded(liveUrl)
                                                }
                                                cleanArticle = parsed
                                            }
                                            extractionCoordinator.finishLiveDomExtraction(liveUrl)
                                            isExtracting = false
                                            onComplete?.invoke(cleanArticle)
                                        }
                                    } catch (_: Exception) {
                                        withContext(Dispatchers.Main) {
                                            extractionCoordinator.finishLiveDomExtraction(liveUrl)
                                            isExtracting = false
                                            onComplete?.invoke(cleanArticle)
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                    withContext(Dispatchers.Main) {
                        extractionCoordinator.finishLiveDomExtraction(liveUrl)
                        isExtracting = false
                        onComplete?.invoke(cleanArticle)
                    }
                }
            }
        }
    }

    // Helper to prepare TTS text & voice off the Main Thread and queue utterances smoothly
    fun startTtsPlayback(articleToRead: CleanArticle?) {
        val engine = tts
        if (engine == null || !isTtsReady) {
            pendingTtsArticle = articleToRead
            pendingTtsAutoPlay = true
            if (!isTtsInitializing) {
                Toast.makeText(
                    context,
                    "টেক্সট-টু-স্পিচ ইঞ্জিন প্রস্তুত হচ্ছে, অনুগ্রহ করে একটু অপেক্ষা করুন",
                    Toast.LENGTH_SHORT
                ).show()
            }
            return
        }

        ttsPlaybackJob?.cancel()
        ttsPlaybackJob = scope.launch(Dispatchers.Default) {
            val fullText = extractor.buildTtsSpeechText(articleToRead, fallbackHeadline)
            if (fullText.isBlank()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "পড়ে শোনানোর মতো পাঠ্য পাওয়া যায়নি", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            val hasBengali = Regex("[\u0980-\u09FF]").containsMatchIn(fullText)
            val bnSupported = configureNaturalTtsVoice(engine, hasBengali)

            val paragraphs = fullText
                .split(Regex("\\n{2,}"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            val paragraphChunks = paragraphs
                .map { splitTextIntoTtsChunks(it) }
                .filter { it.isNotEmpty() }

            if (paragraphChunks.isEmpty()) return@launch
            ensureActive()

            withContext(Dispatchers.Main) {
                if (hasBengali && !bnSupported) {
                    Toast.makeText(
                        context,
                        "আপনার ফোনের TTS সেটিংসে বাংলা ভয়েস ডাউনলোড করা নেই",
                        Toast.LENGTH_LONG
                    ).show()
                }

                val effectiveRate = if (hasBengali) (ttsSpeed * 0.97f).coerceIn(0.6f, 1.6f) else ttsSpeed
                engine.setSpeechRate(effectiveRate)
                engine.setPitch(0.98f)
                engine.stop()
                isTtsPlaying = true

                val totalUtterances = paragraphChunks.sumOf { it.size }
                var spokenCount = 0

                paragraphChunks.forEachIndexed { pIndex, chunks ->
                    chunks.forEachIndexed { cIndex, chunk ->
                        spokenCount++
                        val isFirst = (pIndex == 0 && cIndex == 0)
                        val isLast = (spokenCount == totalUtterances)
                        val queueMode = if (isFirst) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                        val utteranceId = if (isLast) "potrikaghor_tts_last" else "potrikaghor_tts_${pIndex}_$cIndex"
                        engine.speak(chunk, queueMode, null, utteranceId)

                        if (!isLast && cIndex < chunks.lastIndex) {
                            engine.playSilentUtterance(
                                160L,
                                TextToSpeech.QUEUE_ADD,
                                "potrikaghor_tts_sent_pause_${pIndex}_$cIndex"
                            )
                        }
                    }

                    if (pIndex < paragraphChunks.lastIndex) {
                        val pauseDurationMs = if (pIndex == 0) 480L else 380L
                        engine.playSilentUtterance(
                            pauseDurationMs,
                            TextToSpeech.QUEUE_ADD,
                            "potrikaghor_tts_para_pause_$pIndex"
                        )
                    }
                }
            }
        }
    }

    // Lazy / On-Demand TTS initializer — only invoked when user presses Listen or Play
    fun ensureTtsInitializedAndPlay(articleToRead: CleanArticle?) {
        pendingTtsArticle = articleToRead
        pendingTtsAutoPlay = true

        if (tts != null && isTtsReady) {
            startTtsPlayback(articleToRead)
            return
        }

        if (isTtsInitializing) {
            Toast.makeText(
                context,
                "টেক্সট-টু-স্পিচ ইঞ্জিন প্রস্তুত হচ্ছে...",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        ttsLifecycle.requestEngineInitialization {
            isTtsInitializing = true
            var createdEngine: TextToSpeech? = null
            val initListener = TextToSpeech.OnInitListener { status ->
                val success = (status == TextToSpeech.SUCCESS)
                ttsLifecycle.onEngineInitResult(success)
                if (success && !ttsLifecycle.isDisposed) {
                    val engine = createdEngine ?: tts
                    engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            scope.launch(Dispatchers.Main) {
                                isTtsPlaying = true
                            }
                        }

                        override fun onDone(utteranceId: String?) {
                            if (utteranceId == "potrikaghor_tts_last") {
                                scope.launch(Dispatchers.Main) {
                                    isTtsPlaying = false
                                }
                            }
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            scope.launch(Dispatchers.Main) {
                                isTtsPlaying = false
                            }
                        }

                        override fun onError(utteranceId: String?, errorCode: Int) {
                            scope.launch(Dispatchers.Main) {
                                isTtsPlaying = false
                            }
                        }
                    })

                    scope.launch(Dispatchers.Default) {
                        if (engine != null) {
                            configureNaturalTtsVoice(engine, hasBengali = true)
                            engine.setSpeechRate(0.97f)
                            engine.setPitch(0.98f)
                        }
                        withContext(Dispatchers.Main) {
                            if (!ttsLifecycle.isDisposed) {
                                isTtsInitializing = false
                                isTtsReady = true
                                if (pendingTtsAutoPlay && showTtsControls) {
                                    pendingTtsAutoPlay = false
                                    startTtsPlayback(pendingTtsArticle ?: cleanArticle)
                                }
                            }
                        }
                    }
                } else {
                    scope.launch(Dispatchers.Main) {
                        isTtsInitializing = false
                        isTtsReady = false
                        pendingTtsAutoPlay = false
                    }
                }
            }

            scope.launch(Dispatchers.IO) {
                val googleTtsPackage = "com.google.android.tts"
                val hasGoogleTts = try {
                    context.packageManager.getPackageInfo(googleTtsPackage, 0) != null
                } catch (_: Exception) {
                    false
                }
                withContext(Dispatchers.Main) {
                    if (!ttsLifecycle.isDisposed) {
                        createdEngine = if (hasGoogleTts) {
                            TextToSpeech(context.applicationContext, initListener, googleTtsPackage)
                        } else {
                            TextToSpeech(context.applicationContext, initListener)
                        }
                        tts = createdEngine
                    }
                }
            }
        }
    }

    // Dispose TTS & stop WebView cleanly on screen exit
    DisposableEffect(Unit) {
        onDispose {
            liveDomExtractionJob?.cancel()
            delayedPageFinishedJob?.cancel()
            ttsPlaybackJob?.cancel()
            extractionCoordinator.reset()
            try {
                webViewRef?.stopLoading()
            } catch (_: Exception) {}
            webViewRef = null
            ttsLifecycle.onDispose {
                try {
                    tts?.stop()
                    tts?.shutdown()
                } catch (_: Exception) {}
                tts = null
            }
        }
    }

    // Safety timeout for Google News redirection overlay
    LaunchedEffect(isRedirectingGoogleNews) {
        if (isRedirectingGoogleNews) {
            delay(5500L)
            isRedirectingGoogleNews = false
        }
    }

    // Track recently read paper & mark article read after initial transition frame settles
    LaunchedEffect(paper.url, initialUrl, initialTitle) {
        delay(90L)
        if (!paper.url.contains("news.google.com")) {
            appRepo.addRecent(paper.url)
        }
        if (initialTitle.isNotBlank() || !initialUrl.contains("news.google.com")) {
            appRepo.markArticleRead(
                url = initialUrl,
                title = initialTitle.ifBlank { paper.bn },
                sourceName = paper.bn,
                paperUrl = paper.url
            )
        }
    }

    // Reset state if initialUrl prop changes to a completely different article
    LaunchedEffect(initialUrl) {
        if (currentWebUrl != initialUrl) {
            liveDomExtractionJob?.cancel()
            delayedPageFinishedJob?.cancel()
            ttsPlaybackJob?.cancel()
            tts?.stop()
            isTtsPlaying = false
            pendingTtsAutoPlay = false
            cleanArticle = null
            summaryBullets = emptyList()
            summaryArticleUrl = ""
            isExtracting = true
            currentWebUrl = initialUrl
            isRedirectingGoogleNews = initialUrl.contains("news.google.com")
            webViewRef?.loadUrl(initialUrl)
        }
    }

    // Extract clean article via HTTP when URL changes (for direct non-Google News URLs or offline bookmarks)
    LaunchedEffect(currentWebUrl) {
        val requestVersion = extractionCoordinator.onUrlChanged(currentWebUrl)
        liveDomExtractionJob?.cancel()
        delayedPageFinishedJob?.cancel()

        // Invalidate summary if it belonged to a previous article URL
        if (summaryArticleUrl.isNotEmpty() &&
            summaryArticleUrl != currentWebUrl &&
            !currentWebUrl.contains("news.google.com")
        ) {
            summaryBullets = emptyList()
            summaryArticleUrl = ""
        }

        val savedOffline = appState.bookmarks.firstOrNull {
            it.url == currentWebUrl || it.url == initialUrl
        }?.offlineContent

        if (!savedOffline.isNullOrEmpty()) {
            val offlineArticle = CleanArticle(
                title = fallbackHeadline,
                source = paper.bn,
                url = currentWebUrl,
                leadImageUrl = null,
                paragraphs = savedOffline.split("\n\n").filter { it.isNotBlank() },
                plainText = savedOffline
            )
            extractionCoordinator.markSucceeded(currentWebUrl)
            cleanArticle = offlineArticle
            isExtracting = false
        } else if (!currentWebUrl.contains("news.google.com")) {
            if (extractionCoordinator.hasSubstantiveResultFor(currentWebUrl) &&
                cleanArticle?.url == currentWebUrl &&
                extractor.hasSubstantiveContent(cleanArticle)
            ) {
                isExtracting = false
                return@LaunchedEffect
            }

            isExtracting = !extractor.hasSubstantiveContent(cleanArticle) || cleanArticle?.url != currentWebUrl
            val extracted = extractor.extractArticle(currentWebUrl, fallbackHeadline, paper.bn)
            if (!extractionCoordinator.isCurrent(requestVersion, currentWebUrl)) {
                return@LaunchedEffect
            }
            if (extractor.hasSubstantiveContent(extracted)) {
                extractionCoordinator.markSucceeded(currentWebUrl)
            }
            if (!extractor.hasSubstantiveContent(cleanArticle) || cleanArticle?.url != currentWebUrl) {
                cleanArticle = extracted
            }
            isExtracting = false
            if (isBookmarked && extractor.hasSubstantiveContent(cleanArticle)) {
                appRepo.saveBookmarkOfflineContent(currentWebUrl, (cleanArticle ?: extracted).plainText)
            }
        } else {
            cleanArticle = extractor.extractArticle(currentWebUrl, fallbackHeadline, paper.bn)
            isExtracting = true
        }
    }

    // Generate smart summary strictly on Dispatchers.Default and only when showAiSummary is enabled
    LaunchedEffect(showAiSummary, cleanArticle, fallbackHeadline, currentWebUrl) {
        if (!showAiSummary) return@LaunchedEffect
        val targetArticle = cleanArticle
        val targetUrl = currentWebUrl
        val computed = extractor.generateSmartSummaryAsync(
            article = targetArticle,
            fallbackTitle = fallbackHeadline,
            dispatcher = Dispatchers.Default
        )
        ensureActive()
        if (targetUrl == currentWebUrl) {
            summaryBullets = computed
            summaryArticleUrl = targetUrl
        }
    }

    BackHandler {
        val wv = webViewRef
        if (!isCleanReaderMode && wv != null && wv.canGoBack()) {
            val backList = wv.copyBackForwardList()
            val prevUrl = backList.getItemAtIndex(backList.currentIndex - 1)?.url ?: ""
            if (prevUrl.isNotEmpty() && !prevUrl.contains("news.google.com")) {
                wv.goBack()
                return@BackHandler
            }
        }
        tts?.stop()
        onBack()
    }

    // Scroll state & reading progress for Clean Reader Mode
    val readerScrollState = rememberScrollState()
    val readingProgress by remember {
        derivedStateOf {
            val max = readerScrollState.maxValue
            if (max <= 0) 0f else (readerScrollState.value.toFloat() / max.toFloat()).coerceIn(0f, 1f)
        }
    }
    val animatedReadingProgress by animateFloatAsState(
        targetValue = readingProgress,
        label = "readingProgress"
    )

    val readerBgColor = when (readerThemeStyle) {
        "amoled" -> AmoledBackground
        "sepia" -> SepiaBackground
        else -> MaterialTheme.colorScheme.background
    }

    val readerTextColor = when (readerThemeStyle) {
        "amoled" -> Color.White
        "sepia" -> SepiaText
        else -> MaterialTheme.colorScheme.onBackground
    }

    val displayHost = remember(currentWebUrl, paper.host) {
        try {
            val h = URI(currentWebUrl).host?.removePrefix("www.") ?: ""
            if (h.isNotEmpty() && !h.contains("news.google.com")) h else paper.host.takeIf { !it.contains("news.google.com") } ?: paper.bn
        } catch (_: Exception) {
            paper.host
        }
    }

    val reducedMotion = rememberReducedMotion()
    var bookmarkPulse by remember { mutableStateOf(false) }
    LaunchedEffect(bookmarkPulse) {
        if (bookmarkPulse) {
            delay(160L)
            bookmarkPulse = false
        }
    }
    val bookmarkScale by animateFloatAsState(
        targetValue = if (bookmarkPulse && !reducedMotion) 1.18f else 1f,
        animationSpec = tween(
            durationMillis = if (reducedMotion) 0 else MotionTokens.FAST_MS,
            easing = FastOutSlowInEasing
        ),
        label = "bookmarkScale"
    )
    val bookmarkTint by animateColorAsState(
        targetValue = if (isBookmarked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        animationSpec = tween(
            durationMillis = if (reducedMotion) 0 else MotionTokens.FAST_MS,
            easing = FastOutSlowInEasing
        ),
        label = "bookmarkTint"
    )
    val bookmarkInteraction = remember { MutableInteractionSource() }
    val backInteraction = remember { MutableInteractionSource() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PaperAvatar(paper = paper, size = 30.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = paper.bn,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = displayHost,
                                style = MaterialTheme.typography.bodySmall,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            tts?.stop()
                            onBack()
                        },
                        interactionSource = backInteraction,
                        modifier = Modifier.pressScaleEffect(backInteraction, pressedScale = 0.90f)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "ফিরে যান")
                    }
                },
                actions = {
                    // AI Summary button in TopAppBar (accessible in BOTH Website View and Clean Reader Mode)
                    IconButton(
                        onClick = {
                            val nextState = !showAiSummary
                            showAiSummary = nextState
                            if (nextState) {
                                extractLiveFromWebView(webViewRef)
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.AutoAwesome,
                            contentDescription = "সংক্ষেপে পড়ুন",
                            tint = if (showAiSummary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }

                    // Toggle Clean Reader vs Website View
                    IconButton(
                        onClick = {
                            val switchingToClean = !isCleanReaderMode
                            isCleanReaderMode = switchingToClean
                            appRepo.setPreferCleanReader(switchingToClean)
                            if (switchingToClean) {
                                extractLiveFromWebView(webViewRef)
                            }
                        }
                    ) {
                        Icon(
                            imageVector = if (isCleanReaderMode) Icons.Outlined.Language else Icons.Outlined.MenuBook,
                            contentDescription = if (isCleanReaderMode) "ওয়েবসাইট ভিউ" else "ক্লিন রিডার মোড",
                            tint = if (isCleanReaderMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }

                    // TTS button (initializes TTS lazily on first tap)
                    IconButton(
                        onClick = {
                            val openingControls = !showTtsControls
                            showTtsControls = openingControls
                            if (openingControls && !isTtsPlaying) {
                                if (extractor.hasSubstantiveContent(cleanArticle)) {
                                    ensureTtsInitializedAndPlay(cleanArticle)
                                } else {
                                    extractLiveFromWebView(webViewRef) { updatedArticle ->
                                        ensureTtsInitializedAndPlay(updatedArticle ?: cleanArticle)
                                    }
                                }
                            } else if (!openingControls) {
                                pendingTtsAutoPlay = false
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = "খবর শুনুন (টেক্সট টু স্পিচ)",
                            tint = if (isTtsPlaying || showTtsControls) FlagRed else MaterialTheme.colorScheme.onSurface
                        )
                    }

                    // Bookmark toggle with subtle scale + icon state transition
                    IconButton(
                        onClick = {
                            bookmarkPulse = true
                            if (isBookmarked) {
                                appRepo.removeBookmark(currentWebUrl)
                                appRepo.removeBookmark(initialUrl)
                                Toast.makeText(context, "বুকমার্ক সরানো হয়েছে", Toast.LENGTH_SHORT).show()
                            } else {
                                val resolvedTitle = cleanArticle?.title
                                    ?.takeIf { !it.equals("Google News", ignoreCase = true) }
                                    ?: fallbackHeadline
                                val b = Bookmark(
                                    url = currentWebUrl,
                                    title = resolvedTitle,
                                    paperUrl = paper.url,
                                    savedAt = System.currentTimeMillis(),
                                    folder = "পরে পড়ব",
                                    offlineContent = cleanArticle?.plainText
                                )
                                appRepo.addBookmark(b)
                                Toast.makeText(context, "বুকমার্ক ও অফলাইনে সেভ হয়েছে", Toast.LENGTH_SHORT).show()
                            }
                        },
                        interactionSource = bookmarkInteraction,
                        modifier = Modifier
                            .pressScaleEffect(bookmarkInteraction, pressedScale = 0.88f)
                            .graphicsLayer {
                                scaleX = bookmarkScale
                                scaleY = bookmarkScale
                            }
                    ) {
                        Crossfade(
                            targetState = isBookmarked,
                            animationSpec = tween(
                                durationMillis = if (reducedMotion) 0 else MotionTokens.FAST_MS,
                                easing = FastOutSlowInEasing
                            ),
                            label = "bookmarkIconCrossfade"
                        ) { bookmarked ->
                            Icon(
                                imageVector = if (bookmarked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                                contentDescription = "বুকমার্ক",
                                tint = bookmarkTint
                            )
                        }
                    }

                    // Share Card
                    IconButton(onClick = { showShareDialog = true }) {
                        Icon(Icons.Default.Share, contentDescription = "শেয়ার কার্ড")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(if (isCleanReaderMode) readerBgColor else MaterialTheme.colorScheme.background)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Prominent Clean Reader vs Website View Segmented Switch Bar
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    if (!isCleanReaderMode) {
                                        isCleanReaderMode = true
                                        appRepo.setPreferCleanReader(true)
                                        extractLiveFromWebView(webViewRef)
                                    }
                                },
                            shape = RoundedCornerShape(10.dp),
                            color = if (isCleanReaderMode) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surface
                            }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 7.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.MenuBook,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp),
                                    tint = if (isCleanReaderMode) {
                                        MaterialTheme.colorScheme.onPrimary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "ক্লিন রিডার",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontSize = 12.sp,
                                        lineHeight = 15.sp
                                    ),
                                    fontWeight = FontWeight.Bold,
                                    color = if (isCleanReaderMode) {
                                        MaterialTheme.colorScheme.onPrimary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                        }

                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    if (isCleanReaderMode) {
                                        isCleanReaderMode = false
                                        appRepo.setPreferCleanReader(false)
                                    }
                                },
                            shape = RoundedCornerShape(10.dp),
                            color = if (!isCleanReaderMode) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surface
                            }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 7.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Language,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp),
                                    tint = if (!isCleanReaderMode) {
                                        MaterialTheme.colorScheme.onPrimary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "মূল ওয়েবসাইট",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontSize = 12.sp,
                                        lineHeight = 15.sp
                                    ),
                                    fontWeight = FontWeight.Bold,
                                    color = if (!isCleanReaderMode) {
                                        MaterialTheme.colorScheme.onPrimary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                        }
                    }
                }

                // Subtle reading progress indicator bar at the top of Reader Mode
                if (isCleanReaderMode && !isExtracting && cleanArticle != null) {
                    LinearProgressIndicator(
                        progress = { animatedReadingProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.5.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.88f),
                        trackColor = if (readerThemeStyle == "amoled") Color(0xFF141414) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        strokeCap = StrokeCap.Round
                    )
                }

                // Loading indicator for web view
                if (!isCleanReaderMode && webProgress in 1..99) {
                    LinearProgressIndicator(
                        progress = { webProgress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                // TTS floating control strip
                AnimatedVisibility(visible = showTtsControls) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = {
                                        if (isTtsPlaying) {
                                            ttsPlaybackJob?.cancel()
                                            tts?.stop()
                                            isTtsPlaying = false
                                            pendingTtsAutoPlay = false
                                        } else {
                                            if (extractor.hasSubstantiveContent(cleanArticle)) {
                                                ensureTtsInitializedAndPlay(cleanArticle)
                                            } else {
                                                extractLiveFromWebView(webViewRef) { updatedArticle ->
                                                    ensureTtsInitializedAndPlay(updatedArticle ?: cleanArticle)
                                                }
                                            }
                                        }
                                    }
                                ) {
                                    Icon(
                                        imageVector = if (isTtsPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                        contentDescription = if (isTtsPlaying) "থামান" else "শুনুন"
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        ttsPlaybackJob?.cancel()
                                        tts?.stop()
                                        isTtsPlaying = false
                                        pendingTtsAutoPlay = false
                                    }
                                ) {
                                    Icon(Icons.Filled.Stop, contentDescription = "বন্ধ করুন")
                                }
                                Text(
                                    text = when {
                                        isTtsPlaying -> "পড়ে শোনানো হচ্ছে..."
                                        isTtsInitializing -> "ইঞ্জিন প্রস্তুত হচ্ছে..."
                                        else -> "শুনতে Play চাপুন"
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    maxLines = 1
                                )
                            }

                            // Speed selection
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("গতি: ", style = MaterialTheme.typography.bodySmall)
                                listOf(0.75f, 1.0f, 1.25f, 1.5f).forEach { spd ->
                                    Surface(
                                        modifier = Modifier
                                            .padding(horizontal = 2.dp)
                                            .clickable {
                                                ttsSpeed = spd
                                                tts?.setSpeechRate((spd * 0.97f).coerceIn(0.6f, 1.6f))
                                                if (isTtsPlaying) {
                                                    startTtsPlayback(cleanArticle)
                                                }
                                            },
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (ttsSpeed == spd) MaterialTheme.colorScheme.primary else Color.Transparent
                                    ) {
                                        Text(
                                            text = "${spd}x",
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            fontSize = 11.sp,
                                            color = if (ttsSpeed == spd) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // AI News Summary Drawer/Card (computed off Main Thread on Dispatchers.Default)
                AnimatedVisibility(visible = showAiSummary) {
                    val displayedBullets = if (summaryBullets.isNotEmpty() && summaryArticleUrl == currentWebUrl) {
                        summaryBullets
                    } else {
                        listOf("সংবাদটির মূল অংশ বিশ্লেষণ করা হচ্ছে...")
                    }

                    // The summary card sits on the reader background. On a dark reader (AMOLED) the old
                    // tinted container + onPrimaryContainer text produced dark text on a dark card.
                    val summaryBehindColor = if (isCleanReaderMode) readerBgColor else MaterialTheme.colorScheme.background
                    val summaryOnDark = summaryBehindColor.luminance() < 0.5f
                    val summaryContainerColor = if (summaryOnDark) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.25f).compositeOver(summaryBehindColor)
                    } else {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                    }
                    val summaryTitleColor = if (summaryOnDark) Color.White else MaterialTheme.colorScheme.onPrimaryContainer
                    val summaryBodyColor = if (summaryOnDark) Color(0xFFF0F0F0) else MaterialTheme.colorScheme.onPrimaryContainer
                    val summaryAccentColor = if (summaryOnDark) lerp(MaterialTheme.colorScheme.primary, Color.White, 0.45f) else MaterialTheme.colorScheme.primary

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = summaryContainerColor
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Filled.AutoAwesome,
                                        contentDescription = null,
                                        tint = summaryAccentColor,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "সংক্ষেপে পড়ুন (মূল বক্তব্য)",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = summaryTitleColor
                                    )
                                }
                                TextButton(onClick = { showAiSummary = false }) {
                                    Text("লুকান", color = summaryAccentColor)
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            displayedBullets.forEach { bullet ->
                                Row(modifier = Modifier.padding(vertical = 3.dp)) {
                                    Text(
                                        text = "• ",
                                        fontWeight = FontWeight.Bold,
                                        color = summaryAccentColor
                                    )
                                    Text(
                                        text = bullet,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = summaryBodyColor
                                    )
                                }
                            }
                        }
                    }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    // Keep WebView mounted so Google News JS redirects complete reliably
                    // and switching between Website View and Clean Reader Mode is instant.
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                webViewRef = this
                                CookieManager.getInstance().setAcceptCookie(true)
                                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.databaseEnabled = true
                                settings.loadsImagesAutomatically = true
                                settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                                settings.loadWithOverviewMode = true
                                settings.useWideViewPort = true
                                settings.setSupportMultipleWindows(false)
                                settings.javaScriptCanOpenWindowsAutomatically = true
                                settings.textZoom = appState.readerZoom
                                settings.userAgentString = if (appState.readerDesktop) {
                                    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                                } else {
                                    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                                }

                                webChromeClient = object : WebChromeClient() {
                                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                        val nowMs = SystemClock.uptimeMillis()
                                        if (shouldUpdateWebProgress(webProgress, newProgress, lastProgressUpdateMs, nowMs)) {
                                            webProgress = newProgress.coerceIn(0, 100)
                                            lastProgressUpdateMs = nowMs
                                        }
                                        val loadedUrl = view?.url ?: ""
                                        if (loadedUrl.isNotEmpty() && !loadedUrl.contains("news.google.com") && newProgress >= 45) {
                                            if (currentWebUrl != loadedUrl) {
                                                currentWebUrl = loadedUrl
                                            }
                                            if (isRedirectingGoogleNews) {
                                                isRedirectingGoogleNews = false
                                            }
                                        }
                                    }
                                }

                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                        val u = request?.url?.toString() ?: return false
                                        if (u.startsWith("http://") || u.startsWith("https://")) {
                                            if (currentWebUrl != u) {
                                                currentWebUrl = u
                                            }
                                            if (!u.contains("news.google.com") && isRedirectingGoogleNews) {
                                                isRedirectingGoogleNews = false
                                            }
                                            return false
                                        }
                                        return try {
                                            val intent = Intent(Intent.ACTION_VIEW, request.url)
                                            ctx.startActivity(intent)
                                            true
                                        } catch (_: Exception) {
                                            true
                                        }
                                    }

                                    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                                        super.doUpdateVisitedHistory(view, url, isReload)
                                        if (!url.isNullOrEmpty()) {
                                            if (currentWebUrl != url) {
                                                currentWebUrl = url
                                            }
                                            if (!url.contains("news.google.com") && isRedirectingGoogleNews) {
                                                isRedirectingGoogleNews = false
                                            }
                                        }
                                    }

                                    override fun onPageCommitVisible(view: WebView?, url: String?) {
                                        super.onPageCommitVisible(view, url)
                                        val committedUrl = (url ?: view?.url ?: "").trim()
                                        if (committedUrl.isNotEmpty() && !committedUrl.contains("news.google.com")) {
                                            if (currentWebUrl != committedUrl) {
                                                currentWebUrl = committedUrl
                                            }
                                            if (isRedirectingGoogleNews) {
                                                isRedirectingGoogleNews = false
                                            }
                                        }
                                    }

                                    override fun onReceivedError(
                                        view: WebView?,
                                        request: WebResourceRequest?,
                                        error: WebResourceError?
                                    ) {
                                        super.onReceivedError(view, request, error)
                                        if (request?.isForMainFrame == true) {
                                            isRedirectingGoogleNews = false
                                            if (cleanArticle == null) {
                                                isExtracting = false
                                            }
                                        }
                                    }

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        super.onPageFinished(view, url)
                                        val resolvedUrl = (url ?: currentWebUrl).trim()
                                        if (resolvedUrl.isNotEmpty() && currentWebUrl != resolvedUrl) {
                                            currentWebUrl = resolvedUrl
                                        }

                                        if (!resolvedUrl.contains("news.google.com")) {
                                            if (isRedirectingGoogleNews) {
                                                isRedirectingGoogleNews = false
                                            }

                                            // Only extract if this URL has not already produced substantive content
                                            if (!extractionCoordinator.hasSubstantiveResultFor(resolvedUrl)) {
                                                extractLiveFromWebView(view)
                                                delayedPageFinishedJob?.cancel()
                                                delayedPageFinishedJob = scope.launch {
                                                    delay(1400L)
                                                    if (!extractionCoordinator.hasSubstantiveResultFor(resolvedUrl)) {
                                                        extractLiveFromWebView(view)
                                                    }
                                                }
                                            }
                                        } else {
                                            // If still on news.google.com, help click any direct external article anchor if present
                                            view?.evaluateJavascript(
                                                "(function() {" +
                                                    "var links = document.querySelectorAll('a[href^=\"http\"]');" +
                                                    "for (var i = 0; i < links.length; i++) {" +
                                                    "  var h = links[i].href;" +
                                                    "  if (h && h.indexOf('google.com') === -1 && h.indexOf('gstatic.com') === -1) {" +
                                                    "    window.location.replace(h); break;" +
                                                    "  }" +
                                                    "}" +
                                                    "})();",
                                                null
                                            )
                                        }

                                        // Inject night mode or safe ad hiding if enabled
                                        if (appState.readerNight) {
                                            view?.evaluateJavascript(
                                                "(function() {" +
                                                    "if (document.documentElement) document.documentElement.style.filter = 'invert(1) hue-rotate(180deg)';" +
                                                    "if (document.body) document.body.style.backgroundColor = '#121212';" +
                                                    "})();",
                                                null
                                            )
                                        }
                                        if (appState.hideAds) {
                                            view?.evaluateJavascript(
                                                "(function() {" +
                                                    "var selectors = ['.adsbygoogle', 'ins.adsbygoogle', '.floating-ad', '#header-ad', '[id^=\"google_ads_iframe\"]', 'iframe[src*=\"doubleclick.net\"]', 'iframe[src*=\"googlesyndication.com\"]'];" +
                                                    "selectors.forEach(function(s) {" +
                                                    "  document.querySelectorAll(s).forEach(function(e) { e.style.display = 'none'; });" +
                                                    "});" +
                                                    "})();",
                                                null
                                            )
                                        }
                                    }
                                }

                                loadUrl(initialUrl)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    // Friendly redirect overlay while Google News resolves the target newspaper page
                    if (!isCleanReaderMode && isRedirectingGoogleNews) {
                        val redirectOverlayInteraction = remember { MutableInteractionSource() }
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.background)
                                .clickable(
                                    interactionSource = redirectOverlayInteraction,
                                    indication = null,
                                    onClick = {}
                                )
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            com.potrikaghor.ui.components.BrandedLoadingIndicator(
                                message = "পত্রিকা প্রস্তুত হচ্ছে...",
                                subtitle = if (initialTitle.isNotBlank()) {
                                    "$initialTitle\n(${paper.bn}-এর মূল পাতায় নিয়ে যাওয়া হচ্ছে...)"
                                } else {
                                    "${paper.bn}-এর মূল পাতায় নিয়ে যাওয়া হচ্ছে..."
                                },
                                logoSize = 74.dp
                            )
                        }
                    }

                    // CLEAN READER MODE OVERLAY
                    if (isCleanReaderMode) {
                        val cleanOverlayInteraction = remember { MutableInteractionSource() }
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(readerBgColor)
                                .clickable(
                                    interactionSource = cleanOverlayInteraction,
                                    indication = null,
                                    onClick = {}
                                )
                        ) {
                            if (isExtracting && (cleanArticle == null || currentWebUrl.contains("news.google.com"))) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    com.potrikaghor.ui.components.BrandedLoadingIndicator(
                                        message = "আপনার খবর সাজানো হচ্ছে...",
                                        subtitle = initialTitle.takeIf { it.isNotBlank() }
                                            ?: "ক্লিন রিডার মোডে সাজানো হচ্ছে...",
                                        logoSize = 74.dp
                                    )
                                }
                            } else if (cleanArticle != null && (cleanArticle!!.paragraphs.isNotEmpty() || cleanArticle!!.title.isNotBlank())) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .verticalScroll(readerScrollState)
                                        .padding(horizontal = 20.dp, vertical = 14.dp)
                                ) {
                                    // Quick control pill: Font size & AI summary button
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Surface(
                                            modifier = Modifier.clickable {
                                                val next = !showAiSummary
                                                showAiSummary = next
                                                if (next) {
                                                    extractLiveFromWebView(webViewRef)
                                                }
                                            },
                                            shape = RoundedCornerShape(12.dp),
                                            color = MaterialTheme.colorScheme.primaryContainer
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    Icons.Filled.AutoAwesome,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp),
                                                    tint = MaterialTheme.colorScheme.primary
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = "সংক্ষেপে পড়ুন",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }

                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            IconButton(
                                                onClick = {
                                                    if (readerFontSize > 14) {
                                                        readerFontSize -= 2
                                                        appRepo.setArticleFontSize(readerFontSize)
                                                    }
                                                }
                                            ) {
                                                Text("A-", fontWeight = FontWeight.Bold, color = readerTextColor)
                                            }
                                            Text(
                                                text = BengaliUtils.bnDigits(readerFontSize),
                                                fontSize = 13.sp,
                                                color = readerTextColor
                                            )
                                            IconButton(
                                                onClick = {
                                                    if (readerFontSize < 28) {
                                                        readerFontSize += 2
                                                        appRepo.setArticleFontSize(readerFontSize)
                                                    }
                                                }
                                            ) {
                                                Text("A+", fontWeight = FontWeight.Bold, color = readerTextColor)
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(14.dp))

                                    // Article Headline
                                    Text(
                                        text = cleanArticle!!.title.takeIf { !it.equals("Google News", ignoreCase = true) }
                                            ?: fallbackHeadline,
                                        style = MaterialTheme.typography.headlineMedium.copy(
                                            fontFamily = FontFamily.Serif,
                                            fontWeight = FontWeight.Black,
                                            fontSize = (readerFontSize * 1.35f).sp,
                                            lineHeight = (readerFontSize * 1.8f).sp
                                        ),
                                        color = readerTextColor
                                    )

                                    Spacer(modifier = Modifier.height(8.dp))

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = paper.bn,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = " • ${BengaliUtils.bnToday()}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    HorizontalDivider(modifier = Modifier.padding(vertical = 14.dp))

                                    // Lead Image (if present and not a Google News icon)
                                    if (!cleanArticle!!.leadImageUrl.isNullOrEmpty()) {
                                        AsyncImage(
                                            model = cleanArticle!!.leadImageUrl,
                                            contentDescription = cleanArticle!!.title,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(220.dp)
                                                .clip(RoundedCornerShape(14.dp)),
                                            contentScale = ContentScale.Crop
                                        )
                                        Spacer(modifier = Modifier.height(16.dp))
                                    }

                                    // Article Paragraphs
                                    cleanArticle!!.paragraphs.forEach { paragraph ->
                                        Text(
                                            text = paragraph,
                                            style = MaterialTheme.typography.bodyLarge.copy(
                                                fontSize = readerFontSize.sp,
                                                lineHeight = (readerFontSize * readerLineHeight).sp
                                            ),
                                            color = readerTextColor
                                        )
                                        Spacer(modifier = Modifier.height(14.dp))
                                    }

                                    Spacer(modifier = Modifier.height(30.dp))
                                }
                            } else {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(24.dp),
                                    verticalArrangement = Arrangement.Center,
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = "ক্লিন রিডার মোডে লেখাটি সাজানো যায়নি",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = readerTextColor
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "এই পাতাটির পূর্ণাঙ্গ লেখা সরাসরি মূল ওয়েবসাইটে পড়তে পারেন।",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = readerTextColor.copy(alpha = 0.78f)
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Button(
                                        onClick = {
                                            isCleanReaderMode = false
                                            appRepo.setPreferCleanReader(false)
                                        }
                                    ) {
                                        Text("মূল ওয়েবসাইটে পড়ুন")
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                    TextButton(
                                        onClick = {
                                            isExtracting = true
                                            extractLiveFromWebView(webViewRef, forceRefresh = true)
                                        }
                                    ) {
                                        Text("আবার চেষ্টা করুন")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showShareDialog) {
        ShareCardDialog(
            paper = paper,
            title = cleanArticle?.title?.takeIf { !it.equals("Google News", ignoreCase = true) } ?: fallbackHeadline,
            url = currentWebUrl,
            onDismiss = { showShareDialog = false }
        )
    }
}

/**
 * Selects the most natural available Bengali (bn-BD / bn-IN) or English voice on the TTS engine.
 * Executed off the Main Thread to avoid blocking UI frames on Binder IPC calls.
 */
private fun configureNaturalTtsVoice(engine: TextToSpeech, hasBengali: Boolean): Boolean {
    if (!hasBengali) {
        val res = engine.setLanguage(Locale.US)
        if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
            engine.language = Locale.ENGLISH
        }
        return true
    }

    val localesToTry = listOf(
        Locale("bn", "BD"),
        Locale("bn", "IN"),
        Locale("bn")
    )

    var supported = false
    for (loc in localesToTry) {
        val avail = try {
            engine.isLanguageAvailable(loc)
        } catch (_: Exception) {
            TextToSpeech.LANG_NOT_SUPPORTED
        }
        if (avail >= TextToSpeech.LANG_AVAILABLE) {
            val res = try {
                engine.setLanguage(loc)
            } catch (_: Exception) {
                TextToSpeech.LANG_NOT_SUPPORTED
            }
            if (res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED) {
                supported = true
                break
            }
        }
    }

    if (!supported) {
        val resBd = try {
            engine.setLanguage(Locale("bn", "BD"))
        } catch (_: Exception) {
            TextToSpeech.LANG_NOT_SUPPORTED
        }
        if (resBd != TextToSpeech.LANG_MISSING_DATA && resBd != TextToSpeech.LANG_NOT_SUPPORTED) {
            supported = true
        } else {
            val resIn = try {
                engine.setLanguage(Locale("bn", "IN"))
            } catch (_: Exception) {
                TextToSpeech.LANG_NOT_SUPPORTED
            }
            if (resIn != TextToSpeech.LANG_MISSING_DATA && resIn != TextToSpeech.LANG_NOT_SUPPORTED) {
                supported = true
            }
        }
    }

    // Pick the highest-quality local/neural Bengali voice if the engine exposes voices
    try {
        val availableVoices = engine.voices
        if (!availableVoices.isNullOrEmpty()) {
            val bestBnVoice = availableVoices
                .filter { v ->
                    v.locale != null &&
                        v.locale.language.equals("bn", ignoreCase = true) &&
                        (v.features == null || !v.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED))
                }
                .maxWithOrNull(
                    compareBy<android.speech.tts.Voice> { v ->
                        if (v.locale.country.equals("BD", ignoreCase = true)) 1 else 0
                    }.thenBy { v ->
                        v.quality
                    }.thenBy { v ->
                        if (!v.isNetworkConnectionRequired) 1 else 0
                    }
                )
            if (bestBnVoice != null) {
                engine.voice = bestBnVoice
                supported = true
            }
        }
    } catch (_: Exception) {
        // Fallback to locale selection already applied above
    }

    return supported
}

/**
 * Splits Bengali or English paragraph text into natural sentence and clause chunks
 * without ever cutting inside a word or phrase.
 */
private fun splitTextIntoTtsChunks(text: String, maxChunkLen: Int = 220): List<String> {
    if (text.isBlank()) return emptyList()

    val paragraphs = text
        .split(Regex("\\n{2,}"))
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    val chunks = mutableListOf<String>()

    for (paragraph in paragraphs) {
        val sentences = paragraph
            .split(
                Regex(
                    "(?<=[।!?])\\s+|" +
                        "(?<!(?:^|\\s)(?:ড|ডা|মো|মি|প্রা|ইঞ্জি|অ্যাড|লে|জে|ব্রি|লি|কি\\.মি|খ্রি|Dr|Mr|Mrs|Ms|Prof|St|Jr|Sr|vs|etc))(?<=\\.)\\s+(?=[A-Z\u0980-\u09FF])|" +
                        "\\n+"
                )
            )
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        for (sentence in sentences) {
            if (sentence.length <= maxChunkLen) {
                chunks.add(sentence)
            } else {
                chunks.addAll(splitLongSentenceAtNaturalBoundaries(sentence, maxChunkLen))
            }
        }
    }

    return chunks
}

/**
 * Breaks a long sentence (> maxChunkLen) at natural clause boundaries (commas, semicolons,
 * conjunctions) first, and falls back to the last word boundary (space) before maxChunkLen.
 * Never cuts in the middle of a word.
 */
private fun splitLongSentenceAtNaturalBoundaries(sentence: String, maxChunkLen: Int): List<String> {
    val result = mutableListOf<String>()
    val clauses = sentence
        .split(Regex("(?<![0-9০-৯])(?<=[,;:])\\s+|\\s+(?=(?:এবং|কিন্তু|তবে|অথবা|কারণ|যাতে)\\s)"))
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    var current = StringBuilder()

    fun flushCurrent() {
        if (current.isNotEmpty()) {
            result.add(current.toString().trim())
            current = StringBuilder()
        }
    }

    for (clause in clauses) {
        if (clause.length > maxChunkLen) {
            flushCurrent()
            val words = clause.split(Regex("\\s+")).filter { it.isNotEmpty() }
            var wordBuf = StringBuilder()
            for (word in words) {
                if (wordBuf.isEmpty()) {
                    wordBuf.append(word)
                } else if (wordBuf.length + 1 + word.length <= maxChunkLen) {
                    wordBuf.append(" ").append(word)
                } else {
                    result.add(wordBuf.toString().trim())
                    wordBuf = StringBuilder(word)
                }
            }
            if (wordBuf.isNotEmpty()) {
                result.add(wordBuf.toString().trim())
            }
        } else if (current.isEmpty()) {
            current.append(clause)
        } else if (current.length + 1 + clause.length <= maxChunkLen) {
            current.append(" ").append(clause)
        } else {
            flushCurrent()
            current.append(clause)
        }
    }
    flushCurrent()
    return result
}
