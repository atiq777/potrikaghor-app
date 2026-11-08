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
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.potrikaghor.data.AppStateRepository
import com.potrikaghor.data.NewsRepository
import com.potrikaghor.model.Coverage
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

private enum class CoverageBodyState {
    LOADING,
    ERROR,
    EMPTY,
    CONTENT
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
    var isIntl by remember { mutableStateOf(false) }
    var coverageList by remember { mutableStateOf<List<Coverage>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    fun loadCoverage() {
        scope.launch {
            isLoading = true
            errorMsg = null
            try {
                coverageList = newsRepo.fetchCoverage(isIntl)
            } catch (e: Exception) {
                errorMsg = "কভারেজ লোড করা যায়নি। ইন্টারনেট সংযোগ পরীক্ষা করুন।"
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(isIntl) {
        loadCoverage()
    }

    val appState by appRepo.state.collectAsState()
    val filteredCoverageList = remember(coverageList, appState.mutedKeywords) {
        if (appState.mutedKeywords.isEmpty()) {
            coverageList
        } else {
            coverageList.filter { cov ->
                cov.items.none { appRepo.isMuted(it.title, it.snippet) }
            }
        }
    }
    val manyCoverage = remember(filteredCoverageList) { filteredCoverageList.filter { it.count >= 3 } }
    val fewCoverage = remember(filteredCoverageList) { filteredCoverageList.filter { it.count == 2 } }

    val refreshRotation = if (isLoading && !reducedMotion) {
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
                        onClick = { if (!isLoading) loadCoverage() },
                        enabled = !isLoading,
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
                    onClick = { isIntl = false },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                ) {
                    Text("বাংলাদেশ সংস্করণ", fontSize = 12.5.sp)
                }
                SegmentedButton(
                    selected = isIntl,
                    onClick = { isIntl = true },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                ) {
                    Text("আন্তর্জাতিক", fontSize = 12.5.sp)
                }
            }

            val bodyState = when {
                isLoading && coverageList.isEmpty() -> CoverageBodyState.LOADING
                errorMsg != null && coverageList.isEmpty() -> CoverageBodyState.ERROR
                coverageList.isEmpty() -> CoverageBodyState.EMPTY
                else -> CoverageBodyState.CONTENT
            }

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
                            body = errorMsg ?: "",
                            actionLabel = "আবার চেষ্টা করুন",
                            onAction = { loadCoverage() }
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
                            contentPadding = PaddingValues(bottom = 82.dp, top = 4.dp)
                        ) {
                            if (manyCoverage.isNotEmpty()) {
                                item {
                                    Text(
                                        text = "বেশি কভার হওয়া খবর (৩+ পত্রিকায়)",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontFamily = FontFamily.Serif,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                                    )
                                }

                                items(manyCoverage) { item ->
                                    CoverageDetailCard(
                                        coverage = item,
                                        appRepo = appRepo,
                                        onOpenReader = onOpenReader
                                    )
                                }
                            }

                            if (fewCoverage.isNotEmpty()) {
                                item {
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

                                items(fewCoverage) { item ->
                                    CoverageDetailCard(
                                        coverage = item,
                                        appRepo = appRepo,
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

@Composable
fun CoverageDetailCard(
    coverage: Coverage,
    appRepo: AppStateRepository,
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

            val appState by appRepo.state.collectAsState()
            coverage.items.forEachIndexed { idx, itm ->
                val paper = appRepo.allPapers.firstOrNull { it.bn == itm.source || it.en == itm.source }
                    ?: Paper(itm.source, "", itm.link, "online")
                val isRead = remember(appState.readArticles, itm.link, itm.title) {
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
