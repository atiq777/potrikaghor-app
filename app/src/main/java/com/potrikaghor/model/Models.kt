package com.potrikaghor.model

import kotlinx.serialization.Serializable
import java.net.URI

@Serializable
data class Category(
    val id: String,
    val label: String
)

@Serializable
data class Paper(
    val bn: String,
    val en: String,
    val url: String,
    val cat: String,
    val custom: Boolean = false,
    val fix: String? = null,
    val hidden: Boolean = false
) {
    val openUrl: String
        get() = fix ?: url

    val host: String
        get() = try {
            val h = URI(openUrl).host ?: ""
            if (h.startsWith("www.")) h.substring(4) else h
        } catch (_: Exception) {
            ""
        }

    val logoUrl: String
        get() = if (host.isNotEmpty()) "https://www.google.com/s2/favicons?sz=128&domain=$host" else ""
}

@Serializable
data class Bookmark(
    val url: String,
    val title: String,
    val paperUrl: String,
    val savedAt: Long,
    val folder: String = "পরে পড়ব",
    val offlineContent: String? = null
)

val DefaultBookmarkFolders = listOf(
    "সব",
    "পরে পড়ব",
    "গুরুত্বপূর্ণ",
    "প্রযুক্তি",
    "গবেষণা",
    "অন্যান্য"
)

data class NewsItem(
    val title: String,
    val source: String,
    val sourceUrl: String,
    val link: String,
    val time: Long? = null,
    val categoryId: String = "all",
    val related: List<NewsItem> = emptyList(),
    val snippet: String = ""
)

data class Coverage(
    val items: List<NewsItem>
) {
    val headline: String
        get() = items.firstOrNull()?.title ?: ""

    val count: Int
        get() = items.size

    val latest: Long?
        get() = items.mapNotNull { it.time }.maxOrNull()
}

@Serializable
data class HomeCategoryPref(
    val id: String,
    val label: String,
    val query: String,
    val enabled: Boolean = true,
    val order: Int = 0
)

val DefaultHomeCategories = listOf(
    HomeCategoryPref("top", "আজকের গুরুত্বপূর্ণ", "", true, 0),
    HomeCategoryPref("bd", "বাংলাদেশ", "বাংলাদেশ", true, 1),
    HomeCategoryPref("intl", "আন্তর্জাতিক", "আন্তর্জাতিক", true, 2),
    HomeCategoryPref("tech", "প্রযুক্তি", "প্রযুক্তি", true, 3),
    HomeCategoryPref("science", "বিজ্ঞান", "বিজ্ঞান", true, 4),
    HomeCategoryPref("biz", "ব্যবসা/অর্থনীতি", "অর্থনীতি", true, 5),
    HomeCategoryPref("jobs", "চাকরি", "চাকরি", true, 6),
    HomeCategoryPref("sports", "খেলাধুলা", "খেলাধুলা", true, 7),
    HomeCategoryPref("ent", "বিনোদন", "বিনোদন", true, 8),
    HomeCategoryPref("health", "স্বাস্থ্য", "স্বাস্থ্য", true, 9),
    HomeCategoryPref("edu", "শিক্ষা", "শিক্ষা", true, 10)
)
