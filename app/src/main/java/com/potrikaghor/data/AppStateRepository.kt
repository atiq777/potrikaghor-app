package com.potrikaghor.data

import android.content.Context
import android.content.SharedPreferences
import com.potrikaghor.model.Bookmark
import com.potrikaghor.model.DefaultHomeCategories
import com.potrikaghor.model.HomeCategoryPref
import com.potrikaghor.model.Paper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.encodeToJsonElement

data class AppSettings(
    val customPapers: List<Paper> = emptyList(),
    val urlFixes: Map<String, String> = emptyMap(),
    val favs: List<String> = emptyList(),
    val recents: List<String> = emptyList(),
    val bookmarks: List<Bookmark> = emptyList(),
    val hiddenPapers: List<String> = emptyList(),
    val onlyShowFavorites: Boolean = false,
    val homeCategories: List<HomeCategoryPref> = DefaultHomeCategories,
    val themeMode: String = "dark", // system | light | dark
    val themeStyle: String = "amoled", // normal | amoled | sepia
    val accent: Int = 4, // default pink/salmon accent
    val hideAds: Boolean = true,
    val gridView: Boolean = false,
    val readerNight: Boolean = false,
    val readerDesktop: Boolean = false,
    val readerZoom: Int = 100,
    val textScale: Float = 1.0f,
    val articleFontSize: Int = 18,
    val articleLineHeight: Float = 1.7f,
    val readingDensity: String = "comfortable", // comfortable | compact
    val offlineMode: String = "wifi_only", // auto | wifi_only | manual
    val notificationTopics: List<String> = listOf("breaking", "bd")
)

