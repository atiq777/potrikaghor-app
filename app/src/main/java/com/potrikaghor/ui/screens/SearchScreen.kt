package com.potrikaghor.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
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

private val SearchDivisions = listOf("ঢাকা", "চট্টগ্রাম", "রাজশাহী", "খুলনা", "বরিশাল", "সিলেট", "রংপুর", "ময়মনসিংহ")

private val SearchPopularDistricts = listOf("কুমিল্লা", "গাজীপুর", "নারায়ণগঞ্জ", "বগুড়া", "কক্সবাজার", "যশোর")

private val SearchAllDistricts: List<Pair<String, List<String>>> = listOf(
    "ঢাকা বিভাগ" to listOf("ঢাকা", "গাজীপুর", "কিশোরগঞ্জ", "মানিকগঞ্জ", "মুন্সিগঞ্জ", "নারায়ণগঞ্জ", "নরসিংদী", "টাঙ্গাইল", "ফরিদপুর", "গোপালগঞ্জ", "মাদারীপুর", "রাজবাড়ী", "শরীয়তপুর"),
    "চট্টগ্রাম বিভাগ" to listOf("চট্টগ্রাম", "কক্সবাজার", "কুমিল্লা", "ব্রাহ্মণবাড়িয়া", "চাঁদপুর", "ফেনী", "লক্ষ্মীপুর", "নোয়াখালী", "খাগড়াছড়ি", "রাঙামাটি", "বান্দরবান"),
    "রাজশাহী বিভাগ" to listOf("রাজশাহী", "বগুড়া", "জয়পুরহাট", "নওগাঁ", "নাটোর", "চাঁপাইনবাবগঞ্জ", "পাবনা", "সিরাজগঞ্জ"),
    "খুলনা বিভাগ" to listOf("খুলনা", "বাগেরহাট", "চুয়াডাঙ্গা", "যশোর", "ঝিনাইদহ", "কুষ্টিয়া", "মাগুরা", "মেহেরপুর", "নড়াইল", "সাতক্ষীরা"),
    "বরিশাল বিভাগ" to listOf("বরিশাল", "বরগুনা", "ভোলা", "ঝালকাঠি", "পটুয়াখালী", "পিরোজপুর"),
    "সিলেট বিভাগ" to listOf("সিলেট", "হবিগঞ্জ", "মৌলভীবাজার", "সুনামগঞ্জ"),
    "রংপুর বিভাগ" to listOf("রংপুর", "দিনাজপুর", "গাইবান্ধা", "কুড়িগ্রাম", "লালমনিরহাট", "নীলফামারী", "পঞ্চগড়", "ঠাকুরগাঁও"),
    "ময়মনসিংহ বিভাগ" to listOf("ময়মনসিংহ", "জামালপুর", "নেত্রকোণা", "শেরপুর")
)

