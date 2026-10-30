package com.potrikaghor.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.potrikaghor.data.AppStateRepository
import com.potrikaghor.data.NewsRepository
import com.potrikaghor.model.Coverage
import com.potrikaghor.model.Paper
import com.potrikaghor.ui.components.EmptyState
import com.potrikaghor.ui.components.PaperAvatar
import com.potrikaghor.ui.components.SkeletonCard
import com.potrikaghor.ui.util.BengaliUtils
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoverageScreen(
    appRepo: AppStateRepository,
    newsRepo: NewsRepository,
    onOpenReader: (Paper, String, String) -> Unit
) {
    val scope = rememberCoroutineScope()
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

    val manyCoverage = remember(coverageList) { coverageList.filter { it.count >= 3 } }
    val fewCoverage = remember(coverageList) { coverageList.filter { it.count == 2 } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "কভারেজ তুলনা",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Serif
                            )
                        )
                        Text(
                            text = "কোন খবর কয়টি পত্রিকায়, কে কীভাবে শিরোনাম দিয়েছে",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { loadCoverage() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "রিফ্রেশ")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
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
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                SegmentedButton(
                    selected = !isIntl,
                    onClick = { isIntl = false },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                ) {
                    Text("বাংলাদেশ সংস্করণ")
                }
                SegmentedButton(
                    selected = isIntl,
                    onClick = { isIntl = true },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                ) {
                    Text("আন্তর্জাতিক")
                }
            }

            if (isLoading) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(6) { SkeletonCard() }
                }
            } else if (errorMsg != null && coverageList.isEmpty()) {
                EmptyState(
                    icon = Icons.Filled.Hub,
                    title = "কভারেজ লোড হয়নি",
                    body = errorMsg ?: "",
                    actionLabel = "আবার চেষ্টা করুন",
                    onAction = { loadCoverage() }
                )
            } else if (coverageList.isEmpty()) {
                EmptyState(
                    icon = Icons.Filled.Hub,
                    title = "কোনো কভারেজ পাওয়া যায়নি",
                    body = "এই মুহূর্তে একাধিক পত্রিকায় প্রকাশিত কোনো সাধারণ ঘটনা মেলেনি। একটু পরে আবার চেষ্টা করুন।"
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 90.dp, top = 6.dp)
                ) {
                    if (manyCoverage.isNotEmpty()) {
                        item {
                            Text(
                                text = "বেশি কভার হওয়া খবর (৩+ পত্রিকায়)",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                fontFamily = FontFamily.Serif,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
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
                            Spacer(modifier = Modifier.height(14.dp))
                            Text(
                                text = "অন্যান্য কভার হওয়া খবর (২টি পত্রিকায়)",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                fontFamily = FontFamily.Serif,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
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

@Composable
fun CoverageDetailCard(
    coverage: Coverage,
    appRepo: AppStateRepository,
    onOpenReader: (Paper, String, String) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = "${BengaliUtils.bnDigits(coverage.count)}টি পত্রিকায় কভার হয়েছে",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                if (coverage.latest != null) {
                    Text(
                        text = BengaliUtils.bnAgo(coverage.latest!!),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            coverage.items.forEachIndexed { idx, itm ->
                val paper = appRepo.allPapers.firstOrNull { it.bn == itm.source || it.en == itm.source }
                    ?: Paper(itm.source, "", itm.link, "online")

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenReader(paper, itm.link, itm.title) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    PaperAvatar(paper = paper, size = 26.dp)
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = itm.source,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = itm.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (idx == 0) FontWeight.Bold else FontWeight.Normal,
                            fontFamily = FontFamily.Serif
                        )
                    }
                }

                if (idx < coverage.items.size - 1) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }
}
