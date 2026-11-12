package com.potrikaghor.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import android.net.Uri
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.StarRate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.potrikaghor.BuildConfig
import com.potrikaghor.data.AppStateRepository
import com.potrikaghor.data.BreakingNewsNotifier
import com.potrikaghor.data.NewsRepository
import com.potrikaghor.ui.theme.AccentColors
import com.potrikaghor.ui.util.BengaliUtils
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    appRepo: AppStateRepository,
    newsRepo: NewsRepository = NewsRepository()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val appState by appRepo.state.collectAsState()

    var showRestoreDialog by remember { mutableStateOf(false) }
    var restoreCodeInput by remember { mutableStateOf("") }
    var showClearDataDialog by remember { mutableStateOf(false) }
    var muteKeywordInput by remember { mutableStateOf("") }

    var hasNotifPermission by remember {
        mutableStateOf(BreakingNewsNotifier.hasNotificationPermission(context))
    }
    var isTestingNotification by remember { mutableStateOf(false) }
    var pendingTestAfterPermission by remember { mutableStateOf(false) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasNotifPermission = granted
        if (granted) {
            BreakingNewsNotifier.ensureChannel(context)
            Toast.makeText(context, "ব্রেকিং নিউজ নোটিফিকেশন চালু হয়েছে!", Toast.LENGTH_SHORT).show()
            if (pendingTestAfterPermission) {
                pendingTestAfterPermission = false
                isTestingNotification = true
                scope.launch {
                    val (_, msg) = BreakingNewsNotifier.checkAndNotifyBreakingNews(
                        context = context,
                        appRepo = appRepo,
                        newsRepo = newsRepo,
                        forceManualTest = true
                    )
                    isTestingNotification = false
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                }
            }
        } else {
            pendingTestAfterPermission = false
            Toast.makeText(context, "নোটিফিকেশন পাঠানোর জন্য পারমিশন প্রয়োজন।", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "সেটিংস ও পছন্দসমূহ",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Serif
                    )
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 90.dp)
        ) {
            // 1. চেহারার সেটিংস (Appearance)
            item {
                SectionHeader("চেহারা ও থিম")
            }

            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("থিম মোড", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            listOf(
                                Triple("system", "অটো", 0),
                                Triple("light", "দিন", 1),
                                Triple("dark", "রাত", 2)
                            ).forEach { (mode, label, idx) ->
                                SegmentedButton(
                                    selected = appState.themeMode == mode,
                                    onClick = { appRepo.setThemeMode(mode) },
                                    shape = SegmentedButtonDefaults.itemShape(index = idx, count = 3)
                                ) {
                                    Text(label)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                        Text("থিম স্টাইল", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            listOf(
                                Triple("amoled", "AMOLED কালো", 0),
                                Triple("normal", "সাধারণ কাগজ", 1),
                                Triple("sepia", "সেপিয়া", 2)
                            ).forEach { (style, label, idx) ->
                                SegmentedButton(
                                    selected = appState.themeStyle == style,
                                    onClick = { appRepo.setThemeStyle(style) },
                                    shape = SegmentedButtonDefaults.itemShape(index = idx, count = 3)
                                ) {
                                    Text(label, fontSize = 12.sp)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        Text("অ্যাকসেন্ট রঙ", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            AccentColors.forEachIndexed { idx, color ->
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .background(color)
                                        .clickable { appRepo.setAccent(idx) }
                                        .border(
                                            width = if (appState.accent == idx) 3.dp else 0.dp,
                                            color = if (appState.accent == idx) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                                            shape = CircleShape
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (appState.accent == idx) {
                                        Icon(
                                            Icons.Filled.Check,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("রিডিং ডেনসিটি", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Row {
                                FilterChip(
                                    selected = appState.readingDensity == "comfortable",
                                    onClick = { appRepo.setReadingDensity("comfortable") },
                                    label = { Text("স্বাচ্ছন্দ্য") }
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                FilterChip(
                                    selected = appState.readingDensity == "compact",
                                    onClick = { appRepo.setReadingDensity("compact") },
                                    label = { Text("কমপ্যাক্ট") }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "অ্যাপ ফন্ট স্কেল: ${BengaliUtils.bnDigits((appState.textScale * 100).toInt())}%",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Slider(
                            value = appState.textScale,
                            onValueChange = { appRepo.setTextScale(it) },
                            valueRange = 0.85f..1.4f,
                            steps = 11
                        )
                    }
                }
            }

            // 2. আমার নিউজ ক্যাটাগরি ব্যবস্থাপনা (Personal Home Section Management)
            item {
                SectionHeader("আমার নিউজ: ক্যাটাগরি নিয়ন্ত্রণ")
            }

            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "হোম স্ক্রিনের ক্যাটাগরি অন/অফ করুন ও ক্রম সাজান",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        appState.homeCategories.forEachIndexed { index, cat ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = cat.enabled,
                                        onCheckedChange = { appRepo.toggleHomeCategory(cat.id, it) }
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = cat.label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (cat.enabled) FontWeight.Bold else FontWeight.Normal
                                    )
                                }

                                Row {
                                    IconButton(
                                        onClick = { appRepo.reorderHomeCategories(index, (index - 1).coerceAtLeast(0)) },
                                        enabled = index > 0
                                    ) {
                                        Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "উপরে নিন")
                                    }
                                    IconButton(
                                        onClick = {
                                            appRepo.reorderHomeCategories(
                                                index,
                                                (index + 1).coerceAtMost(appState.homeCategories.size - 1)
                                            )
                                        },
                                        enabled = index < appState.homeCategories.size - 1
                                    ) {
                                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "নিচে নিন")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 3. নোটিফিকেশন নিয়ন্ত্রণ (Notification Control)
            item {
                SectionHeader("নোটিফিকেশন নিয়ন্ত্রণ")
            }

            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "ব্রেকিং নিউজ অ্যালার্ট (৩+ পত্রিকায় প্রকাশিত)",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "ট্যাপ করলে সরাসরি ক্লিন রিডার মোডে খবরটি খুলবে",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (hasNotifPermission) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.errorContainer
                                },
                                modifier = Modifier.clickable {
                                    if (!hasNotifPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                }
                            ) {
                                Text(
                                    text = if (hasNotifPermission) "সক্রিয়" else "অনুমতি দিন",
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (hasNotifPermission) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        listOf(
                            Pair("breaking", "ব্রেকিং নিউজ (জরুরি ঘটনা)"),
                            Pair("bd", "বাংলাদেশ"),
                            Pair("intl", "আন্তর্জাতিক"),
                            Pair("tech", "প্রযুক্তি"),
                            Pair("sports", "খেলাধুলা"),
                            Pair("biz", "ব্যবসা ও অর্থনীতি")
                        ).forEach { (key, label) ->
                            val isChecked = appState.notificationTopics.contains(key)
                            val onToggleTopic = {
                                val willEnable = !isChecked
                                appRepo.toggleNotificationTopic(key)
                                if (willEnable && !hasNotifPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onToggleTopic() }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(text = label, style = MaterialTheme.typography.bodyMedium)
                                Switch(
                                    checked = isChecked,
                                    onCheckedChange = { onToggleTopic() }
                                )
                            }
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

                        OutlinedButton(
                            onClick = {
                                if (!hasNotifPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    pendingTestAfterPermission = true
                                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    isTestingNotification = true
                                    scope.launch {
                                        val (_, msg) = BreakingNewsNotifier.checkAndNotifyBreakingNews(
                                            context = context,
                                            appRepo = appRepo,
                                            newsRepo = newsRepo,
                                            forceManualTest = true
                                        )
                                        isTestingNotification = false
                                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                    }
                                }
                            },
                            enabled = !isTestingNotification,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            if (isTestingNotification) {
                                com.potrikaghor.ui.components.PotrikaGhorLoader(
                                    size = 20.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("ব্রেকিং নিউজ খোঁজা হচ্ছে...")
                            } else {
                                Icon(
                                    Icons.Filled.NotificationsActive,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("ব্রেকিং নিউজ পরীক্ষা করুন (Test Notification)")
                            }
                        }
                    }
                }
            }

            // 3.5 কি-ওয়ার্ড মিউট / ফিল্টার (Topic Mute Filter — ২.খ)
            item {
                SectionHeader("কি-ওয়ার্ড মিউট ফিল্টার")
            }

            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "যে শব্দের খবর ফিডে দেখতে চান না তা যুক্ত করুন",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = muteKeywordInput,
                                onValueChange = { muteKeywordInput = it },
                                modifier = Modifier.weight(1f),
                                placeholder = { Text("শব্দ লিখুন (যেমন: তারকা, গুজব)", fontSize = 13.sp) },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    val kw = muteKeywordInput.trim()
                                    if (kw.length >= 2) {
                                        appRepo.addMutedKeyword(kw)
                                        muteKeywordInput = ""
                                    } else {
                                        Toast.makeText(context, "অন্তত ২ অক্ষরের শব্দ লিখুন", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("যোগ")
                            }
                        }

                        if (appState.mutedKeywords.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(10.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(appState.mutedKeywords, key = { it }) { kw ->
                                    FilterChip(
                                        selected = true,
                                        onClick = { appRepo.removeMutedKeyword(kw) },
                                        label = { Text(kw, fontSize = 12.sp) },
                                        trailingIcon = {
                                            Icon(
                                                Icons.Filled.Close,
                                                contentDescription = "সরান",
                                                modifier = Modifier.size(15.dp)
                                            )
                                        },
                                        shape = RoundedCornerShape(14.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 4. অফলাইন ও ডেটা সাশ্রয় মোড (Offline & Data Saver Mode — ৩.খ & ৪.খ)
            item {
                SectionHeader("অফলাইন ও ডেটা সাশ্রয়")
            }

            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val next = !appState.dataSaverMode
                                    appRepo.setDataSaverMode(next)
                                    newsRepo.dataSaverMode = next
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "ডেটা সেভার মোড (Data Saver)",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                                Text(
                                    text = "ফিডে বড় ছবি লোড বন্ধ রেখে মোবাইল ডেটা ও ব্যাটারি সাশ্রয় করে",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Switch(
                                checked = appState.dataSaverMode,
                                onCheckedChange = {
                                    appRepo.setDataSaverMode(it)
                                    newsRepo.dataSaverMode = it
                                }
                            )
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { appRepo.setHideAds(!appState.hideAds) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "ফিডে বিজ্ঞাপন লুকান",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                                Text(
                                    text = "হোম ফিডের নেটিভ বিজ্ঞাপন স্লট বন্ধ রাখে",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Switch(
                                checked = appState.hideAds,
                                onCheckedChange = { appRepo.setHideAds(it) }
                            )
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                        listOf(
                            Triple("wifi_only", "শুধু ওয়াই-ফাই থাকলে ডাউনলোড", "ডেটা সাশ্রয় করে স্বয়ংক্রিয়ভাবে অফলাইন ক্যাশ করে"),
                            Triple("auto", "যেকোনো নেটওয়ার্কে ডাউনলোড", "মোবাইল ডেটা বা ওয়াই-ফাই উভয়েই কাজ করবে"),
                            Triple("manual", "ম্যানুয়াল ডাউনলোড", "শুধু বুকমার্ক করার সময় নিজে সেভ করবেন")
                        ).forEach { (mode, title, sub) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { appRepo.setOfflineMode(mode) }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = appState.offlineMode == mode,
                                    onClick = { appRepo.setOfflineMode(mode) }
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(text = title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Text(text = sub, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }

            // 5. ব্যাকআপ ও পুনরুদ্ধার (Backup & Restore)
            item {
                SectionHeader("ব্যাকআপ ও পুনরুদ্ধার")
            }

            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val code = appRepo.exportBackupJson()
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("potrikaghor_backup", code))
                                    Toast.makeText(context, "ব্যাকআপ কোড কপি হয়েছে!", Toast.LENGTH_SHORT).show()
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(14.dp))
                            Column {
                                Text("ব্যাকআপ কোড কপি করুন", fontWeight = FontWeight.Bold)
                                Text("প্রিয়, বুকমার্ক ও সেটিংস একটি কোডে ক্লিপবোর্ডে কপি হবে", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val code = appRepo.exportBackupJson()
                                    val intent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, code)
                                    }
                                    context.startActivity(Intent.createChooser(intent, "ব্যাকআপ পাঠান"))
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.Share, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(14.dp))
                            Column {
                                Text("ব্যাকআপ শেয়ার করুন", fontWeight = FontWeight.Bold)
                                Text("হোয়াটসঅ্যাপ, নোট বা মেসেজে নিজেকে পাঠিয়ে রাখুন", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showRestoreDialog = true }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.Restore, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(14.dp))
                            Column {
                                Text("ব্যাকআপ থেকে ফেরান", fontWeight = FontWeight.Bold)
                                Text("আগের কপি করা ব্যাকআপ কোড পেস্ট করুন", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            // 6. ডেটা ও প্রাইভেসি (Privacy & Data Management)
            item {
                SectionHeader("ডেটা ও গোপনীয়তা")
            }

            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    try {
                                        val webView = WebView(context)
                                        webView.clearCache(true)
                                        webView.destroy()
                                    } catch (_: Exception) {
                                    }
                                    Toast.makeText(context, "ওয়েব ক্যাশ খালি করা হয়েছে", Toast.LENGTH_SHORT).show()
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.CleaningServices, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(14.dp))
                            Column {
                                Text("ওয়েব ক্যাশ মুছুন", fontWeight = FontWeight.Bold)
                                Text("সাময়িক ইন্টারনেট ফাইল সরিয়ে স্টোরেজ খালি করে", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showClearDataDialog = true }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            Spacer(modifier = Modifier.width(14.dp))
                            Column {
                                Text("সব ইতিহাস ও প্রিয় তালিকা মুছুন", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                                Text("পড়ার ইতিহাস এবং সংরক্ষিত তালিকা রিসেট হবে", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            // 7. অ্যাপ সম্পর্কে (About & Play Store Share/Rate — ৪.গ)
            item {
                SectionHeader("সম্পর্কে")
            }

            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("পত্রিকাঘর — আপনার ব্যক্তিগত সংবাদপত্র", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Serif)
                        Text("সংস্করণ ${BengaliUtils.bnDigits(BuildConfig.VERSION_NAME)} • সম্পূর্ণ প্রাইভেট ও লোকাল", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "কোনো অ্যাকাউন্ট বা ট্র্যাকিং নেই। সমস্ত ডেটা আপনার নিজের ডিভাইসে নিরাপদ থাকে।",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    val playUrl = "https://play.google.com/store/apps/details?id=${context.packageName}"
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(
                                            Intent.EXTRA_TEXT,
                                            "পত্রিকাঘর (PotrikaGhor) — বাংলাদেশের সব প্রধান পত্রিকা, ই-পেপার ও ব্রেকিং নিউজ এক অ্যাপে:\n$playUrl"
                                        )
                                    }
                                    context.startActivity(Intent.createChooser(shareIntent, "অ্যাপটি শেয়ার করুন"))
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(17.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("অ্যাপ শেয়ার", fontSize = 12.5.sp)
                            }

                            Button(
                                onClick = {
                                    val pkg = context.packageName
                                    try {
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg"))
                                        )
                                    } catch (_: Exception) {
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg"))
                                        )
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Filled.StarRate, contentDescription = null, modifier = Modifier.size(17.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("রেটিং দিন", fontSize = 12.5.sp)
                            }
                        }
                    }
                }
            }
        }
    }

    // Restore Backup Dialog
    if (showRestoreDialog) {
        AlertDialog(
            onDismissRequest = { showRestoreDialog = false },
            title = { Text("ব্যাকআপ থেকে ফেরান") },
            text = {
                Column {
                    Text("আগে কপি করা ব্যাকআপ কোড এখানে পেস্ট করুন:")
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = restoreCodeInput,
                        onValueChange = { restoreCodeInput = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        placeholder = { Text("ব্যাকআপ কোড পেস্ট করুন...") },
                        maxLines = 6
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val err = appRepo.importBackupJson(restoreCodeInput)
                        if (err == null) {
                            Toast.makeText(context, "ব্যাকআপ সফলভাবে ফিরিয়ে আনা হয়েছে!", Toast.LENGTH_SHORT).show()
                            showRestoreDialog = false
                            restoreCodeInput = ""
                        } else {
                            Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                        }
                    }
                ) {
                    Text("ফেরান")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreDialog = false }) {
                    Text("বাতিল")
                }
            }
        )
    }

    // Clear Data Confirmation Dialog
    if (showClearDataDialog) {
        AlertDialog(
            onDismissRequest = { showClearDataDialog = false },
            title = { Text("সব ডেটা মুছে ফেলবেন?") },
            text = { Text("আপনার প্রিয় পত্রিকা, বুকমার্ক ও পড়ার ইতিহাস মুছে যাবে। আপনি কি নিশ্চিত?") },
            confirmButton = {
                Button(
                    onClick = {
                        appRepo.clearUserData()
                        Toast.makeText(context, "ডেটা মোছা হয়েছে", Toast.LENGTH_SHORT).show()
                        showClearDataDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("মুছুন")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDataDialog = false }) {
                    Text("বাতিল")
                }
            }
        )
    }
}

@Composable
fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Serif,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 4.dp)
    )
}
