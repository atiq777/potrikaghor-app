package com.potrikaghor.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.potrikaghor.data.AppStateRepository
import com.potrikaghor.data.PapersData
import com.potrikaghor.model.Paper
import com.potrikaghor.ui.components.EmptyState
import com.potrikaghor.ui.components.PaperAvatar
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "আমার পত্রিকা",
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Serif
                                )
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Text(
                                    text = "${BengaliUtils.bnDigits(filteredList.size)}টি",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(
                            text = "পছন্দের সোর্স ও ব্যক্তিগত রিডিং বোর্ড",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { appRepo.setGridView(!appState.gridView) }) {
                        Icon(
                            imageVector = if (appState.gridView) Icons.Filled.ViewAgenda else Icons.Filled.GridView,
                            contentDescription = "ভিউ মোড"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddPaper,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Row(modifier = Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("পত্রিকা যোগ করুন", fontWeight = FontWeight.Bold)
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
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                placeholder = { Text("পত্রিকার নাম বা লিংক খুঁজুন...") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp)
            )

            // Category Chips Row
            val availableCategories = listOf(
                Pair("all", "সব"),
                Pair("fav", "প্রিয় (${BengaliUtils.bnDigits(appState.favs.size)})"),
                Pair("mine", "নিজের যোগ করা (${BengaliUtils.bnDigits(appState.customPapers.size)})")
            ) + PapersData.categories.drop(1).map { Pair(it.id, it.label) }

            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(availableCategories) { (id, label) ->
                    val isSelected = selectedCat == id
                    FilterChip(
                        selected = isSelected,
                        onClick = { selectedCat = id },
                        label = { Text(label) }
                    )
                }
            }

            // Only favorites toggle row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
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

            if (filteredList.isEmpty()) {
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
            } else if (appState.gridView) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 90.dp, top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filteredList) { paper ->
                        MyPaperGridItem(
                            paper = paper,
                            isFav = appState.favs.contains(paper.url),
                            onToggleFav = { appRepo.toggleFav(paper.url) },
                            onOpen = { onOpenReader(paper, paper.openUrl) },
                            onFixUrl = { onFixUrl(paper) }
                        )
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 90.dp, top = 6.dp)
                ) {
                    items(filteredList) { paper ->
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

@Composable
fun MyPaperListItem(
    paper: Paper,
    isFav: Boolean,
    onToggleFav: () -> Unit,
    onOpen: () -> Unit,
    onFixUrl: () -> Unit,
    onToggleHide: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable { onOpen() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PaperAvatar(paper = paper, size = 44.dp)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = paper.bn,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Serif
                    )
                    if (paper.custom) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                text = "কাস্টম",
                                fontSize = 9.sp,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
                Text(
                    text = if (paper.en.isNotEmpty()) "${paper.en} • ${paper.host}" else paper.host,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            IconButton(onClick = onFixUrl) {
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = "লিংক ঠিক করুন",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            IconButton(onClick = onToggleFav) {
                Icon(
                    imageVector = if (isFav) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    contentDescription = "পছন্দের তালিকা",
                    tint = if (isFav) FlagRed else MaterialTheme.colorScheme.onSurfaceVariant
                )
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
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                PaperAvatar(paper = paper, size = 38.dp)
                IconButton(onClick = onToggleFav, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = if (isFav) Icons.Filled.Star else Icons.Outlined.StarBorder,
                        contentDescription = null,
                        tint = if (isFav) FlagRed else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = paper.bn,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Serif,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = paper.host,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
