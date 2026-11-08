package com.potrikaghor.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.potrikaghor.model.Bookmark
import com.potrikaghor.model.DefaultBookmarkFolders
import com.potrikaghor.model.Paper
import com.potrikaghor.ui.components.EmptyState
import com.potrikaghor.ui.components.MotionTokens
import com.potrikaghor.ui.components.PaperAvatar
import com.potrikaghor.ui.components.pressScaleEffect
import com.potrikaghor.ui.components.rememberReducedMotion
import com.potrikaghor.ui.components.subtleClickable
import com.potrikaghor.ui.theme.BrandGreen
import com.potrikaghor.ui.util.BengaliUtils
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarksScreen(
    appRepo: AppStateRepository,
    onOpenReader: (Paper, String, String) -> Unit
) {
    val appState by appRepo.state.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val reducedMotion = rememberReducedMotion()

    var selectedFolder by remember { mutableStateOf("সব") }
    var changeFolderBookmark by remember { mutableStateOf<Bookmark?>(null) }
    var showReadingHistoryTab by remember { mutableStateOf(false) }

    val bookmarks = appState.bookmarks
    val readHistory = appState.readHistory
    val filteredBookmarks = remember(bookmarks, selectedFolder) {
        if (selectedFolder == "সব") bookmarks else bookmarks.filter { it.folder == selectedFolder }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "বুকমার্ক ও পঠন ইতিহাস",
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
                                    text = if (showReadingHistoryTab) {
                                        "${BengaliUtils.bnDigits(readHistory.size)}টি পঠিত"
                                    } else {
                                        "${BengaliUtils.bnDigits(bookmarks.size)}টি সেভ করা"
                                    },
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
                            text = if (showReadingHistoryTab) {
                                "আপনার সম্প্রতি পড়া সংবাদের তালিকা"
                            } else {
                                "ফোল্ডারে সাজানো আপনার পছন্দের সংবাদ"
                            },
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 11.sp,
                                lineHeight = 14.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    if (showReadingHistoryTab && readHistory.isNotEmpty()) {
                        TextButton(onClick = { appRepo.clearReadHistory() }) {
                            Text("ইতিহাস মুছুন", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                        }
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
            // Segmented Switch: Bookmarks vs Reading History
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 4.dp)
            ) {
                SegmentedButton(
                    selected = !showReadingHistoryTab,
                    onClick = { showReadingHistoryTab = false },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                ) {
                    Text(
                        text = "বুকমার্ক (${BengaliUtils.bnDigits(bookmarks.size)})",
                        fontSize = 12.5.sp
                    )
                }
                SegmentedButton(
                    selected = showReadingHistoryTab,
                    onClick = { showReadingHistoryTab = true },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                ) {
                    Text(
                        text = "সাম্প্রতিক পঠন (${BengaliUtils.bnDigits(readHistory.size)})",
                        fontSize = 12.5.sp
                    )
                }
            }

            if (showReadingHistoryTab) {
                if (readHistory.isEmpty()) {
                    EmptyState(
                        icon = Icons.Filled.History,
                        title = "কোনো পঠিত খবর নেই",
                        body = "আপনি যে খবরগুলো পড়বেন সেগুলোর তালিকা এখানে সংরক্ষিত থাকবে, যাতে পরে সহজেই খুঁজে পান।"
                    )
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(bottom = 82.dp, top = 4.dp)
                    ) {
                        items(
                            items = readHistory,
                            key = { "${it.url}_${it.readAt}" }
                        ) { hist ->
                            val paper = appRepo.paperFor(hist.paperUrl)
                                ?: appRepo.allPapers.firstOrNull { it.bn == hist.sourceName || it.en == hist.sourceName }
                                ?: Paper(hist.sourceName, "", hist.paperUrl, "online")

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 4.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .subtleClickable(pressedScale = 0.985f) {
                                        onOpenReader(paper, hist.url, hist.title)
                                    },
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                            ) {
                                Column(modifier = Modifier.padding(11.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        PaperAvatar(paper = paper, size = 26.dp)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = hist.sourceName,
                                                style = MaterialTheme.typography.labelMedium.copy(
                                                    fontSize = 11.5.sp,
                                                    lineHeight = 15.sp
                                                ),
                                                color = MaterialTheme.colorScheme.primary,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                text = "পড়া হয়েছে ${BengaliUtils.bnAgo(hist.readAt)}",
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
                                        text = hist.title,
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontSize = 14.5.sp,
                                            lineHeight = 20.sp
                                        ),
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Serif,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                // Folder Tabs
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    items(DefaultBookmarkFolders, key = { it }) { folder ->
                        val isSelected = selectedFolder == folder
                        val count = if (folder == "সব") bookmarks.size else bookmarks.count { it.folder == folder }
                        val chipInteraction = remember { MutableInteractionSource() }
                        val chipBg by animateColorAsState(
                            targetValue = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            animationSpec = tween(
                                durationMillis = if (reducedMotion) 0 else MotionTokens.NORMAL_MS,
                                easing = FastOutSlowInEasing
                            ),
                            label = "bmFolderChipBg"
                        )
                        val chipText by animateColorAsState(
                            targetValue = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            animationSpec = tween(
                                durationMillis = if (reducedMotion) 0 else MotionTokens.FAST_MS,
                                easing = FastOutSlowInEasing
                            ),
                            label = "bmFolderChipText"
                        )

                        FilterChip(
                            selected = isSelected,
                            onClick = { selectedFolder = folder },
                            interactionSource = chipInteraction,
                            modifier = Modifier.pressScaleEffect(chipInteraction, pressedScale = 0.95f),
                            label = {
                                Text(
                                    text = "$folder (${BengaliUtils.bnDigits(count)})",
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

                val isAppReady by appRepo.isReady.collectAsState()
                val bookmarksState = when {
                    !isAppReady -> "loading"
                    filteredBookmarks.isEmpty() -> "empty"
                    else -> "list"
                }

                Crossfade(
                    targetState = bookmarksState,
                    animationSpec = tween(
                        durationMillis = if (reducedMotion) 0 else MotionTokens.NORMAL_MS,
                        easing = FastOutSlowInEasing
                    ),
                    label = "bookmarksCrossfade"
                ) { state ->
                    when (state) {
                        "loading" -> {
                            com.potrikaghor.ui.components.BrandedLoadingIndicator(
                                message = "লোড হচ্ছে...",
                                logoSize = 72.dp,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        "empty" -> {
                            EmptyState(
                                icon = Icons.Filled.Bookmark,
                                title = "কোনো বুকমার্ক নেই",
                                body = if (selectedFolder == "সব") {
                                    "কোনো খবর পড়ার সময় উপরের বুকমার্ক আইকন চাপলে সেটি এখানে ফোল্ডারে সেভ হবে এবং অফলাইনেও পড়া যাবে।"
                                } else {
                                    "'$selectedFolder' ফোল্ডারে বর্তমানে কোনো খবর নেই।"
                                }
                            )
                        }
                        else -> {
                            LazyColumn(
                                contentPadding = PaddingValues(bottom = 82.dp, top = 4.dp)
                            ) {
                            items(
                                items = filteredBookmarks,
                                key = { it.url }
                            ) { bookmark ->
                                val paper = appRepo.paperFor(bookmark.paperUrl)
                                    ?: Paper(bookmark.title, "", bookmark.paperUrl, "all")
                                val delInteraction = remember { MutableInteractionSource() }

                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 4.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .subtleClickable(pressedScale = 0.985f) {
                                            onOpenReader(paper, bookmark.url, bookmark.title)
                                        },
                                    shape = RoundedCornerShape(14.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                                ) {
                                    Column(modifier = Modifier.padding(11.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            PaperAvatar(paper = paper, size = 28.dp)
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = paper.bn,
                                                    style = MaterialTheme.typography.labelMedium.copy(
                                                        fontSize = 11.5.sp,
                                                        lineHeight = 15.sp
                                                    ),
                                                    color = MaterialTheme.colorScheme.primary,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                Text(
                                                    text = BengaliUtils.bnAgo(bookmark.savedAt),
                                                    style = MaterialTheme.typography.bodySmall.copy(
                                                        fontSize = 11.sp,
                                                        lineHeight = 14.sp
                                                    ),
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }

                                            // Folder Badge
                                            Surface(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(7.dp))
                                                    .subtleClickable(pressedScale = 0.95f) { changeFolderBookmark = bookmark },
                                                shape = RoundedCornerShape(7.dp),
                                                color = MaterialTheme.colorScheme.secondaryContainer
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Icon(
                                                        Icons.Filled.Folder,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(12.dp),
                                                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                                                    )
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text(
                                                        text = bookmark.folder,
                                                        style = MaterialTheme.typography.labelSmall.copy(
                                                            fontSize = 10.5.sp,
                                                            lineHeight = 14.sp
                                                        ),
                                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                                    )
                                                }
                                            }

                                            IconButton(
                                                onClick = {
                                                    val removed = bookmark
                                                    val idx = bookmarks.indexOf(removed)
                                                    appRepo.removeBookmark(bookmark.url)
                                                    scope.launch {
                                                        val res = snackbarHostState.showSnackbar(
                                                            message = "বুকমার্ক সরানো হয়েছে",
                                                            actionLabel = "ফিরিয়ে আনুন",
                                                            duration = SnackbarDuration.Short
                                                        )
                                                        if (res == SnackbarResult.ActionPerformed) {
                                                            appRepo.restoreBookmark(removed, idx)
                                                        }
                                                    }
                                                },
                                                interactionSource = delInteraction,
                                                modifier = Modifier
                                                    .size(36.dp)
                                                    .pressScaleEffect(delInteraction, pressedScale = 0.88f)
                                            ) {
                                                Icon(
                                                    Icons.Filled.DeleteOutline,
                                                    contentDescription = "মুছুন",
                                                    tint = MaterialTheme.colorScheme.error,
                                                    modifier = Modifier.size(19.dp)
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(7.dp))

                                        Text(
                                            text = bookmark.title,
                                            style = MaterialTheme.typography.titleMedium.copy(
                                                fontSize = 15.sp,
                                                lineHeight = 20.5.sp
                                            ),
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Serif,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )

                                        if (bookmark.offlineContent != null) {
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    Icons.Filled.OfflinePin,
                                                    contentDescription = null,
                                                    tint = BrandGreen,
                                                    modifier = Modifier.size(15.dp)
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    text = "অফলাইনে সংরক্ষিত",
                                                    style = MaterialTheme.typography.bodySmall.copy(
                                                        fontSize = 11.sp,
                                                        lineHeight = 14.sp
                                                    ),
                                                    color = BrandGreen
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

    // Change Folder Dialog
    if (changeFolderBookmark != null) {
        val b = changeFolderBookmark!!
        AlertDialog(
            onDismissRequest = { changeFolderBookmark = null },
            title = { Text("ফোল্ডার পরিবর্তন করুন") },
            text = {
                Column {
                    Text(
                        text = b.title,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    DefaultBookmarkFolders.drop(1).forEach { folder ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    appRepo.updateBookmarkFolder(b.url, folder)
                                    changeFolderBookmark = null
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.Folder,
                                contentDescription = null,
                                tint = if (b.folder == folder) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = folder,
                                fontWeight = if (b.folder == folder) FontWeight.Bold else FontWeight.Normal,
                                color = if (b.folder == folder) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { changeFolderBookmark = null }) {
                    Text("বাতিল")
                }
            }
        )
    }
}
