package com.potrikaghor.ui.screens

import android.content.Intent
import android.speech.tts.TextToSpeech
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.potrikaghor.data.AppStateRepository
import com.potrikaghor.data.ArticleExtractor
import com.potrikaghor.data.CleanArticle
import com.potrikaghor.model.Bookmark
import com.potrikaghor.model.Paper
import com.potrikaghor.ui.components.PaperAvatar
import com.potrikaghor.ui.components.ShareCardDialog
import com.potrikaghor.ui.theme.AmoledBackground
import com.potrikaghor.ui.theme.FlagRed
import com.potrikaghor.ui.theme.SepiaBackground
import com.potrikaghor.ui.theme.SepiaText
import com.potrikaghor.ui.util.BengaliUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI
import java.util.Locale

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

    val fallbackHeadline = remember(initialTitle, paper.bn) {
        initialTitle.trim().ifEmpty { paper.bn }
    }

    // Open in Website View by default so news opens directly on the publisher's site,
    // with Clean Reader Mode accessible via the top toolbar button.
    var isCleanReaderMode by remember { mutableStateOf(false) }
    var cleanArticle by remember { mutableStateOf<CleanArticle?>(null) }
    var isExtracting by remember { mutableStateOf(true) }

    var webProgress by remember { mutableIntStateOf(0) }
    var currentWebUrl by remember { mutableStateOf(initialUrl) }
    var isRedirectingGoogleNews by remember { mutableStateOf(initialUrl.contains("news.google.com")) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    var showShareDialog by remember { mutableStateOf(false) }
    var showAiSummary by remember { mutableStateOf(false) }
    var showTtsControls by remember { mutableStateOf(false) }

    // Reader styling options
    var readerFontSize by remember { mutableIntStateOf(appState.articleFontSize) }
    var readerLineHeight by remember { mutableFloatStateOf(appState.articleLineHeight) }
    var readerThemeStyle by remember { mutableStateOf(appState.themeStyle) } // amoled, normal, sepia

    // TTS state
    var tts: TextToSpeech? by remember { mutableStateOf(null) }
    var isTtsPlaying by remember { mutableStateOf(false) }
    var ttsSpeed by remember { mutableFloatStateOf(1.0f) }

    val isBookmarked = appState.bookmarks.any { it.url == currentWebUrl || it.url == initialUrl }

    // Safety timeout for Google News redirection overlay
    LaunchedEffect(isRedirectingGoogleNews) {
        if (isRedirectingGoogleNews) {
            delay(6500L)
            isRedirectingGoogleNews = false
        }
    }

    // Track recently read paper
    LaunchedEffect(paper.url) {
        if (!paper.url.contains("news.google.com")) {
            appRepo.addRecent(paper.url)
        }
    }

    // Initialize TTS
    DisposableEffect(Unit) {
        var ttsInstance: TextToSpeech? = null
        ttsInstance = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val bnLocale = Locale("bn", "BD")
                val res = ttsInstance?.setLanguage(bnLocale)
                if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                    ttsInstance?.language = Locale.ENGLISH
                }
            }
        }
        tts = ttsInstance
        onDispose {
            ttsInstance?.stop()
            ttsInstance?.shutdown()
        }
    }

    // Extract clean article via HTTP when URL changes (for direct non-Google News URLs or offline bookmarks)
    LaunchedEffect(currentWebUrl) {
        val savedOffline = appState.bookmarks.firstOrNull {
            it.url == currentWebUrl || it.url == initialUrl
        }?.offlineContent

        if (!savedOffline.isNullOrEmpty()) {
            cleanArticle = CleanArticle(
                title = fallbackHeadline,
                source = paper.bn,
                url = currentWebUrl,
                leadImageUrl = null,
                paragraphs = savedOffline.split("\n\n").filter { it.isNotBlank() },
                plainText = savedOffline
            )
            isExtracting = false
        } else if (!currentWebUrl.contains("news.google.com")) {
            isExtracting = cleanArticle == null
            val extracted = extractor.extractArticle(currentWebUrl, fallbackHeadline, paper.bn)
            // Only overwrite if we don't already have a richer DOM-extracted article for this URL
            if (cleanArticle == null || cleanArticle!!.paragraphs.size <= 2) {
                cleanArticle = extracted
            }
            isExtracting = false
            if (isBookmarked) {
                appRepo.saveBookmarkOfflineContent(currentWebUrl, (cleanArticle ?: extracted).plainText)
            }
        } else {
            // Still on news.google.com waiting for WebView JS redirect
            cleanArticle = extractor.extractArticle(currentWebUrl, fallbackHeadline, paper.bn)
            isExtracting = true
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PaperAvatar(paper = paper, size = 32.dp)
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = paper.bn,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = displayHost,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        tts?.stop()
                        onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "ফিরে যান")
                    }
                },
                actions = {
                    // Toggle Clean Reader vs Website View
                    IconButton(onClick = {
                        val switchingToClean = !isCleanReaderMode
                        isCleanReaderMode = switchingToClean
                        // Refresh clean article from live WebView DOM when switching to Clean Reader Mode
                        if (switchingToClean) {
                            webViewRef?.evaluateJavascript("(function(){return document.documentElement.outerHTML;})()") { rawJs ->
                                val decodedHtml = extractor.decodeJsEvaluatedHtml(rawJs)
                                if (decodedHtml.length > 200 && !currentWebUrl.contains("news.google.com")) {
                                    scope.launch(Dispatchers.Default) {
                                        val parsed = extractor.parseHtml(decodedHtml, currentWebUrl, fallbackHeadline, paper.bn)
                                        withContext(Dispatchers.Main) {
                                            cleanArticle = parsed
                                            isExtracting = false
                                        }
                                    }
                                }
                            }
                        }
                    }) {
                        Icon(
                            imageVector = if (isCleanReaderMode) Icons.Outlined.Language else Icons.Outlined.MenuBook,
                            contentDescription = if (isCleanReaderMode) "ওয়েবসাইট ভিউ" else "ক্লিন রিডার মোড",
                            tint = if (isCleanReaderMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }

                    // TTS button
                    IconButton(onClick = { showTtsControls = !showTtsControls }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = "টেক্সট টু স্পিচ",
                            tint = if (isTtsPlaying) FlagRed else MaterialTheme.colorScheme.onSurface
                        )
                    }

                    // Bookmark toggle
                    IconButton(
                        onClick = {
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
                        }
                    ) {
                        Icon(
                            imageVector = if (isBookmarked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                            contentDescription = "বুকমার্ক",
                            tint = if (isBookmarked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }

                    // Share Card
                    IconButton(onClick = { showShareDialog = true }) {
                        Icon(Icons.Default.Share, contentDescription = "শেয়ার কার্ড")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
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
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        tonalElevation = 4.dp
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = {
                                        if (isTtsPlaying) {
                                            tts?.stop()
                                            isTtsPlaying = false
                                        } else {
                                            val text = cleanArticle?.plainText?.ifEmpty { fallbackHeadline } ?: fallbackHeadline
                                            tts?.setSpeechRate(ttsSpeed)
                                            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "potrikaghor_tts")
                                            isTtsPlaying = true
                                        }
                                    }
                                ) {
                                    Icon(
                                        imageVector = if (isTtsPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                        contentDescription = if (isTtsPlaying) "থামান" else "শুনুন",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        tts?.stop()
                                        isTtsPlaying = false
                                    }
                                ) {
                                    Icon(Icons.Filled.Stop, contentDescription = "বন্ধ করুন")
                                }
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
                                                tts?.setSpeechRate(spd)
                                            },
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (ttsSpeed == spd) MaterialTheme.colorScheme.primary else Color.Transparent
                                    ) {
                                        Text(
                                            text = "${spd}x",
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            fontSize = 11.sp,
                                            color = if (ttsSpeed == spd) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // AI News Summary Drawer/Card
                AnimatedVisibility(visible = showAiSummary) {
                    val summaryBullets = remember(cleanArticle, fallbackHeadline) {
                        val text = cleanArticle?.plainText?.takeIf { it.isNotBlank() } ?: fallbackHeadline
                        generateLocalSummary(text)
                    }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
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
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "সংক্ষেপে পড়ুন (মূল বক্তব্য)",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                                TextButton(onClick = { showAiSummary = false }) {
                                    Text("লুকান", color = MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            summaryBullets.forEach { bullet ->
                                Row(modifier = Modifier.padding(vertical = 3.dp)) {
                                    Text("• ", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                    Text(
                                        text = bullet,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
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
                                        webProgress = newProgress
                                        val loadedUrl = view?.url ?: ""
                                        if (loadedUrl.isNotEmpty() && !loadedUrl.contains("news.google.com") && newProgress >= 45) {
                                            currentWebUrl = loadedUrl
                                            isRedirectingGoogleNews = false
                                        }
                                    }
                                }

                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                        val u = request?.url?.toString() ?: return false
                                        if (u.startsWith("http://") || u.startsWith("https://")) {
                                            currentWebUrl = u
                                            if (!u.contains("news.google.com")) {
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
                                            currentWebUrl = url
                                            if (!url.contains("news.google.com")) {
                                                isRedirectingGoogleNews = false
                                            }
                                        }
                                    }

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        super.onPageFinished(view, url)
                                        val resolvedUrl = url ?: currentWebUrl
                                        currentWebUrl = resolvedUrl

                                        if (!resolvedUrl.contains("news.google.com")) {
                                            isRedirectingGoogleNews = false

                                            // Extract live rendered HTML from the publisher's page for Clean Reader Mode, AI Summary & TTS
                                            view?.evaluateJavascript("(function(){return document.documentElement.outerHTML;})()") { rawJs ->
                                                val decodedHtml = extractor.decodeJsEvaluatedHtml(rawJs)
                                                if (decodedHtml.length > 200) {
                                                    scope.launch(Dispatchers.Default) {
                                                        val parsed = extractor.parseHtml(
                                                            html = decodedHtml,
                                                            url = resolvedUrl,
                                                            fallbackTitle = fallbackHeadline,
                                                            source = paper.bn
                                                        )
                                                        withContext(Dispatchers.Main) {
                                                            cleanArticle = parsed
                                                            isExtracting = false
                                                        }
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

                                        // Inject night mode or ad hiding if enabled
                                        if (appState.readerNight) {
                                            view?.evaluateJavascript(
                                                "document.documentElement.style.filter = 'invert(1) hue-rotate(180deg)';" +
                                                    "document.body.style.backgroundColor = '#121212';",
                                                null
                                            )
                                        }
                                        if (appState.hideAds) {
                                            view?.evaluateJavascript(
                                                "const selectors = ['[class*=\"ad-\"]', '[id*=\"ad-\"]', '.adsbygoogle', '.floating-ad', '#header-ad'];" +
                                                    "selectors.forEach(s => document.querySelectorAll(s).forEach(e => e.style.display = 'none'));",
                                                null
                                            )
                                        }
                                    }
                                }

                                loadUrl(initialUrl)
                            }
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .alpha(if (isCleanReaderMode || isRedirectingGoogleNews) 0f else 1f)
                    )

                    // Friendly redirect overlay while Google News resolves the target newspaper page
                    if (!isCleanReaderMode && isRedirectingGoogleNews) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.background)
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                PaperAvatar(paper = paper, size = 52.dp)
                                Spacer(modifier = Modifier.height(16.dp))
                                if (initialTitle.isNotBlank()) {
                                    Text(
                                        text = initialTitle,
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontFamily = FontFamily.Serif,
                                            fontWeight = FontWeight.Bold
                                        ),
                                        textAlign = TextAlign.Center,
                                        color = MaterialTheme.colorScheme.onBackground,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                }
                                CircularProgressIndicator(
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(32.dp),
                                    strokeWidth = 3.dp
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "${paper.bn}-এর মূল পাতায় নিয়ে যাওয়া হচ্ছে...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }

                    // CLEAN READER MODE OVERLAY
                    if (isCleanReaderMode) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(readerBgColor)
                        ) {
                            if (isExtracting && (cleanArticle == null || currentWebUrl.contains("news.google.com"))) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.padding(24.dp)
                                    ) {
                                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                                        Spacer(modifier = Modifier.height(12.dp))
                                        Text(
                                            text = "ক্লিন রিডার মোডে সাজানো হচ্ছে...",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = readerTextColor
                                        )
                                        if (initialTitle.isNotBlank()) {
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Text(
                                                text = initialTitle,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = readerTextColor.copy(alpha = 0.75f),
                                                textAlign = TextAlign.Center,
                                                maxLines = 2
                                            )
                                        }
                                    }
                                }
                            } else if (cleanArticle != null) {
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
                                            modifier = Modifier.clickable { showAiSummary = !showAiSummary },
                                            shape = RoundedCornerShape(16.dp),
                                            color = MaterialTheme.colorScheme.primaryContainer
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                            ) {
                                                Icon(
                                                    Icons.Filled.AutoAwesome,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp),
                                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = "সংক্ষেপে পড়ুন",
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.onPrimaryContainer
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

// Factual local extractive summary without hallucination
fun generateLocalSummary(text: String): List<String> {
    if (text.length < 100) {
        return listOf(text.take(200))
    }
    val sentences = text.split(Regex("[।!?\n]+")).map { it.trim() }.filter { it.length > 25 }
    return sentences.take(4).ifEmpty { listOf(text.take(200)) }
}
