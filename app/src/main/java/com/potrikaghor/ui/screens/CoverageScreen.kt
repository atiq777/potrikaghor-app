package com.potrikaghor.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.potrikaghor.data.AppStateRepository
import com.potrikaghor.data.CachedCoverageResult
import com.potrikaghor.data.NewsRepository
import com.potrikaghor.model.Coverage
import com.potrikaghor.model.NewsItem
import com.potrikaghor.model.Paper
import com.potrikaghor.ui.components.EmptyState
import com.potrikaghor.ui.components.MotionTokens
import com.potrikaghor.ui.components.PaperAvatar
import com.potrikaghor.ui.components.SkeletonCard
import com.potrikaghor.ui.components.pressScaleEffect
import com.potrikaghor.ui.components.rememberReducedMotion
import com.potrikaghor.ui.components.subtleClickable
import com.potrikaghor.ui.util.BengaliUtils
import kotlinx.coroutines.launch

enum class CoverageBodyState {
    LOADING,
    ERROR,
    EMPTY,
    CONTENT
}

enum class CoverageEnterAction {
    SHOW_CACHED_ONLY,
    BACKGROUND_REFRESH,
    INITIAL_LOAD
}

data class CoverageUiStateSnapshot(
    val coverageList: List<Coverage> = emptyList(),
    val manyCoverage: List<Coverage> = emptyList(),
    val fewCoverage: List<Coverage> = emptyList(),
    val originalItems: List<NewsItem> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val errorMsg: String? = null,
    val fetchedAtMs: Long = 0L
)

const val COVERAGE_LOAD_ERROR_MESSAGE = "কভারেজ লোড করা যায়নি। ইন্টারনেট সংযোগ পরীক্ষা করুন।"

fun resolveCoverageEnterAction(
    cached: CachedCoverageResult?,
    isCacheFresh: Boolean,
    returningFromReader: Boolean = false
): CoverageEnterAction {
    if (cached == null || cached.coverageList.isEmpty()) {
        return CoverageEnterAction.INITIAL_LOAD
    }
    if (returningFromReader || isCacheFresh) {
        return CoverageEnterAction.SHOW_CACHED_ONLY
    }
    return CoverageEnterAction.BACKGROUND_REFRESH
}

fun createInitialCoverageSnapshot(cached: CachedCoverageResult?): CoverageUiStateSnapshot {
    return if (cached != null && cached.coverageList.isNotEmpty()) {
        CoverageUiStateSnapshot(
            coverageList = cached.coverageList,
            manyCoverage = cached.manyCoverage,
            fewCoverage = cached.fewCoverage,
            originalItems = cached.originalItems,
            isLoading = false,
            isRefreshing = false,
            errorMsg = null,
            fetchedAtMs = cached.fetchedAtMs
        )
    } else {
        CoverageUiStateSnapshot(
            coverageList = emptyList(),
            manyCoverage = emptyList(),
            fewCoverage = emptyList(),
            originalItems = emptyList(),
            isLoading = true,
            isRefreshing = false,
            errorMsg = null,
            fetchedAtMs = 0L
        )
    }
}

fun beginCoverageRefreshState(current: CoverageUiStateSnapshot): CoverageUiStateSnapshot {
    return if (current.coverageList.isNotEmpty()) {
        current.copy(isLoading = false, isRefreshing = true, errorMsg = null)
    } else {
        current.copy(isLoading = true, isRefreshing = false, errorMsg = null)
    }
}

fun applyCoverageLoadResult(
    current: CoverageUiStateSnapshot,
    newResult: CachedCoverageResult?,
    errorMessage: String? = null
): CoverageUiStateSnapshot {
    return if (newResult != null) {
        CoverageUiStateSnapshot(
            coverageList = newResult.coverageList,
            manyCoverage = newResult.manyCoverage,
            fewCoverage = newResult.fewCoverage,
            originalItems = newResult.originalItems,
            isLoading = false,
            isRefreshing = false,
            errorMsg = null,
            fetchedAtMs = newResult.fetchedAtMs
        )
    } else if (current.coverageList.isNotEmpty()) {
        // Stale-while-revalidate failure: preserve existing results and never blank the screen
        current.copy(
            isLoading = false,
            isRefreshing = false,
            errorMsg = null
        )
    } else {
        current.copy(
            isLoading = false,
            isRefreshing = false,
            errorMsg = errorMessage ?: COVERAGE_LOAD_ERROR_MESSAGE
        )
    }
}

