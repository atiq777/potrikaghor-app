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
import com.potrikaghor.data.AppStateRepository
import com.potrikaghor.data.NewsRepository
import com.potrikaghor.model.Coverage
import com.potrikaghor.model.NewsItem
import com.potrikaghor.model.Paper
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

private enum class HomeBodyState {
    LOADING,
    ERROR,
    EMPTY,
    CONTENT
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonalHomeScreen(
    appRepo: AppStateRepository,
    newsRepo: NewsRepository,
    onOpenReader: (Paper, String, String) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val appState by appRepo.state.collectAsState()
    val scope = rememberCoroutineScope()
    val reducedMotion = rememberReducedMotion()

    var selectedCategoryIndex by remember { mutableStateOf(0) }
    var loadedCategoryId by remember { mutableStateOf<String?>(null) }
    var newsItems by remember { mutableStateOf<List<NewsItem>>(emptyList()) }
    var coverageList by remember { mutableStateOf<List<Coverage>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var loadJob by remember { mutableStateOf<Job?>(null) }
    var requestVersion by remember { mutableStateOf(0) }

    val activeCategories = remember(appState.homeCategories) {
        appState.homeCategories.filter { it.enabled }.sortedBy { it.order }
    }

    val currentCat = activeCategories.getOrNull(selectedCategoryIndex) ?: activeCategories.firstOrNull()

    fun loadData(force: Boolean = false) {
        val targetCat = currentCat
        val targetCatId = targetCat?.id
        val isCategorySwitch = loadedCategoryId != targetCatId

        if (!force && !isCategorySwitch && newsItems.isNotEmpty() && errorMsg == null) {
            return
        }

        // Prevent duplicate manual refresh while already loading the same category
        if (force && !isCategorySwitch && isLoading && loadJob?.isActive == true) {
            return
        }

        loadJob?.cancel()
        val currentRequestVersion = ++requestVersion

        if (isCategorySwitch) {
            loadedCategoryId = targetCatId
            newsItems = emptyList()
            coverageList = emptyList()
            errorMsg = null
            isLoading = true
        } else {
            errorMsg = null
            isLoading = true
        }

        loadJob = scope.launch {
            try {
                val q = targetCat?.query ?: ""
                val items = newsRepo.fetchNews(query = q, intl = (targetCatId == "intl"))
                if (currentRequestVersion == requestVersion && loadedCategoryId == targetCatId) {
                    newsItems = items
                    coverageList = newsRepo.buildCoverage(items)
                    errorMsg = null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (currentRequestVersion == requestVersion && loadedCategoryId == targetCatId) {
                    if (newsItems.isEmpty()) {
                        errorMsg = "সংবাদ লোড করা যায়নি। ইন্টারনেট সংযোগ পরীক্ষা করুন।"
                    }
                }
            } finally {
                if (currentRequestVersion == requestVersion && loadedCategoryId == targetCatId) {
                    isLoading = false
                }
            }
        }
    }

    LaunchedEffect(currentCat?.id) {
        loadData()
    }

    val recentPaper = remember(appState.recents) {
        appState.recents.firstOrNull()?.let { appRepo.paperFor(it) }
    }

    // Track which items have already animated in for the active category so scrolling never repeats reveals
    val seenItemKeys = remember(loadedCategoryId) { HashSet<String>() }

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
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Text(
                                    text = "আমার নিউজ",
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    fontSize = 10.sp,
                                    lineHeight = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 1.5.dp)
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
                        Icon(
                            Icons.Filled.Search,
                            contentDescription = "অনুসন্ধান",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(
                        onClick = { if (!isLoading) loadData(true) },
                        enabled = !isLoading,
                        interactionSource = refreshBtnInteraction,
                        modifier = Modifier
                            .size(38.dp)
                            .pressScaleEffect(refreshBtnInteraction, pressedScale = 0.90f)
                    ) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = "রিফ্রেশ",
                            modifier = Modifier
                                .size(20.dp)
                                .graphicsLayer { rotationZ = refreshRotation }
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
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(
                    items = activeCategories.indices.toList(),
                    key = { idx -> activeCategories[idx].id }
                ) { idx ->
                    val cat = activeCategories[idx]
                    val isSelected = idx == selectedCategoryIndex
                    val chipInteraction = remember { MutableInteractionSource() }
                    val chipScale by animateFloatAsState(
                        targetValue = if (isSelected && !reducedMotion) 1.02f else 1f,
                        animationSpec = tween(
                            durationMillis = if (reducedMotion) 0 else MotionTokens.FAST_MS,
                            easing = FastOutSlowInEasing
                        ),
                        label = "catChipScale"
                    )
                    val containerColor by animateColorAsState(
                        targetValue = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                        animationSpec = tween(
                            durationMillis = if (reducedMotion) 0 else MotionTokens.FAST_MS,
                            easing = FastOutSlowInEasing
                        ),
                        label = "catChipBg"
                    )
                    val labelColor by animateColorAsState(
                        targetValue = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
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
                        modifier = Modifier
                            .height(30.dp)
                            .pressScaleEffect(chipInteraction, pressedScale = 0.95f)
                            .graphicsLayer {
                                scaleX = chipScale
                                scaleY = chipScale
                            },
                        label = {
                            Text(
                                text = cat.label,
                                fontSize = 12.5.sp,
                                lineHeight = 16.sp,
                                color = labelColor,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = containerColor,
                            labelColor = labelColor,
                            selectedContainerColor = containerColor,
                            selectedLabelColor = labelColor
                        )
                    )
                }
            }

            val bodyState = when {
                (isLoading && newsItems.isEmpty()) || loadedCategoryId != currentCat?.id -> HomeBodyState.LOADING
                errorMsg != null && newsItems.isEmpty() -> HomeBodyState.ERROR
                newsItems.isEmpty() -> HomeBodyState.EMPTY
                else -> HomeBodyState.CONTENT
            }

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
                        val rankedStories = remember(coverageList, newsItems) {
                            if (coverageList.isNotEmpty()) coverageList else newsItems.map { Coverage(listOf(it)) }
                        }
                        val leadStory = remember(rankedStories) { rankedStories.firstOrNull() }
                        val briefingStories = remember(rankedStories) { rankedStories.drop(1).take(5) }
                        val feedStories = remember(rankedStories) { rankedStories.drop(6) }

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
                                                .clip(RoundedCornerShape(14.dp))
                                                .subtleClickable(pressedScale = 0.984f) {
                                                    onOpenReader(recentPaper, recentPaper.openUrl, recentPaper.bn)
                                                },
                                            shape = RoundedCornerShape(14.dp),
                                            colors = CardDefaults.cardColors(
                                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                                            )
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
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                    Text(
                                                        text = recentPaper.bn,
                                                        style = MaterialTheme.typography.titleMedium.copy(
                                                            fontSize = 14.5.sp,
                                                            lineHeight = 19.sp
                                                        ),
                                                        fontWeight = FontWeight.Bold,
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
                                            onOpenReader = onOpenReader
                                        )
                                    }
                                }
                            }

                            // 3. Morning Briefing / আজকের গুরুত্বপূর্ণ খবর (Stories #2–#6)
                            if (briefingStories.isNotEmpty()) {
                                item(key = "briefing_header") {
                                    OncePerSessionReveal(
                                        itemKey = "briefing_header_${loadedCategoryId}",
                                        seenKeys = seenItemKeys,
                                        delayMillis = 55,
                                        initialOffsetYDp = 6.dp
                                    ) {
                                        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    Icons.Filled.Bolt,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Spacer(modifier = Modifier.width(5.dp))
                                                Text(
                                                    text = "আজকের গুরুত্বপূর্ণ খবর",
                                                    style = MaterialTheme.typography.titleMedium.copy(
                                                        fontSize = 15.sp,
                                                        lineHeight = 20.sp
                                                    ),
                                                    fontWeight = FontWeight.Bold,
                                                    fontFamily = FontFamily.Serif
                                                )
                                            }
                                            Text(
                                                text = "দিনের শীর্ষ ঘটনা সংক্ষেপে",
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
                                        itemKey = "briefing_row_${loadedCategoryId}",
                                        seenKeys = seenItemKeys,
                                        delayMillis = 75,
                                        initialOffsetYDp = 7.dp
                                    ) {
                                        Column {
                                            LazyRow(
                                                contentPadding = PaddingValues(horizontal = 14.dp),
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

                                                    Card(
                                                        modifier = Modifier
                                                            .width(242.dp)
                                                            .clip(RoundedCornerShape(14.dp))
                                                            .subtleClickable(pressedScale = 0.982f) {
                                                                onOpenReader(paper, item.link, item.title)
                                                            },
                                                        shape = RoundedCornerShape(14.dp),
                                                        colors = CardDefaults.cardColors(
                                                            containerColor = MaterialTheme.colorScheme.surface
                                                        ),
                                                        border = androidx.compose.foundation.BorderStroke(
                                                            1.dp,
                                                            MaterialTheme.colorScheme.outlineVariant
                                                        )
                                                    ) {
                                                        Column(modifier = Modifier.padding(9.dp)) {
                                                            ArticleCoverImage(
                                                                item = item,
                                                                paper = paper,
                                                                newsRepo = newsRepo,
                                                                fallbackItems = fallbackItems,
                                                                shape = RoundedCornerShape(10.dp),
                                                                fallbackAvatarSize = 34.dp,
                                                                modifier = Modifier
                                                                    .fillMaxWidth()
                                                                    .aspectRatio(16f / 9.5f)
                                                            )

                                                            Spacer(modifier = Modifier.height(7.dp))

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
                                                            }

                                                            Spacer(modifier = Modifier.height(4.dp))

                                                            Text(
                                                                text = item.title,
                                                                style = MaterialTheme.typography.titleMedium.copy(
                                                                    fontSize = 14.5.sp,
                                                                    lineHeight = 20.sp
                                                                ),
                                                                fontWeight = FontWeight.Bold,
                                                                fontFamily = FontFamily.Serif,
                                                                color = MaterialTheme.colorScheme.onSurface,
                                                                minLines = 3,
                                                                maxLines = 3,
                                                                overflow = TextOverflow.Ellipsis,
                                                                modifier = Modifier.padding(horizontal = 2.dp, vertical = 1.dp)
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                            Spacer(modifier = Modifier.height(10.dp))
                                            HorizontalDivider(modifier = Modifier.padding(horizontal = 14.dp))
                                        }
                                    }
                                }
                            }

                            // 4. Smart Grouped Feed (Stories #7+)
                            if (feedStories.isNotEmpty()) {
                                item(key = "smart_feed_header") {
                                    OncePerSessionReveal(
                                        itemKey = "smart_feed_header_${loadedCategoryId}",
                                        seenKeys = seenItemKeys,
                                        delayMillis = 90,
                                        initialOffsetYDp = 5.dp
                                    ) {
                                        Text(
                                            text = "স্মার্ট নিউজ ফিড",
                                            style = MaterialTheme.typography.titleMedium.copy(
                                                fontSize = 15.sp,
                                                lineHeight = 20.sp
                                            ),
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Serif,
                                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                                        )
                                    }
                                }

                                itemsIndexed(
                                    items = feedStories,
                                    key = { idx, cov ->
                                        val first = cov.items.firstOrNull()
                                        val baseKey = first?.link?.ifBlank { first.title } ?: "story_$idx"
                                        "feed_${idx}_$baseKey"
                                    }
                                ) { idx, cov ->
                                    val first = cov.items.firstOrNull()
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
    onOpenReader: (Paper, String, String) -> Unit
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

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(16.dp))
            .subtleClickable(pressedScale = 0.985f) {
                onOpenReader(paper, mainItem.link, mainItem.title)
            },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(11.dp)) {
            ArticleCoverImage(
                item = mainItem,
                paper = paper,
                newsRepo = newsRepo,
                fallbackItems = siblingItems,
                shape = RoundedCornerShape(12.dp),
                fallbackAvatarSize = 42.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9.6f)
            )

            Spacer(modifier = Modifier.height(9.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PaperAvatar(
                    paper = paper,
                    size = 15.dp
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
            }

            Spacer(modifier = Modifier.height(5.dp))

            Text(
                text = mainItem.title,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = 17.5.sp,
                    lineHeight = 24.5.sp
                ),
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Serif,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )

            if (multiCoverage) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .subtleClickable(pressedScale = 0.96f) { expanded = !expanded }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.5.dp)
                    ) {
                        Text(
                            text = "এই খবরটি ${BengaliUtils.bnDigits(coverage.count)}টি পত্রিকায়",
                            fontSize = 10.5.sp,
                            lineHeight = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Icon(
                            imageVector = Icons.Default.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier
                                .size(14.dp)
                                .graphicsLayer { rotationZ = arrowRotation },
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
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

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .subtleClickable(pressedScale = 0.984f) {
                onOpenReader(paper, mainItem.link, mainItem.title)
            },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            // A) Top metadata row
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
                            lineHeight = 21.sp
                        ),
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Serif,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

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
                Spacer(modifier = Modifier.height(7.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .subtleClickable(pressedScale = 0.96f) { expanded = !expanded }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.5.dp)
                    ) {
                        Text(
                            text = "এই খবরটি ${BengaliUtils.bnDigits(coverage.count)}টি পত্রিকায়",
                            fontSize = 10.5.sp,
                            lineHeight = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Icon(
                            imageVector = Icons.Default.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier
                                .size(14.dp)
                                .graphicsLayer { rotationZ = arrowRotation },
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
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

