package com.potrikaghor.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.potrikaghor.MainActivity
import com.potrikaghor.R
import com.potrikaghor.model.Coverage
import com.potrikaghor.model.NewsItem
import com.potrikaghor.ui.util.BengaliUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

object BreakingNewsNotifier {
    const val CHANNEL_ID = "breaking_news_channel"
    const val EXTRA_NOTIFICATION_URL = "extra_notification_url"
    const val EXTRA_NOTIFICATION_SOURCE = "extra_notification_source"
    const val EXTRA_NOTIFICATION_TITLE = "extra_notification_title"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ব্রেকিং নিউজ ও জরুরি খবর",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "৩ বা তার বেশি পত্রিকায় প্রকাশিত গুরুত্বপূর্ণ ব্রেকিং নিউজের অ্যালার্ট"
                enableVibration(true)
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
    }

    fun sendBreakingNotification(
        context: Context,
        title: String,
        source: String,
        url: String,
        coverageCount: Int
    ): Boolean {
        if (!hasNotificationPermission(context)) return false
        ensureChannel(context)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_NOTIFICATION_URL, url)
            putExtra(EXTRA_NOTIFICATION_SOURCE, source)
            putExtra(EXTRA_NOTIFICATION_TITLE, title)
        }

        val requestCode = url.hashCode()
        val pendingIntent = PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val countBadge = if (coverageCount >= 3) {
            "ব্রেকিং নিউজ • ${BengaliUtils.bnDigits(coverageCount)}টি পত্রিকায় প্রকাশিত"
        } else {
            "ব্রেকিং নিউজ • $source"
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(countBadge)
            .setContentText(title)
            .setSubText(source)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(title)
                    .setBigContentTitle(countBadge)
                    .setSummaryText("$source সহ একাধিক শীর্ষ গণমাধ্যমে প্রকাশিত")
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setColor(0xFFFF6E6E.toInt())
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        return try {
            NotificationManagerCompat.from(context).notify(requestCode, notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    suspend fun checkAndNotifyBreakingNews(
        context: Context,
        appRepo: AppStateRepository,
        newsRepo: NewsRepository,
        forceManualTest: Boolean = false
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (!hasNotificationPermission(context)) {
            return@withContext false to "নোটিফিকেশন পারমিশন দেওয়া নেই। অনুগ্রহ করে পারমিশন অন করুন।"
        }

        val enabledTopics = appRepo.state.value.notificationTopics
        if (enabledTopics.isEmpty() && !forceManualTest) {
            return@withContext false to "কোনো নোটিফিকেশন ক্যাটাগরি নির্বাচন করা নেই।"
        }

        val queriesToFetch = buildList {
            if (enabledTopics.contains("breaking") || forceManualTest) add("" to false)
            if (enabledTopics.contains("bd")) add("বাংলাদেশ" to false)
            if (enabledTopics.contains("intl")) add("WORLD" to true)
            if (enabledTopics.contains("tech")) add("প্রযুক্তি" to false)
            if (enabledTopics.contains("sports")) add("খেলাধুলা" to false)
            if (enabledTopics.contains("biz")) add("অর্থনীতি" to false)
        }.distinct()

        val allItems: List<NewsItem> = try {
            coroutineScope {
                queriesToFetch.map { (query, isIntl) ->
                    async {
                        try {
                            if (isIntl) {
                                newsRepo.fetchNews(intl = true, section = query)
                            } else {
                                newsRepo.fetchNews(query = query)
                            }
                        } catch (_: Exception) {
                            emptyList()
                        }
                    }
                }.awaitAll().flatten()
            }
        } catch (_: Exception) {
            emptyList()
        }

        val coverages: List<Coverage> = if (allItems.isNotEmpty()) {
            newsRepo.buildCoverage(allItems)
        } else {
            emptyList()
        }

        // Filter stories covered by 3 or more sources
        val multiPaperStories = coverages.filter { it.count >= 3 }

        val candidateCoverage: Coverage? = if (forceManualTest) {
            multiPaperStories.firstOrNull { !appRepo.isStoryNotified(it.headline) }
                ?: multiPaperStories.firstOrNull()
                ?: coverages.firstOrNull()
        } else {
            multiPaperStories.firstOrNull { !appRepo.isStoryNotified(it.headline) }
        }

        if (candidateCoverage != null) {
            val mainItem = candidateCoverage.items.first()
            val sent = sendBreakingNotification(
                context = context,
                title = mainItem.title,
                source = mainItem.source,
                url = mainItem.link,
                coverageCount = candidateCoverage.count.coerceAtLeast(3)
            )
            if (sent) {
                appRepo.markStoryNotified(candidateCoverage.headline)
                return@withContext true to "ব্রেকিং নিউজ নোটিফিকেশন পাঠানো হয়েছে: \"${mainItem.title.take(45)}...\""
            }
        } else if (forceManualTest) {
            // Fallback if offline during manual test
            val fallbackPaper = PapersData.builtInPapers.first()
            val sent = sendBreakingNotification(
                context = context,
                title = "দেশের শীর্ষ সংবাদপত্রগুলোতে প্রকাশিত আজকের বিশেষ ব্রেকিং শিরোনাম",
                source = fallbackPaper.bn,
                url = fallbackPaper.url,
                coverageCount = 5
            )
            if (sent) {
                return@withContext true to "টেস্ট ব্রেকিং নিউজ নোটিফিকেশন পাঠানো হয়েছে!"
            }
        }

        false to "এই মুহূর্তে ৩+ পত্রিকায় প্রকাশিত নতুন কোনো ব্রেকিং নিউজ পাওয়া যায়নি।"
    }
}
