package com.potrikaghor.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.ui.platform.testTag
import com.potrikaghor.data.AppStateRepository
import com.potrikaghor.data.NewsRepository
import com.potrikaghor.model.BangladeshLocalRegions
import com.potrikaghor.model.Coverage
import com.potrikaghor.model.HomeFeedMode
import com.potrikaghor.model.NewsItem
import com.potrikaghor.model.Paper
import com.potrikaghor.ui.ads.AdMobConfig
import com.potrikaghor.ui.ads.PotrikaGhorNativeAd
import com.potrikaghor.ui.ads.rememberNativeAdSlotManager
import com.potrikaghor.ui.components.ArticleCoverImage
import com.potrikaghor.ui.components.EmptyState
import com.potrikaghor.ui.components.MotionTokens
import com.potrikaghor.ui.components.PaperAvatar
import com.potrikaghor.ui.components.SkeletonCard
import com.potrikaghor.ui.components.pressScaleEffect
import com.potrikaghor.ui.components.rememberReducedMotion
import com.potrikaghor.ui.components.subtleClickable
import com.potrikaghor.ui.theme.FlagRed
import com.potrikaghor.ui.util.BengaliUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal const val HOME_SCREEN_LOAD_TIMEOUT_MS = 16_000L
internal const val HOME_LOAD_ERROR_MESSAGE = "সংবাদ লোড করা যায়নি। ইন্টারনেট সংযোগ পরীক্ষা করুন।"

internal enum class HomeBodyState {
    LOADING,
    ERROR,
    EMPTY,
    CONTENT
}

internal data class HomeLoadStateSnapshot(
    val newsItems: List<NewsItem>,
    val coverageList: List<Coverage>,
    val isLoading: Boolean,
    val isManualRefreshing: Boolean,
    val errorMsg: String?,
    val loadedCategoryKey: String,
    val requestVersion: Int
)

internal fun resolveHomeBodyState(
    isLoading: Boolean,
    newsItems: List<NewsItem>,
    loadedCategoryKey: String?,
    effectiveCatKey: String,
    errorMsg: String?
): HomeBodyState = when {
    (isLoading && newsItems.isEmpty()) || loadedCategoryKey != effectiveCatKey -> HomeBodyState.LOADING
    errorMsg != null && newsItems.isEmpty() -> HomeBodyState.ERROR
    newsItems.isEmpty() -> HomeBodyState.EMPTY
    else -> HomeBodyState.CONTENT
}

