package com.potrikaghor

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Newspaper
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Newspaper
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.potrikaghor.data.AppStateRepository
import com.potrikaghor.data.BreakingNewsNotifier
import com.potrikaghor.data.NewsRepository
import com.potrikaghor.model.Paper
import com.potrikaghor.ui.components.NavCapsule
import com.potrikaghor.ui.components.NavItemSpec
import com.potrikaghor.ui.dialogs.AddPaperDialog
import com.potrikaghor.ui.dialogs.FixUrlDialog
import com.potrikaghor.ui.screens.BookmarksScreen
import com.potrikaghor.ui.screens.CoverageScreen
import com.potrikaghor.ui.screens.MyPapersScreen
import com.potrikaghor.ui.screens.PersonalHomeScreen
import com.potrikaghor.ui.screens.ReaderScreen
import com.potrikaghor.ui.screens.SearchScreen
import com.potrikaghor.ui.screens.SettingsScreen
import com.potrikaghor.ui.theme.PotrikaghorTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private lateinit var appRepo: AppStateRepository
    private val newsRepo = NewsRepository()
    private val pendingNotificationScreen = MutableStateFlow<Screen.Reader?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        appRepo = AppStateRepository(applicationContext)
        BreakingNewsNotifier.ensureChannel(applicationContext)
        handleNotificationIntent(intent)

        setContent {
            val appState by appRepo.state.collectAsState()
            val notifTarget by pendingNotificationScreen.collectAsState()

            PotrikaghorTheme(
                themeMode = appState.themeMode,
                themeStyle = appState.themeStyle,
                accentIndex = appState.accent,
                textScale = appState.textScale
            ) {
                PotrikaghorApp(
                    appRepo = appRepo,
                    newsRepo = newsRepo,
                    notificationTarget = notifTarget,
                    onConsumeNotificationTarget = { pendingNotificationScreen.value = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationIntent(intent)
    }

    private fun handleNotificationIntent(intent: Intent?) {
        val url = intent?.getStringExtra(BreakingNewsNotifier.EXTRA_NOTIFICATION_URL)
        if (!url.isNullOrEmpty()) {
            val source = intent.getStringExtra(BreakingNewsNotifier.EXTRA_NOTIFICATION_SOURCE) ?: "সংবাদ"
            val title = intent.getStringExtra(BreakingNewsNotifier.EXTRA_NOTIFICATION_TITLE) ?: ""
            val paper = appRepo.allPapers.firstOrNull { it.bn == source || it.en == source }
                ?: Paper(source, "", url, "online")
            pendingNotificationScreen.value = Screen.Reader(paper, url, title)
            intent.removeExtra(BreakingNewsNotifier.EXTRA_NOTIFICATION_URL)
        }
    }
}

sealed class Screen {
    object Main : Screen()
    data class Reader(val paper: Paper, val url: String, val articleTitle: String = "") : Screen()
    object Search : Screen()
}

@Composable
fun PotrikaghorApp(
    appRepo: AppStateRepository,
    newsRepo: NewsRepository,
    notificationTarget: Screen.Reader? = null,
    onConsumeNotificationTarget: () -> Unit = {}
) {
    val context = LocalContext.current
    val appState by appRepo.state.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Main) }

    // Dialog states
    var showAddPaperDialog by remember { mutableStateOf(false) }
    var paperToFixUrl by remember { mutableStateOf<Paper?>(null) }

    // Open Reader when a breaking news notification is tapped
    LaunchedEffect(notificationTarget) {
        if (notificationTarget != null) {
            currentScreen = notificationTarget
            onConsumeNotificationTarget()
        }
    }

    // Periodic background check for breaking news (3+ sources)
    LaunchedEffect(appState.notificationTopics) {
        if (appState.notificationTopics.isNotEmpty()) {
            // Initial check shortly after launch, then periodic every 15 minutes
            delay(4_000L)
            while (true) {
                BreakingNewsNotifier.checkAndNotifyBreakingNews(
                    context = context,
                    appRepo = appRepo,
                    newsRepo = newsRepo,
                    forceManualTest = false
                )
                delay(15 * 60 * 1000L)
            }
        }
    }

    BackHandler(enabled = currentScreen !is Screen.Main || selectedTab != 0) {
        if (currentScreen !is Screen.Main) {
            currentScreen = Screen.Main
        } else if (selectedTab != 0) {
            selectedTab = 0
        }
    }

    val navItems = listOf(
        NavItemSpec(Icons.Outlined.Bolt, Icons.Filled.Bolt, "আমার নিউজ"),
        NavItemSpec(Icons.Outlined.Newspaper, Icons.Filled.Newspaper, "আমার পত্রিকা"),
        NavItemSpec(Icons.Outlined.Hub, Icons.Filled.Hub, "কভারেজ"),
        NavItemSpec(Icons.Outlined.BookmarkBorder, Icons.Filled.Bookmark, "বুকমার্ক", badge = appState.bookmarks.size),
        NavItemSpec(Icons.Outlined.Settings, Icons.Filled.Settings, "সেটিংস")
    )

    when (val screen = currentScreen) {
        is Screen.Reader -> {
            ReaderScreen(
                paper = screen.paper,
                initialUrl = screen.url,
                initialTitle = screen.articleTitle,
                appRepo = appRepo,
                onBack = { currentScreen = Screen.Main }
            )
        }
        is Screen.Search -> {
            SearchScreen(
                appRepo = appRepo,
                newsRepo = newsRepo,
                onBack = { currentScreen = Screen.Main },
                onOpenReader = { paper, url, title ->
                    currentScreen = Screen.Reader(paper, url, title)
                }
            )
        }
        is Screen.Main -> {
            Scaffold(
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                bottomBar = {
                    NavCapsule(
                        selectedIndex = selectedTab,
                        onSelectIndex = { selectedTab = it },
                        items = navItems
                    )
                }
            ) { paddingValues ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                ) {
                    when (selectedTab) {
                        0 -> PersonalHomeScreen(
                            appRepo = appRepo,
                            newsRepo = newsRepo,
                            onOpenReader = { paper, url, title -> currentScreen = Screen.Reader(paper, url, title) },
                            onOpenSearch = { currentScreen = Screen.Search },
                            onOpenSettings = { selectedTab = 4 }
                        )
                        1 -> MyPapersScreen(
                            appRepo = appRepo,
                            onOpenReader = { paper, url -> currentScreen = Screen.Reader(paper, url) },
                            onAddPaper = { showAddPaperDialog = true },
                            onFixUrl = { paperToFixUrl = it }
                        )
                        2 -> CoverageScreen(
                            appRepo = appRepo,
                            newsRepo = newsRepo,
                            onOpenReader = { paper, url, title -> currentScreen = Screen.Reader(paper, url, title) }
                        )
                        3 -> BookmarksScreen(
                            appRepo = appRepo,
                            onOpenReader = { paper, url, title -> currentScreen = Screen.Reader(paper, url, title) }
                        )
                        4 -> SettingsScreen(
                            appRepo = appRepo,
                            newsRepo = newsRepo
                        )
                    }
                }
            }
        }
    }

    if (showAddPaperDialog) {
        AddPaperDialog(
            onDismiss = { showAddPaperDialog = false },
            onSave = { appRepo.addCustomPaper(it) }
        )
    }

    if (paperToFixUrl != null) {
        FixUrlDialog(
            paper = paperToFixUrl!!,
            onDismiss = { paperToFixUrl = null },
            onSaveFix = { newUrl ->
                paperToFixUrl?.let { p ->
                    appRepo.setUrlFix(p.url, newUrl)
                }
            }
        )
    }
}