@Composable
private fun SearchSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 6.dp, bottom = 4.dp)
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchPlaceChips(
    label: String,
    places: List<String>,
    query: String,
    onPick: (String) -> Unit,
    trailingLabel: String? = null,
    onTrailingClick: (() -> Unit)? = null
) {
    SearchSectionLabel(label)
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        places.forEach { place ->
            val isSelected = query == place
            FilterChip(
                selected = isSelected,
                onClick = { onPick(place) },
                label = { Text(place, fontSize = 12.sp) },
                shape = RoundedCornerShape(14.dp),
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                ),
                border = null
            )
        }
        if (trailingLabel != null && onTrailingClick != null) {
            FilterChip(
                selected = false,
                onClick = onTrailingClick,
                label = { Text(trailingLabel, fontSize = 12.sp) },
                shape = RoundedCornerShape(14.dp),
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    labelColor = MaterialTheme.colorScheme.onSecondaryContainer
                ),
                border = null
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AllDistrictsDialog(
    onDismiss: () -> Unit,
    onPick: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("বন্ধ করুন") }
        },
        title = { Text("সব জেলা") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                SearchAllDistricts.forEach { (division, districts) ->
                    Text(
                        text = division,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        districts.forEach { district ->
                            FilterChip(
                                selected = false,
                                onClick = { onPick(district) },
                                label = { Text(district, fontSize = 12.sp) },
                                shape = RoundedCornerShape(14.dp),
                                border = null
                            )
                        }
                    }
                }
            }
        }
    )
}

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
    val appState by appRepo.state.collectAsState()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val searchFieldInteraction = remember { MutableInteractionSource() }

    var query by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("all") }
    var results by remember { mutableStateOf<List<NewsItem>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var hasSearched by remember { mutableStateOf(false) }
    var showAllDistricts by remember { mutableStateOf(false) }
    var isSearchSuggestionsOpen by remember { mutableStateOf(false) }

    val filteredHistory = remember(appState.searchHistory, query) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            appState.searchHistory.take(8)
        } else {
            appState.searchHistory
                .filter { it.contains(trimmed, ignoreCase = true) && !it.equals(trimmed, ignoreCase = true) }
                .take(8)
        }
    }

    LaunchedEffect(searchFieldInteraction) {
        searchFieldInteraction.interactions.collect { interaction ->
            if (interaction is PressInteraction.Release) {
                isSearchSuggestionsOpen = true
            }
        }
    }

    BackHandler(enabled = isSearchSuggestionsOpen) {
        isSearchSuggestionsOpen = false
        focusManager.clearFocus()
    }

    fun doSearch(text: String, saveToHistory: Boolean = false) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        if (saveToHistory) {
            appRepo.addSearchQuery(trimmed)
            isSearchSuggestionsOpen = false
            focusManager.clearFocus()
            keyboardController?.hide()
        }
        scope.launch {
            isSearching = true
            hasSearched = true
            try {
                val q = if (selectedCategory != "all") "$trimmed $selectedCategory" else trimmed
                results = newsRepo.fetchNews(query = q)
            } catch (_: Exception) {
                results = emptyList()
            } finally {
                isSearching = false
            }
        }
    }

    val quickTopics = listOf("আজকের শীর্ষ", "বাজেট", "রাজনীতি", "ক্রিকেট", "শেয়ারবাজার", "প্রযুক্তি", "চাকরি", "শিক্ষা")
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
                        modifier = Modifier
                            .size(38.dp)
                            .pressScaleEffect(backBtnInteraction, pressedScale = 0.90f)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "ফিরে যান")
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
            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it
                    isSearchSuggestionsOpen = true
                    if (it.length >= 3) doSearch(it, saveToHistory = false)
                },
                interactionSource = searchFieldInteraction,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 6.dp)
                    .onFocusChanged { focusState ->
                        if (focusState.isFocused) {
                            isSearchSuggestionsOpen = true
                        }
                    }
                    .testTag("search_input"),
                placeholder = { Text("কীওয়ার্ড বা পত্রিকার নাম দিয়ে খুঁজুন...", fontSize = 13.5.sp) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                query = ""
                                results = emptyList()
                                hasSearched = false
                                isSearchSuggestionsOpen = true
                            },
                            modifier = Modifier.testTag("search_clear_button")
                        ) {
                            Icon(Icons.Filled.Clear, contentDescription = "মুছুন")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { doSearch(query, saveToHistory = true) })
            )

            AnimatedVisibility(
                visible = isSearchSuggestionsOpen && filteredHistory.isNotEmpty(),
                enter = fadeIn(tween(if (reducedMotion) 0 else MotionTokens.FAST_MS)) +
                    expandVertically(tween(if (reducedMotion) 0 else MotionTokens.NORMAL_MS)),
                exit = fadeOut(tween(if (reducedMotion) 0 else MotionTokens.FAST_MS)) +
                    shrinkVertically(tween(if (reducedMotion) 0 else MotionTokens.FAST_MS))
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 4.dp)
                        .testTag("search_history_dropdown"),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.History,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "সাম্প্রতিক অনুসন্ধান",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = {
                                    appRepo.clearSearchHistory()
                                    isSearchSuggestionsOpen = false
                                },
                                modifier = Modifier.testTag("clear_search_history_button")
                            ) {
                                Text("সব মুছুন", fontSize = 12.sp)
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 220.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            filteredHistory.forEachIndexed { idx, historyItem ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 48.dp)
                                        .clickable {
                                            query = historyItem
                                            doSearch(historyItem, saveToHistory = true)
                                        }
                                        .testTag("search_history_item_$idx")
                                        .padding(start = 14.dp, end = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.History,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = historyItem,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(
                                        onClick = { appRepo.removeSearchQuery(historyItem) },
                                        modifier = Modifier
                                            .size(48.dp)
                                            .testTag("remove_search_history_item_$idx")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Close,
                                            contentDescription = "ইতিহাস থেকে মুছুন",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                                if (idx < filteredHistory.lastIndex) {
                                    HorizontalDivider(
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                                        modifier = Modifier.padding(horizontal = 14.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Quick Topic Suggestion Chips
            SearchSectionLabel("বিষয়")
            LazyRow(
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                items(quickTopics, key = { it }) { topic ->
                    val isTopicSelected = query == topic
                    val chipInteraction = remember { MutableInteractionSource() }
                    val chipBg by animateColorAsState(
                        targetValue = if (isTopicSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        animationSpec = tween(
                            durationMillis = if (reducedMotion) 0 else MotionTokens.NORMAL_MS,
                            easing = FastOutSlowInEasing
                        ),
                        label = "searchTopicBg"
                    )
                    val chipText by animateColorAsState(
                        targetValue = if (isTopicSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        animationSpec = tween(
                            durationMillis = if (reducedMotion) 0 else MotionTokens.FAST_MS,
                            easing = FastOutSlowInEasing
                        ),
                        label = "searchTopicText"
                    )

                    FilterChip(
                        selected = isTopicSelected,
                        onClick = {
                            query = topic
                            doSearch(topic, saveToHistory = true)
                        },
                        interactionSource = chipInteraction,
                        modifier = Modifier.pressScaleEffect(chipInteraction, pressedScale = 0.95f),
                        label = { Text(topic, fontSize = 12.sp) },
                        shape = RoundedCornerShape(14.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = chipBg,
                            selectedLabelColor = chipText,
                            containerColor = chipBg,
                            labelColor = chipText
                        ),
                        border = null
                    )
                }
            }

            // Place shortcuts: divisions and popular districts. Any other place can be typed in the search box.
            SearchPlaceChips(
                label = "বিভাগ",
                places = SearchDivisions,
                query = query,
                onPick = { place ->
                    query = place
                    doSearch(place, saveToHistory = true)
                }
            )
            SearchPlaceChips(
                label = "জেলা",
                places = SearchPopularDistricts,
                query = query,
                onPick = { place ->
                    query = place
                    doSearch(place, saveToHistory = true)
                },
                trailingLabel = "সব জেলা ›",
                onTrailingClick = { showAllDistricts = true }
            )
            if (showAllDistricts) {
                AllDistrictsDialog(
                    onDismiss = { showAllDistricts = false },
                    onPick = { place ->
                        showAllDistricts = false
                        query = place
                        doSearch(place, saveToHistory = true)
                    }
                )
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
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 24.dp)
                        ) {
                            item(key = "search_branded_loader") {
                                com.potrikaghor.ui.components.BrandedLoadingIndicator(
                                    message = "খবর লোড হচ্ছে...",
                                    logoSize = 68.dp
                                )
                            }
                            items(4, key = { "search_skeleton_$it" }) {
                                SkeletonCard()
                            }
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
                            if (hasSearched && results.isNotEmpty()) {
                                item(key = "search_results_header") {
                                    Text(
                                        text = "‘$query’ নিয়ে খবর",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                                    )
                                }
                            }
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
                                            .clip(RoundedCornerShape(12.dp))
                                            .subtleClickable(pressedScale = 0.985f) {
                                                if (query.isNotBlank()) {
                                                    appRepo.addSearchQuery(query)
                                                }
                                                isSearchSuggestionsOpen = false
                                                onOpenReader(paper, item.link, item.title)
                                            },
                                        shape = RoundedCornerShape(12.dp),
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
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
                                                    lineHeight = 20.5.sp
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