fun resolveCoverageBodyState(snapshot: CoverageUiStateSnapshot): CoverageBodyState {
    return when {
        snapshot.coverageList.isNotEmpty() -> CoverageBodyState.CONTENT
        snapshot.isLoading -> CoverageBodyState.LOADING
        snapshot.errorMsg != null -> CoverageBodyState.ERROR
        else -> CoverageBodyState.EMPTY
    }
}

internal fun coverageStableKey(prefix: String, index: Int, coverage: Coverage): String {
    val firstLink = coverage.items.firstOrNull()?.link?.takeIf { it.isNotBlank() }
    return if (firstLink != null) {
        "${prefix}_${firstLink}"
    } else {
        "${prefix}_${index}_${coverage.headline.hashCode()}"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoverageScreen(
    appRepo: AppStateRepository,
    newsRepo: NewsRepository,
    onOpenReader: (Paper, String, String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val reducedMotion = rememberReducedMotion()
    val stateHolder = newsRepo.coverageStateHolder

    var isIntl by remember { mutableStateOf(stateHolder.isIntl) }
    val selectedTopic by remember { mutableStateOf(stateHolder.selectedTopic) }
    val activeKey = remember(isIntl, selectedTopic) {
        newsRepo.coverageCacheKey(isIntl, selectedTopic)
    }

    var uiState by remember(activeKey) {
        val cached = newsRepo.getCachedCoverageResult(isIntl, selectedTopic)
        mutableStateOf(createInitialCoverageSnapshot(cached))
    }

    val listState = remember(activeKey) {
        val savedPos = stateHolder.getScrollPosition(activeKey)
        LazyListState(
            firstVisibleItemIndex = savedPos.firstVisibleItemIndex,
            firstVisibleItemScrollOffset = savedPos.firstVisibleItemScrollOffset
        )
    }

    DisposableEffect(activeKey, listState) {
        onDispose {
            stateHolder.isIntl = isIntl
            stateHolder.selectedTopic = selectedTopic
            stateHolder.saveScrollPosition(
                cacheKey = activeKey,
                index = listState.firstVisibleItemIndex,
                offset = listState.firstVisibleItemScrollOffset
            )
        }
    }

    suspend fun performLoad(forceRefresh: Boolean) {
        uiState = beginCoverageRefreshState(uiState)
        stateHolder.isLoading = uiState.isLoading
        stateHolder.isRefreshing = uiState.isRefreshing
        stateHolder.errorMsg = uiState.errorMsg
        try {
            val result = newsRepo.fetchCoverageCached(
                intl = isIntl,
                filterQuery = selectedTopic,
                forceRefresh = forceRefresh
            )
            uiState = applyCoverageLoadResult(uiState, newResult = result)
            stateHolder.lastSuccessfulResult = result
        } catch (_: Exception) {
            uiState = applyCoverageLoadResult(
                current = uiState,
                newResult = null,
                errorMessage = COVERAGE_LOAD_ERROR_MESSAGE
            )
        } finally {
            stateHolder.isLoading = uiState.isLoading
            stateHolder.isRefreshing = uiState.isRefreshing
            stateHolder.errorMsg = uiState.errorMsg
        }
    }

    fun triggerManualRefresh() {
        scope.launch {
            performLoad(forceRefresh = true)
        }
    }

    LaunchedEffect(activeKey) {
        stateHolder.isIntl = isIntl
        stateHolder.selectedTopic = selectedTopic
        val wasReturningFromReader = stateHolder.consumeReturningFromReader()

        val cached = newsRepo.getCachedCoverageResult(isIntl, selectedTopic)
        val isFresh = newsRepo.isCoverageCacheFresh(isIntl, selectedTopic)
        val action = resolveCoverageEnterAction(
            cached = cached,
            isCacheFresh = isFresh,
            returningFromReader = wasReturningFromReader
        )

        when (action) {
            CoverageEnterAction.SHOW_CACHED_ONLY -> {
                if (cached != null) {
                    uiState = applyCoverageLoadResult(uiState, newResult = cached)
                    stateHolder.lastSuccessfulResult = cached
                    stateHolder.isLoading = false
                    stateHolder.isRefreshing = false
                    stateHolder.errorMsg = null
                }
            }
            CoverageEnterAction.BACKGROUND_REFRESH -> {
                if (cached != null && uiState.coverageList.isEmpty()) {
                    uiState = createInitialCoverageSnapshot(cached)
                }
                performLoad(forceRefresh = true)
            }
            CoverageEnterAction.INITIAL_LOAD -> {
                performLoad(forceRefresh = false)
            }
        }
    }

    val appState by appRepo.state.collectAsState()
    val (manyCoverage, fewCoverage) = remember(
        uiState.coverageList,
        uiState.manyCoverage,
        uiState.fewCoverage,
        appState.mutedKeywords
    ) {
        if (appState.mutedKeywords.isEmpty()) {
            Pair(uiState.manyCoverage, uiState.fewCoverage)
        } else {
            val filtered = uiState.coverageList.filter { cov ->
                cov.items.none { appRepo.isMuted(it.title, it.snippet) }
            }
            Pair(
                filtered.filter { it.count >= 3 },
                filtered.filter { it.count == 2 }
            )
        }
    }

    val isBusy = uiState.isLoading || uiState.isRefreshing
    val refreshRotation = if (isBusy && !reducedMotion) {
        val infiniteTransition = rememberInfiniteTransition(label = "covRefreshSpin")
        val angle by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 750, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "covRefreshAngle"
        )
        angle
    } else {
        0f
    }
    val refreshInteraction = remember { MutableInteractionSource() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "কভারেজ তুলনা",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontSize = 19.sp,
                                lineHeight = 24.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Serif
                            )
                        )
                        Text(
                            text = "কোন খবর কয়টি পত্রিকায়, কে কীভাবে শিরোনাম দিয়েছে",
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
                        onClick = { if (!isBusy) triggerManualRefresh() },
                        enabled = !isBusy,
                        interactionSource = refreshInteraction,
                        modifier = Modifier
                            .size(38.dp)
                            .pressScaleEffect(refreshInteraction, pressedScale = 0.90f)
                    ) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = "রিফ্রেশ",
                            modifier = Modifier.graphicsLayer { rotationZ = refreshRotation }
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Edition Toggle: Bangladesh vs International
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                SegmentedButton(
                    selected = !isIntl,
                    onClick = {
                        if (isIntl) {
                            stateHolder.saveScrollPosition(
                                cacheKey = activeKey,
                                index = listState.firstVisibleItemIndex,
                                offset = listState.firstVisibleItemScrollOffset
                            )
                            stateHolder.isIntl = false
                            isIntl = false
                        }
                    },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                ) {
                    Text("বাংলাদেশ সংস্করণ", fontSize = 12.5.sp)
                }
                SegmentedButton(
                    selected = isIntl,
                    onClick = {
                        if (!isIntl) {
                            stateHolder.saveScrollPosition(
                                cacheKey = activeKey,
                                index = listState.firstVisibleItemIndex,
                                offset = listState.firstVisibleItemScrollOffset
                            )
                            stateHolder.isIntl = true
                            isIntl = true
                        }
                    },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                ) {
                    Text("আন্তর্জাতিক", fontSize = 12.5.sp)
                }
            }

            if (uiState.isRefreshing && uiState.coverageList.isNotEmpty()) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                )
            }

            val bodyState = resolveCoverageBodyState(uiState)

            Crossfade(
                targetState = bodyState,
                animationSpec = tween(
                    durationMillis = if (reducedMotion) 0 else MotionTokens.NORMAL_MS,
                    easing = FastOutSlowInEasing
                ),
                label = "coverageBodyCrossfade"
            ) { state ->
                when (state) {
                    CoverageBodyState.LOADING -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 82.dp)
                        ) {
                            item(key = "coverage_branded_loader") {
                                com.potrikaghor.ui.components.BrandedLoadingIndicator(
                                    message = "আপনার খবর সাজানো হচ্ছে...",
                                    logoSize = 72.dp
                                )
                            }
                            items(4, key = { "coverage_skeleton_$it" }) {
                                SkeletonCard()
                            }
                        }
                    }

                    CoverageBodyState.ERROR -> {
                        EmptyState(
                            icon = Icons.Filled.Hub,
                            title = "কভারেজ লোড হয়নি",
                            body = uiState.errorMsg ?: "",
                            actionLabel = "আবার চেষ্টা করুন",
                            onAction = { triggerManualRefresh() }
                        )
                    }

                    CoverageBodyState.EMPTY -> {
                        EmptyState(
                            icon = Icons.Filled.Hub,
                            title = "কোনো কভারেজ পাওয়া যায়নি",
                            body = "এই মুহূর্তে একাধিক পত্রিকায় প্রকাশিত কোনো সাধারণ ঘটনা মেলেনি। একটু পরে আবার চেষ্টা করুন।"
                        )
                    }

                    CoverageBodyState.CONTENT -> {
                        LazyColumn(
                            state = listState,
                            contentPadding = PaddingValues(bottom = 82.dp, top = 4.dp)
                        ) {
                            if (manyCoverage.isNotEmpty()) {
                                item(key = "header_many_coverage") {
                                    Text(
                                        text = "বেশি কভার হওয়া খবর (৩+ পত্রিকায়)",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontFamily = FontFamily.Serif,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                                    )
                                }

                                itemsIndexed(
                                    items = manyCoverage,
                                    key = { idx, item -> coverageStableKey("many", idx, item) }
                                ) { _, item ->
                                    CoverageDetailCard(
                                        coverage = item,
                                        appRepo = appRepo,
                                        readArticles = appState.readArticles,
                                        onOpenReader = { paper, link, title ->
                                            stateHolder.saveScrollPosition(
                                                cacheKey = activeKey,
                                                index = listState.firstVisibleItemIndex,
                                                offset = listState.firstVisibleItemScrollOffset
                                            )
                                            stateHolder.markNavigatingToReader()
                                            onOpenReader(paper, link, title)
                                        }
                                    )
                                }
                            }

                            if (fewCoverage.isNotEmpty()) {
                                item(key = "header_few_coverage") {
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Text(
                                        text = "অন্যান্য কভার হওয়া খবর (২টি পত্রিকায়)",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontFamily = FontFamily.Serif,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                                    )
                                }

                                itemsIndexed(
                                    items = fewCoverage,
                                    key = { idx, item -> coverageStableKey("few", idx, item) }
                                ) { _, item ->
                                    CoverageDetailCard(
                                        coverage = item,
                                        appRepo = appRepo,
                                        readArticles = appState.readArticles,
                                        onOpenReader = { paper, link, title ->
                                            stateHolder.saveScrollPosition(
                                                cacheKey = activeKey,
                                                index = listState.firstVisibleItemIndex,
                                                offset = listState.firstVisibleItemScrollOffset
                                            )
                                            stateHolder.markNavigatingToReader()
                                            onOpenReader(paper, link, title)
                                        }
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

@Composable
fun CoverageDetailCard(
    coverage: Coverage,
    appRepo: AppStateRepository,
    readArticles: List<String> = emptyList(),
    onOpenReader: (Paper, String, String) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.5.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(11.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(7.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = "${BengaliUtils.bnDigits(coverage.count)}টি পত্রিকায় কভার হয়েছে",
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.5.sp,
                            lineHeight = 14.sp
                        ),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                if (coverage.latest != null) {
                    Text(
                        text = BengaliUtils.bnAgo(coverage.latest!!),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 11.sp,
                            lineHeight = 14.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            coverage.items.forEachIndexed { idx, itm ->
                val paper = appRepo.allPapers.firstOrNull { it.bn == itm.source || it.en == itm.source }
                    ?: Paper(itm.source, "", itm.link, "online")
                val isRead = remember(readArticles, itm.link, itm.title) {
                    appRepo.isArticleRead(itm.link, itm.title)
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .subtleClickable(pressedScale = 0.985f) {
                            appRepo.markArticleRead(itm.link, itm.title, paper.bn, paper.url)
                            onOpenReader(paper, itm.link, itm.title)
                        }
                        .padding(vertical = 5.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    PaperAvatar(paper = paper, size = 22.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = itm.source,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 11.sp,
                                    lineHeight = 14.sp
                                ),
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            if (isRead) {
                                Text(
                                    text = " • পঠিত",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 10.sp,
                                        lineHeight = 13.sp
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                                )
                            }
                        }
                        Text(
                            text = itm.title,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 13.5.sp,
                                lineHeight = 19.sp
                            ),
                            color = if (isRead) {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f)
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            fontWeight = if (idx == 0) FontWeight.Bold else FontWeight.Normal,
                            fontFamily = FontFamily.Serif
                        )
                    }
                }

                if (idx < coverage.items.size - 1) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 3.dp))
                }
            }
        }
    }
}