class AppStateRepository(private val context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("potrikaghor_prefs", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    private val _state = MutableStateFlow(AppSettings())
    val state: StateFlow<AppSettings> = _state.asStateFlow()

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    init {
        loadFromPrefs()
    }

    private fun loadFromPrefs() {
        val customPapers = try {
            val raw = prefs.getString("custom_json", null)
            if (!raw.isNullOrEmpty()) json.decodeFromString<List<Paper>>(raw) else emptyList()
        } catch (_: Exception) {
            emptyList()
        }

        val urlFixes = try {
            val raw = prefs.getString("url_fixes_json", null)
            if (!raw.isNullOrEmpty()) json.decodeFromString<Map<String, String>>(raw) else emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }

        val favs = try {
            val raw = prefs.getString("favs_json", null)
            if (!raw.isNullOrEmpty()) json.decodeFromString<List<String>>(raw) else emptyList()
        } catch (_: Exception) {
            emptyList()
        }

        val recents = try {
            val raw = prefs.getString("recents_json", null)
            if (!raw.isNullOrEmpty()) json.decodeFromString<List<String>>(raw) else emptyList()
        } catch (_: Exception) {
            emptyList()
        }

        val bookmarks = try {
            val raw = prefs.getString("bookmarks_json", null)
            if (!raw.isNullOrEmpty()) json.decodeFromString<List<Bookmark>>(raw) else emptyList()
        } catch (_: Exception) {
            emptyList()
        }

        val hiddenPapers = try {
            val raw = prefs.getString("hidden_papers_json", null)
            if (!raw.isNullOrEmpty()) json.decodeFromString<List<String>>(raw) else emptyList()
        } catch (_: Exception) {
            emptyList()
        }

        val homeCats = try {
            val raw = prefs.getString("home_categories_json", null)
            if (!raw.isNullOrEmpty()) json.decodeFromString<List<HomeCategoryPref>>(raw) else DefaultHomeCategories
        } catch (_: Exception) {
            DefaultHomeCategories
        }

        val notifTopics = try {
            val raw = prefs.getString("notif_topics_json", null)
            if (!raw.isNullOrEmpty()) json.decodeFromString<List<String>>(raw) else listOf("breaking", "bd")
        } catch (_: Exception) {
            listOf("breaking", "bd")
        }

        val themeMode = prefs.getString("theme_mode", "dark") ?: "dark"
        val themeStyle = prefs.getString("theme_style", "amoled") ?: "amoled"
        val accent = prefs.getInt("accent", 4).coerceIn(0, 6)
        val hideAds = prefs.getBoolean("hide_ads", true)
        val gridView = prefs.getBoolean("grid_view", false)
        val readerNight = prefs.getBoolean("reader_night", false)
        val readerDesktop = prefs.getBoolean("reader_desktop", false)
        val readerZoom = prefs.getInt("reader_zoom", 100).coerceIn(70, 200)
        val textScale = prefs.getFloat("text_scale", 1.0f).coerceIn(0.85f, 1.4f)
        val articleFontSize = prefs.getInt("article_font_size", 18).coerceIn(14, 28)
        val articleLineHeight = prefs.getFloat("article_line_height", 1.7f).coerceIn(1.3f, 2.4f)
        val readingDensity = prefs.getString("reading_density", "comfortable") ?: "comfortable"
        val offlineMode = prefs.getString("offline_mode", "wifi_only") ?: "wifi_only"
        val onlyShowFavorites = prefs.getBoolean("only_show_favorites", false)

        _state.value = AppSettings(
            customPapers = customPapers,
            urlFixes = urlFixes,
            favs = favs,
            recents = recents,
            bookmarks = bookmarks,
            hiddenPapers = hiddenPapers,
            onlyShowFavorites = onlyShowFavorites,
            homeCategories = homeCats,
            themeMode = themeMode,
            themeStyle = themeStyle,
            accent = accent,
            hideAds = hideAds,
            gridView = gridView,
            readerNight = readerNight,
            readerDesktop = readerDesktop,
            readerZoom = readerZoom,
            textScale = textScale,
            articleFontSize = articleFontSize,
            articleLineHeight = articleLineHeight,
            readingDensity = readingDensity,
            offlineMode = offlineMode,
            notificationTopics = notifTopics
        )
        _isReady.value = true
    }

    val allPapers: List<Paper>
        get() {
            val cur = _state.value
            val fixedBuiltIns = PapersData.builtInPapers.map { p ->
                val f = cur.urlFixes[p.url]
                val isHidden = cur.hiddenPapers.contains(p.url)
                val base = if (f != null) p.copy(fix = f) else p
                base.copy(hidden = isHidden)
            }
            val customs = cur.customPapers.map { p ->
                p.copy(hidden = cur.hiddenPapers.contains(p.url))
            }
            val combined = customs + fixedBuiltIns
            return if (cur.onlyShowFavorites) {
                combined.filter { cur.favs.contains(it.url) }
            } else {
                combined
            }
        }

    fun paperFor(url: String): Paper? {
        val cur = _state.value
        cur.customPapers.firstOrNull { it.url == url }?.let { return it }
        val b = PapersData.builtInPaperFor(url) ?: return null
        val f = cur.urlFixes[b.url]
        val isHidden = cur.hiddenPapers.contains(b.url)
        val fixed = if (f != null) b.copy(fix = f) else b
        return fixed.copy(hidden = isHidden)
    }

    fun isFav(url: String): Boolean = _state.value.favs.contains(url)

    fun toggleFav(url: String) {
        val curFavs = _state.value.favs.toMutableList()
        if (curFavs.contains(url)) {
            curFavs.remove(url)
        } else {
            curFavs.add(0, url)
        }
        _state.value = _state.value.copy(favs = curFavs)
        prefs.edit().putString("favs_json", json.encodeToString(curFavs)).apply()
    }

    fun removeFav(url: String) {
        val curFavs = _state.value.favs.toMutableList()
        if (curFavs.remove(url)) {
            _state.value = _state.value.copy(favs = curFavs)
            prefs.edit().putString("favs_json", json.encodeToString(curFavs)).apply()
        }
    }

    fun restoreFav(url: String, index: Int) {
        val curFavs = _state.value.favs.toMutableList()
        if (!curFavs.contains(url)) {
            val safeIdx = index.coerceIn(0, curFavs.size)
            curFavs.add(safeIdx, url)
            _state.value = _state.value.copy(favs = curFavs)
            prefs.edit().putString("favs_json", json.encodeToString(curFavs)).apply()
        }
    }

    fun reorderFavs(fromIndex: Int, toIndex: Int) {
        val curFavs = _state.value.favs.toMutableList()
        if (fromIndex in curFavs.indices && toIndex in curFavs.indices) {
            val item = curFavs.removeAt(fromIndex)
            curFavs.add(toIndex, item)
            _state.value = _state.value.copy(favs = curFavs)
            prefs.edit().putString("favs_json", json.encodeToString(curFavs)).apply()
        }
    }

    fun toggleHidePaper(url: String) {
        val cur = _state.value.hiddenPapers.toMutableList()
        if (cur.contains(url)) {
            cur.remove(url)
        } else {
            cur.add(url)
        }
        _state.value = _state.value.copy(hiddenPapers = cur)
        prefs.edit().putString("hidden_papers_json", json.encodeToString(cur)).apply()
    }

    fun setOnlyShowFavorites(enabled: Boolean) {
        _state.value = _state.value.copy(onlyShowFavorites = enabled)
        prefs.edit().putBoolean("only_show_favorites", enabled).apply()
    }

    fun addRecent(url: String) {
        val curRecents = _state.value.recents.toMutableList()
        curRecents.remove(url)
        curRecents.add(0, url)
        val limited = curRecents.take(12)
        _state.value = _state.value.copy(recents = limited)
        prefs.edit().putString("recents_json", json.encodeToString(limited)).apply()
    }

    fun isBookmarked(url: String): Boolean = _state.value.bookmarks.any { it.url == url }

    fun addBookmark(bookmark: Bookmark) {
        val cur = _state.value.bookmarks.toMutableList()
        cur.removeAll { it.url == bookmark.url }
        cur.add(0, bookmark)
        _state.value = _state.value.copy(bookmarks = cur)
        prefs.edit().putString("bookmarks_json", json.encodeToString(cur)).apply()
    }

    fun removeBookmark(url: String) {
        val cur = _state.value.bookmarks.toMutableList()
        cur.removeAll { it.url == url }
        _state.value = _state.value.copy(bookmarks = cur)
        prefs.edit().putString("bookmarks_json", json.encodeToString(cur)).apply()
    }

    fun restoreBookmark(bookmark: Bookmark, index: Int) {
        val cur = _state.value.bookmarks.toMutableList()
        if (cur.none { it.url == bookmark.url }) {
            val safeIdx = index.coerceIn(0, cur.size)
            cur.add(safeIdx, bookmark)
            _state.value = _state.value.copy(bookmarks = cur)
            prefs.edit().putString("bookmarks_json", json.encodeToString(cur)).apply()
        }
    }

    fun updateBookmarkFolder(url: String, newFolder: String) {
        val cur = _state.value.bookmarks.map { b ->
            if (b.url == url) b.copy(folder = newFolder) else b
        }
        _state.value = _state.value.copy(bookmarks = cur)
        prefs.edit().putString("bookmarks_json", json.encodeToString(cur)).apply()
    }

    fun saveBookmarkOfflineContent(url: String, content: String) {
        val cur = _state.value.bookmarks.map { b ->
            if (b.url == url) b.copy(offlineContent = content) else b
        }
        _state.value = _state.value.copy(bookmarks = cur)
        prefs.edit().putString("bookmarks_json", json.encodeToString(cur)).apply()
    }

    fun addCustomPaper(paper: Paper) {
        val cur = _state.value.customPapers.toMutableList()
        cur.add(0, paper.copy(custom = true))
        _state.value = _state.value.copy(customPapers = cur)
        prefs.edit().putString("custom_json", json.encodeToString(cur)).apply()
    }

    fun updateCustomPaper(oldUrl: String, updated: Paper) {
        val cur = _state.value.customPapers.toMutableList()
        val idx = cur.indexOfFirst { it.url == oldUrl }
        if (idx >= 0) {
            cur[idx] = updated.copy(custom = true)
            _state.value = _state.value.copy(customPapers = cur)
            prefs.edit().putString("custom_json", json.encodeToString(cur)).apply()
        }
    }

    fun removeCustomPaper(url: String) {
        val cur = _state.value.customPapers.toMutableList()
        cur.removeAll { it.url == url }
        val curFavs = _state.value.favs.toMutableList()
        curFavs.remove(url)
        val curRecents = _state.value.recents.toMutableList()
        curRecents.remove(url)
        _state.value = _state.value.copy(
            customPapers = cur,
            favs = curFavs,
            recents = curRecents
        )
        prefs.edit()
            .putString("custom_json", json.encodeToString(cur))
            .putString("favs_json", json.encodeToString(curFavs))
            .putString("recents_json", json.encodeToString(curRecents))
            .apply()
    }

    fun setUrlFix(originalUrl: String, fixedUrl: String?) {
        val cur = _state.value.urlFixes.toMutableMap()
        if (fixedUrl.isNullOrEmpty() || fixedUrl == originalUrl) {
            cur.remove(originalUrl)
        } else {
            cur[originalUrl] = fixedUrl
        }
        _state.value = _state.value.copy(urlFixes = cur)
        prefs.edit().putString("url_fixes_json", json.encodeToString(cur)).apply()
    }

    fun updateHomeCategories(categories: List<HomeCategoryPref>) {
        _state.value = _state.value.copy(homeCategories = categories)
        prefs.edit().putString("home_categories_json", json.encodeToString(categories)).apply()
    }

    fun toggleHomeCategory(id: String, enabled: Boolean) {
        val updated = _state.value.homeCategories.map {
            if (it.id == id) it.copy(enabled = enabled) else it
        }
        updateHomeCategories(updated)
    }

    fun reorderHomeCategories(fromIndex: Int, toIndex: Int) {
        val list = _state.value.homeCategories.toMutableList()
        if (fromIndex in list.indices && toIndex in list.indices) {
            val item = list.removeAt(fromIndex)
            list.add(toIndex, item)
            val reIndexed = list.mapIndexed { idx, it -> it.copy(order = idx) }
            updateHomeCategories(reIndexed)
        }
    }

    fun setThemeMode(mode: String) {
        _state.value = _state.value.copy(themeMode = mode)
        prefs.edit().putString("theme_mode", mode).apply()
    }

    fun setThemeStyle(style: String) {
        val mode = when (style) {
            "amoled" -> "dark"
            "sepia" -> "light"
            else -> _state.value.themeMode
        }
        _state.value = _state.value.copy(themeStyle = style, themeMode = mode)
        prefs.edit()
            .putString("theme_style", style)
            .putString("theme_mode", mode)
            .apply()
    }

    fun setAccent(accent: Int) {
        val a = accent.coerceIn(0, 6)
        _state.value = _state.value.copy(accent = a)
        prefs.edit().putInt("accent", a).apply()
    }

    fun setHideAds(hide: Boolean) {
        _state.value = _state.value.copy(hideAds = hide)
        prefs.edit().putBoolean("hide_ads", hide).apply()
    }

    fun setGridView(grid: Boolean) {
        _state.value = _state.value.copy(gridView = grid)
        prefs.edit().putBoolean("grid_view", grid).apply()
    }

    fun setReaderNight(night: Boolean) {
        _state.value = _state.value.copy(readerNight = night)
        prefs.edit().putBoolean("reader_night", night).apply()
    }

    fun setReaderDesktop(desktop: Boolean) {
        _state.value = _state.value.copy(readerDesktop = desktop)
        prefs.edit().putBoolean("reader_desktop", desktop).apply()
    }

    fun setReaderZoom(zoom: Int) {
        val z = zoom.coerceIn(70, 200)
        _state.value = _state.value.copy(readerZoom = z)
        prefs.edit().putInt("reader_zoom", z).apply()
    }

    fun setTextScale(scale: Float) {
        val s = scale.coerceIn(0.85f, 1.4f)
        _state.value = _state.value.copy(textScale = s)
        prefs.edit().putFloat("text_scale", s).apply()
    }

    fun setArticleFontSize(size: Int) {
        val s = size.coerceIn(14, 28)
        _state.value = _state.value.copy(articleFontSize = s)
        prefs.edit().putInt("article_font_size", s).apply()
    }

    fun setArticleLineHeight(height: Float) {
        val h = height.coerceIn(1.3f, 2.4f)
        _state.value = _state.value.copy(articleLineHeight = h)
        prefs.edit().putFloat("article_line_height", h).apply()
    }

    fun setReadingDensity(density: String) {
        _state.value = _state.value.copy(readingDensity = density)
        prefs.edit().putString("reading_density", density).apply()
    }

    fun setOfflineMode(mode: String) {
        _state.value = _state.value.copy(offlineMode = mode)
        prefs.edit().putString("offline_mode", mode).apply()
    }

    fun toggleNotificationTopic(topic: String) {
        val cur = _state.value.notificationTopics.toMutableList()
        if (cur.contains(topic)) cur.remove(topic) else cur.add(topic)
        _state.value = _state.value.copy(notificationTopics = cur)
        prefs.edit().putString("notif_topics_json", json.encodeToString(cur)).apply()
    }

    fun isStoryNotified(storyKey: String): Boolean {
        val notified = try {
            val raw = prefs.getString("notified_stories_json", null)
            if (!raw.isNullOrEmpty()) json.decodeFromString<List<String>>(raw) else emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        return notified.contains(storyKey)
    }

    fun markStoryNotified(storyKey: String) {
        val notified = try {
            val raw = prefs.getString("notified_stories_json", null)
            if (!raw.isNullOrEmpty()) json.decodeFromString<List<String>>(raw).toMutableList() else mutableListOf()
        } catch (_: Exception) {
            mutableListOf()
        }
        notified.remove(storyKey)
        notified.add(0, storyKey)
        val trimmed = notified.take(50)
        prefs.edit().putString("notified_stories_json", json.encodeToString(trimmed)).apply()
    }

    fun exportBackupJson(): String {
        val s = _state.value
        val root = buildJsonObject {
            put("app", "potrikaghor")
            put("v", 2)
            put("favs", json.encodeToJsonElement(s.favs))
            put("recents", json.encodeToJsonElement(s.recents))
            put("bookmarks", json.encodeToJsonElement(s.bookmarks))
            put("custom", json.encodeToJsonElement(s.customPapers))
            put("fixes", json.encodeToJsonElement(s.urlFixes))
            put("hidden", json.encodeToJsonElement(s.hiddenPapers))
            put("home_categories", json.encodeToJsonElement(s.homeCategories))
            put("settings", buildJsonObject {
                val themeInt = when (s.themeMode) {
                    "light" -> 1
                    "dark" -> 2
                    else -> 0
                }
                put("theme", themeInt)
                put("textScale", s.textScale.toDouble())
                put("grid", s.gridView)
                put("readerNight", s.readerNight)
                put("readerDesktop", s.readerDesktop)
                put("readerZoom", s.readerZoom)
                put("themeStyle", s.themeStyle)
                put("accent", s.accent)
                put("hideAds", s.hideAds)
                put("density", s.readingDensity)
                put("articleFontSize", s.articleFontSize)
                put("articleLineHeight", s.articleLineHeight.toDouble())
            })
        }
        return json.encodeToString(root)
    }

    fun importBackupJson(raw: String): String? {
        return try {
            val element = json.parseToJsonElement(raw.trim()).jsonObject
            if (element["app"]?.jsonPrimitive?.content != "potrikaghor") {
                return "এটি পত্রিকাঘরের ব্যাকআপ কোড নয়"
            }
            val favs = element["favs"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            val recents = element["recents"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            val bookmarks = element["bookmarks"]?.jsonArray?.map {
                json.decodeFromJsonElement(Bookmark.serializer(), it)
            } ?: emptyList()
            val custom = element["custom"]?.jsonArray?.map {
                json.decodeFromJsonElement(Paper.serializer(), it)
            } ?: emptyList()
            val fixes = element["fixes"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
            val hidden = element["hidden"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            val homeCats = element["home_categories"]?.jsonArray?.map {
                json.decodeFromJsonElement(HomeCategoryPref.serializer(), it)
            } ?: DefaultHomeCategories

            val settings = element["settings"]?.jsonObject
            val themeInt = settings?.get("theme")?.jsonPrimitive?.intOrNull ?: 2
            val themeMode = when (themeInt) {
                1 -> "light"
                2 -> "dark"
                else -> "system"
            }
            val textScale = (settings?.get("textScale")?.jsonPrimitive?.doubleOrNull ?: 1.0).toFloat().coerceIn(0.85f, 1.4f)
            val grid = settings?.get("grid")?.jsonPrimitive?.booleanOrNull ?: false
            val readerNight = settings?.get("readerNight")?.jsonPrimitive?.booleanOrNull ?: false
            val readerDesktop = settings?.get("readerDesktop")?.jsonPrimitive?.booleanOrNull ?: false
            val readerZoom = (settings?.get("readerZoom")?.jsonPrimitive?.intOrNull ?: 100).coerceIn(70, 200)
            val themeStyle = settings?.get("themeStyle")?.jsonPrimitive?.content ?: "amoled"
            val accent = (settings?.get("accent")?.jsonPrimitive?.intOrNull ?: 4).coerceIn(0, 6)
            val hideAds = settings?.get("hideAds")?.jsonPrimitive?.booleanOrNull ?: true
            val density = settings?.get("density")?.jsonPrimitive?.content ?: "comfortable"
            val articleFontSize = settings?.get("articleFontSize")?.jsonPrimitive?.intOrNull ?: 18
            val articleLineHeight = (settings?.get("articleLineHeight")?.jsonPrimitive?.doubleOrNull ?: 1.7).toFloat()

            _state.value = AppSettings(
                customPapers = custom,
                urlFixes = fixes,
                favs = favs,
                recents = recents,
                bookmarks = bookmarks,
                hiddenPapers = hidden,
                homeCategories = homeCats,
                themeMode = themeMode,
                themeStyle = themeStyle,
                accent = accent,
                hideAds = hideAds,
                gridView = grid,
                readerNight = readerNight,
                readerDesktop = readerDesktop,
                readerZoom = readerZoom,
                textScale = textScale,
                articleFontSize = articleFontSize,
                articleLineHeight = articleLineHeight,
                readingDensity = density
            )

            prefs.edit()
                .putString("custom_json", json.encodeToString(custom))
                .putString("url_fixes_json", json.encodeToString(fixes))
                .putString("favs_json", json.encodeToString(favs))
                .putString("recents_json", json.encodeToString(recents))
                .putString("bookmarks_json", json.encodeToString(bookmarks))
                .putString("hidden_papers_json", json.encodeToString(hidden))
                .putString("home_categories_json", json.encodeToString(homeCats))
                .putString("theme_mode", themeMode)
                .putString("theme_style", themeStyle)
                .putInt("accent", accent)
                .putBoolean("hide_ads", hideAds)
                .putBoolean("grid_view", grid)
                .putBoolean("reader_night", readerNight)
                .putBoolean("reader_desktop", readerDesktop)
                .putInt("reader_zoom", readerZoom)
                .putFloat("text_scale", textScale)
                .putInt("article_font_size", articleFontSize)
                .putFloat("article_line_height", articleLineHeight)
                .putString("reading_density", density)
                .apply()

            null
        } catch (_: Exception) {
            "ব্যাকআপ কোডটি পড়া যায়নি। পুরোটা কপি করেছেন কিনা দেখুন"
        }
    }

    fun clearUserData() {
        _state.value = _state.value.copy(
            favs = emptyList(),
            recents = emptyList(),
            bookmarks = emptyList()
        )
        prefs.edit()
            .remove("favs_json")
            .remove("recents_json")
            .remove("bookmarks_json")
            .apply()
    }
}
