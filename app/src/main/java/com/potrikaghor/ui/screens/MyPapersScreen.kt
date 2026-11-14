package com.potrikaghor.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material.icons.filled.History
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
import com.potrikaghor.data.DirectoryRepository
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
import java.net.URI

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
    var selectedDivision by remember { mutableStateOf("all") }
    var selectedDistrict by remember { mutableStateOf("all") }
    var searchQuery by remember { mutableStateOf("") }

    val allList = appRepo.allPapers
    val directoryEntries = remember { DirectoryRepository.getAllEntries() }
    val divisionByUrl = remember(directoryEntries) {
        directoryEntries.associate { it.websiteUrl to it.division }
    }
    val districtByUrl = remember(directoryEntries) {
        directoryEntries.associate { it.websiteUrl to it.district }
    }

    val allDirectoryAndCustom = remember(allList, directoryEntries, appState.hiddenPapers, appState.urlFixes) {
        val customPapers = allList.filter { it.custom }
        val builtInByNormalizedUrl = PapersData.builtInPapers.associateBy { normalizeLookupUrl(it.url) }
        val builtInByUniqueHost = PapersData.builtInPapers
            .groupBy { normalizeLookupHost(it.url) }
            .filter { (host, list) -> host.isNotEmpty() && list.size == 1 }
            .mapValues { it.value.first() }

        val mappedDirectory = directoryEntries
            .filter { !appState.hiddenPapers.contains(it.websiteUrl) }
            .map { dir ->
                val normalizedCat = when (dir.primaryCategory) {
                    "bu" -> "biz"
                    "on" -> "online"
                    "te" -> "tech"
                    "ed" -> "edu"
                    "sp" -> "sports"
                    "jo" -> "jobs"
                    "ko" -> "kol"
                    "lo" -> "local"
                    "ma" -> "mag"
                    "bl" -> "blog"
                    "in" -> "intl"
                    else -> dir.primaryCategory
                }
                val builtInMatch = builtInByNormalizedUrl[normalizeLookupUrl(dir.websiteUrl)]
                    ?: builtInByUniqueHost[normalizeLookupHost(dir.websiteUrl)]
                Paper(
                    cat = normalizedCat,
                    bn = builtInMatch?.bn ?: dir.newspaperName,
                    en = dir.newspaperName,
                    url = dir.websiteUrl,
                    custom = false,
                    fix = appState.urlFixes[dir.websiteUrl]
                )
            }
        customPapers + mappedDirectory
    }

    val allPapersByUrl = remember(allDirectoryAndCustom) {
        allDirectoryAndCustom.associateBy { it.url }
    }

    val recentPapers = remember(appState.recents, allPapersByUrl) {
        appState.recents
            .mapNotNull { recentUrl -> allPapersByUrl[recentUrl] ?: appRepo.paperFor(recentUrl) }
            .distinctBy { it.url }
    }

    val combinedList = remember(allDirectoryAndCustom, appState.onlyShowFavorites, appState.favs) {
        if (appState.onlyShowFavorites) {
            allDirectoryAndCustom.filter { appState.favs.contains(it.url) }
        } else {
            allDirectoryAndCustom
        }
    }

    val filteredList = remember(combinedList, selectedCat, selectedDivision, selectedDistrict, searchQuery, appState.favs) {
        combinedList.filter { p ->
            val matchCat = when (selectedCat) {
                "all" -> true
                "mine" -> p.custom
                "fav" -> appState.favs.contains(p.url)
                else -> p.cat == selectedCat
            }
            val matchDiv = if (selectedCat != "local" || selectedDivision == "all") {
                true
            } else {
                divisionByUrl[p.url] == selectedDivision
            }
            val matchDist = if (selectedCat != "local" || selectedDistrict == "all") {
                true
            } else {
                districtByUrl[p.url] == selectedDistrict
            }
            val matchQuery = if (searchQuery.trim().isEmpty()) true else {
                matchesBilingualPaperSearch(
                    paper = p,
                    query = searchQuery,
                    division = divisionByUrl[p.url],
                    district = districtByUrl[p.url]
                )
            }
            matchCat && matchDiv && matchDist && matchQuery
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
                placeholder = { Text("পত্রিকার নাম (বাংলা/English) বা লিংক খুঁজুন...", fontSize = 13.5.sp) },
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

            // Recently Read Row ("সম্প্রতি পড়া")
            if (recentPapers.isNotEmpty() && searchQuery.isBlank()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp, bottom = 4.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.History,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "সম্প্রতি পড়া",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(recentPapers, key = { "recent_${it.url}" }) { recentPaper ->
                            Surface(
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .widthIn(max = 190.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .subtleClickable(pressedScale = 0.97f) {
                                        appRepo.addRecent(recentPaper.url)
                                        onOpenReader(recentPaper, recentPaper.openUrl)
                                    },
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    PaperAvatar(paper = recentPaper, size = 26.dp)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = recentPaper.bn,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }

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
                        onClick = {
                            selectedCat = id
                            if (id != "local") {
                                selectedDivision = "all"
                                selectedDistrict = "all"
                            }
                        },
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

            // Division & District Filter Rows (shown when Local category is selected)
            if (selectedCat == "local") {
                val divisions = listOf(
                    "all" to "সব বিভাগ",
                    "Dhaka" to "ঢাকা",
                    "Chittagong" to "চট্টগ্রাম",
                    "Rajshahi" to "রাজশাহী",
                    "Khulna" to "খুলনা",
                    "Barisal" to "বরিশাল",
                    "Sylhet" to "সিলেট",
                    "Rangpur" to "রংপুর",
                    "Mymensingh" to "ময়মনসিংহ"
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    items(divisions, key = { it.first }) { (divId, divLabel) ->
                        FilterChip(
                            selected = selectedDivision == divId,
                            onClick = {
                                selectedDivision = divId
                                selectedDistrict = "all"
                            },
                            label = { Text(divLabel, fontSize = 11.5.sp) },
                            shape = RoundedCornerShape(14.dp)
                        )
                    }
                }

                val availableDistricts = remember(directoryEntries, selectedDivision) {
                    directoryEntries
                        .asSequence()
                        .filter { !it.district.isNullOrBlank() && (selectedDivision == "all" || it.division == selectedDivision) }
                        .mapNotNull { it.district }
                        .distinct()
                        .sortedBy { DISTRICT_LABELS_BN[it] ?: it }
                        .toList()
                }
                if (availableDistricts.isNotEmpty()) {
                    val districtChips = remember(availableDistricts) {
                        listOf("all" to "সব জেলা") + availableDistricts.map { it to (DISTRICT_LABELS_BN[it] ?: it) }
                    }
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        items(districtChips, key = { it.first }) { (distId, distLabel) ->
                            FilterChip(
                                selected = selectedDistrict == distId,
                                onClick = { selectedDistrict = distId },
                                label = { Text(distLabel, fontSize = 11.5.sp) },
                                shape = RoundedCornerShape(14.dp)
                            )
                        }
                    }
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
                                selectedDivision = "all"
                                selectedDistrict = "all"
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
                                    onOpen = {
                                        appRepo.addRecent(paper.url)
                                        onOpenReader(paper, paper.openUrl)
                                    },
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
                                    onOpen = {
                                        appRepo.addRecent(paper.url)
                                        onOpenReader(paper, paper.openUrl)
                                    },
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

private val DISTRICT_LABELS_BN = mapOf(
    "Bagerhat" to "বাগেরহাট",
    "Bandarban" to "বান্দরবান",
    "Barisal" to "বরিশাল",
    "Bhola" to "ভোলা",
    "Bogra" to "বগুড়া",
    "Brahmanbaria" to "ব্রাহ্মণবাড়িয়া",
    "Chandpur" to "চাঁদপুর",
    "Chapainawabganj" to "চাঁপাইনবাবগঞ্জ",
    "Chittagong" to "চট্টগ্রাম",
    "Chuadanga" to "চুয়াডাঙ্গা",
    "Comilla" to "কুমিল্লা",
    "Cox's Bazar" to "কক্সবাজার",
    "Dhaka" to "ঢাকা",
    "Dinajpur" to "দিনাজপুর",
    "Feni" to "ফেনী",
    "Gaibandha" to "গাইবান্ধা",
    "Gazipur" to "গাজীপুর",
    "Gopalganj" to "গোপালগঞ্জ",
    "Habiganj" to "হবিগঞ্জ",
    "Jamalpur" to "জামালপুর",
    "Jessore" to "যশোর",
    "Jhenaidah" to "ঝিনাইদহ",
    "Khulna" to "খুলনা",
    "Kishoreganj" to "কিশোরগঞ্জ",
    "Kurigram" to "কুড়িগ্রাম",
    "Kushtia" to "কুষ্টিয়া",
    "Lakshmipur" to "লক্ষ্মীপুর",
    "Lalmonirhat" to "লালমনিরহাট",
    "Magura" to "মাগুরা",
    "Meherpur" to "মেহেরপুর",
    "Moulvibazar" to "মৌলভীবাজার",
    "Munshiganj" to "মুন্সিগঞ্জ",
    "Mymensingh" to "ময়মনসিংহ",
    "Naogaon" to "নওগাঁ",
    "Narail" to "নড়াইল",
    "Narayanganj" to "নারায়ণগঞ্জ",
    "Narsingdi" to "নরসিংদী",
    "Natore" to "নাটোর",
    "Netrokona" to "নেত্রকোণা",
    "Nilphamari" to "নীলফামারী",
    "Pabna" to "পাবনা",
    "Panchagarh" to "পঞ্চগড়",
    "Patuakhali" to "পটুয়াখালী",
    "Pirojpur" to "পিরোজপুর",
    "Rajshahi" to "রাজশাহী",
    "Rangamati" to "রাঙ্গামাটি",
    "Rangpur" to "রংপুর",
    "Satkhira" to "সাতক্ষীরা",
    "Shariatpur" to "শরীয়তপুর",
    "Sherpur" to "শেরপুর",
    "Sirajganj" to "সিরাজগঞ্জ",
    "Sunamganj" to "সুনামগঞ্জ",
    "Sylhet" to "সিলেট",
    "Tangail" to "টাঙ্গাইল"
)

private val DIVISION_LABELS_BN = mapOf(
    "Dhaka" to "ঢাকা",
    "Chittagong" to "চট্টগ্রাম",
    "Rajshahi" to "রাজশাহী",
    "Khulna" to "খুলনা",
    "Barisal" to "বরিশাল",
    "Sylhet" to "সিলেট",
    "Rangpur" to "রংপুর",
    "Mymensingh" to "ময়মনসিংহ"
)

private fun normalizeLookupUrl(url: String): String =
    url.trim().trimEnd('/').lowercase()
        .replace("http://", "https://")
        .replace("://www.", "://")

private fun normalizeLookupHost(url: String): String = try {
    URI(url.trim()).host?.lowercase()?.removePrefix("www.") ?: ""
} catch (_: Exception) {
    ""
}

private fun hasBengaliChars(text: String): Boolean =
    text.any { it in '\u0980'..'\u09FF' }

private fun matchesBilingualPaperSearch(
    paper: Paper,
    query: String,
    division: String?,
    district: String?
): Boolean {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return true

    val bnLower = paper.bn.lowercase()
    val enLower = paper.en.lowercase()
    val urlLower = paper.url.lowercase()
    val fixLower = (paper.fix ?: "").lowercase()
    val divLower = (division ?: "").lowercase()
    val distLower = (district ?: "").lowercase()
    val divBnLower = (division?.let { DIVISION_LABELS_BN[it] } ?: "").lowercase()
    val distBnLower = (district?.let { DISTRICT_LABELS_BN[it] } ?: "").lowercase()

    if (bnLower.contains(q) ||
        enLower.contains(q) ||
        urlLower.contains(q) ||
        fixLower.contains(q) ||
        divLower.contains(q) ||
        distLower.contains(q) ||
        divBnLower.contains(q) ||
        distBnLower.contains(q)
    ) {
        return true
    }

    if (hasBengaliChars(q)) {
        val collapsedTarget = (enLower + " " + urlLower + " " + divLower + " " + distLower)
            .replace(Regex("[^a-z0-9]"), "")
        val latinCandidates = buildBengaliToLatinCandidates(q)
        for (candidate in latinCandidates) {
            if (candidate.isBlank()) continue
            if (enLower.contains(candidate) ||
                urlLower.contains(candidate) ||
                divLower.contains(candidate) ||
                distLower.contains(candidate)
            ) {
                return true
            }
            val collapsedCandidate = candidate.replace(Regex("[^a-z0-9]"), "")
            if (collapsedCandidate.length >= 2 && collapsedTarget.contains(collapsedCandidate)) {
                return true
            }
        }
    } else {
        val collapsedEnQuery = q.replace(Regex("[^a-z0-9]"), "")
        if (collapsedEnQuery.length >= 2) {
            val collapsedTarget = (enLower + " " + urlLower).replace(Regex("[^a-z0-9]"), "")
            if (collapsedTarget.contains(collapsedEnQuery)) {
                return true
            }
        }
    }

    return false
}

private val BENGALI_WORD_TRANSLITERATIONS = listOf(
    "প্রথম আলো" to listOf("prothom alo", "prothomalo"),
    "কালের কণ্ঠ" to listOf("kaler kantho", "kalerkantho"),
    "বাংলাদেশ প্রতিদিন" to listOf("bangladesh pratidin", "bd-pratidin"),
    "নয়া দিগন্ত" to listOf("naya diganta", "nayadiganta"),
    "নয়া দিগন্ত" to listOf("naya diganta", "nayadiganta"),
    "আমাদের সময়" to listOf("amader shomoy", "amadershomoy"),
    "আমাদের সময়" to listOf("amader shomoy", "amadershomoy"),
    "যায়যায়দিন" to listOf("jaijaidin", "jai jai din"),
    "যায়যায়দিন" to listOf("jaijaidin", "jai jai din"),
    "ভোরের কাগজ" to listOf("bhorer kagoj", "vorerkagoj"),
    "দেশ রূপান্তর" to listOf("desh rupantor"),
    "আজকের পত্রিকা" to listOf("ajker patrika"),
    "মানবজমিন" to listOf("manob zamin", "manab zamin", "mzamin"),
    "দৈনিক" to listOf("dainik", "daily"),
    "প্রথম" to listOf("prothom"),
    "আলো" to listOf("alo"),
    "যুগান্তর" to listOf("jugantor"),
    "ইত্তেফাক" to listOf("ittefaq"),
    "সমকাল" to listOf("samakal"),
    "ইনকিলাব" to listOf("inqilab"),
    "জনকণ্ঠ" to listOf("janakantha"),
    "কালবেলা" to listOf("kalbela"),
    "সংবাদ" to listOf("sangbad", "sambad"),
    "খবর" to listOf("khabor", "khobor"),
    "বার্তা" to listOf("barta"),
    "প্রতিদিন" to listOf("pratidin", "protidin"),
    "পত্রিকা" to listOf("patrika"),
    "সময়" to listOf("shomoy", "somoy"),
    "সময়" to listOf("shomoy", "somoy"),
    "আমাদের" to listOf("amader"),
    "আজকের" to listOf("ajker"),
    "বাংলাদেশ" to listOf("bangladesh"),
    "বাংলা" to listOf("bangla"),
    "দেশ" to listOf("desh"),
    "নিউজ" to listOf("news"),
    "টাইমস" to listOf("times"),
    "পোস্ট" to listOf("post"),
    "ডেইলি" to listOf("daily"),
    "স্টার" to listOf("star"),
    "ট্রিবিউন" to listOf("tribune"),
    "টিভি" to listOf("tv"),
    "বিডি" to listOf("bd"),
    "২৪" to listOf("24"),
    "ঢাকা" to listOf("dhaka"),
    "চট্টগ্রাম" to listOf("chittagong", "chattogram"),
    "রাজশাহী" to listOf("rajshahi"),
    "খুলনা" to listOf("khulna"),
    "বরিশাল" to listOf("barisal", "barishal"),
    "সিলেট" to listOf("sylhet"),
    "রংপুর" to listOf("rangpur"),
    "ময়মনসিংহ" to listOf("mymensingh"),
    "ময়মনসিংহ" to listOf("mymensingh"),
    "কুমিল্লা" to listOf("comilla", "cumilla"),
    "কক্সবাজার" to listOf("cox's bazar", "coxsbazar"),
    "যশোর" to listOf("jessore", "jashore"),
    "বগুড়া" to listOf("bogra", "bogura"),
    "বগুড়া" to listOf("bogra", "bogura")
)

private fun buildBengaliToLatinCandidates(bnQuery: String): List<String> {
    val results = linkedSetOf<String>()
    var primary = bnQuery
    var secondary = bnQuery
    for ((bn, enVariants) in BENGALI_WORD_TRANSLITERATIONS) {
        if (primary.contains(bn)) {
            primary = primary.replace(bn, enVariants.first())
            secondary = secondary.replace(bn, enVariants.last())
        }
    }
    results.add(transliterateRemainingBengali(primary).trim())
    results.add(transliterateRemainingBengali(secondary).trim())
    results.add(transliterateRemainingBengali(bnQuery).trim())
    return results.filter { it.isNotBlank() }
}

private fun transliterateRemainingBengali(input: String): String {
    val normalized = input
        .replace("\u09AF\u09BC", "y")
        .replace("\u09A1\u09BC", "r")
        .replace("\u09A2\u09BC", "r")
    val sb = StringBuilder(normalized.length * 2)
    for (ch in normalized) {
        val mapped = when (ch) {
            'অ' -> "o"
            'আ', 'া' -> "a"
            'ই', 'ঈ', 'ি', 'ী' -> "i"
            'উ', 'ঊ', 'ু', 'ূ' -> "u"
            'ঋ', 'ৃ' -> "ri"
            'এ', 'ে' -> "e"
            'ঐ', 'ৈ' -> "oi"
            'ও', 'ো' -> "o"
            'ঔ', 'ৌ' -> "ou"
            'ক', 'খ' -> if (ch == 'ক') "k" else "kh"
            'গ', 'ঘ' -> if (ch == 'গ') "g" else "gh"
            'ঙ', 'ং' -> "ng"
            'চ' -> "ch"
            'ছ' -> "chh"
            'জ', 'য' -> "j"
            'ঝ' -> "jh"
            'ঞ' -> "n"
            'ট', 'ত', 'ৎ' -> "t"
            'ঠ', 'থ' -> "th"
            'ড', 'দ' -> "d"
            'ঢ', 'ধ' -> "dh"
            'ন', 'ণ' -> "n"
            'প' -> "p"
            'ফ' -> "f"
            'ব', 'ভ' -> if (ch == 'ব') "b" else "bh"
            'ম' -> "m"
            '\u09DF' -> "y"
            'র', '\u09DC', '\u09DD' -> "r"
            'ল' -> "l"
            'শ', 'ষ' -> "sh"
            'স' -> "s"
            'হ' -> "h"
            '০' -> "0"
            '১' -> "1"
            '২' -> "2"
            '৩' -> "3"
            '৪' -> "4"
            '৫' -> "5"
            '৬' -> "6"
            '৭' -> "7"
            '৮' -> "8"
            '৯' -> "9"
            '্', 'ঁ', '়' -> ""
            else -> ch.toString()
        }
        sb.append(mapped)
    }
    return sb.toString().replace(Regex("\\s+"), " ")
}
