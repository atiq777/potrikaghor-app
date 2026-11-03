package com.potrikaghor.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.potrikaghor.data.AppStateRepository
import com.potrikaghor.data.PapersData
import com.potrikaghor.model.Paper
import com.potrikaghor.ui.components.EmptyState
import com.potrikaghor.ui.components.MotionTokens
import com.potrikaghor.ui.components.PaperAvatar
import com.potrikaghor.ui.components.pressScaleEffect
import com.potrikaghor.ui.components.rememberReducedMotion
import com.potrikaghor.ui.components.subtleClickable
import com.potrikaghor.ui.theme.FlagRed
import com.potrikaghor.ui.util.BengaliUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyPapersScreen(
    appRepo: AppStateRepository,
    onOpenReader: (Paper, String) -> Unit,
    onAddPaper: () -> Unit,
    onFixUrl: (Paper) -> Unit
) {
    val appState by appRepo.state.collectAsState()
    val reducedMotion = rememberReducedMotion()
    var selectedCat by remember { mutableStateOf("all") }
    var searchQuery by remember { mutableStateOf("") }

    val allList = appRepo.allPapers
    val filteredList = remember(allList, selectedCat, searchQuery, appState.onlyShowFavorites) {
        allList.filter { p ->
            val matchCat = when (selectedCat) {
                "all" -> true
                "mine" -> p.custom
                "fav" -> appState.favs.contains(p.url)
                else -> p.cat == selectedCat
            }
            val matchQuery = if (searchQuery.trim().isEmpty()) true else {
                val q = searchQuery.trim().lowercase()
                p.bn.lowercase().contains(q) || p.en.lowercase().contains(q) || p.url.lowercase().contains(q)
            }
            matchCat && matchQuery
        }
    }

    val viewModeInteraction = remember { MutableInteractionSource() }
    val fabInteraction = remember { MutableInteractionSource() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "আমার পত্রিকা",
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontSize = 19.sp,
                                    lineHeight = 24.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Serif
                                )
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(7.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer
                            ) {
                                Text(
                                    text = "${BengaliUtils.bnDigits(filteredList.size)}টি",
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontSize = 11.sp,
                                        lineHeight = 14.sp
                                    ),
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        Text(
                            text = "পছন্দের সোর্স ও ব্যক্তিগত রিডিং বোর্ড",
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
                        onClick = { appRepo.setGridView(!appState.gridView) },
                        interactionSource = viewModeInteraction,
                        modifier = Modifier
                            .size(38.dp)
                            .pressScaleEffect(viewModeInteraction, pressedScale = 0.90f)
                    ) {
                        Crossfade(
                            targetState = appState.gridView,
                            animationSpec = tween(
                                durationMillis = if (reducedMotion) 0 else MotionTokens.FAST_MS,
                                easing = FastOutSlowInEasing
                            ),
                            label = "gridListToggleIcon"
                        ) { isGrid ->
                            Icon(
                                imageVector = if (isGrid) Icons.Filled.ViewAgenda else Icons.Filled.GridView,
                                contentDescription = "ভিউ মোড"
                            )
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddPaper,
                interactionSource = fabInteraction,
                modifier = Modifier.pressScaleEffect(fabInteraction, pressedScale = 0.95f),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Row(modifier = Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(5.dp))
                    Text("পত্রিকা যোগ করুন", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 4.dp),
                placeholder = { Text("পত্রিকার নাম বা লিংক খুঁজুন...", fontSize = 13.5.sp) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Filled.Clear, contentDescription = "মুছুন")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp)
            )

            // Category Chips Row
            val availableCategories = listOf(
                Pair("all", "সব"),
                Pair("fav", "প্রিয় (${BengaliUtils.bnDigits(appState.favs.size)})"),
                Pair("mine", "নিজের যোগ করা (${BengaliUtils.bnDigits(appState.customPapers.size)})")
            ) + PapersData.categories.drop(1).map { Pair(it.id, it.label) }

            LazyRow(
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                items(availableCategories, key = { it.first }) { (id, label) ->
                    val isSelected = selectedCat == id
                    val chipInteraction = remember { MutableInteractionSource() }
                    val chipBg by animateColorAsState(
                        targetValue = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        animationSpec = tween(
                            durationMillis = if (reducedMotion) 0 else MotionTokens.NORMAL_MS,
                            easing = FastOutSlowInEasing
                        ),
                        label = "paperCatChipBg"
                    )
                    val chipText by animateColorAsState(
                        targetValue = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        animationSpec = tween(
                            durationMillis = if (reducedMotion) 0 else MotionTokens.FAST_MS,
                            easing = FastOutSlowInEasing
                        ),
                        label = "paperCatChipText"
                    )

                    FilterChip(
                        selected = isSelected,
                        onClick = { selectedCat = id },
                        interactionSource = chipInteraction,
                        modifier = Modifier.pressScaleEffect(chipInteraction, pressedScale = 0.95f),
                        label = {
                            Text(
                                text = label,
                                fontSize = 12.5.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = chipBg,
                            selectedLabelColor = chipText,
                            containerColor = chipBg,
                            labelColor = chipText
                        ),
                        shape = RoundedCornerShape(16.dp),
                        border = null
                    )
                }
            }

            // Only favorites toggle row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "শুধু পছন্দের পত্রিকা প্রদর্শন",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Switch(
                    checked = appState.onlyShowFavorites,
                    onCheckedChange = { appRepo.setOnlyShowFavorites(it) }
                )
            }

            val isAppReady by appRepo.isReady.collectAsState()
            val bodyState = when {
                !isAppReady -> "loading"
                filteredList.isEmpty() -> "empty"
                appState.gridView -> "grid"
                else -> "list"
            }

            Crossfade(
                targetState = bodyState,
                animationSpec = tween(
                    durationMillis = if (reducedMotion) 0 else MotionTokens.NORMAL_MS,
                    easing = FastOutSlowInEasing
                ),
                label = "myPapersBodyCrossfade"
            ) { state ->
                when (state) {
                    "loading" -> {
                        com.potrikaghor.ui.components.BrandedLoadingIndicator(
                            message = "পত্রিকা প্রস্তুত হচ্ছে...",
                            logoSize = 72.dp,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    "empty" -> {
                        EmptyState(
                            icon = Icons.Filled.Star,
                            title = "কোনো পত্রিকা মেলেনি",
                            body = "আপনার অনুসন্ধান বা নির্বাচিত ক্যাটাগরিতে কোনো পত্রিকা পাওয়া যায়নি।",
                            actionLabel = "সব দেখুন",
                            onAction = {
                                selectedCat = "all"
                                searchQuery = ""
                                appRepo.setOnlyShowFavorites(false)
                            }
                        )
                    }
                    "grid" -> {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 82.dp, top = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(filteredList, key = { it.url }) { paper ->
                                MyPaperGridItem(
                                    paper = paper,
                                    isFav = appState.favs.contains(paper.url),
                                    onToggleFav = { appRepo.toggleFav(paper.url) },
                                    onOpen = { onOpenReader(paper, paper.openUrl) },
                                    onFixUrl = { onFixUrl(paper) }
                                )
                            }
                        }
                    }
                    else -> {
                        LazyColumn(
                            contentPadding = PaddingValues(bottom = 82.dp, top = 4.dp)
                        ) {
                            items(filteredList, key = { it.url }) { paper ->
                                MyPaperListItem(
                                    paper = paper,
                                    isFav = appState.favs.contains(paper.url),
                                    onToggleFav = { appRepo.toggleFav(paper.url) },
                                    onOpen = { onOpenReader(paper, paper.openUrl) },
                                    onFixUrl = { onFixUrl(paper) },
                                    onToggleHide = { appRepo.toggleHidePaper(paper.url) }
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
fun MyPaperListItem(
    paper: Paper,
    isFav: Boolean,
    onToggleFav: () -> Unit,
    onOpen: () -> Unit,
    onFixUrl: () -> Unit,
    onToggleHide: () -> Unit
) {
    val reducedMotion = rememberReducedMotion()
    val favInteraction = remember { MutableInteractionSource() }
    val editInteraction = remember { MutableInteractionSource() }
    val starTint by animateColorAsState(
        targetValue = if (isFav) FlagRed else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(
            durationMillis = if (reducedMotion) 0 else MotionTokens.FAST_MS,
            easing = FastOutSlowInEasing
        ),
        label = "listStarTint"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 3.5.dp)
            .clip(RoundedCornerShape(12.dp))
            .subtleClickable(pressedScale = 0.985f, onClick = onOpen),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PaperAvatar(paper = paper, size = 38.dp)
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = paper.bn,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontSize = 15.sp,
                            lineHeight = 20.sp
                        ),
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Serif
                    )
                    if (paper.custom) {
                        Spacer(modifier = Modifier.width(5.dp))
                        Surface(
                            shape = RoundedCornerShape(5.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Text(
                                text = "কাস্টম",
                                fontSize = 9.5.sp,
                                lineHeight = 12.sp,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.5.dp),
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
                Text(
                    text = if (paper.en.isNotEmpty()) "${paper.en} • ${paper.host}" else paper.host,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 11.sp,
                        lineHeight = 14.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            IconButton(
                onClick = onFixUrl,
                interactionSource = editInteraction,
                modifier = Modifier
                    .size(36.dp)
                    .pressScaleEffect(editInteraction, pressedScale = 0.88f)
            ) {
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = "লিংক ঠিক করুন",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }

            IconButton(
                onClick = onToggleFav,
                interactionSource = favInteraction,
                modifier = Modifier
                    .size(36.dp)
                    .pressScaleEffect(favInteraction, pressedScale = 0.86f)
            ) {
                Crossfade(
                    targetState = isFav,
                    animationSpec = tween(
                        durationMillis = if (reducedMotion) 0 else MotionTokens.FAST_MS,
                        easing = FastOutSlowInEasing
                    ),
                    label = "listStarCrossfade"
                ) { favored ->
                    Icon(
                        imageVector = if (favored) Icons.Filled.Star else Icons.Outlined.StarBorder,
                        contentDescription = "পছন্দের তালিকা",
                        tint = starTint,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun MyPaperGridItem(
    paper: Paper,
    isFav: Boolean,
    onToggleFav: () -> Unit,
    onOpen: () -> Unit,
    onFixUrl: () -> Unit
) {
    val reducedMotion = rememberReducedMotion()
    val favInteraction = remember { MutableInteractionSource() }
    val starTint by animateColorAsState(
        targetValue = if (isFav) FlagRed else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(
            durationMillis = if (reducedMotion) 0 else MotionTokens.FAST_MS,
            easing = FastOutSlowInEasing
        ),
        label = "gridStarTint"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .subtleClickable(pressedScale = 0.98f, onClick = onOpen),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                PaperAvatar(paper = paper, size = 34.dp)
                IconButton(
                    onClick = onToggleFav,
                    interactionSource = favInteraction,
                    modifier = Modifier
                        .size(28.dp)
                        .pressScaleEffect(favInteraction, pressedScale = 0.86f)
                ) {
                    Crossfade(
                        targetState = isFav,
                        animationSpec = tween(
                            durationMillis = if (reducedMotion) 0 else MotionTokens.FAST_MS,
                            easing = FastOutSlowInEasing
                        ),
                        label = "gridStarCrossfade"
                    ) { favored ->
                        Icon(
                            imageVector = if (favored) Icons.Filled.Star else Icons.Outlined.StarBorder,
                            contentDescription = null,
                            tint = starTint,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(7.dp))
            Text(
                text = paper.bn,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = 14.5.sp,
                    lineHeight = 19.sp
                ),
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Serif,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = paper.host,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 11.sp,
                    lineHeight = 14.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