internal fun applyHomeLoadResult(
    currentSnapshot: HomeLoadStateSnapshot,
    completedRequestVersion: Int,
    targetCategoryKey: String,
    fetchedItems: List<NewsItem>?,
    builtCoverage: List<Coverage>?,
    failure: Throwable?
): HomeLoadStateSnapshot {
    // Prevent an older cancelled or superseded request from overwriting newer state
    if (completedRequestVersion != currentSnapshot.requestVersion ||
        currentSnapshot.loadedCategoryKey != targetCategoryKey
    ) {
        return currentSnapshot
    }

    if (failure is CancellationException) {
        throw failure
    }

    return if (failure == null && !fetchedItems.isNullOrEmpty()) {
        currentSnapshot.copy(
            newsItems = fetchedItems,
            coverageList = builtCoverage ?: fetchedItems.map { Coverage(listOf(it)) },
            isLoading = false,
            isManualRefreshing = false,
            errorMsg = null,
            loadedCategoryKey = targetCategoryKey
        )
    } else {
        // Preserve cached news if already present; only show error when no items are available
        val hasCachedNews = currentSnapshot.newsItems.isNotEmpty()
        currentSnapshot.copy(
            isLoading = false,
            isManualRefreshing = false,
            errorMsg = if (hasCachedNews) null else HOME_LOAD_ERROR_MESSAGE,
            loadedCategoryKey = targetCategoryKey
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonalHomeScreen(
    appRepo: AppStateRepository,
    newsRepo: NewsRepository,
    onOpenReader: (Paper, String, String) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenCoverage: () -> Unit = {}
) {
    val appState by appRepo.state.collectAsState()
    val scope = rememberCoroutineScope()
    val reducedMotion = rememberReducedMotion()
    val nativeAdSlotManager = rememberNativeAdSlotManager()

    var selectedCategoryIndex by remember { mutableStateOf(0) }
    var selectedLocalRegionId by remember { mutableStateOf(BangladeshLocalRegions.first().id) }
    var loadedCategoryKey by remember { mutableStateOf<String?>(null) }
    var newsItems by remember { mutableStateOf<List<NewsItem>>(emptyList()) }
    var coverageList by remember { mutableStateOf<List<Coverage>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var isManualRefreshing by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var loadJob by remember { mutableStateOf<Job?>(null) }
    var requestVersion by remember { mutableStateOf(0) }

    val currentFeedMode = appState.homeFeedMode
    val favoriteUrls = remember(appState.favs) { appState.favs.toSet() }

    val activeCategories = remember(appState.homeCategories) {
        appState.homeCategories.filter { it.enabled }.sortedBy { it.order }
    }

    val currentCat = activeCategories.getOrNull(selectedCategoryIndex) ?: activeCategories.firstOrNull()
    val currentLocalRegion = remember(selectedLocalRegionId) {
        BangladeshLocalRegions.firstOrNull { it.id == selectedLocalRegionId } ?: BangladeshLocalRegions.first()
    }
    val effectiveCatKey = remember(currentCat?.id, selectedLocalRegionId) {
        if (currentCat?.id == "local") "local_$selectedLocalRegionId" else (currentCat?.id ?: "top")
    }

    fun buildCoverageSyncForMode(
        items: List<NewsItem>,
        mode: HomeFeedMode,
        favUrls: Set<String>
    ): List<Coverage> {
        if (items.isEmpty()) return emptyList()
        return try {
            when (mode) {
                HomeFeedMode.TOP_STORIES -> newsRepo.buildTopStories(all = items, favoriteUrls = favUrls)
                HomeFeedMode.LATEST_NEWS -> newsRepo.buildLatestNews(all = items)
            }
        } catch (_: Exception) {
            items.map { Coverage(listOf(it)) }
        }
    }

    suspend fun buildCoverageAsyncForMode(
        items: List<NewsItem>,
        mode: HomeFeedMode,
        favUrls: Set<String>
    ): List<Coverage> {
        if (items.isEmpty()) return emptyList()
        return try {
            when (mode) {
                HomeFeedMode.TOP_STORIES -> newsRepo.buildTopStoriesAsync(all = items, favoriteUrls = favUrls)
                HomeFeedMode.LATEST_NEWS -> newsRepo.buildLatestNewsAsync(all = items)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            items.map { Coverage(listOf(it)) }
        }
    }

    fun loadData(force: Boolean = false) {
        val targetCat = currentCat
        val targetCatId = targetCat?.id
        val cacheKey = if (targetCatId == "local") "local_$selectedLocalRegionId" else (targetCatId ?: "top")
        val isCategorySwitch = loadedCategoryKey != cacheKey

        if (!force && !isCategorySwitch && newsItems.isNotEmpty() && errorMsg == null) {
            return
        }

        // Prevent duplicate manual refresh while already loading the same category
        if (force && !isCategorySwitch && isLoading && loadJob?.isActive == true) {
            return
        }

        loadJob?.cancel()
        val currentRequestVersion = ++requestVersion
        loadedCategoryKey = cacheKey

        if (isCategorySwitch || newsItems.isEmpty()) {
            isManualRefreshing = false
            val cachedItems = newsRepo.getMemoryCachedCategoryFeed(cacheKey).ifEmpty {
                appRepo.getCachedFeed(cacheKey)
            }
            if (cachedItems.isNotEmpty()) {
                newsItems = cachedItems
                coverageList = buildCoverageSyncForMode(cachedItems, currentFeedMode, favoriteUrls)
                errorMsg = null
                isLoading = true
            } else {
                newsItems = emptyList()
                coverageList = emptyList()
                errorMsg = null
                isLoading = true
            }
        } else {
            errorMsg = null
            isLoading = true
            isManualRefreshing = force && newsItems.isNotEmpty()
        }

        loadJob = scope.launch {
            try {
                val q = if (targetCatId == "local") {
                    currentLocalRegion.query
                } else {
                    targetCat?.query ?: ""
                }
                val fetchedResult = withTimeoutOrNull(HOME_SCREEN_LOAD_TIMEOUT_MS) {
                    val items = newsRepo.fetchNews(
                        query = q,
                        intl = (targetCatId == "intl"),
                        forceRefresh = force
                    )
                    val activeMode = appRepo.state.value.homeFeedMode
                    val activeFavs = appRepo.state.value.favs.toSet()
                    val builtCoverage = buildCoverageAsyncForMode(items, activeMode, activeFavs)
                    items to builtCoverage
                }

                val updated = applyHomeLoadResult(
                    currentSnapshot = HomeLoadStateSnapshot(
                        newsItems = newsItems,
                        coverageList = coverageList,
                        isLoading = isLoading,
                        isManualRefreshing = isManualRefreshing,
                        errorMsg = errorMsg,
                        loadedCategoryKey = loadedCategoryKey ?: cacheKey,
                        requestVersion = requestVersion
                    ),
                    completedRequestVersion = currentRequestVersion,
                    targetCategoryKey = cacheKey,
                    fetchedItems = fetchedResult?.first,
                    builtCoverage = fetchedResult?.second,
                    failure = if (fetchedResult == null) Exception("Home load timed out") else null
                )

                if (currentRequestVersion == requestVersion && loadedCategoryKey == cacheKey) {
                    newsItems = updated.newsItems
                    coverageList = updated.coverageList
                    errorMsg = updated.errorMsg
                    if (!fetchedResult?.first.isNullOrEmpty()) {
                        val validItems = fetchedResult!!.first
                        newsRepo.putMemoryCachedCategoryFeed(cacheKey, validItems)
                        appRepo.saveCachedFeed(cacheKey, validItems)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val updated = applyHomeLoadResult(
                    currentSnapshot = HomeLoadStateSnapshot(
                        newsItems = newsItems,
                        coverageList = coverageList,
                        isLoading = isLoading,
                        isManualRefreshing = isManualRefreshing,
                        errorMsg = errorMsg,
                        loadedCategoryKey = loadedCategoryKey ?: cacheKey,
                        requestVersion = requestVersion
                    ),
                    completedRequestVersion = currentRequestVersion,
                    targetCategoryKey = cacheKey,
                    fetchedItems = null,
                    builtCoverage = null,
                    failure = e
                )
                if (currentRequestVersion == requestVersion && loadedCategoryKey == cacheKey) {
                    newsItems = updated.newsItems
                    coverageList = updated.coverageList
                    errorMsg = updated.errorMsg
                }
            } finally {
                if (currentRequestVersion == requestVersion) {
                    if (loadedCategoryKey == null) {
                        loadedCategoryKey = cacheKey
                    }
                    isLoading = false
                    isManualRefreshing = false
                }
                newsRepo.markInitialHomeLoadCompleted()
            }
        }
    }

    LaunchedEffect(effectiveCatKey) {
        loadData(force = false)
    }

    LaunchedEffect(currentFeedMode, favoriteUrls) {
        if (newsItems.isNotEmpty()) {
            coverageList = buildCoverageAsyncForMode(newsItems, currentFeedMode, favoriteUrls)
        }
    }

    val recentPaper = remember(appState.recents) {
        appState.recents.firstOrNull()?.let { appRepo.paperFor(it) }
    }

    // Track which items have already animated in for the active category so scrolling never repeats reveals
    val seenItemKeys = remember(loadedCategoryKey) { HashSet<String>() }

    // Header quick fade-in on first mount
    var headerVisible by remember { mutableStateOf(reducedMotion) }
    LaunchedEffect(reducedMotion) {
        headerVisible = true
    }
    val headerAlpha by animateFloatAsState(
        targetValue = if (headerVisible) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (reducedMotion) 0 else MotionTokens.FAST_MS,
            easing = FastOutSlowInEasing
        ),
        label = "homeHeaderAlpha"
    )

    // Natural refresh icon rotation while loading
    val refreshRotation = if (isLoading && !reducedMotion) {
        val infiniteTransition = rememberInfiniteTransition(label = "homeRefreshSpin")
        val angle by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 750, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "homeRefreshAngle"
        )
        angle
    } else {
        0f
    }

    val searchBtnInteraction = remember { MutableInteractionSource() }
    val refreshBtnInteraction = remember { MutableInteractionSource() }

    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier.graphicsLayer { alpha = headerAlpha },
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "পত্রিকাঘর",
                                style = MaterialTheme.typography.headlineSmall.copy(
                                    fontSize = 21.sp,
                                    lineHeight = 25.sp,
                                    fontWeight = FontWeight.Black,
                                    fontFamily = FontFamily.Serif
                                )
                            )
                            Text(
                                text = ".",
                                color = FlagRed,
                                style = MaterialTheme.typography.headlineSmall.copy(
                                    fontSize = 21.sp,
                                    lineHeight = 25.sp,
                                    fontWeight = FontWeight.Black,
                                    fontFamily = FontFamily.Serif
                                )
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(7.dp),
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Text(
                                    text = "আমার নিউজ",
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontSize = 11.sp,
                                        lineHeight = 14.sp
                                    ),
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        Text(
                            text = BengaliUtils.bnToday(),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 11.sp,
                                lineHeight = 14.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = onOpenSearch,
                        interactionSource = searchBtnInteraction,
                        modifier = Modifier
                            .size(38.dp)
                            .pressScaleEffect(searchBtnInteraction, pressedScale = 0.90f)
                    ) {
                        Icon(Icons.Filled.Search, contentDescription = "অনুসন্ধান")
                    }
                    IconButton(
                        onClick = { if (!isLoading) loadData(true) },
                        enabled = !isLoading,
                        interactionSource = refreshBtnInteraction,
                        modifier = Modifier
                            .testTag("home_refresh_button")
                            .size(38.dp)
                            .pressScaleEffect(refreshBtnInteraction, pressedScale = 0.90f)
                    ) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = "রিফ্রেশ",
                            modifier = Modifier.graphicsLayer { rotationZ = refreshRotation }
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Category Chips Row
            LazyRow(
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                items(
                    items = activeCategories.indices.toList(),
                    key = { idx -> activeCategories[idx].id }
                ) { idx ->
                    val cat = activeCategories[idx]
                    val isSelected = idx == selectedCategoryIndex
                    val chipInteraction = remember { MutableInteractionSource() }
                    val chipContainerColor by animateColorAsState(
                        targetValue = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        animationSpec = tween(
                            durationMillis = if (reducedMotion) 0 else MotionTokens.NORMAL_MS,
                            easing = FastOutSlowInEasing
                        ),
                        label = "catChipBg"
                    )
                    val chipLabelColor by animateColorAsState(
                        targetValue = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        animationSpec = tween(
                            durationMillis = if (reducedMotion) 0 else MotionTokens.FAST_MS,
                            easing = FastOutSlowInEasing
                        ),
                        label = "catChipLabel"
                    )

                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            if (selectedCategoryIndex != idx) {
                                selectedCategoryIndex = idx
                            } else if (errorMsg != null || newsItems.isEmpty()) {
                                loadData(true)
                            }
                        },
                        interactionSource = chipInteraction,
                        modifier = Modifier.pressScaleEffect(chipInteraction, pressedScale = 0.95f),
                        label = {
                            Text(
                                text = cat.label,
                                fontSize = 12.5.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = chipContainerColor,
                            selectedLabelColor = chipLabelColor,
                            containerColor = chipContainerColor,
                            labelColor = chipLabelColor
                        ),
                        shape = RoundedCornerShape(18.dp),
                        border = null
                    )
                }
            }

            // Secondary Division / District Filter Chips when "স্থানীয়" ("local") is selected
            AnimatedVisibility(visible = currentCat?.id == "local") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(
                        items = BangladeshLocalRegions,
                        key = { it.id }
                    ) { region ->
                        val isRegionSelected = region.id == selectedLocalRegionId
                        FilterChip(
                            selected = isRegionSelected,
                            onClick = {
                                if (selectedLocalRegionId != region.id) {
                                    selectedLocalRegionId = region.id
                                } else if (errorMsg != null || newsItems.isEmpty()) {
                                    loadData(true)
                                }
                            },
                            leadingIcon = if (region.isDivision) {
                                {
                                    Icon(
                                        imageVector = Icons.Filled.LocationOn,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            } else null,
                            label = {
                                Text(
                                    text = region.nameBn,
                                    fontSize = 11.5.sp,
                                    fontWeight = if (isRegionSelected) FontWeight.Bold else FontWeight.Medium
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer
                            ),
                            shape = RoundedCornerShape(14.dp)
                        )
                    }
                }
            }

            // Feed Mode Toggle Chips: প্রধান সংবাদ (Top Stories) | সর্বশেষ সংবাদ (Latest News)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val feedModes = remember {
                    listOf(
                        HomeFeedMode.TOP_STORIES to "প্রধান সংবাদ",
                        HomeFeedMode.LATEST_NEWS to "সর্বশেষ সংবাদ"
                    )
                }
                feedModes.forEach { (mode, modeLabel) ->
                    val isModeSelected = currentFeedMode == mode
                    val modeInteraction = remember(mode) { MutableInteractionSource() }
                    val modeTestTag = if (mode == HomeFeedMode.TOP_STORIES) {
                        "mode_chip_top_stories"
                    } else {
                        "mode_chip_latest_news"
                    }
                    FilterChip(
                        selected = isModeSelected,
                        onClick = {
                            if (currentFeedMode != mode) {
                                appRepo.setHomeFeedMode(mode)
                            } else if (errorMsg != null || newsItems.isEmpty()) {
                                loadData(true)
                            }
                        },
                        interactionSource = modeInteraction,
                        modifier = Modifier
                            .testTag(modeTestTag)
                            .pressScaleEffect(modeInteraction, pressedScale = 0.95f),
                        leadingIcon = {
                            Icon(
                                imageVector = if (mode == HomeFeedMode.TOP_STORIES) {
                                    Icons.Filled.Bolt
                                } else {
                                    Icons.Filled.Schedule
                                },
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                        },
                        label = {
                            Text(
                                text = modeLabel,
                                fontSize = 12.sp,
                                fontWeight = if (isModeSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.primary,
                            selectedLeadingIconColor = MaterialTheme.colorScheme.primary,
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            iconColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        shape = RoundedCornerShape(14.dp),
                        border = null
                    )
                }
            }

            val bodyState = resolveHomeBodyState(
                isLoading = isLoading,
                newsItems = newsItems,
                loadedCategoryKey = loadedCategoryKey,
                effectiveCatKey = effectiveCatKey,
                errorMsg = errorMsg
            )

            PullToRefreshBox(
                isRefreshing = isManualRefreshing,
                onRefresh = { loadData(true) },
                modifier = Modifier.fillMaxSize()
            ) {
                Crossfade(
                    targetState = bodyState,
                    animationSpec = tween(
                        durationMillis = if (reducedMotion) 0 else MotionTokens.NORMAL_MS,
                        easing = FastOutSlowInEasing
                    ),
                    label = "homeBodyStateCrossfade"
                ) { state ->
                when (state) {
                    HomeBodyState.LOADING -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 82.dp)
                        ) {
                            item(key = "home_branded_loader") {
                                com.potrikaghor.ui.components.BrandedLoadingIndicator(
                                    message = "খবর লোড হচ্ছে...",
                                    logoSize = 72.dp
                                )
                            }
                            items(4, key = { "home_skeleton_$it" }) {
                                SkeletonCard()
                            }
                        }
                    }

                    HomeBodyState.ERROR -> {
                        EmptyState(
                            icon = Icons.Filled.Bolt,
                            title = "সংবাদ পাওয়া যায়নি",
                            body = errorMsg ?: "",
                            actionLabel = "আবার চেষ্টা করুন",
                            onAction = { loadData(true) }
                        )
                    }

                    HomeBodyState.EMPTY -> {
                        EmptyState(
                            icon = Icons.Filled.Bolt,
                            title = "কোনো সংবাদ পাওয়া যায়নি",
                            body = "এই বিভাগে বর্তমানে কোনো সংবাদ নেই।",
                            actionLabel = "আবার চেষ্টা করুন",
                            onAction = { loadData(true) }
                        )
                    }

                    HomeBodyState.CONTENT -> {
                        val rawStories = remember(coverageList, newsItems) {
                            if (coverageList.isNotEmpty()) coverageList else newsItems.map { Coverage(listOf(it)) }
                        }
                        val rankedStories = remember(rawStories, appState.mutedKeywords) {
                            if (appState.mutedKeywords.isEmpty()) {
                                rawStories
                            } else {
                                rawStories.filter { cov ->
                                    cov.items.none { appRepo.isMuted(it.title, it.snippet) }
                                }
                            }
                        }
                        val leadStory = remember(rankedStories) { rankedStories.firstOrNull() }
                        val briefingStories = remember(rankedStories) { rankedStories.drop(1).take(5) }
                        val feedStories = remember(rankedStories) { rankedStories.drop(6) }
                        val topMultiCoverage = remember(rankedStories) {
                            rankedStories.filter { it.count >= 3 }.take(2)
                        }

                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 82.dp)
                        ) {
                            // 1. Continue reading recent paper card
                            if (recentPaper != null) {
                                item(key = "continue_reading_${recentPaper.url}") {
                                    OncePerSessionReveal(
                                        itemKey = "continue_${recentPaper.url}",
                                        seenKeys = seenItemKeys,
                                        delayMillis = 0,
                                        initialOffsetYDp = 6.dp
                                    ) {
                                        Card(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 14.dp, vertical = 4.dp)
                                                .clip(RoundedCornerShape(12.dp))
                                                .subtleClickable(pressedScale = 0.985f) {
                                                    onOpenReader(recentPaper, recentPaper.openUrl, recentPaper.bn)
                                                },
                                            colors = CardDefaults.cardColors(
                                                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                            ),
                                            shape = RoundedCornerShape(12.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                PaperAvatar(paper = recentPaper, size = 34.dp)
                                                Spacer(modifier = Modifier.width(10.dp))
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = "চালিয়ে পড়ুন",
                                                        style = MaterialTheme.typography.bodySmall.copy(
                                                            fontSize = 11.sp,
                                                            lineHeight = 14.sp
                                                        ),
                                                        color = MaterialTheme.colorScheme.primary,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                    Text(
                                                        text = recentPaper.bn,
                                                        style = MaterialTheme.typography.titleMedium.copy(
                                                            fontSize = 14.5.sp,
                                                            lineHeight = 19.sp
                                                        ),
                                                        fontWeight = FontWeight.Bold,
                                                        fontFamily = FontFamily.Serif,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                                Icon(
                                                    imageVector = Icons.Default.ChevronRight,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(20.dp),
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            // 2. Lead Story / Hero (Story #1)
                            if (leadStory != null) {
                                val leadKey = leadStory.items.firstOrNull()?.link?.ifBlank {
                                    leadStory.items.firstOrNull()?.title
                                } ?: "lead_story"
                                item(key = "lead_$leadKey") {
                                    OncePerSessionReveal(
                                        itemKey = "lead_$leadKey",
                                        seenKeys = seenItemKeys,
                                        delayMillis = 25,
                                        initialOffsetYDp = 8.dp
                                    ) {
                                        LeadStoryCard(
                                            coverage = leadStory,
                                            appRepo = appRepo,
                                            newsRepo = newsRepo,
                                            onOpenReader = onOpenReader,
                                            badgeLabel = if (currentFeedMode == HomeFeedMode.LATEST_NEWS) {
                                                "সর্বশেষ সংবাদ"
                                            } else {
                                                "প্রধান সংবাদ"
                                            }
                                        )
                                    }
                                }
                            }

                            // 3. Morning Briefing / আজকের গুরুত্বপূর্ণ খবর (Stories #2–#6)
                            if (briefingStories.isNotEmpty()) {
                                item(key = "briefing_header") {
                                    OncePerSessionReveal(
                                        itemKey = "briefing_header_${loadedCategoryKey}",
                                        seenKeys = seenItemKeys,
                                        delayMillis = 55,
                                        initialOffsetYDp = 6.dp
                                    ) {
                                        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    if (currentFeedMode == HomeFeedMode.LATEST_NEWS) {
                                                        Icons.Filled.Schedule
                                                    } else {
                                                        Icons.Filled.Bolt
                                                    },
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Spacer(modifier = Modifier.width(5.dp))
                                                Text(
                                                    text = if (currentFeedMode == HomeFeedMode.LATEST_NEWS) {
                                                        "সর্বশেষ আপডেট"
                                                    } else {
                                                        "আজকের গুরুত্বপূর্ণ খবর"
                                                    },
                                                    style = MaterialTheme.typography.titleMedium.copy(
                                                        fontSize = 15.sp,
                                                        lineHeight = 20.sp
                                                    ),
                                                    fontWeight = FontWeight.Bold,
                                                    fontFamily = FontFamily.Serif
                                                )
                                            }
                                            Text(
                                                text = if (currentFeedMode == HomeFeedMode.LATEST_NEWS) {
                                                    "প্রকাশের সময় অনুযায়ী সাম্প্রতিক খবর"
                                                } else {
                                                    "দিনের শীর্ষ ঘটনা সংক্ষেপে"
                                                },
                                                style = MaterialTheme.typography.bodySmall.copy(
                                                    fontSize = 11.sp,
                                                    lineHeight = 14.sp
                                                ),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }

                                item(key = "briefing_row") {
                                    OncePerSessionReveal(
                                        itemKey = "briefing_row_${loadedCategoryKey}",
                                        seenKeys = seenItemKeys,
                                        delayMillis = 75,
                                        initialOffsetYDp = 7.dp
                                    ) {
                                        Column {
                                            LazyRow(
                                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 2.dp),
                                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                                            ) {
                                                items(
                                                    items = briefingStories,
                                                    key = { cov ->
                                                        cov.items.firstOrNull()?.link?.ifBlank {
                                                            cov.items.firstOrNull()?.title
                                                        } ?: cov.hashCode()
                                                    }
                                                ) { coverage ->
                                                    val item = coverage.items.firstOrNull() ?: return@items
                                                    val paper = remember(item.source, item.link) {
                                                        appRepo.allPapers.firstOrNull { it.bn == item.source || it.en == item.source }
                                                            ?: Paper(item.source, "", item.link, "online")
                                                    }
                                                    val fallbackItems = remember(coverage.items, item) {
                                                        coverage.items.drop(1) + item.related
                                                    }

                                                    val isBriefingRead = remember(appState.readArticles, item.link, item.title) {
                                                        appRepo.isArticleRead(item.link, item.title)
                                                    }

                                                    Card(
                                                        modifier = Modifier
                                                            .width(240.dp)
                                                            .clip(RoundedCornerShape(14.dp))
                                                            .subtleClickable(pressedScale = 0.982f) {
                                                                appRepo.markArticleRead(item.link, item.title, paper.bn, paper.url)
                                                                onOpenReader(paper, item.link, item.title)
                                                            },
                                                        shape = RoundedCornerShape(14.dp),
                                                        colors = CardDefaults.cardColors(
                                                            containerColor = MaterialTheme.colorScheme.surface
                                                        ),
                                                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                                                    ) {
                                                        Column(modifier = Modifier.padding(8.dp)) {
                                                            // 16:9 Top Cover Image
                                                            ArticleCoverImage(
                                                                item = item,
                                                                paper = paper,
                                                                newsRepo = newsRepo,
                                                                fallbackItems = fallbackItems,
                                                                shape = RoundedCornerShape(10.dp),
                                                                fallbackAvatarSize = 34.dp,
                                                                modifier = Modifier
                                                                    .fillMaxWidth()
                                                                    .aspectRatio(16f / 9f)
                                                            )

                                                            Spacer(modifier = Modifier.height(7.dp))

                                                            // Source row (small logo + source name + relative time)
                                                            Row(
                                                                modifier = Modifier
                                                                    .fillMaxWidth()
                                                                    .padding(horizontal = 2.dp),
                                                                verticalAlignment = Alignment.CenterVertically
                                                            ) {
                                                                PaperAvatar(
                                                                    paper = paper,
                                                                    size = 14.dp
                                                                )
                                                                Spacer(modifier = Modifier.width(5.dp))
                                                                Text(
                                                                    text = item.source,
                                                                    style = MaterialTheme.typography.labelMedium.copy(
                                                                        fontSize = 11.sp,
                                                                        lineHeight = 14.sp
                                                                    ),
                                                                    fontWeight = FontWeight.Bold,
                                                                    color = MaterialTheme.colorScheme.primary,
                                                                    maxLines = 1,
                                                                    overflow = TextOverflow.Ellipsis,
                                                                    modifier = Modifier.weight(1f, fill = false)
                                                                )
                                                                if (item.time != null) {
                                                                    Text(
                                                                        text = " • ${BengaliUtils.bnAgo(item.time)}",
                                                                        style = MaterialTheme.typography.bodySmall.copy(
                                                                            fontSize = 10.5.sp,
                                                                            lineHeight = 14.sp
                                                                        ),
                                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                                        maxLines = 1
                                                                    )
                                                                }
                                                                if (isBriefingRead) {
                                                                    Text(
                                                                        text = " • পঠিত",
                                                                        style = MaterialTheme.typography.labelSmall.copy(
                                                                            fontSize = 10.sp,
                                                                            lineHeight = 13.sp
                                                                        ),
                                                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                                                                        maxLines = 1
                                                                    )
                                                                }
                                                            }

                                                            Spacer(modifier = Modifier.height(4.dp))

                                                            // Full-width Headline
                                                            Text(
                                                                text = item.title,
                                                                style = MaterialTheme.typography.titleMedium.copy(
                                                                    fontSize = 14.5.sp,
                                                                    lineHeight = 19.5.sp
                                                                ),
                                                                fontWeight = FontWeight.Bold,
                                                                fontFamily = FontFamily.Serif,
                                                                color = if (isBriefingRead) {
                                                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f)
                                                                } else {
                                                                    MaterialTheme.colorScheme.onSurface
                                                                },
                                                                minLines = 3,
                                                                maxLines = 3,
                                                                overflow = TextOverflow.Ellipsis,
                                                                modifier = Modifier.padding(horizontal = 2.dp, vertical = 1.dp)
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                            Spacer(modifier = Modifier.height(8.dp))
                                            HorizontalDivider(modifier = Modifier.padding(horizontal = 14.dp))
                                        }
                                    }
                                }
                            }

                            // 3.5 Top Multi-Newspaper Coverage Preview Card (২.ক)
                            if (topMultiCoverage.isNotEmpty()) {
                                item(key = "top_coverage_preview_card") {
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 14.dp, vertical = 6.dp),
                                        shape = RoundedCornerShape(14.dp),
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
                                        )
                                    ) {
                                        Column(modifier = Modifier.padding(12.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Icon(
                                                        imageVector = Icons.Filled.Hub,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(17.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text(
                                                        text = "বহু-পত্রিকা শীর্ষ কভারেজ",
                                                        style = MaterialTheme.typography.titleSmall.copy(
                                                            fontSize = 14.sp,
                                                            lineHeight = 18.sp
                                                        ),
                                                        fontWeight = FontWeight.Bold,
                                                        fontFamily = FontFamily.Serif
                                                    )
                                                }
                                                TextButton(
                                                    onClick = onOpenCoverage,
                                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                                ) {
                                                    Text(
                                                        text = "সব কভারেজ দেখুন →",
                                                        style = MaterialTheme.typography.labelMedium,
                                                        fontWeight = FontWeight.Bold,
                                                        color = MaterialTheme.colorScheme.primary
                                                    )
                                                }
                                            }

                                            topMultiCoverage.forEachIndexed { idx, cov ->
                                                val topItem = cov.items.firstOrNull() ?: return@forEachIndexed
                                                val topPaper = appRepo.allPapers.firstOrNull {
                                                    it.bn == topItem.source || it.en == topItem.source
                                                } ?: Paper(topItem.source, "", topItem.link, "online")

                                                if (idx > 0) {
                                                    HorizontalDivider(
                                                        modifier = Modifier.padding(vertical = 6.dp),
                                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                                    )
                                                }

                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .subtleClickable(pressedScale = 0.985f) {
                                                            appRepo.markArticleRead(topItem.link, topItem.title, topPaper.bn, topPaper.url)
                                                            onOpenReader(topPaper, topItem.link, topItem.title)
                                                        }
                                                        .padding(vertical = 4.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(
                                                            text = topItem.title,
                                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                                fontSize = 13.5.sp,
                                                                lineHeight = 18.5.sp
                                                            ),
                                                            fontWeight = FontWeight.Bold,
                                                            fontFamily = FontFamily.Serif,
                                                            maxLines = 2,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                        Spacer(modifier = Modifier.height(2.dp))
                                                        Text(
                                                            text = "${BengaliUtils.bnDigits(cov.count)}টি পত্রিকায় কভার হয়েছে • ${topItem.source}",
                                                            style = MaterialTheme.typography.labelSmall.copy(
                                                                fontSize = 10.5.sp,
                                                                lineHeight = 14.sp
                                                            ),
                                                            color = MaterialTheme.colorScheme.primary,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                    }
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Icon(
                                                        imageVector = Icons.Default.ChevronRight,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            // 4. Smart Grouped Feed (Stories #7+)
                            if (feedStories.isNotEmpty()) {
                                item(key = "smart_feed_header") {
                                    OncePerSessionReveal(
                                        itemKey = "smart_feed_header_${loadedCategoryKey}",
                                        seenKeys = seenItemKeys,
                                        delayMillis = 90,
                                        initialOffsetYDp = 5.dp
                                    ) {
                                        Text(
                                            text = if (currentFeedMode == HomeFeedMode.LATEST_NEWS) {
                                                "সর্বশেষ নিউজ ফিড"
                                            } else {
                                                "স্মার্ট নিউজ ফিড"
                                            },
                                            style = MaterialTheme.typography.titleMedium.copy(
                                                fontSize = 15.sp,
                                                lineHeight = 20.sp
                                            ),
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Serif,
                                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                                        )
                                    }
                                }

                                feedStories.forEachIndexed { idx, cov ->
                                    val first = cov.items.firstOrNull()
                                    val baseKey = first?.link?.ifBlank { first.title } ?: "story_$idx"
                                    item(
                                        key = "feed_${idx}_$baseKey",
                                        contentType = "news_story"
                                    ) {
                                        val itemKey = first?.link?.ifBlank { first.title } ?: "feed_$idx"
                                        // Subtle stagger only for the first 5 cards on initial category load
                                        val staggerDelay = if (idx < 5) (idx + 1) * 28 else 0

                                        OncePerSessionReveal(
                                            itemKey = itemKey,
                                            seenKeys = seenItemKeys,
                                            delayMillis = staggerDelay,
                                            initialOffsetYDp = 6.dp
                                        ) {
                                            SmartStoryCard(
                                                coverage = cov,
                                                appRepo = appRepo,
                                                newsRepo = newsRepo,
                                                onOpenReader = onOpenReader
                                            )
                                        }
                                    }

                                    if (!appState.hideAds && AdMobConfig.shouldShowAdAfterFeedIndex(idx, feedStories.size)) {
                                        val slotIndex = AdMobConfig.adSlotIndexForFeedIndex(idx)
                                        val slotKey = "${loadedCategoryKey ?: "home"}_slot_$slotIndex"
                                        item(
                                            key = "native_ad_$slotKey",
                                            contentType = "native_ad"
                                        ) {
                                            PotrikaGhorNativeAd(
                                                slotKey = slotKey,
                                                slotManager = nativeAdSlotManager
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            }
        }
    }
}

/**
 * Animates a feed item only the first time it appears in the current category session.
 * Subsequent scrolls up/down render at 1f alpha and 0f offset immediately with zero overhead.
 */
@Composable
private fun OncePerSessionReveal(
    itemKey: String,
    seenKeys: HashSet<String>,
    delayMillis: Int = 0,
    initialOffsetYDp: Dp = 6.dp,
    content: @Composable () -> Unit
) {
    val reducedMotion = rememberReducedMotion()
    val alreadySeen = remember(itemKey) {
        if (reducedMotion) {
            seenKeys.add(itemKey)
            true
        } else {
            seenKeys.contains(itemKey)
        }
    }
    var visible by remember(itemKey) { mutableStateOf(alreadySeen) }
    val offsetPx = with(LocalDensity.current) { initialOffsetYDp.toPx() }

    LaunchedEffect(itemKey, reducedMotion) {
        if (!visible) {
            if (delayMillis > 0 && !reducedMotion) {
                delay(delayMillis.toLong())
            }
            seenKeys.add(itemKey)
            visible = true
        }
    }

    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (alreadySeen || reducedMotion) 0 else MotionTokens.REVEAL_MS,
            easing = LinearOutSlowInEasing
        ),
        label = "onceRevealAlpha"
    )
    val translationY by animateFloatAsState(
        targetValue = if (visible || alreadySeen || reducedMotion) 0f else offsetPx,
        animationSpec = tween(
            durationMillis = if (alreadySeen || reducedMotion) 0 else MotionTokens.REVEAL_MS,
            easing = FastOutSlowInEasing
        ),
        label = "onceRevealTranslationY"
    )

    Box(
        modifier = Modifier.graphicsLayer {
            this.alpha = alpha
            this.translationY = translationY
        }
    ) {
        content()
    }
}

@Composable
fun LeadStoryCard(
    coverage: Coverage,
    appRepo: AppStateRepository,
    newsRepo: NewsRepository,
    onOpenReader: (Paper, String, String) -> Unit,
    badgeLabel: String = "প্রধান সংবাদ"
) {
    val mainItem = coverage.items.firstOrNull() ?: return
    val reducedMotion = rememberReducedMotion()
    var expanded by remember(mainItem.link, mainItem.title) { mutableStateOf(false) }
    val paper = remember(mainItem.source, mainItem.link) {
        appRepo.allPapers.firstOrNull { it.bn == mainItem.source || it.en == mainItem.source }
            ?: Paper(mainItem.source, "", mainItem.link, "online")
    }

    val multiCoverage = coverage.count > 1
    val siblingItems = remember(coverage.items, mainItem) {
        coverage.items.drop(1) + mainItem.related
    }

    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(
            durationMillis = if (reducedMotion) 0 else MotionTokens.NORMAL_MS,
            easing = FastOutSlowInEasing
        ),
        label = "leadExpandArrow"
    )

    val appState by appRepo.state.collectAsState()
    val isLeadRead = remember(appState.readArticles, mainItem.link, mainItem.title) {
        appRepo.isArticleRead(mainItem.link, mainItem.title)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .subtleClickable(pressedScale = 0.986f) {
                appRepo.markArticleRead(mainItem.link, mainItem.title, paper.bn, paper.url)
                onOpenReader(paper, mainItem.link, mainItem.title)
            },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            ArticleCoverImage(
                item = mainItem,
                paper = paper,
                newsRepo = newsRepo,
                fallbackItems = siblingItems,
                shape = RoundedCornerShape(10.dp),
                fallbackAvatarSize = 42.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PaperAvatar(
                    paper = paper,
                    size = 16.dp
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = mainItem.source,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = 11.5.sp,
                        lineHeight = 15.sp
                    ),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (mainItem.time != null) {
                    Text(
                        text = " • ${BengaliUtils.bnAgo(mainItem.time)}",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 11.sp,
                            lineHeight = 14.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                if (isLeadRead) {
                    Text(
                        text = " • পঠিত",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.5.sp,
                            lineHeight = 14.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        maxLines = 1
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = FlagRed.copy(alpha = 0.14f)
                ) {
                    Text(
                        text = badgeLabel,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.sp,
                            lineHeight = 13.sp
                        ),
                        color = FlagRed,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(5.dp))

            Text(
                text = mainItem.title,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = 17.5.sp,
                    lineHeight = 24.sp
                ),
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Serif,
                color = if (isLeadRead) {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f)
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )

            if (multiCoverage) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .subtleClickable(pressedScale = 0.96f) { expanded = !expanded },
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "এই খবরটি ${BengaliUtils.bnDigits(coverage.count)}টি পত্রিকায়",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.5.sp,
                                lineHeight = 14.sp
                            ),
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Icon(
                            imageVector = Icons.Default.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier
                                .size(14.dp)
                                .graphicsLayer { rotationZ = arrowRotation },
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = expanded && multiCoverage,
                enter = expandVertically(
                    animationSpec = tween(
                        durationMillis = if (reducedMotion) 0 else MotionTokens.NORMAL_MS,
                        easing = FastOutSlowInEasing
                    )
                ) + fadeIn(
                    animationSpec = tween(
                        durationMillis = if (reducedMotion) 0 else MotionTokens.FAST_MS,
                        easing = FastOutSlowInEasing
                    )
                ),
                exit = shrinkVertically(
                    animationSpec = tween(
                        durationMillis = if (reducedMotion) 0 else MotionTokens.FAST_MS,
                        easing = FastOutSlowInEasing
                    )
                ) + fadeOut(
                    animationSpec = tween(
                        durationMillis = if (reducedMotion) 0 else 120,
                        easing = FastOutSlowInEasing
                    )
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                ) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                    Text(
                        text = "অন্যান্য পত্রিকার শিরোনাম ও দৃষ্টিভঙ্গি:",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.5.sp,
                            lineHeight = 14.sp
                        ),
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))

                    coverage.items.drop(1).forEach { rel ->
                        val relPaper = appRepo.allPapers.firstOrNull { it.bn == rel.source || it.en == rel.source }
                            ?: Paper(rel.source, "", rel.link, "online")

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .subtleClickable(pressedScale = 0.985f) {
                                    onOpenReader(relPaper, rel.link, rel.title)
                                }
                                .padding(vertical = 4.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            PaperAvatar(
                                paper = relPaper,
                                size = 18.dp
                            )
                            Spacer(modifier = Modifier.width(7.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = rel.source,
                                    fontSize = 10.5.sp,
                                    lineHeight = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = rel.title,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontSize = 12.sp,
                                        lineHeight = 16.5.sp
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SmartStoryCard(
    coverage: Coverage,
    appRepo: AppStateRepository,
    newsRepo: NewsRepository,
    onOpenReader: (Paper, String, String) -> Unit
) {
    val mainItem = coverage.items.firstOrNull() ?: return
    val reducedMotion = rememberReducedMotion()
    var expanded by remember(mainItem.link, mainItem.title) {
        mutableStateOf(false)
    }
    val paper = remember(mainItem.source, mainItem.link) {
        appRepo.allPapers.firstOrNull {
            it.bn == mainItem.source || it.en == mainItem.source
        } ?: Paper(mainItem.source, "", mainItem.link, "online")
    }

    val multiCoverage = coverage.count > 1
    val siblingItems = remember(coverage.items, mainItem) {
        coverage.items.drop(1) + mainItem.related
    }

    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(
            durationMillis = if (reducedMotion) 0 else MotionTokens.NORMAL_MS,
            easing = FastOutSlowInEasing
        ),
        label = "smartExpandArrow"
    )

    val appState by appRepo.state.collectAsState()
    val isSmartRead = remember(appState.readArticles, mainItem.link, mainItem.title) {
        appRepo.isArticleRead(mainItem.link, mainItem.title)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .subtleClickable(pressedScale = 0.985f) {
                appRepo.markArticleRead(mainItem.link, mainItem.title, paper.bn, paper.url)
                onOpenReader(paper, mainItem.link, mainItem.title)
            },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            // A) Top metadata row: small source logo + source name + relative time
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PaperAvatar(
                    paper = paper,
                    size = 14.dp
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = mainItem.source,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = 11.5.sp,
                        lineHeight = 15.sp
                    ),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (mainItem.time != null) {
                    Text(
                        text = " • ${BengaliUtils.bnAgo(mainItem.time)}",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 11.sp,
                            lineHeight = 14.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                if (isSmartRead) {
                    Text(
                        text = " • পঠিত",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.5.sp,
                            lineHeight = 14.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        maxLines = 1
                    )
                }
            }

            Spacer(modifier = Modifier.height(5.dp))

            // B) Main content row: Headline on left, Thumbnail on right
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = mainItem.title,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontSize = 15.sp,
                            lineHeight = 20.5.sp
                        ),
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Serif,
                        color = if (isSmartRead) {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f)
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                // 4:3 Rounded Thumbnail on the right
                ArticleCoverImage(
                    item = mainItem,
                    paper = paper,
                    newsRepo = newsRepo,
                    fallbackItems = siblingItems,
                    shape = RoundedCornerShape(10.dp),
                    fallbackAvatarSize = 30.dp,
                    modifier = Modifier
                        .width(90.dp)
                        .aspectRatio(4f / 3f)
                )
            }

            // Multi-source coverage control below the main content row
            if (multiCoverage) {
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .subtleClickable(pressedScale = 0.96f) { expanded = !expanded },
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "এই খবরটি ${BengaliUtils.bnDigits(coverage.count)}টি পত্রিকায়",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.5.sp,
                                lineHeight = 14.sp
                            ),
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Icon(
                            imageVector = Icons.Default.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier
                                .size(14.dp)
                                .graphicsLayer { rotationZ = arrowRotation },
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }

            // Smoothly animated expand/collapse of sibling newspapers' versions
            AnimatedVisibility(
                visible = expanded && multiCoverage,
                enter = expandVertically(
                    animationSpec = tween(
                        durationMillis = if (reducedMotion) 0 else MotionTokens.NORMAL_MS,
                        easing = FastOutSlowInEasing
                    )
                ) + fadeIn(
                    animationSpec = tween(
                        durationMillis = if (reducedMotion) 0 else MotionTokens.FAST_MS,
                        easing = FastOutSlowInEasing
                    )
                ),
                exit = shrinkVertically(
                    animationSpec = tween(
                        durationMillis = if (reducedMotion) 0 else MotionTokens.FAST_MS,
                        easing = FastOutSlowInEasing
                    )
                ) + fadeOut(
                    animationSpec = tween(
                        durationMillis = if (reducedMotion) 0 else 120,
                        easing = FastOutSlowInEasing
                    )
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                ) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                    Text(
                        text = "অন্যান্য পত্রিকার শিরোনাম ও দৃষ্টিভঙ্গি:",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.5.sp,
                            lineHeight = 14.sp
                        ),
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))

                    coverage.items.drop(1).forEach { rel ->
                        val relPaper = appRepo.allPapers.firstOrNull { it.bn == rel.source || it.en == rel.source }
                            ?: Paper(rel.source, "", rel.link, "online")

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .subtleClickable(pressedScale = 0.985f) {
                                    onOpenReader(relPaper, rel.link, rel.title)
                                }
                                .padding(vertical = 4.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            PaperAvatar(
                                paper = relPaper,
                                size = 18.dp
                            )
                            Spacer(modifier = Modifier.width(7.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = rel.source,
                                    fontSize = 10.5.sp,
                                    lineHeight = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = rel.title,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontSize = 12.sp,
                                        lineHeight = 16.5.sp
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

