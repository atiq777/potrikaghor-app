package com.potrikaghor.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
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
import com.potrikaghor.model.NewsItem
import com.potrikaghor.model.Paper
import com.potrikaghor.ui.components.EmptyState
import com.potrikaghor.ui.components.MotionTokens
import com.potrikaghor.ui.components.PaperAvatar
import com.potrikaghor.ui.components.SkeletonCard
import com.potrikaghor.ui.components.SubtleContentReveal
import com.potrikaghor.ui.components.pressScaleEffect
import com.potrikaghor.ui.components.rememberReducedMotion
import com.potrikaghor.ui.components.subtleClickable
import com.potrikaghor.ui.util.BengaliUtils
import kotlinx.coroutines.launch

private enum class SearchViewState {
    LOADING,
    EMPTY,
    RESULTS
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    appRepo: AppStateRepository,
    newsRepo: NewsRepository,
    onBack: () -> Unit,
    onOpenReader: (Paper, String, String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val reducedMotion = rememberReducedMotion()
    var query by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("all") }
    var results by remember { mutableStateOf<List<NewsItem>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var hasSearched by remember { mutableStateOf(false) }

    fun doSearch(text: String) {
        if (text.trim().isEmpty()) return
        scope.launch {
            isSearching = true
            hasSearched = true
            try {
                val q = if (selectedCategory != "all") "$text $selectedCategory" else text
                results = newsRepo.fetchNews(query = q)
            } catch (_: Exception) {
                results = emptyList()
            } finally {
                isSearching = false
            }
        }
    }

    val quickTopics = listOf("আজকের শীর্ষ", "বাজেট", "রাজনীতি", "ক্রিকেট", "শেয়ারবাজার", "প্রযুক্তি", "চাকরি", "শিক্ষা")
    val searchFieldInteraction = remember { MutableInteractionSource() }
    val isSearchFocused by searchFieldInteraction.collectIsFocusedAsState()
    val searchFieldScale by animateFloatAsState(
        targetValue = if (isSearchFocused && !reducedMotion) 1.008f else 1f,
        animationSpec = tween(
            durationMillis = if (reducedMotion) 0 else MotionTokens.FAST_MS,
            easing = FastOutSlowInEasing
        ),
        label = "searchFocusScale"
    )
    val searchIconTint by animateColorAsState(
        targetValue = if (isSearchFocused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(
            durationMillis = if (reducedMotion) 0 else MotionTokens.FAST_MS,
            easing = FastOutSlowInEasing
        ),
        label = "searchIconTint"
    )
    val backBtnInteraction = remember { MutableInteractionSource() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "খবর অনুসন্ধান",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontSize = 19.sp,
                            lineHeight = 24.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Serif
                        )
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        interactionSource = backBtnInteraction,
                        modifier = Modifier.pressScaleEffect(backBtnInteraction, pressedScale = 0.90f)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "ফিরে যান")
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
            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it
                    if (it.length >= 3) doSearch(it)
                },
                interactionSource = searchFieldInteraction,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 4.dp)
                    .graphicsLayer {
                        scaleX = searchFieldScale
                        scaleY = searchFieldScale
                    },
                placeholder = { Text("কীওয়ার্ড বা পত্রিকার নাম দিয়ে খুঁজুন...", fontSize = 13.sp) },
                leadingIcon = {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = null,
                        tint = searchIconTint,
                        modifier = Modifier.size(20.dp)
                    )
                },
                trailingIcon = {
                    AnimatedVisibility(
                        visible = query.isNotEmpty(),
                        enter = fadeIn(tween(MotionTokens.FAST_MS)) + scaleIn(tween(MotionTokens.FAST_MS), initialScale = 0.8f),
                        exit = fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.8f)
                    ) {
                        IconButton(onClick = { query = ""; results = emptyList(); hasSearched = false }) {
                            Icon(Icons.Filled.Clear, contentDescription = "মুছুন")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp)
            )

            // Quick Topic Suggestion Chips
            LazyRow(
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(quickTopics, key = { it }) { topic ->
                    val isTopicSelected = query == topic
                    val topicBg by animateColorAsState(
                        targetValue = if (isTopicSelected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        animationSpec = tween(
                            durationMillis = if (reducedMotion) 0 else MotionTokens.FAST_MS,
                            easing = FastOutSlowInEasing
                        ),
                        label = "topicChipBg"
                    )
                    val topicTextColor by animateColorAsState(
                        targetValue = if (isTopicSelected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        animationSpec = tween(
                            durationMillis = if (reducedMotion) 0 else MotionTokens.FAST_MS,
                            easing = FastOutSlowInEasing
                        ),
                        label = "topicChipText"
                    )

                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .subtleClickable(pressedScale = 0.95f) {
                                query = topic
                                doSearch(topic)
                            },
                        shape = RoundedCornerShape(10.dp),
                        color = topicBg
                    ) {
                        Text(
                            text = topic,
                            color = topicTextColor,
                            fontWeight = if (isTopicSelected) FontWeight.Bold else FontWeight.Normal,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 12.sp,
                                lineHeight = 16.sp
                            ),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            val viewState = when {
                isSearching -> SearchViewState.LOADING
                hasSearched && results.isEmpty() -> SearchViewState.EMPTY
                else -> SearchViewState.RESULTS
            }

            Crossfade(
                targetState = viewState,
                animationSpec = tween(
                    durationMillis = if (reducedMotion) 0 else MotionTokens.NORMAL_MS,
                    easing = FastOutSlowInEasing
                ),
                label = "searchBodyCrossfade"
            ) { state ->
                when (state) {
                    SearchViewState.LOADING -> {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(6) { SkeletonCard() }
                        }
                    }

                    SearchViewState.EMPTY -> {
                        EmptyState(
                            icon = Icons.Filled.Search,
                            title = "কোনো ফলাফল পাওয়া যায়নি",
                            body = "'$query' সম্পর্কিত কোনো খবর এই মুহূর্তে পাওয়া যায়নি। অন্য শব্দ দিয়ে চেষ্টা করুন।"
                        )
                    }

                    SearchViewState.RESULTS -> {
                        val seenResultKeys = remember(results) { HashSet<String>() }
                        LazyColumn(
                            contentPadding = PaddingValues(bottom = 24.dp)
                        ) {
                            itemsIndexed(
                                items = results,
                                key = { idx, item -> "search_${idx}_${item.link.ifBlank { item.title }}" }
                            ) { idx, item ->
                                val paper = appRepo.allPapers.firstOrNull { it.bn == item.source || it.en == item.source }
                                    ?: Paper(item.source, "", item.link, "online")
                                val itemKey = item.link.ifBlank { item.title }
                                val isFirstReveal = remember(itemKey) { seenResultKeys.add(itemKey) }

                                SubtleContentReveal(
                                    visibleKey = itemKey,
                                    delayMillis = if (isFirstReveal && idx < 6) idx * 25 else 0,
                                    durationMillis = if (isFirstReveal) MotionTokens.REVEAL_MS else 0,
                                    initialOffsetYDp = if (isFirstReveal) 6.dp else 0.dp
                                ) {
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 14.dp, vertical = 4.dp)
                                            .clip(RoundedCornerShape(14.dp))
                                            .subtleClickable(pressedScale = 0.984f) {
                                                onOpenReader(paper, item.link, item.title)
                                            },
                                        shape = RoundedCornerShape(14.dp),
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                                    ) {
                                        Column(modifier = Modifier.padding(11.dp)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                PaperAvatar(paper = paper, size = 18.dp)
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = item.source,
                                                    style = MaterialTheme.typography.labelMedium.copy(
                                                        fontSize = 11.5.sp,
                                                        lineHeight = 15.sp
                                                    ),
                                                    color = MaterialTheme.colorScheme.primary,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                Spacer(modifier = Modifier.weight(1f))
                                                if (item.time != null) {
                                                    Text(
                                                        text = BengaliUtils.bnAgo(item.time),
                                                        style = MaterialTheme.typography.bodySmall.copy(
                                                            fontSize = 11.sp,
                                                            lineHeight = 14.sp
                                                        ),
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            }

                                            Spacer(modifier = Modifier.height(6.dp))

                                            Text(
                                                text = item.title,
                                                style = MaterialTheme.typography.bodyMedium.copy(
                                                    fontSize = 15.sp,
                                                    lineHeight = 21.sp
                                                ),
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = FontFamily.Serif
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

