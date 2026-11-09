package com.potrikaghor

import android.content.SharedPreferences
import com.potrikaghor.data.AppStateRepository
import com.potrikaghor.data.ArticleExtractionCoordinator
import com.potrikaghor.data.ArticleExtractor
import com.potrikaghor.data.CachedCoverageResult
import com.potrikaghor.data.CleanArticle
import com.potrikaghor.data.DirectRssFeedSpec
import com.potrikaghor.data.NewsRepository
import com.potrikaghor.model.Coverage
import com.potrikaghor.model.HomeFeedMode
import com.potrikaghor.model.NewsItem
import com.potrikaghor.ui.screens.COVERAGE_LOAD_ERROR_MESSAGE
import com.potrikaghor.ui.screens.CoverageBodyState
import com.potrikaghor.ui.screens.CoverageEnterAction
import com.potrikaghor.ui.screens.HOME_LOAD_ERROR_MESSAGE
import com.potrikaghor.ui.screens.HomeBodyState
import com.potrikaghor.ui.screens.HomeFeedEnterAction
import com.potrikaghor.ui.screens.HomeLoadStateSnapshot
import com.potrikaghor.ui.screens.LazyTtsLifecycleState
import com.potrikaghor.ui.screens.applyCoverageLoadResult
import com.potrikaghor.ui.screens.applyHomeLoadResult
import com.potrikaghor.ui.screens.beginCoverageRefreshState
import com.potrikaghor.ui.screens.buildStableFeedStoryKeys
import com.potrikaghor.ui.screens.coverageStableKey
import com.potrikaghor.ui.screens.createInitialCoverageSnapshot
import com.potrikaghor.ui.screens.resolveCoverageBodyState
import com.potrikaghor.ui.screens.resolveCoverageEnterAction
import com.potrikaghor.ui.screens.resolveHomeBodyState
import com.potrikaghor.ui.screens.resolveHomeFeedEnterAction
import com.potrikaghor.ui.screens.shouldUpdateWebProgress
import com.potrikaghor.ui.screens.stableArticleIdentifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference


/**
 * Phase 1 Verification Suite for PotrikaGhor Core News Engine:
 * 1. API 24-compatible date parsing (RFC-822, RFC-1123, ISO-8601)
 * 2. "+06:00" and "-05:00" colon offset parsing
 * 3. Ambiguous timezone abbreviation ("EST", "PST", missing tz) -> null
 * 4. Invalid / malformed timestamp -> null
 * 5. Future timestamp guard (> now + 10m -> null)
 * 6. Pinned stale article (>7d in fresh feed) vs feed-level stale (>72h)
 * 7. Bangla duplicate detection true positive
 * 8. Bangla duplicate detection false positive prevention ("কাদের", different districts, >12h)
 * 9. Canonical publisher identity ("প্রথম আলো" == "Prothom Alo", unknown stays unmerged)
 * 10. Latest News strict chronological sorting (newest first, null last, newest headline)
 * 11. Zero-drop source diversity (max 2 consecutive from same publisher, 0 dropped)
 * 12. Top Stories time-decay, favorite tie-breaker, and zero image/keyword bias
 * 13. Manual refresh forceRefresh cache bypass
 */
class NewsFeedAlgorithmTest {

    private val repo = NewsRepository()
    private val fixedNowMs = 1762452000000L // 2025-11-06T18:00:00Z

    private fun checkEquals(expected: Any?, actual: Any?, message: String) {
        if (expected != actual) {
            throw AssertionError("$message — expected: <$expected>, actual: <$actual>")
        }
    }

    private fun checkTrue(condition: Boolean, message: String) {
        if (!condition) {
            throw AssertionError(message)
        }
    }

    private fun checkFalse(condition: Boolean, message: String) {
        if (condition) {
            throw AssertionError(message)
        }
    }

    private fun checkNull(actual: Any?, message: String) {
        if (actual != null) {
            throw AssertionError("$message — expected null, actual: <$actual>")
        }
    }

    private fun checkNotNull(actual: Any?, message: String) {
        if (actual == null) {
            throw AssertionError("$message — expected non-null value")
        }
    }

    @Test
    fun test01_api24CompatibleDateParsing() {
        val rfcGmt = repo.parseDate("Thu, 06 Nov 2025 16:30:00 GMT", fixedNowMs)
        checkEquals(1762446600000L, rfcGmt, "RFC-1123 GMT timestamp should parse accurately")

        val rfcNumeric = repo.parseDate("Thu, 06 Nov 2025 22:30:00 +0600", fixedNowMs)
        checkEquals(1762446600000L, rfcNumeric, "RFC-822 +0600 timestamp should equal 16:30 UTC")

        val isoUtc = repo.parseDate("2025-11-06T16:30:00Z", fixedNowMs)
        checkEquals(1762446600000L, isoUtc, "ISO-8601 Z timestamp should parse accurately")

        val isoMillisUtc = repo.parseDate("2025-11-06T16:30:00.250Z", fixedNowMs)
        checkEquals(1762446600250L, isoMillisUtc, "ISO-8601 fractional Z timestamp should preserve ms")
    }

    @Test
    fun test02_colonOffsetParsing() {
        // +06:00 in RFC-822 (used by Jugantor, Samakal, Somoy TV, Bangla Tribune, Prothom Alo)
        val rfcColonPlus6 = repo.parseDate("Thu, 06 Nov 2025 22:30:00 +06:00", fixedNowMs)
        checkEquals(1762446600000L, rfcColonPlus6, "RFC-822 +06:00 must normalize and parse")

        // -05:00 in RFC-822 (used by NYT / US feeds)
        val rfcColonMinus5 = repo.parseDate("Thu, 06 Nov 2025 11:30:00 -05:00", fixedNowMs)
        checkEquals(1762446600000L, rfcColonMinus5, "RFC-822 -05:00 must normalize and parse")

        // ISO-8601 with +06:00
        val isoColonPlus6 = repo.parseDate("2025-11-06T22:30:00+06:00", fixedNowMs)
        checkEquals(1762446600000L, isoColonPlus6, "ISO-8601 +06:00 must normalize and parse")

        // ISO-8601 with -05:00
        val isoColonMinus5 = repo.parseDate("2025-11-06T11:30:00-05:00", fixedNowMs)
        checkEquals(1762446600000L, isoColonMinus5, "ISO-8601 -05:00 must normalize and parse")
    }

    @Test
    fun test03_ambiguousTimezoneReturnsNull() {
        checkNull(
            repo.parseDate("Thu, 06 Nov 2025 11:30:00 EST", fixedNowMs),
            "EST must be rejected as ambiguous"
        )
        checkNull(
            repo.parseDate("Thu, 06 Nov 2025 11:30:00 PST", fixedNowMs),
            "PST must be rejected as ambiguous"
        )
        checkNull(
            repo.parseDate("Thu, 06 Nov 2025 11:30:00 BST", fixedNowMs),
            "BST (Bangladesh vs British Summer Time) must be rejected as ambiguous"
        )
        checkNull(
            repo.parseDate("Thu, 06 Nov 2025 11:30:00 IST", fixedNowMs),
            "IST must be rejected as ambiguous"
        )
        checkNull(
            repo.parseDate("2025-11-06 16:30:00", fixedNowMs),
            "Timestamp without explicit timezone must return null"
        )
    }

    @Test
    fun test04_invalidTimestampReturnsNull() {
        checkNull(repo.parseDate("", fixedNowMs), "Empty string must return null")
        checkNull(repo.parseDate("   ", fixedNowMs), "Blank string must return null")
        checkNull(repo.parseDate("not-a-date", fixedNowMs), "Garbage string must return null")
        checkNull(
            repo.parseDate("Thu, 32 Nov 2025 16:30:00 GMT", fixedNowMs),
            "Impossible day-of-month (32 Nov) must return null with isLenient=false"
        )
    }

    @Test
    fun test05_futureTimestampGuard() {
        val fiveMinFuture = repo.parseDate("Thu, 06 Nov 2025 18:05:00 GMT", fixedNowMs)
        checkNotNull(fiveMinFuture, "Timestamp +5 minutes ahead should be accepted within +10m guard")

        val fifteenMinFuture = repo.parseDate("Thu, 06 Nov 2025 18:15:00 GMT", fixedNowMs)
        checkNull(fifteenMinFuture, "Timestamp +15 minutes ahead must return null")

        val futureYear = repo.parseDate("Sun, 21 Jun 2026 00:00:00 +06:00", fixedNowMs)
        checkNull(futureYear, "Future year timestamp must return null")
    }

    @Test
    fun test06_pinnedStaleItemFilteringAndFeedStaleCheck() {
        val oneHourAgo = fixedNowMs - 3600_000L
        val twoHoursAgo = fixedNowMs - 2 * 3600_000L
        val tenDaysAgo = fixedNowMs - 10 * 24 * 3600_000L
        val fiveDaysAgo = fixedNowMs - 5 * 24 * 3600_000L

        val freshDirectFeedWithPinnedOldItem = listOf(
            NewsItem(
                title = "Fresh 1",
                source = "ডিডব্লিউ বাংলা",
                sourceUrl = "https://www.dw.com/bn",
                link = "https://www.dw.com/bn/1",
                time = oneHourAgo
            ),
            NewsItem(
                title = "Fresh 2",
                source = "ডিডব্লিউ বাংলা",
                sourceUrl = "https://www.dw.com/bn",
                link = "https://www.dw.com/bn/2",
                time = twoHoursAgo
            ),
            NewsItem(
                title = "Pinned Old Item",
                source = "ডিডব্লিউ বাংলা",
                sourceUrl = "https://www.dw.com/bn",
                link = "https://www.dw.com/bn/old",
                time = tenDaysAgo
            )
        )

        checkFalse(
            repo.isFeedStale(freshDirectFeedWithPinnedOldItem, fixedNowMs),
            "Feed with 1h-old item must NOT be marked stale"
        )

        val filtered = repo.filterPinnedStaleDirectItems(freshDirectFeedWithPinnedOldItem, fixedNowMs)
        checkEquals(2, filtered.size, "Pinned >7d old item in an active fresh feed must be removed")
        checkTrue(filtered.none { it.link.endsWith("/old") }, "Old pinned item must not remain")

        val completelyStaleFeed = listOf(
            NewsItem(
                title = "Old 1",
                source = "CNN",
                sourceUrl = "https://cnn.com",
                link = "https://cnn.com/1",
                time = fiveDaysAgo
            ),
            NewsItem(
                title = "Old 2",
                source = "CNN",
                sourceUrl = "https://cnn.com",
                link = "https://cnn.com/2",
                time = tenDaysAgo
            )
        )
        checkTrue(
            repo.isFeedStale(completelyStaleFeed, fixedNowMs),
            "Feed whose newest article is 5 days (>72h) old must be flagged stale"
        )
    }

    @Test
    fun test07_banglaDuplicateTruePositive() {
        val t1 = fixedNowMs - 30 * 60_000L
        val t2 = fixedNowMs - 15 * 60_000L

        val itemA = NewsItem(
            title = "চট্টগ্রাম বন্দরে কনটেইনার জট নিরসনে নতুন নির্দেশনা জারি করেছে কর্তৃপক্ষ",
            source = "প্রথম আলো",
            sourceUrl = "https://www.prothomalo.com",
            link = "https://www.prothomalo.com/bangladesh/101",
            time = t1
        )
        val itemB = NewsItem(
            title = "চট্টগ্রাম বন্দরের কনটেইনার জট নিরসনে কর্তৃপক্ষের নতুন নির্দেশনা জারি",
            source = "যুগান্তর",
            sourceUrl = "https://www.jugantor.com",
            link = "https://www.jugantor.com/national/202",
            time = t2
        )

        checkTrue(
            repo.areArticlesDuplicate(itemA, itemB),
            "Same event reported by Prothom Alo and Jugantor with inflected Bangla tokens must match"
        )
    }

    @Test
    fun test08_banglaDuplicateFalsePositivePrevention() {
        val t1 = fixedNowMs - 20 * 60_000L
        val t2 = fixedNowMs - 10 * 60_000L

        // Case A: Different districts (Satkhira vs Madaripur road accident) must NEVER merge
        val satkhiraAccident = NewsItem(
            title = "সাতক্ষীরায় বাস ও ট্রাকের সংঘর্ষে তিনজন নিহত হয়েছেন এবং আহত পাঁচজন",
            source = "প্রথম আলো",
            sourceUrl = "https://www.prothomalo.com",
            link = "https://www.prothomalo.com/accident/1",
            time = t1
        )
        val madaripurAccident = NewsItem(
            title = "মাদারীপুরে বাস ও ট্রাকের সংঘর্ষে তিনজন নিহত হয়েছেন এবং আহত পাঁচজন",
            source = "যুগান্তর",
            sourceUrl = "https://www.jugantor.com",
            link = "https://www.jugantor.com/accident/2",
            time = t2
        )
        checkFalse(
            repo.areArticlesDuplicate(satkhiraAccident, madaripurAccident),
            "Accidents in different districts (Satkhira vs Madaripur) must NOT be merged"
        )

        // Case B: Protected Bangla word "কাদের" must NOT be stripped to "কাদ"
        checkEquals(
            "কাদের",
            repo.normalizeBanglaToken("কাদের"),
            "Protected word 'কাদের' must remain intact without suffix corruption"
        )

        // Case C: Articles > 12 hours apart must NOT merge
        val itemEarly = satkhiraAccident.copy(
            title = "রাজধানীতে মেট্রোরেল চলাচলে নতুন সময়সূচি ঘোষণা করেছে কর্তৃপক্ষ আজ",
            time = fixedNowMs - 14 * 3600_000L
        )
        val itemLate = satkhiraAccident.copy(
            title = "রাজধানীতে মেট্রোরেল চলাচলে নতুন সময়সূচি ঘোষণা করেছে কর্তৃপক্ষ আজ",
            source = "সমকাল",
            sourceUrl = "https://samakal.com",
            link = "https://samakal.com/metro/99",
            time = fixedNowMs - 1 * 3600_000L
        )
        checkFalse(
            repo.areArticlesDuplicate(itemEarly, itemLate),
            "Articles separated by >12 hours must not be merged as duplicates"
        )
    }

    @Test
    fun test09_canonicalPublisherIdentity() {
        // Priority 1: sourceUrl canonical ID resolution
        val fromUrl = repo.canonicalPublisherId("Unmapped Display Label", "https://www.prothomalo.com/bangladesh")
        checkEquals("prothomalo.com", fromUrl, "Priority 1: sourceUrl must resolve to canonical publisher ID")

        // Priority 2: Explicit reviewed alias resolution when sourceUrl is empty (e.g. Google News <li> items)
        val idBn = repo.canonicalPublisherId("প্রথম আলো", "https://www.prothomalo.com")
        val idEn = repo.canonicalPublisherId("Prothom Alo", "")
        checkEquals(idBn, idEn, "'প্রথম আলো' and 'Prothom Alo' must resolve to the same canonical publisher ID")

        val jagoBn = repo.canonicalPublisherId("জাগো নিউজ ২৪", "")
        val jagoEn = repo.canonicalPublisherId("Jagonews24", "https://www.jagonews24.com")
        checkEquals(jagoBn, jagoEn, "'জাগো নিউজ ২৪' and 'Jagonews24' must resolve to the same canonical ID")

        checkEquals(
            repo.canonicalPublisherId("BBC", "https://www.bbc.com"),
            repo.canonicalPublisherId("BBC", ""),
            "'BBC' with and without sourceUrl must resolve to bbc.com"
        )
        checkEquals(
            repo.canonicalPublisherId("Dhakatimes24", "https://www.dhakatimes24.com"),
            repo.canonicalPublisherId("Dhakatimes24", ""),
            "'Dhakatimes24' with and without sourceUrl must resolve to dhakatimes24.com"
        )
        checkEquals(
            repo.canonicalPublisherId("Bangladesh Sangbad Sangstha (BSS)", "https://www.bssnews.net"),
            repo.canonicalPublisherId("Bangladesh Sangbad Sangstha (BSS)", ""),
            "'Bangladesh Sangbad Sangstha (BSS)' with and without sourceUrl must resolve to bssnews.net"
        )

        // Priority 3: Unknown publishers must get distinct stable raw: IDs and NEVER merge together
        val unknownA = repo.canonicalPublisherId("অজানা বার্তা ডটকম", "")
        val unknownB = repo.canonicalPublisherId("নতুন সকাল নিউজ", "")
        checkTrue(unknownA.startsWith("raw:"), "Unknown publisher A must use unmerged raw: prefix")
        checkTrue(unknownB.startsWith("raw:"), "Unknown publisher B must use unmerged raw: prefix")
        checkFalse(unknownA == unknownB, "Distinct unknown publishers must never collide")

        // Cluster-level safety: same publisher appearing once with sourceUrl and once with empty sourceUrl
        // must NOT inflate Coverage.count in Top Stories
        val t = fixedNowMs - 15 * 60_000L
        val mainItem = NewsItem(
            title = "রাজধানীতে মেট্রোরেল চলাচলে নতুন সময়সূচি ঘোষণা করেছে কর্তৃপক্ষ আজ",
            source = "নতুন আঞ্চলিক বার্তা",
            sourceUrl = "https://natunanchalikbarta.com",
            link = "https://natunanchalikbarta.com/news/1",
            time = t,
            related = listOf(
                NewsItem(
                    title = "রাজধানীতে মেট্রোরেল চলাচলে নতুন সময়সূচি ঘোষণা করেছে কর্তৃপক্ষ আজ",
                    source = "নতুন আঞ্চলিক বার্তা",
                    sourceUrl = "",
                    link = "https://news.google.com/rss/articles/1",
                    time = t
                )
            )
        )
        val topClusters = repo.buildTopStories(listOf(mainItem), emptySet(), fixedNowMs)
        checkEquals(1, topClusters.size, "Must form 1 cluster")
        checkEquals(
            1,
            topClusters.first().count,
            "Same publisher with and without sourceUrl in the same cluster must count as 1 publisher, not 2"
        )
    }

    @Test
    fun test10_latestNewsStrictChronologicalSorting() {
        val tNewestNoImage = fixedNowMs - 5 * 60_000L
        val tMiddleWithImage = fixedNowMs - 25 * 60_000L
        val tOlderWithImage = fixedNowMs - 50 * 60_000L

        val rawItems = listOf(
            NewsItem(
                title = "পুরোনো সংবাদ কিন্তু ছবি আছে এখানে বিস্তারিত দেখুন",
                source = "যুগান্তর",
                sourceUrl = "https://www.jugantor.com",
                link = "https://www.jugantor.com/1",
                time = tOlderWithImage,
                imageUrl = "https://www.jugantor.com/img1.jpg"
            ),
            NewsItem(
                title = "একদম নতুন ব্রেকিং সংবাদ কোনো ছবি ছাড়াই প্রকাশিত হয়েছে",
                source = "প্রথম আলো",
                sourceUrl = "https://www.prothomalo.com",
                link = "https://www.prothomalo.com/1",
                time = tNewestNoImage,
                imageUrl = null
            ),
            NewsItem(
                title = "সময়বিহীন সংবাদ যা সবার শেষে থাকা উচিত সবসময়",
                source = "ইত্তেফাক",
                sourceUrl = "https://www.ittefaq.com.bd",
                link = "https://www.ittefaq.com.bd/null-time",
                time = null
            ),
            NewsItem(
                title = "মাঝারি সময়ের সংবাদ ছবি সহকারে প্রকাশিত হয়েছে আজ",
                source = "সমকাল",
                sourceUrl = "https://samakal.com",
                link = "https://samakal.com/1",
                time = tMiddleWithImage,
                imageUrl = "https://samakal.com/img2.jpg"
            )
        )

        val latest = repo.buildLatestNews(rawItems, fixedNowMs)

        checkEquals(4, latest.size, "All 4 distinct stories must be present")
        checkEquals(tNewestNoImage, latest[0].items.first().time, "Newest item WITHOUT image must still rank #1 in Latest News")
        checkEquals(tMiddleWithImage, latest[1].items.first().time, "Second newest item must rank #2")
        checkEquals(tOlderWithImage, latest[2].items.first().time, "Third newest item must rank #3")
        checkNull(latest[3].items.first().time, "Null-timestamp item must always be placed at the bottom")

        // Verify cluster headline is the newest report when two sources cover the same story
        val dupOlderWithImg = NewsItem(
            title = "চট্টগ্রাম বন্দরে কনটেইনার জট নিরসনে নতুন নির্দেশনা জারি করেছে কর্তৃপক্ষ",
            source = "যুগান্তর",
            sourceUrl = "https://www.jugantor.com",
            link = "https://www.jugantor.com/port-1",
            time = fixedNowMs - 40 * 60_000L,
            imageUrl = "https://www.jugantor.com/port.jpg"
        )
        val dupNewerNoImg = NewsItem(
            title = "চট্টগ্রাম বন্দরের কনটেইনার জট নিরসনে কর্তৃপক্ষের নতুন নির্দেশনা জারি",
            source = "প্রথম আলো",
            sourceUrl = "https://www.prothomalo.com",
            link = "https://www.prothomalo.com/port-2",
            time = fixedNowMs - 10 * 60_000L,
            imageUrl = null
        )
        val mergedCluster = repo.buildLatestNews(listOf(dupOlderWithImg, dupNewerNoImg), fixedNowMs)
        checkEquals(1, mergedCluster.size, "Duplicate stories must merge into 1 Coverage cluster")
        checkEquals(
            dupNewerNoImg.title,
            mergedCluster[0].headline,
            "Cluster headline must be the newest report's title"
        )
        checkEquals(
            "https://www.jugantor.com/port.jpg",
            mergedCluster[0].items.first().imageUrl,
            "Newest report must inherit image from earlier cluster item when its own imageUrl is null"
        )
    }

    @Test
    fun test11_zeroDropSourceDiversity() {
        val items = listOf(
            Coverage(listOf(NewsItem("PA 1", "প্রথম আলো", "https://www.prothomalo.com", "https://www.prothomalo.com/1", fixedNowMs - 1000L))),
            Coverage(listOf(NewsItem("PA 2", "প্রথম আলো", "https://www.prothomalo.com", "https://www.prothomalo.com/2", fixedNowMs - 2000L))),
            Coverage(listOf(NewsItem("PA 3", "প্রথম আলো", "https://www.prothomalo.com", "https://www.prothomalo.com/3", fixedNowMs - 3000L))),
            Coverage(listOf(NewsItem("PA 4", "প্রথম আলো", "https://www.prothomalo.com", "https://www.prothomalo.com/4", fixedNowMs - 4000L))),
            Coverage(listOf(NewsItem("JG 1", "যুগান্তর", "https://www.jugantor.com", "https://www.jugantor.com/1", fixedNowMs - 5000L))),
            Coverage(listOf(NewsItem("JG 2", "যুগান্তর", "https://www.jugantor.com", "https://www.jugantor.com/2", fixedNowMs - 6000L)))
        )

        val diversified = repo.applyZeroDropSourceDiversity(items, maxConsecutive = 2)
        checkEquals(6, diversified.size, "Zero-drop diversity must NEVER drop any news item")
        checkEquals("JG 1", diversified[2].headline, "3rd position must interleave Jugantor after 2 consecutive Prothom Alo items")
    }

    @Test
    fun test12_topStoriesTimeDecayAndFavoriteTieBreaker() {
        val thirtyMinAgo = fixedNowMs - 30 * 60_000L
        val twentyHoursAgo = fixedNowMs - 20 * 3600_000L

        val freshThreeSourceCluster = Coverage(
            items = listOf(
                NewsItem("Fresh 3-Source Story", "প্রথম আলো", "https://www.prothomalo.com", "https://www.prothomalo.com/a", thirtyMinAgo),
                NewsItem("Fresh 3-Source Story", "যুগান্তর", "https://www.jugantor.com", "https://www.jugantor.com/a", thirtyMinAgo - 60_000L),
                NewsItem("Fresh 3-Source Story", "সমকাল", "https://samakal.com", "https://samakal.com/a", thirtyMinAgo - 120_000L)
            )
        )

        val oldFourSourceCluster = Coverage(
            items = listOf(
                NewsItem("Old 4-Source Story", "প্রথম আলো", "https://www.prothomalo.com", "https://www.prothomalo.com/b", twentyHoursAgo, imageUrl = "https://img.com/1.jpg"),
                NewsItem("Old 4-Source Story", "যুগান্তর", "https://www.jugantor.com", "https://www.jugantor.com/b", twentyHoursAgo),
                NewsItem("Old 4-Source Story", "সমকাল", "https://samakal.com", "https://samakal.com/b", twentyHoursAgo),
                NewsItem("Old 4-Source Story", "ইত্তেফাক", "https://www.ittefaq.com.bd", "https://www.ittefaq.com.bd/b", twentyHoursAgo)
            )
        )

        val scoreFresh3 = repo.computeTopStoryScore(freshThreeSourceCluster, emptySet(), emptySet(), fixedNowMs)
        val scoreOld4 = repo.computeTopStoryScore(oldFourSourceCluster, emptySet(), emptySet(), fixedNowMs)
        checkTrue(
            scoreFresh3 > scoreOld4,
            "Fresh 3-source story (30m old) must outrank 20-hour-old 4-source story due to exponential time decay"
        )

        // Verify favorite publisher tie-breaker (+2.0) and zero image bonus
        val nonFavWithImage = Coverage(
            items = listOf(
                NewsItem("Non-Fav With Image", "যুগান্তর", "https://www.jugantor.com", "https://www.jugantor.com/c", thirtyMinAgo, imageUrl = "https://www.jugantor.com/pic.jpg")
            )
        )
        val favWithoutImage = Coverage(
            items = listOf(
                NewsItem("Fav Without Image", "প্রথম আলো", "https://www.prothomalo.com", "https://www.prothomalo.com/c", thirtyMinAgo, imageUrl = null)
            )
        )

        val scoreNonFav = repo.computeTopStoryScore(
            nonFavWithImage,
            favoriteCanonicalIds = setOf("prothomalo.com"),
            favoriteUrls = setOf("https://www.prothomalo.com"),
            nowMs = fixedNowMs
        )
        val scoreFav = repo.computeTopStoryScore(
            favWithoutImage,
            favoriteCanonicalIds = setOf("prothomalo.com"),
            favoriteUrls = setOf("https://www.prothomalo.com"),
            nowMs = fixedNowMs
        )
        checkTrue(
            scoreFav > scoreNonFav,
            "Favorite publisher (+2.0 bonus) without image must beat non-favorite with image (image bonus = 0)"
        )
    }

    @Test
    fun test13_manualRefreshForceBypass() = runBlocking {
        val freshRepo = NewsRepository()
        var networkFetchCount = 0

        val fakeFetcher: (DirectRssFeedSpec) -> List<NewsItem> = { spec ->
            networkFetchCount++
            listOf(
                NewsItem(
                    title = "Test Item from ${spec.sourceBn} #$networkFetchCount",
                    source = spec.sourceBn,
                    sourceUrl = spec.baseUrl,
                    link = "${spec.baseUrl}/item-$networkFetchCount",
                    time = System.currentTimeMillis() - 60_000L
                )
            )
        }

        // First call populates cache
        val firstResult = freshRepo.fetchDirectPublisherFeeds(
            isIntl = false,
            forceRefresh = false,
            fetcher = fakeFetcher
        )
        val countAfterFirst = networkFetchCount
        checkTrue(countAfterFirst > 0, "Initial fetch must invoke fetcher")
        checkTrue(firstResult.isNotEmpty(), "Initial fetch must return items")

        // Second call with forceRefresh = false must serve from cache without invoking fetcher
        val cachedResult = freshRepo.fetchDirectPublisherFeeds(
            isIntl = false,
            forceRefresh = false,
            fetcher = fakeFetcher
        )
        checkEquals(countAfterFirst, networkFetchCount, "forceRefresh=false within TTL must use cache without network calls")
        checkEquals(firstResult.size, cachedResult.size, "Cached result size must match initial fetch")

        // Third call with forceRefresh = true MUST bypass cache and re-invoke fetcher
        val forcedResult = freshRepo.fetchDirectPublisherFeeds(
            isIntl = false,
            forceRefresh = true,
            fetcher = fakeFetcher
        )
        checkTrue(networkFetchCount > countAfterFirst, "forceRefresh=true must bypass cache and fetch fresh data")
        checkTrue(forcedResult.isNotEmpty(), "Forced refresh must return fresh items")

        // Verify HomeFeedMode enum values exist
        checkEquals(2, HomeFeedMode.entries.size, "HomeFeedMode must contain TOP_STORIES and LATEST_NEWS")
    }

    @Test
    fun test14_publisherDeduplicationCollisionSafety() {
        val t = fixedNowMs - 15 * 60_000L
        val headline = "চট্টগ্রাম বন্দরে কনটেইনার জট নিরসনে নতুন নির্দেশনা জারি করেছে কর্তৃপক্ষ"

        // Case 1: একই Publisher-এর একই নাম ও একই canonical domain হলে একবার গণনা হবে
        val samePubSameDomainA = NewsItem(
            title = headline,
            source = "প্রথম আলো",
            sourceUrl = "https://www.prothomalo.com",
            link = "https://www.prothomalo.com/bangladesh/1",
            time = t
        )
        val samePubSameDomainB = NewsItem(
            title = headline,
            source = "প্রথম আলো",
            sourceUrl = "https://www.prothomalo.com/national",
            link = "https://www.prothomalo.com/bangladesh/2",
            time = t - 60_000L
        )
        val case1Clusters = repo.buildTopStories(listOf(samePubSameDomainA, samePubSameDomainB), emptySet(), fixedNowMs)
        checkEquals(1, case1Clusters.size, "Case 1: Must form 1 cluster")
        checkEquals(1, case1Clusters.first().count, "Case 1: Same publisher with same name and same canonical domain must be counted once")

        // Case 2: একই Publisher-এর URL-সহ মূল item এবং URL-বিহীন Google News related item হলে একবার গণনা হবে
        val mainWithUrl = NewsItem(
            title = headline,
            source = "নতুন আঞ্চলিক বার্তা",
            sourceUrl = "https://natunanchalikbarta.com",
            link = "https://natunanchalikbarta.com/news/10",
            time = t,
            related = listOf(
                NewsItem(
                    title = headline,
                    source = "নতুন আঞ্চলিক বার্তা",
                    sourceUrl = "",
                    link = "https://news.google.com/rss/articles/10",
                    time = t
                )
            )
        )
        val case2Clusters = repo.buildTopStories(listOf(mainWithUrl), emptySet(), fixedNowMs)
        checkEquals(1, case2Clusters.size, "Case 2: Must form 1 cluster")
        checkEquals(1, case2Clusters.first().count, "Case 2: Main item with URL and Google News related item without URL for same publisher must be counted once")

        // Case 3: দুটি ভিন্ন Publisher-এর নাম একই কিন্তু canonical domain আলাদা হলে দুটিই গণনা হবে
        // (শুধু নামের মিলের ভিত্তিতে ভিন্ন canonical domain-কে merge করা যাবে না)
        val diffDomainSameNameA = NewsItem(
            title = headline,
            source = "সিটি নিউজ",
            sourceUrl = "https://citynewsbd.com",
            link = "https://citynewsbd.com/1",
            time = t
        )
        val diffDomainSameNameB = NewsItem(
            title = headline,
            source = "সিটি নিউজ",
            sourceUrl = "https://ctgcitynews.com",
            link = "https://ctgcitynews.com/2",
            time = t - 30_000L
        )
        val case3Clusters = repo.buildTopStories(listOf(diffDomainSameNameA, diffDomainSameNameB), emptySet(), fixedNowMs)
        checkEquals(1, case3Clusters.size, "Case 3: Must form 1 event cluster")
        checkEquals(
            2,
            case3Clusters.first().count,
            "Case 3: Two different publishers with identical display name but distinct canonical domains must BOTH be counted (2)"
        )
        val singleScore = repo.computeTopStoryScore(Coverage(listOf(diffDomainSameNameA)), emptySet(), emptySet(), fixedNowMs)
        val dualScore = repo.computeTopStoryScore(case3Clusters.first(), emptySet(), emptySet(), fixedNowMs)
        checkTrue(
            dualScore > singleScore,
            "Case 3: computeTopStoryScore must count both distinct canonical domains even when their display names match"
        )

        // Case 4: দুটি ভিন্ন Unknown Publisher-এর নাম আলাদা হলে তারা আলাদা থাকবে
        val unknownPubA = NewsItem(
            title = headline,
            source = "অজানা বার্তা ডটকম",
            sourceUrl = "",
            link = "https://news.google.com/rss/articles/u1",
            time = t
        )
        val unknownPubB = NewsItem(
            title = headline,
            source = "নতুন সকাল নিউজ",
            sourceUrl = "",
            link = "https://news.google.com/rss/articles/u2",
            time = t - 20_000L
        )
        val case4Clusters = repo.buildTopStories(listOf(unknownPubA, unknownPubB), emptySet(), fixedNowMs)
        checkEquals(1, case4Clusters.size, "Case 4: Must form 1 event cluster")
        checkEquals(2, case4Clusters.first().count, "Case 4: Two distinct unknown publishers must remain separate and count as 2")

        // Case 5: Unknown Publisher-এর ক্ষেত্রে অনুমানভিত্তিক domain mapping হবে না
        val canonicalUnknownA = repo.canonicalPublisherId("অজানা বার্তা ডটকম", "")
        val canonicalUnknownB = repo.canonicalPublisherId("নতুন সকাল নিউজ", "")
        checkEquals("raw:অজানা বার্তা ডটকম", canonicalUnknownA, "Case 5: Unknown publisher A must not be mapped to a guessed domain")
        checkEquals("raw:নতুন সকাল নিউজ", canonicalUnknownB, "Case 5: Unknown publisher B must not be mapped to a guessed domain")
        checkFalse(canonicalUnknownA.contains(".com"), "Case 5: Unknown publisher ID must not fabricate a .com domain")
    }

    @Test
    fun test15_oneFeedTimingOutDoesNotBlockSuccessfulFeeds() = runBlocking {
        val freshRepo = NewsRepository()
        val now = System.currentTimeMillis()

        // Verify OkHttp clients have finite callTimeouts configured
        checkEquals(10_000, freshRepo.fastFeedClient.callTimeoutMillis, "fastFeedClient must have a 10s callTimeout")
        checkEquals(10_000, freshRepo.client.callTimeoutMillis, "client must have a 10s callTimeout")

        val startMs = System.currentTimeMillis()
        val results = freshRepo.fetchDirectPublisherFeeds(
            isIntl = false,
            forceRefresh = true,
            perFeedTimeoutMs = 150L,
            batchDeadlineMs = 800L,
            fetcher = { spec ->
                when (spec.sourceBn) {
                    "প্রথম আলো" -> {
                        // Simulate a stalled/hung feed that sleeps for 5 seconds
                        Thread.sleep(5_000L)
                        emptyList()
                    }
                    "বাংলা ট্রিবিউন" -> {
                        // Simulate an immediate HTTP failure on another feed in DIRECT_PUBLISHER_FEEDS
                        throw IOException("Simulated HTTP 503 failure")
                    }
                    else -> {
                        listOf(
                            NewsItem(
                                title = "${spec.sourceBn} তাজা সংবাদ শিরোনাম",
                                source = spec.sourceBn,
                                sourceUrl = spec.baseUrl,
                                link = "${spec.baseUrl}/article-1",
                                time = now - 60_000L
                            )
                        )
                    }
                }
            }
        )
        val elapsedMs = System.currentTimeMillis() - startMs

        val expectedDomesticCount = NewsRepository.DIRECT_PUBLISHER_FEEDS.count { !it.isIntl } - 2
        checkEquals(
            expectedDomesticCount,
            results.size,
            "All 16 healthy domestic feeds must return their articles even when 1 feed times out and 1 throws IOException"
        )
        checkTrue(
            elapsedMs < 2_500L,
            "Batch with a 5s hung feed must complete within bounded timeout (<2500ms), actual=${elapsedMs}ms"
        )
        checkEquals(
            6,
            freshRepo.availableDirectFeedPermits(),
            "All 6 semaphore permits must be released after timed-out feed is aborted"
        )
    }

    @Test
    fun test16_googleNewsResultsRemainAvailableWhenDirectFeedsTimeOut() = runBlocking {
        val freshRepo = NewsRepository()
        val now = System.currentTimeMillis()

        val googleArticle = NewsItem(
            title = "গুগল নিউজ থেকে প্রাপ্ত জরুরি জাতীয় সংবাদ শিরোনাম",
            source = "প্রথম আলো",
            sourceUrl = "https://www.prothomalo.com",
            link = "https://news.google.com/rss/articles/g1",
            time = now - 120_000L
        )

        val items = freshRepo.fetchNewsInternal(
            query = "",
            intl = false,
            section = "",
            forceRefresh = true,
            perFeedTimeoutMs = 120L,
            batchDeadlineMs = 350L,
            googleFeedTimeoutMs = 1_000L,
            directFeedFetcher = {
                // Every direct feed times out
                Thread.sleep(3_000L)
                emptyList()
            },
            googleRssFetcher = { _, _, _ ->
                listOf(googleArticle)
            }
        )

        checkEquals(1, items.size, "Google News items must still be returned when all direct feeds time out")
        checkEquals(googleArticle.title, items.first().title, "Returned item must match Google News article")
        checkEquals(6, freshRepo.availableDirectFeedPermits(), "All direct feed semaphore permits must be released")
    }

    @Test
    fun test17_allFeedsFailingLeadsToErrorStateNotInfiniteLoading() = runBlocking {
        val freshRepo = NewsRepository()
        var caughtException: Exception? = null

        try {
            freshRepo.fetchNewsInternal(
                query = "",
                intl = false,
                section = "",
                forceRefresh = true,
                perFeedTimeoutMs = 100L,
                batchDeadlineMs = 250L,
                googleFeedTimeoutMs = 200L,
                directFeedFetcher = {
                    Thread.sleep(2_000L)
                    emptyList()
                },
                googleRssFetcher = { _, _, _ ->
                    throw IOException("Network unreachable")
                }
            )
        } catch (e: Exception) {
            caughtException = e
        }

        checkNotNull(caughtException, "When both direct feeds and Google News fail, fetchNews must throw an exception")

        val initialSnapshot = HomeLoadStateSnapshot(
            newsItems = emptyList(),
            coverageList = emptyList(),
            isLoading = true,
            isManualRefreshing = false,
            errorMsg = null,
            loadedCategoryKey = "top",
            requestVersion = 1
        )
        val afterFailure = applyHomeLoadResult(
            currentSnapshot = initialSnapshot,
            completedRequestVersion = 1,
            targetCategoryKey = "top",
            fetchedItems = null,
            builtCoverage = null,
            failure = caughtException
        )

        checkFalse(afterFailure.isLoading, "isLoading must become false after failure")
        checkEquals(HOME_LOAD_ERROR_MESSAGE, afterFailure.errorMsg, "errorMsg must be populated when no cached news exists")
        val bodyState = resolveHomeBodyState(
            isLoading = afterFailure.isLoading,
            newsItems = afterFailure.newsItems,
            loadedCategoryKey = afterFailure.loadedCategoryKey,
            effectiveCatKey = "top",
            errorMsg = afterFailure.errorMsg
        )
        checkEquals(
            HomeBodyState.ERROR,
            bodyState,
            "Home Screen must transition to ERROR state (with Retry), never remaining in LOADING"
        )
    }

    @Test
    fun test18_cancellationReleasesSemaphorePermitsAndCancelsHttpCalls() = runBlocking {
        val freshRepo = NewsRepository()
        val callCanceledFlag = AtomicBoolean(false)
        val responseClosedFlag = AtomicBoolean(false)
        val callStartedLatch = CountDownLatch(1)
        val blockLatch = CountDownLatch(1)

        val fakeBlockingCall = object : Call {
            private val req = Request.Builder().url("https://www.prothomalo.com/feed/").build()
            override fun request(): Request = req
            override fun execute(): Response {
                callStartedLatch.countDown()
                try {
                    blockLatch.await(10, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    // Interrupted on cancellation
                }
                if (callCanceledFlag.get()) {
                    throw IOException("Canceled")
                }
                val body = "<rss></rss>".toByteArray().toResponseBody("application/xml".toMediaType())
                return Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(object : okhttp3.ResponseBody() {
                        override fun contentType() = body.contentType()
                        override fun contentLength() = body.contentLength()
                        override fun source() = body.source()
                        override fun close() {
                            responseClosedFlag.set(true)
                            super.close()
                        }
                    })
                    .build()
            }
            override fun enqueue(responseCallback: Callback) {}
            override fun cancel() {
                callCanceledFlag.set(true)
                blockLatch.countDown()
            }
            override fun isExecuted(): Boolean = true
            override fun isCanceled(): Boolean = callCanceledFlag.get()
            override fun timeout(): Timeout = Timeout.NONE
            override fun clone(): Call = this
        }

        // Part A: Verify cancelling a coroutine executing executeCancellableCall immediately calls Call.cancel()
        val callJob = launch(Dispatchers.IO) {
            val myJob = coroutineContext[kotlinx.coroutines.Job]
            freshRepo.executeCancellableCall(fakeBlockingCall, myJob) { res ->
                res.code
            }
        }
        checkTrue(callStartedLatch.await(2, TimeUnit.SECONDS), "Blocking OkHttp Call must start executing")
        callJob.cancelAndJoin()
        checkTrue(fakeBlockingCall.isCanceled(), "Cancelling coroutine must invoke Call.cancel() on active OkHttp Call")

        // Part B: Verify Response body is always closed when executeCancellableCall succeeds
        val okCall = object : Call by fakeBlockingCall {
            override fun execute(): Response {
                val req = Request.Builder().url("https://www.prothomalo.com/feed/").build()
                val rawBody = "<rss></rss>".toResponseBody("application/xml".toMediaType())
                return Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(object : okhttp3.ResponseBody() {
                        override fun contentType() = rawBody.contentType()
                        override fun contentLength() = rawBody.contentLength()
                        override fun source() = rawBody.source()
                        override fun close() {
                            responseClosedFlag.set(true)
                            rawBody.close()
                        }
                    })
                    .build()
            }
            override fun isCanceled(): Boolean = false
        }
        freshRepo.executeCancellableCall(okCall, null) { res -> res.code }
        checkTrue(responseClosedFlag.get(), "Response body must always be closed after executeCancellableCall")

        // Part C: Verify cancelling fetchDirectPublisherFeeds while all 6 semaphore permits are occupied
        // immediately releases all 6 permits
        val sixPermitsAcquired = CountDownLatch(6)
        val batchJob = launch(Dispatchers.IO) {
            freshRepo.fetchDirectPublisherFeeds(
                isIntl = false,
                forceRefresh = true,
                perFeedTimeoutMs = 10_000L,
                batchDeadlineMs = 15_000L,
                fetcher = {
                    sixPermitsAcquired.countDown()
                    Thread.sleep(10_000L)
                    emptyList()
                }
            )
        }
        checkTrue(sixPermitsAcquired.await(2, TimeUnit.SECONDS), "All 6 semaphore permits should be acquired by active feeds")
        checkEquals(0, freshRepo.availableDirectFeedPermits(), "0 permits should be available while 6 feeds are active")

        batchJob.cancelAndJoin()
        checkEquals(
            6,
            freshRepo.availableDirectFeedPermits(),
            "All 6 semaphore permits must be released immediately when batch coroutine is cancelled"
        )
    }

    @Test
    fun test19_concurrentStartupRequestsDoNotTriggerDuplicateBatches() = runBlocking {
        val freshRepo = NewsRepository()
        val fetchInvocations = AtomicInteger(0)
        val now = System.currentTimeMillis()

        val slowCountingFetcher: (DirectRssFeedSpec) -> List<NewsItem> = { spec ->
            fetchInvocations.incrementAndGet()
            Thread.sleep(60L)
            listOf(
                NewsItem(
                    title = "${spec.sourceBn} একক ব্যাচ পরীক্ষা সংবাদ",
                    source = spec.sourceBn,
                    sourceUrl = spec.baseUrl,
                    link = "${spec.baseUrl}/single-batch",
                    time = now - 30_000L
                )
            )
        }

        // Launch 3 concurrent startup fetchNewsInternal calls before cache is populated
        val callers = (1..3).map {
            async(Dispatchers.IO) {
                freshRepo.fetchNewsInternal(
                    query = "",
                    intl = false,
                    section = "",
                    forceRefresh = false,
                    directFeedFetcher = slowCountingFetcher,
                    googleRssFetcher = { _, _, _ -> emptyList() }
                )
            }
        }
        val results = callers.awaitAll()

        val domesticFeedCount = NewsRepository.DIRECT_PUBLISHER_FEEDS.count { !it.isIntl }
        checkEquals(
            domesticFeedCount,
            fetchInvocations.get(),
            "3 concurrent startup requests must share 1 in-flight batch ($domesticFeedCount feed calls), not ${domesticFeedCount * 3}"
        )
        results.forEach { list ->
            checkEquals(domesticFeedCount, list.size, "Each concurrent caller must receive the full list of fetched articles")
        }
    }

    @Test
    fun test20_breakingNewsNotifierWaitsForInitialHomeLoadAndReusesCache() = runBlocking {
        val freshRepo = NewsRepository()
        val directFetchCount = AtomicInteger(0)
        val notifierStartedFetch = AtomicBoolean(false)
        val now = System.currentTimeMillis()

        val countingDirectFetcher: (DirectRssFeedSpec) -> List<NewsItem> = { spec ->
            directFetchCount.incrementAndGet()
            listOf(
                NewsItem(
                    title = "চট্টগ্রাম বন্দরে কনটেইনার জট নিরসনে নতুন নির্দেশনা জারি করেছে কর্তৃপক্ষ",
                    source = spec.sourceBn,
                    sourceUrl = spec.baseUrl,
                    link = "${spec.baseUrl}/breaking-1",
                    time = now - 60_000L
                )
            )
        }

        checkFalse(freshRepo.initialHomeLoadCompleted.value, "initialHomeLoadCompleted must start as false")

        // Start simulated BreakingNewsNotifier startup coroutine waiting on awaitInitialHomeLoadBeforeBackgroundWork()
        val notifierJob = async(Dispatchers.IO) {
            freshRepo.awaitInitialHomeLoadBeforeBackgroundWork(maxWaitMs = 5_000L)
            notifierStartedFetch.set(true)
            freshRepo.fetchNewsInternal(
                query = "",
                intl = false,
                section = "",
                forceRefresh = false,
                directFeedFetcher = countingDirectFetcher,
                googleRssFetcher = { _, _, _ -> emptyList() }
            )
        }

        delay(100L)
        checkFalse(
            notifierStartedFetch.get(),
            "BreakingNewsNotifier must NOT start fetching while initial Home Screen load is still in progress"
        )

        // Now perform initial Home Screen load and mark completion
        val homeItems = freshRepo.fetchNewsInternal(
            query = "",
            intl = false,
            section = "",
            forceRefresh = false,
            directFeedFetcher = countingDirectFetcher,
            googleRssFetcher = { _, _, _ -> emptyList() }
        )
        val countAfterHomeLoad = directFetchCount.get()
        checkTrue(homeItems.isNotEmpty(), "Initial Home Screen load must return items")

        freshRepo.markInitialHomeLoadCompleted()
        val notifierItems = notifierJob.await()

        checkTrue(notifierStartedFetch.get(), "BreakingNewsNotifier must proceed once initial Home Screen load completes")
        checkEquals(
            countAfterHomeLoad,
            directFetchCount.get(),
            "BreakingNewsNotifier must reuse cached direct feeds with 0 duplicate direct feed network calls"
        )
        checkEquals(homeItems.size, notifierItems.size, "Notifier must receive the cached domestic items")
    }

    @Test
    fun test21_cachedNewsRemainsVisibleWhenRefreshFails() {
        val now = fixedNowMs
        val cachedArticle = NewsItem(
            title = "ক্যাশে সংরক্ষিত পূর্ববর্তী সংবাদ যা অফলাইনেও দৃশ্যমান থাকবে",
            source = "প্রথম আলো",
            sourceUrl = "https://www.prothomalo.com",
            link = "https://www.prothomalo.com/cached-1",
            time = now - 300_000L
        )
        val cachedCoverage = listOf(Coverage(listOf(cachedArticle)))

        val snapshotWithCachedNews = HomeLoadStateSnapshot(
            newsItems = listOf(cachedArticle),
            coverageList = cachedCoverage,
            isLoading = true,
            isManualRefreshing = true,
            errorMsg = null,
            loadedCategoryKey = "top",
            requestVersion = 2
        )

        // Case A: Network refresh fails with IOException while cached news is displayed
        val afterFailedRefresh = applyHomeLoadResult(
            currentSnapshot = snapshotWithCachedNews,
            completedRequestVersion = 2,
            targetCategoryKey = "top",
            fetchedItems = null,
            builtCoverage = null,
            failure = IOException("Simulated offline refresh failure")
        )

        checkFalse(afterFailedRefresh.isLoading, "isLoading must reset to false after failed refresh")
        checkFalse(afterFailedRefresh.isManualRefreshing, "isManualRefreshing must reset to false")
        checkNull(afterFailedRefresh.errorMsg, "errorMsg must remain null when cached news is preserved")
        checkEquals(1, afterFailedRefresh.newsItems.size, "Cached newsItems must remain intact when refresh fails")
        checkEquals(
            HomeBodyState.CONTENT,
            resolveHomeBodyState(
                isLoading = afterFailedRefresh.isLoading,
                newsItems = afterFailedRefresh.newsItems,
                loadedCategoryKey = afterFailedRefresh.loadedCategoryKey,
                effectiveCatKey = "top",
                errorMsg = afterFailedRefresh.errorMsg
            ),
            "HomeBodyState must remain CONTENT when cached news is present"
        )

        // Case B: Older superseded request (version 1) must NEVER overwrite newer state (version 2)
        val staleAttempt = applyHomeLoadResult(
            currentSnapshot = afterFailedRefresh,
            completedRequestVersion = 1,
            targetCategoryKey = "top",
            fetchedItems = emptyList(),
            builtCoverage = emptyList(),
            failure = IOException("Stale request failure")
        )
        checkEquals(afterFailedRefresh, staleAttempt, "Superseded older request version must be ignored")

        // Case C: CancellationException must be rethrown, never swallowed as an ordinary error
        var cancellationRethrown = false
        try {
            applyHomeLoadResult(
                currentSnapshot = snapshotWithCachedNews,
                completedRequestVersion = 2,
                targetCategoryKey = "top",
                fetchedItems = null,
                builtCoverage = null,
                failure = CancellationException("User switched category")
            )
        } catch (_: CancellationException) {
            cancellationRethrown = true
        }
        checkTrue(cancellationRethrown, "CancellationException must always be rethrown and never swallowed")
    }

    @Test
    fun test22_precomputedTitleSignatureAvoidsRepeatedTokenization() {
        val freshRepo = NewsRepository()
        freshRepo.clearTitleSignatureCacheForTest()
        val now = fixedNowMs

        val directItems = (1..20).map { idx ->
            NewsItem(
                title = "চট্টগ্রাম বন্দরে কনটেইনার জট নিরসনে নতুন নির্দেশনা জারি করেছে কর্তৃপক্ষ পর্ব $idx",
                source = "প্রথম আলো",
                sourceUrl = "https://www.prothomalo.com",
                link = "https://www.prothomalo.com/d/$idx",
                time = now - idx * 60_000L,
                imageUrl = "https://images.prothomalo.com/img-$idx.jpg"
            )
        }
        val googleItems = (1..20).map { idx ->
            NewsItem(
                title = "চট্টগ্রাম বন্দরের কনটেইনার জট নিরসনে কর্তৃপক্ষের নতুন নির্দেশনা জারি পর্ব $idx",
                source = "যুগান্তর",
                sourceUrl = "https://www.jugantor.com",
                link = "https://www.jugantor.com/g/$idx",
                time = now - idx * 60_000L,
                imageUrl = null
            )
        }

        // 20 Google items x 20 Direct items = 400 duplicate comparisons in enrichGoogleItemsWithDirectImages,
        // followed by clustering in buildTopStories. Without caching, tokenization would run >800 times.
        val enriched = freshRepo.enrichGoogleItemsWithDirectImages(googleItems, directItems)
        val clusters = freshRepo.buildTopStories(directItems + enriched, emptySet(), now)

        val uniqueTitlesCount = 40
        val actualTokenizations = freshRepo.titleTokenizationCounter.get()
        checkEquals(
            uniqueTitlesCount,
            actualTokenizations,
            "Each unique article title must be tokenized exactly once ($uniqueTitlesCount times total), actual=$actualTokenizations"
        )
        checkTrue(
            enriched.all { !it.imageUrl.isNullOrBlank() },
            "All matching Google News items must still be enriched with direct RSS images"
        )
        checkTrue(clusters.isNotEmpty(), "Top Stories clustering must succeed with precomputed signatures")
    }

    @Test
    fun test23_boundedHtmlExtractionAndTruncatedStreamFallback() {
        val extractor = ArticleExtractor()

        // Case A: Oversized stream with heavy <head> script before <article> triggers adaptive extension
        // and still bounds total bytes read instead of reading unbounded megabytes.
        val heavyHeadScript = "<script>" + ("var x = '0123456789ABCDEF';\n".repeat(1200)) + "</script>"
        val articleBodyHtml = """
            <article>
                <h1>বাংলাদেশে মেট্রোরেলের নতুন সময়সূচি ঘোষণা করেছে কর্তৃপক্ষ</h1>
                <p>ঢাকায় মেট্রোরেলের যাত্রীদের সুবিধার্থে আগামী রবিবার থেকে নতুন সময়সূচি কার্যকর হতে যাচ্ছে বলে জানিয়েছে কর্তৃপক্ষ।</p>
                <p>সকাল সাতটা থেকে রাত নয়টা পর্যন্ত নিয়মিত বিরতিতে ট্রেন চলাচল করবে এবং পিক আওয়ারে ট্রেনের সংখ্যা বাড়ানো হয়েছে।</p>
                <p>যাত্রীরা এমআরটি পাস ও সিঙ্গেল জার্নি টিকিট উভয়ই ব্যবহার করে সহজে যাতায়াত করতে পারবেন বলে বিজ্ঞপ্তিতে জানানো হয়।</p>
            </article>
        """.trimIndent()
        val trailingNoise = "<div>" + ("trailing_padding_data_".repeat(5000)) + "</div>"
        val fullLargeHtml = "<html><head><title>মেট্রোরেল সংবাদ</title>$heavyHeadScript</head><body>$articleBodyHtml$trailingNoise</body></html>"
        val fullBytes = fullLargeHtml.toByteArray(Charsets.UTF_8)

        val readResult = extractor.readBoundedHtmlStream(
            inputStream = ByteArrayInputStream(fullBytes),
            charset = Charsets.UTF_8,
            maxBytes = 24 * 1024,
            fallbackExtensionBytes = 48 * 1024
        )

        checkTrue(readResult.extendedReadUsed, "Adaptive extension read should activate when initial 24KB had only script header")
        checkTrue(readResult.bytesRead <= 72 * 1024, "Total bytes read must be strictly bounded by primary + extension limit")
        checkTrue(readResult.bytesRead < fullBytes.size, "Bounded stream reader must stop before reading entire oversized stream")

        val parsed = extractor.parseHtml(
            html = readResult.html,
            url = "https://www.prothomalo.com/bangladesh/metro-schedule",
            fallbackTitle = "মেট্রোরেলের নতুন সময়সূচি",
            source = "প্রথম আলো"
        )
        checkTrue(extractor.hasSubstantiveContent(parsed), "Article paragraphs after heavy script header must be extracted via adaptive fallback")
        checkTrue(parsed.paragraphs.size >= 3, "All 3 article paragraphs must be extracted")

        // Case B: Stream truncated mid-<p> still salvages completed paragraphs and substantive trailing text
        val truncatedHtml = """
            <html><head><meta property="og:title" content="বাজেট আলোচনা শুরু"/></head><body>
            <p>আগামী অর্থবছরের জাতীয় বাজেটকে সামনে রেখে অর্থ মন্ত্রণালয়ের প্রাক-বাজেট আলোচনা আজ আনুষ্ঠানিকভাবে শুরু হয়েছে।</p>
            <p>অর্থনীতিবিদ ও ব্যবসায়ী নেতারা মূল্যস্ফীতি নিয়ন্ত্রণ এবং কর্মসংস্থান বৃদ্ধির ওপর বিশেষ গুরুত্ব আরোপ করেছেন এই বৈঠকে
        """.trimIndent()
        val parsedTruncated = extractor.parseHtml(
            html = truncatedHtml,
            url = "https://www.jugantor.com/economics/budget",
            fallbackTitle = "বাজেট আলোচনা শুরু",
            source = "যুগান্তর"
        )
        checkEquals(2, parsedTruncated.paragraphs.size, "Both completed <p> and substantive truncated trailing <p> must be salvaged")
    }

    @Test
    fun test24_jsonLdValidInvalidAndHtmlFallbackExtraction() {
        val extractor = ArticleExtractor()

        // Case A: Valid JSON-LD with @graph, articleBody, headline, and image object
        val validJsonLdHtml = """
            <html>
            <head>
                <script type="application/ld+json">
                {
                  "@context": "https://schema.org",
                  "@graph": [
                    {
                      "@type": "NewsArticle",
                      "headline": "সুন্দরবনে পর্যটকদের জন্য নতুন নির্দেশনা জারি করেছে বন বিভাগ",
                      "image": {
                        "@type": "ImageObject",
                        "url": "https://cdn.example.com/sundarbans-lead.jpg"
                      },
                      "articleBody": "সুন্দরবনের জীববৈচিত্র্য রক্ষায় পর্যটকদের ভ্রমণে নতুন নির্দেশনা জারি করেছে বন বিভাগ।\n\nএখন থেকে নির্ধারিত রুটের বাইরে কোনো নৌযান চলাচল করতে পারবে না এবং প্লাস্টিক বহন সম্পূর্ণ নিষিদ্ধ করা হয়েছে।"
                    }
                  ]
                }
                </script>
            </head>
            <body></body>
            </html>
        """.trimIndent()

        val fromJsonLd = extractor.parseHtml(
            html = validJsonLdHtml,
            url = "https://www.prothomalo.com/environment/sundarbans",
            fallbackTitle = "সুন্দরবন সংবাদ",
            source = "প্রথম আলো"
        )
        checkEquals("সুন্দরবনে পর্যটকদের জন্য নতুন নির্দেশনা জারি করেছে বন বিভাগ", fromJsonLd.title, "Headline must be extracted from JSON-LD")
        checkEquals("https://cdn.example.com/sundarbans-lead.jpg", fromJsonLd.leadImageUrl, "Lead image URL must be extracted from JSON-LD")
        checkEquals(2, fromJsonLd.paragraphs.size, "Both paragraphs from JSON-LD articleBody must be extracted")

        // Case B: Malformed / pathological JSON-LD (which previously caused catastrophic regex backtracking)
        // plus unclosed <footer tag must complete quickly (<500ms) and fall back cleanly to HTML <p> extraction
        val pathologicalBody = "\\\"".repeat(4000) + "unclosed_json_string_without_end"
        val invalidJsonLdWithHtmlFallback = """
            <html>
            <head>
                <meta property="og:title" content="কৃষি প্রযুক্তিতে নতুন উদ্ভাবন"/>
                <script type="application/ld+json">
                {"@type": "NewsArticle", "articleBody": "$pathologicalBody
                </script>
            </head>
            <body>
                <div class="story-element-text">দেশের দক্ষিণাঞ্চলে লবণাক্ততা সহনশীল ধানের নতুন জাত উদ্ভাবন করেছেন কৃষি বিজ্ঞানীরা, যা ফলন বাড়াতে বড় ভূমিকা রাখবে।</div>
                <p>নতুন এই ধানের জাতটি উপকূলীয় অঞ্চলের কৃষকদের জন্য আশীর্বাদ হয়ে আসবে বলে আশা করছেন সংশ্লিষ্ট গবেষকেরা।</p>
                <footer class="broken-footer">কপিরাইট তথ্য
            </body>
            </html>
        """.trimIndent()

        val startMs = System.currentTimeMillis()
        val fallbackResult = extractor.parseHtml(
            html = invalidJsonLdWithHtmlFallback,
            url = "https://www.kalbela.com/agri/innovation",
            fallbackTitle = "কৃষি প্রযুক্তি",
            source = "কালবেলা"
        )
        val elapsedMs = System.currentTimeMillis() - startMs

        checkTrue(elapsedMs < 500L, "Malformed JSON-LD with escaped quotes must never backtrack catastrophically (took ${elapsedMs}ms)")
        checkEquals("কৃষি প্রযুক্তিতে নতুন উদ্ভাবন", fallbackResult.title, "Title must be extracted from og:title")
        checkEquals(2, fallbackResult.paragraphs.size, "Fallback HTML extraction must extract both story-element-text and <p> paragraphs")
    }

    @Test
    fun test25_duplicateExtractionPreventionAndUrlChangeInvalidation() {
        val coordinator = ArticleExtractionCoordinator()
        val urlA = "https://www.prothomalo.com/bangladesh/story-a"
        val urlB = "https://www.prothomalo.com/bangladesh/story-b"

        val v1 = coordinator.onUrlChanged(urlA)
        checkTrue(coordinator.isCurrent(v1, urlA), "Version v1 must be current for urlA")
        checkFalse(coordinator.hasSubstantiveResultFor(urlA), "urlA should not be marked succeeded initially")

        // First live DOM extraction starts
        checkTrue(coordinator.tryStartLiveDomExtraction(urlA), "First live DOM extraction for urlA must be allowed")
        // Concurrent duplicate call (e.g., second onPageFinished or delayed callback) must be blocked
        checkFalse(coordinator.tryStartLiveDomExtraction(urlA), "Duplicate concurrent live DOM extraction for urlA must be rejected")

        // Mark urlA succeeded
        coordinator.markSucceeded(urlA)
        checkTrue(coordinator.hasSubstantiveResultFor(urlA), "urlA must be recorded as succeeded")
        checkFalse(coordinator.tryStartLiveDomExtraction(urlA), "Subsequent non-forced extraction for already succeeded urlA must be skipped")

        // Now user navigates to urlB
        val v2 = coordinator.onUrlChanged(urlB)
        checkTrue(v2 > v1, "URL change must increment requestVersion")
        checkFalse(coordinator.isCurrent(v1, urlA), "Old version v1 for urlA must be invalidated after switching to urlB")
        checkTrue(coordinator.isCurrent(v2, urlB), "New version v2 for urlB must be active")
        checkTrue(coordinator.tryStartLiveDomExtraction(urlB), "Live DOM extraction for new urlB must be allowed")
    }

    @Test
    fun test26_concurrentReadAndHistoryUpdatesPreserveAllEntriesWithO1SetLookup() = runBlocking {
        val fakePrefs = InMemorySharedPreferences()
        val testRepo = AppStateRepository(prefs = fakePrefs, ioDispatcher = Dispatchers.IO)
        val callerThreadName = Thread.currentThread().name

        // Launch 25 concurrent markArticleRead calls and 10 concurrent addRecent calls
        val jobs = (1..25).map { idx ->
            launch(Dispatchers.Default) {
                testRepo.markArticleRead(
                    url = "https://www.prothomalo.com/article-$idx",
                    title = "গুরুত্বপূর্ণ সংবাদ শিরোনাম নম্বর $idx",
                    sourceName = "প্রথম আলো",
                    paperUrl = "https://www.prothomalo.com"
                )
                if (idx <= 10) {
                    testRepo.addRecent("https://www.paper-$idx.com")
                }
            }
        }
        jobs.forEach { it.join() }

        // Verify immediate in-memory O(1) Set lookup and zero lost entries
        for (idx in 1..25) {
            checkTrue(
                testRepo.isArticleRead("https://www.prothomalo.com/article-$idx"),
                "O(1) Set lookup must report article-$idx as read"
            )
            checkTrue(
                testRepo.isArticleRead("", "গুরুত্বপূর্ণ সংবাদ শিরোনাম নম্বর $idx"),
                "O(1) Set lookup by title key must report article-$idx as read"
            )
        }
        checkEquals(25, testRepo.state.value.readHistory.size, "All 25 concurrent readHistory items must be preserved without loss")
        checkEquals(10, testRepo.state.value.recents.size, "All 10 concurrent recent paper URLs must be preserved")
        checkTrue(testRepo.readLookupSetSizeForTest() >= 50, "In-memory HashSet must hold both URL and title keys for all 25 articles")

        // Wait for asynchronous IO persistence to finish and verify SharedPreferences compatibility
        testRepo.awaitPendingPersistence()
        checkTrue(
            testRepo.lastPersistenceThreadName.isNotEmpty() && testRepo.lastPersistenceThreadName != callerThreadName,
            "JSON serialization and SharedPreferences persistence must execute on background IO thread, actual=${testRepo.lastPersistenceThreadName}"
        )

        // Reload into a brand-new AppStateRepository instance from the same SharedPreferences
        val reloadedRepo = AppStateRepository(prefs = fakePrefs, ioDispatcher = Dispatchers.IO)
        checkEquals(25, reloadedRepo.state.value.readHistory.size, "Reloaded repository must restore all 25 persisted readHistory entries")
        checkEquals(10, reloadedRepo.state.value.recents.size, "Reloaded repository must restore all 10 persisted recent entries")
        for (idx in 1..25) {
            checkTrue(
                reloadedRepo.isArticleRead("https://www.prothomalo.com/article-$idx"),
                "Reloaded repository O(1) Set lookup must contain article-$idx"
            )
        }
    }

    @Test
    fun test27_articleExtractorCoroutineCancellationAndTimeoutHandling() = runBlocking {
        val extractor = ArticleExtractor()

        // Part A: Cancelling extractArticle coroutine immediately calls Call.cancel()
        val callCanceled = AtomicBoolean(false)
        val callStarted = CountDownLatch(1)
        val blockLatch = CountDownLatch(1)

        val blockingCallFactory: (Request) -> Call = { req ->
            object : Call {
                override fun request(): Request = req
                override fun execute(): Response {
                    callStarted.countDown()
                    try {
                        blockLatch.await(10, TimeUnit.SECONDS)
                    } catch (_: InterruptedException) {}
                    throw IOException("Canceled")
                }
                override fun enqueue(responseCallback: Callback) {}
                override fun cancel() {
                    callCanceled.set(true)
                    blockLatch.countDown()
                }
                override fun isExecuted(): Boolean = true
                override fun isCanceled(): Boolean = callCanceled.get()
                override fun timeout(): Timeout = Timeout.NONE
                override fun clone(): Call = this
            }
        }

        val extractionJob = launch(Dispatchers.IO) {
            extractor.extractArticle(
                url = "https://www.prothomalo.com/slow-article",
                fallbackTitle = "ধীরগতির সংবাদ",
                source = "প্রথম আলো",
                timeoutMs = 10_000L,
                callFactory = blockingCallFactory
            )
        }
        checkTrue(callStarted.await(2, TimeUnit.SECONDS), "ArticleExtractor HTTP Call must start")
        extractionJob.cancelAndJoin()
        checkTrue(callCanceled.get(), "Cancelling extractArticle coroutine must cancel underlying OkHttp Call")

        // Part B: Timeout returns offline fallback CleanArticle within bounded time and cancels underlying Call
        val timeoutCallCanceled = AtomicBoolean(false)
        val timeoutLatch = CountDownLatch(1)
        val startMs = System.currentTimeMillis()
        val timedOutArticle = extractor.extractArticle(
            url = "https://www.jugantor.com/hung-article",
            fallbackTitle = "টাইমআউট সংবাদ শিরোনাম",
            source = "যুগান্তর",
            timeoutMs = 250L,
            callFactory = { req ->
                object : Call {
                    override fun request(): Request = req
                    override fun execute(): Response {
                        try {
                            timeoutLatch.await(5, TimeUnit.SECONDS)
                        } catch (_: InterruptedException) {}
                        throw IOException("Timed out")
                    }
                    override fun enqueue(responseCallback: Callback) {}
                    override fun cancel() {
                        timeoutCallCanceled.set(true)
                        timeoutLatch.countDown()
                    }
                    override fun isExecuted(): Boolean = true
                    override fun isCanceled(): Boolean = timeoutCallCanceled.get()
                    override fun timeout(): Timeout = Timeout.NONE
                    override fun clone(): Call = this
                }
            }
        )
        val elapsedMs = System.currentTimeMillis() - startMs
        checkTrue(elapsedMs < 1_500L, "Timed-out article extraction must return promptly (<1500ms), actual=${elapsedMs}ms")
        checkTrue(timeoutCallCanceled.get(), "Timed-out extraction must cancel the hung OkHttp Call")
        checkEquals("টাইমআউট সংবাদ শিরোনাম", timedOutArticle.title, "Fallback title must be preserved on timeout")
        checkEquals(ArticleExtractor.PLACEHOLDER_OFFLINE, timedOutArticle.paragraphs.first(), "Offline placeholder must be returned on timeout")
    }

    @Test
    fun test28_smartSummaryRunsOnBackgroundDispatcherAndPreventsCrossArticleLeak() = runBlocking {
        val extractor = ArticleExtractor()
        val callerThreadName = Thread.currentThread().name
        val summaryWorkerThread = AtomicReference("")

        val article1 = CleanArticle(
            title = "পদ্মা সেতুতে একদিনে রেকর্ড টোল আদায় হয়েছে",
            source = "প্রথম আলো",
            url = "https://www.prothomalo.com/bangladesh/padma-toll",
            paragraphs = listOf(
                "ঈদযাত্রাকে কেন্দ্র করে পদ্মা সেতুতে গত ২৪ ঘণ্টায় ৪ কোটি ৮০ লাখ টাকার রেকর্ড টোল আদায় হয়েছে বলে জানিয়েছে সেতু কর্তৃপক্ষ।",
                "এই সময়ে সেতু দিয়ে মোট ৪৫ হাজারের বেশি যানবাহন পারাপার হয়েছে যা স্বাভাবিক সময়ের তুলনায় প্রায় দ্বিগুণ।",
                "যানজট এড়াতে মাওয়া ও জাজিরা প্রান্তে অতিরিক্ত টোল বুথ চালু রাখা হয়েছে এবং নিরাপত্তা জোরদার করা হয়েছে।",
                "সেতু বিভাগের কর্মকর্তারা জানিয়েছেন যে ইলেকট্রনিক টোল কালেকশন ব্যবস্থা চালু হওয়ায় দ্রুত যানবাহন পারাপার সম্ভব হচ্ছে।",
                "আগামী কয়েক দিন যানবাহনের এই চাপ অব্যাহত থাকতে পারে বলে ধারণা করছে সংশ্লিষ্ট কর্তৃপক্ষ।"
            ),
            plainText = ""
        )

        val bullets = withContext(Dispatchers.Default) {
            summaryWorkerThread.set(Thread.currentThread().name)
            extractor.generateSmartSummaryAsync(article1, article1.title, Dispatchers.Default)
        }

        checkTrue(
            summaryWorkerThread.get().contains("DefaultDispatcher") || summaryWorkerThread.get() != callerThreadName,
            "Smart summary must execute on Dispatchers.Default background thread, actual=${summaryWorkerThread.get()}"
        )
        checkTrue(bullets.size in 3..4, "Smart summary must generate 3 to 4 extractive bullets, actual=${bullets.size}")
        checkTrue(bullets.any { it.contains("পদ্মা সেতু") || it.contains("টোল") }, "Summary bullets must reflect article1 facts")
    }

    @Test
    fun test29_lazyTtsLifecycleAndWebProgressThrottling() {
        // Part A: Verify ReaderScreen opens without initializing TTS until user explicitly requests Listen
        val ttsLifecycle = LazyTtsLifecycleState()
        checkFalse(ttsLifecycle.shouldCreateEngineOnReaderOpen(), "ReaderScreen must NOT initialize TTS on screen open")
        checkEquals(0, ttsLifecycle.initRequestCount, "TTS initRequestCount must be 0 when ReaderScreen opens without tapping Listen")
        checkFalse(ttsLifecycle.isInitialized, "TTS must not be initialized before Listen tap")

        // User taps Listen for the first time
        var factoryCalls = 0
        val startedFirst = ttsLifecycle.requestEngineInitialization { factoryCalls++ }
        checkTrue(startedFirst, "First Listen tap must trigger lazy TTS initialization")
        checkEquals(1, factoryCalls, "TTS factory must be invoked once")
        checkTrue(ttsLifecycle.isInitializing, "isInitializing must be true while TTS engine binds")

        // Duplicate tap while initializing must NOT create a second TTS engine
        val startedDuplicate = ttsLifecycle.requestEngineInitialization { factoryCalls++ }
        checkFalse(startedDuplicate, "Duplicate tap while initializing must not create another TTS instance")
        checkEquals(1, factoryCalls, "TTS factory count must remain 1")

        // Engine initialization succeeds
        ttsLifecycle.onEngineInitResult(success = true)
        checkFalse(ttsLifecycle.isInitializing, "isInitializing must become false after init callback")
        checkTrue(ttsLifecycle.isInitialized, "isInitialized must become true after SUCCESS callback")

        // Disposing ReaderScreen shuts down TTS once
        var shutdownCalls = 0
        ttsLifecycle.onDispose { shutdownCalls++ }
        checkEquals(1, shutdownCalls, "Disposing ReaderScreen must invoke TTS shutdown")
        checkTrue(ttsLifecycle.isDisposed, "isDisposed must be true after disposal")

        // Part B: Verify WebView progress updates are throttled to prevent excessive recompositions
        checkTrue(shouldUpdateWebProgress(0, 10, 0L, 100L), "Initial transition from 0 to >0 must update webProgress")
        checkFalse(shouldUpdateWebProgress(10, 15, 100L, 150L), "Small 5% progress step after 50ms must be throttled")
        checkFalse(shouldUpdateWebProgress(10, 28, 100L, 200L), "18% step within 100ms (<180ms) must be throttled")
        checkTrue(shouldUpdateWebProgress(10, 28, 100L, 300L), ">=15% step after >=180ms must update webProgress")
        checkTrue(shouldUpdateWebProgress(28, 100, 300L, 350L), "Completion (100%) must always update immediately")
    }

    @Test
    fun test30_paragraphTagsRemoveInCleanReader() {
        val extractor = ArticleExtractor()
        val html = "<p>পশ্চিমবঙ্গের ভোটার তালিকা সংশোধনের কাজ আজ থেকে আনুষ্ঠানিকভাবে শুরু হয়েছে।</p>"
        val parsed = extractor.parseHtml(
            html = html,
            url = "https://www.anandabazar.com/west-bengal/voter-list",
            fallbackTitle = "ভোটার তালিকা সংশোধন",
            source = "আনন্দবাজার পত্রিকা"
        )
        checkEquals(1, parsed.paragraphs.size, "Single <p> block must produce 1 clean paragraph")
        checkEquals(
            "পশ্চিমবঙ্গের ভোটার তালিকা সংশোধনের কাজ আজ থেকে আনুষ্ঠানিকভাবে শুরু হয়েছে।",
            parsed.paragraphs.first(),
            "Paragraph text must not contain <p> or </p>"
        )
        checkFalse(parsed.plainText.contains("<p"), "plainText must never contain <p")
        checkFalse(parsed.plainText.contains("</p"), "plainText must never contain </p")
    }

    @Test
    fun test31_multipleConsecutiveParagraphsSeparatedWithoutRawTags() {
        val extractor = ArticleExtractor()

        // Exact prompt requirement: "<p>বাংলা সংবাদ</p><p>পরবর্তী অনুচ্ছেদ</p>" -> 2 clean paragraphs, no raw HTML tags
        val exactPromptHtml = "<p>বাংলা সংবাদ</p><p>পরবর্তী অনুচ্ছেদ</p>"
        val exactParsed = extractor.parseHtml(
            html = exactPromptHtml,
            url = "https://www.prothomalo.com/bangladesh/test",
            fallbackTitle = "সংবাদ শিরোনাম",
            source = "প্রথম আলো"
        )
        checkEquals(2, exactParsed.paragraphs.size, "'<p>বাংলা সংবাদ</p><p>পরবর্তী অনুচ্ছেদ</p>' must yield exactly 2 paragraphs")
        checkEquals("বাংলা সংবাদ", exactParsed.paragraphs[0], "First paragraph must be 'বাংলা সংবাদ'")
        checkEquals("পরবর্তী অনুচ্ছেদ", exactParsed.paragraphs[1], "Second paragraph must be 'পরবর্তী অনুচ্ছেদ'")
        checkFalse(exactParsed.plainText.contains("<"), "No raw HTML tag characters may remain in exactPromptHtml result")
        checkFalse(exactParsed.plainText.contains(">"), "No raw HTML tag characters may remain in exactPromptHtml result")

        // Real device screenshot scenario: consecutive </p><p> and structural <div>, <br>, <br/>
        val screenshotHtml = "<p>পশ্চিমবঙ্গের ভোটার তালিকা সংশোধনের প্রক্রিয়ায় নতুন জটিলতা তৈরি হয়েছে এবং চারজন বিচারপতি পদত্যাগ করলেন।</p><p>সর্বশেষ পদত্যাগ করা বিচারপতির চিঠি ইতিমধ্যে সংশ্লিষ্ট দপ্তরে পৌঁছেছে।</p><div>তৃতীয় অনুচ্ছেদটি ডিভ ট্যাগের ভেতরে রয়েছে।<br>চতুর্থ লাইনটি ব্রেক ট্যাগের পরে এসেছে।<br/>পঞ্চম লাইনটি সেলফ-ক্লোজিং ব্রেক ট্যাগের পরে এসেছে।</div>"
        val screenshotParsed = extractor.parseHtml(
            html = screenshotHtml,
            url = "https://www.anandabazar.com/west-bengal/judges",
            fallbackTitle = "বিচারপতি পদত্যাগ",
            source = "আনন্দবাজার পত্রিকা"
        )
        checkEquals(5, screenshotParsed.paragraphs.size, "Consecutive <p>, <div>, <br>, and <br/> blocks must produce 5 separate paragraphs")
        checkEquals(
            "পশ্চিমবঙ্গের ভোটার তালিকা সংশোধনের প্রক্রিয়ায় নতুন জটিলতা তৈরি হয়েছে এবং চারজন বিচারপতি পদত্যাগ করলেন।",
            screenshotParsed.paragraphs[0],
            "First screenshot paragraph must be clean"
        )
        checkEquals(
            "সর্বশেষ পদত্যাগ করা বিচারপতির চিঠি ইতিমধ্যে সংশ্লিষ্ট দপ্তরে পৌঁছেছে।",
            screenshotParsed.paragraphs[1],
            "Second screenshot paragraph must be separated cleanly without </p><p>"
        )
        screenshotParsed.paragraphs.forEach { p ->
            checkFalse(p.contains("<p", ignoreCase = true), "Paragraph must not contain <p: $p")
            checkFalse(p.contains("</p", ignoreCase = true), "Paragraph must not contain </p: $p")
            checkFalse(p.contains("<div", ignoreCase = true), "Paragraph must not contain <div: $p")
            checkFalse(p.contains("</div", ignoreCase = true), "Paragraph must not contain </div: $p")
            checkFalse(p.contains("<br", ignoreCase = true), "Paragraph must not contain <br: $p")
        }
    }

    @Test
    fun test32_nestedHtmlTagsStrippedPreservingParagraphs() {
        val extractor = ArticleExtractor()
        val nestedHtml = """
            <div class="article-content">
                <p>এটি <strong>খুবই <em>গুরুত্বপূর্ণ</em></strong> এবং <span class="highlight"><a href="https://example.com/news">বিস্তারিত তথ্যসমৃদ্ধ</a></span> একটি বাংলা সংবাদ অনুচ্ছেদ।</p>
                <p>দ্বিতীয় অনুচ্ছেদে <b>বিশেষ <i>অর্থনৈতিক বিশ্লেষণ</i></b> ও <mark>পরিসংখ্যান</mark> যুক্ত করা হয়েছে।</p>
            </div>
        """.trimIndent()

        val parsed = extractor.parseHtml(
            html = nestedHtml,
            url = "https://www.jugantor.com/national/nested",
            fallbackTitle = "নেস্টেড ট্যাগ সংবাদ",
            source = "যুগান্তর"
        )
        checkEquals(2, parsed.paragraphs.size, "Nested <p> blocks inside <div class='article-content'> must remain 2 separate paragraphs")
        checkEquals(
            "এটি খুবই গুরুত্বপূর্ণ এবং বিস্তারিত তথ্যসমৃদ্ধ একটি বাংলা সংবাদ অনুচ্ছেদ।",
            parsed.paragraphs[0],
            "All nested inline tags (strong, em, span, a) must be stripped cleanly"
        )
        checkEquals(
            "দ্বিতীয় অনুচ্ছেদে বিশেষ অর্থনৈতিক বিশ্লেষণ ও পরিসংখ্যান যুক্ত করা হয়েছে।",
            parsed.paragraphs[1],
            "All nested inline tags (b, i, mark) in second paragraph must be stripped cleanly"
        )
    }

    @Test
    fun test33_htmlEntitiesAndEscapedTagsDecodedCorrectly() {
        val extractor = ArticleExtractor()

        // Part A: Standard and numeric HTML entities (&nbsp;, &amp;, &lt;, &gt;, &quot;, &#39;, &#8220;, &#8221;)
        val entityHtml = "<p>বাংলাদেশ &amp; ভারতের মধ্যে &nbsp; বাণিজ্য বৈঠক: &quot;নতুন দিগন্ত&quot;, &#39;২০২৬&#39;, &#8220;সফল&#8221; এবং প্রবৃদ্ধি &lt; ১০% ও রপ্তানি &gt; ৫%।</p>"
        val parsedEntity = extractor.parseHtml(
            html = entityHtml,
            url = "https://samakal.com/economics/trade",
            fallbackTitle = "বাণিজ্য বৈঠক",
            source = "সমকাল"
        )
        checkEquals(1, parsedEntity.paragraphs.size, "Entity paragraph must be extracted")
        checkEquals(
            "বাংলাদেশ & ভারতের মধ্যে বাণিজ্য বৈঠক: \"নতুন দিগন্ত\", '২০২৬', “সফল” এবং প্রবৃদ্ধি < ১০% ও রপ্তানি > ৫%।",
            parsedEntity.paragraphs[0],
            "All HTML entities (&nbsp;, &amp;, &quot;, &#39;, &#8220;, &#8221;, &lt;, &gt;) must decode accurately"
        )

        // Part B: HTML-escaped paragraph tags (&lt;p&gt;...&lt;/p&gt;) and double-escaped (&amp;lt;p&amp;gt;)
        val escapedParagraphTags = "&lt;p&gt;পশ্চিমবঙ্গের ভোটার তালিকা সংশোধনের কাজ চলছে।&lt;/p&gt;&lt;p&gt;সর্বশেষ পদত্যাগ করা চারজন বিচারপতি পদত্যাগ করলেন।&lt;/p&gt;"
        val parsedEscaped = extractor.parseHtml(
            html = escapedParagraphTags,
            url = "https://www.anandabazar.com/escaped",
            fallbackTitle = "ভোটার তালিকা",
            source = "আনন্দবাজার পত্রিকা"
        )
        checkEquals(2, parsedEscaped.paragraphs.size, "Escaped &lt;p&gt;...&lt;/p&gt; tags must be decoded, stripped, and split into 2 paragraphs")
        checkEquals("পশ্চিমবঙ্গের ভোটার তালিকা সংশোধনের কাজ চলছে।", parsedEscaped.paragraphs[0], "First escaped paragraph must have no <p> tag")
        checkEquals("সর্বশেষ পদত্যাগ করা চারজন বিচারপতি পদত্যাগ করলেন।", parsedEscaped.paragraphs[1], "Second escaped paragraph must have no <p> tag")
    }

    @Test
    fun test34_bengaliUnicodePunctuationAndValidAngleBracketsPreserved() {
        val extractor = ArticleExtractor()
        val bengaliTextWithMath = "<p>“বাংলাদেশের অর্থনীতিতে র‍্যাপিড প্রবৃদ্ধি > ৫.৫% এবং মূল্যস্ফীতি < ৯.২% রাখা জরুরি”—বলেছেন বিশিষ্ট অর্থনীতিবিদ ড. মুহাম্মদ ইউনূস; বিস্তারিত: (১০০ > ৫০ এবং ২৫ < ৫০)!</p>"
        val parsed = extractor.parseHtml(
            html = bengaliTextWithMath,
            url = "https://www.prothomalo.com/business/economy",
            fallbackTitle = "অর্থনীতি সংবাদ",
            source = "প্রথম আলো"
        )
        checkEquals(1, parsed.paragraphs.size, "Must extract 1 paragraph")
        checkEquals(
            "“বাংলাদেশের অর্থনীতিতে র‍্যাপিড প্রবৃদ্ধি > ৫.৫% এবং মূল্যস্ফীতি < ৯.২% রাখা জরুরি”—বলেছেন বিশিষ্ট অর্থনীতিবিদ ড. মুহাম্মদ ইউনূস; বিস্তারিত: (১০০ > ৫০ এবং ২৫ < ৫০)!",
            parsed.paragraphs[0],
            "Bengali Unicode (including ZWNJ/ZWJ র‍্যা), punctuation, smart quotes, and valid '<' and '>' comparisons must remain completely intact"
        )
    }

    @Test
    fun test35_mixedHtmlTagsAndPlainTextExtractedInOrder() {
        val extractor = ArticleExtractor()
        val mixedInput = "প্রথম অনুচ্ছেদ কোনো এইচটিএমএল ট্যাগ ছাড়াই সরাসরি লেখা হয়েছে।<p>দ্বিতীয় অনুচ্ছেদ প্যারাগ্রাফ ট্যাগের ভেতরে লেখা হয়েছে।</p>তৃতীয় অনুচ্ছেদ আবার সাধারণ টেক্সট হিসেবে শেষে যুক্ত রয়েছে।"
        val parsed = extractor.parseHtml(
            html = mixedInput,
            url = "https://www.kalbela.com/national/mixed",
            fallbackTitle = "মিশ্র টেক্সট সংবাদ",
            source = "কালবেলা"
        )
        checkEquals(3, parsed.paragraphs.size, "Mixed plain text and <p> paragraphs must all be extracted in order")
        checkEquals("প্রথম অনুচ্ছেদ কোনো এইচটিএমএল ট্যাগ ছাড়াই সরাসরি লেখা হয়েছে।", parsed.paragraphs[0], "1st plain text segment must be preserved")
        checkEquals("দ্বিতীয় অনুচ্ছেদ প্যারাগ্রাফ ট্যাগের ভেতরে লেখা হয়েছে।", parsed.paragraphs[1], "2nd <p> segment must be preserved without tags")
        checkEquals("তৃতীয় অনুচ্ছেদ আবার সাধারণ টেক্সট হিসেবে শেষে যুক্ত রয়েছে।", parsed.paragraphs[2], "3rd plain text segment must be preserved")
    }

    @Test
    fun test36_malformedAndIncompleteParagraphTagsHandledCleanly() {
        val extractor = ArticleExtractor()
        val malformedHtml = "<p>প্রথম অনুচ্ছেদে ক্লোজিং ট্যাগ নেই<p class=\"lead\">দ্বিতীয় অনুচ্ছেদের শেষে ক্লোজিং ব্র্যাকেট নেই</p<p class=\"broken\" তৃতীয় অনুচ্ছেদে ওপেনিং ট্যাগের ব্র্যাকেট নেই।</p><p class=\"truncated"
        val parsed = extractor.parseHtml(
            html = malformedHtml,
            url = "https://www.ittefaq.com.bd/malformed",
            fallbackTitle = "অসম্পূর্ণ ট্যাগ সংবাদ",
            source = "ইত্তেফাক"
        )
        checkEquals(3, parsed.paragraphs.size, "All 3 substantive paragraphs from malformed/incomplete <p> markup must be salvaged")
        checkEquals("প্রথম অনুচ্ছেদে ক্লোজিং ট্যাগ নেই", parsed.paragraphs[0], "Unclosed <p> before next <p> must be separated")
        checkEquals("দ্বিতীয় অনুচ্ছেদের শেষে ক্লোজিং ব্র্যাকেট নেই", parsed.paragraphs[1], "Incomplete </p without '>' must be stripped")
        checkEquals("তৃতীয় অনুচ্ছেদে ওপেনিং ট্যাগের ব্র্যাকেট নেই।", parsed.paragraphs[2], "Malformed <p class=\"broken\" without '>' must strip attribute and preserve Bengali text")
        parsed.paragraphs.forEach { p ->
            checkFalse(p.contains("<p", ignoreCase = true), "No malformed <p may leak: $p")
            checkFalse(p.contains("</p", ignoreCase = true), "No malformed </p may leak: $p")
            checkFalse(p.contains("truncated", ignoreCase = true), "Trailing truncated tag attribute must not leak: $p")
        }
    }

    @Test
    fun test37_jsonLdLiveDomAndFallbackHtmlTagLeakageRegression() {
        val extractor = ArticleExtractor()

        // Part A: JSON-LD articleBody containing single-line consecutive <p>...</p><p>...</p> without '\n'
        val jsonLdWithHtmlTags = """
            <html>
            <head>
                <script type="application/ld+json">
                {
                  "@context": "https://schema.org",
                  "@type": "NewsArticle",
                  "headline": "পশ্চিমবঙ্গের ভোটার তালিকা সংশোধনের বিশেষ প্রতিবেদন",
                  "articleBody": "<p>পশ্চিমবঙ্গের ভোটার তালিকা সংশোধনের প্রক্রিয়ায় নতুন পদক্ষেপ নেওয়া হয়েছে।</p><p>চারজন বিচারপতি পদত্যাগ করলেন এবং সর্বশেষ পদত্যাগ করা বিচারপতির বক্তব্য প্রকাশিত হয়েছে।</p>"
                }
                </script>
            </head>
            <body></body>
            </html>
        """.trimIndent()

        val jsonLdParsed = extractor.parseHtml(
            html = jsonLdWithHtmlTags,
            url = "https://www.anandabazar.com/west-bengal/report",
            fallbackTitle = "ভোটার তালিকা",
            source = "আনন্দবাজার পত্রিকা"
        )
        checkEquals(2, jsonLdParsed.paragraphs.size, "JSON-LD articleBody with <p>...</p><p>...</p> must split into 2 clean paragraphs")
        checkEquals(
            "পশ্চিমবঙ্গের ভোটার তালিকা সংশোধনের প্রক্রিয়ায় নতুন পদক্ষেপ নেওয়া হয়েছে।",
            jsonLdParsed.paragraphs[0],
            "JSON-LD paragraph 1 must not contain <p> or </p>"
        )
        checkEquals(
            "চারজন বিচারপতি পদত্যাগ করলেন এবং সর্বশেষ পদত্যাগ করা বিচারপতির বক্তব্য প্রকাশিত হয়েছে।",
            jsonLdParsed.paragraphs[1],
            "JSON-LD paragraph 2 must not contain <p> or </p>"
        )

        // Part B: parseLiveDomJson where WebView DOM / JSON-LD passed raw <p>...</p><p>...</p> inside paragraphs array
        val rawLiveDomJs = """
            {"title":"<p>ভোটার তালিকা সংশোধন সংবাদ</p>","image":"https://cdn.example.com/img.jpg","url":"https://www.anandabazar.com/live","paragraphs":["<p>পশ্চিমবঙ্গের ভোটার তালিকা সংশোধনের কাজ চলছে।</p><p>সর্বশেষ পদত্যাগ করা বিচারপতির চিঠি গৃহীত হয়েছে।</p>"]}
        """.trimIndent()
        val liveDomParsed = extractor.parseLiveDomJson(
            rawJsResult = rawLiveDomJs,
            url = "https://www.anandabazar.com/live",
            fallbackTitle = "ভোটার তালিকা",
            source = "আনন্দবাজার পত্রিকা"
        )
        checkNotNull(liveDomParsed, "parseLiveDomJson must return a valid CleanArticle")
        checkEquals("ভোটার তালিকা সংশোধন সংবাদ", liveDomParsed!!.title, "Live DOM title must have <p> tags stripped")
        checkEquals(2, liveDomParsed.paragraphs.size, "Live DOM paragraph entry containing </p><p> must split into 2 clean paragraphs")
        checkEquals("পশ্চিমবঙ্গের ভোটার তালিকা সংশোধনের কাজ চলছে।", liveDomParsed.paragraphs[0], "Live DOM paragraph 1 must be tag-free")
        checkEquals("সর্বশেষ পদত্যাগ করা বিচারপতির চিঠি গৃহীত হয়েছে।", liveDomParsed.paragraphs[1], "Live DOM paragraph 2 must be tag-free")
    }

    private class InMemorySharedPreferences : SharedPreferences {
        private val data = ConcurrentHashMap<String, Any>()

        override fun getAll(): MutableMap<String, *> = HashMap(data)
        override fun getString(key: String?, defValue: String?): String? =
            if (key != null) (data[key] as? String) ?: defValue else defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            if (key != null) (data[key] as? MutableSet<String>) ?: defValues else defValues
        override fun getInt(key: String?, defValue: Int): Int =
            if (key != null) (data[key] as? Int) ?: defValue else defValue
        override fun getLong(key: String?, defValue: Long): Long =
            if (key != null) (data[key] as? Long) ?: defValue else defValue
        override fun getFloat(key: String?, defValue: Float): Float =
            if (key != null) (data[key] as? Float) ?: defValue else defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            if (key != null) (data[key] as? Boolean) ?: defValue else defValue
        override fun contains(key: String?): Boolean = key != null && data.containsKey(key)
        override fun edit(): SharedPreferences.Editor = EditorImpl()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        private inner class EditorImpl : SharedPreferences.Editor {
            private val staged = HashMap<String, Any?>()
            private var clearAll = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply {
                if (key != null) staged[key] = value
            }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = apply {
                if (key != null) staged[key] = values?.toMutableSet()
            }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply {
                if (key != null) staged[key] = value
            }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply {
                if (key != null) staged[key] = value
            }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply {
                if (key != null) staged[key] = value
            }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply {
                if (key != null) staged[key] = value
            }
            override fun remove(key: String?): SharedPreferences.Editor = apply {
                if (key != null) staged[key] = null
            }
            override fun clear(): SharedPreferences.Editor = apply {
                clearAll = true
            }
            override fun commit(): Boolean {
                apply()
                return true
            }
            override fun apply() {
                if (clearAll) data.clear()
                for ((k, v) in staged) {
                    if (v == null) data.remove(k) else data[k] = v
                }
            }
        }
    }

    fun runAllTests(): List<String> {
        val results = mutableListOf<String>()
        val tests = listOf<Pair<String, () -> Unit>>(
            "test01_api24CompatibleDateParsing" to ::test01_api24CompatibleDateParsing,
            "test02_colonOffsetParsing" to ::test02_colonOffsetParsing,
            "test03_ambiguousTimezoneReturnsNull" to ::test03_ambiguousTimezoneReturnsNull,
            "test04_invalidTimestampReturnsNull" to ::test04_invalidTimestampReturnsNull,
            "test05_futureTimestampGuard" to ::test05_futureTimestampGuard,
            "test06_pinnedStaleItemFilteringAndFeedStaleCheck" to ::test06_pinnedStaleItemFilteringAndFeedStaleCheck,
            "test07_banglaDuplicateTruePositive" to ::test07_banglaDuplicateTruePositive,
            "test08_banglaDuplicateFalsePositivePrevention" to ::test08_banglaDuplicateFalsePositivePrevention,
            "test09_canonicalPublisherIdentity" to ::test09_canonicalPublisherIdentity,
            "test10_latestNewsStrictChronologicalSorting" to ::test10_latestNewsStrictChronologicalSorting,
            "test11_zeroDropSourceDiversity" to ::test11_zeroDropSourceDiversity,
            "test12_topStoriesTimeDecayAndFavoriteTieBreaker" to ::test12_topStoriesTimeDecayAndFavoriteTieBreaker,
            "test13_manualRefreshForceBypass" to ::test13_manualRefreshForceBypass,
            "test14_publisherDeduplicationCollisionSafety" to ::test14_publisherDeduplicationCollisionSafety,
            "test15_oneFeedTimingOutDoesNotBlockSuccessfulFeeds" to ::test15_oneFeedTimingOutDoesNotBlockSuccessfulFeeds,
            "test16_googleNewsResultsRemainAvailableWhenDirectFeedsTimeOut" to ::test16_googleNewsResultsRemainAvailableWhenDirectFeedsTimeOut,
            "test17_allFeedsFailingLeadsToErrorStateNotInfiniteLoading" to ::test17_allFeedsFailingLeadsToErrorStateNotInfiniteLoading,
            "test18_cancellationReleasesSemaphorePermitsAndCancelsHttpCalls" to ::test18_cancellationReleasesSemaphorePermitsAndCancelsHttpCalls,
            "test19_concurrentStartupRequestsDoNotTriggerDuplicateBatches" to ::test19_concurrentStartupRequestsDoNotTriggerDuplicateBatches,
            "test20_breakingNewsNotifierWaitsForInitialHomeLoadAndReusesCache" to ::test20_breakingNewsNotifierWaitsForInitialHomeLoadAndReusesCache,
            "test21_cachedNewsRemainsVisibleWhenRefreshFails" to ::test21_cachedNewsRemainsVisibleWhenRefreshFails,
            "test22_precomputedTitleSignatureAvoidsRepeatedTokenization" to ::test22_precomputedTitleSignatureAvoidsRepeatedTokenization,
            "test23_boundedHtmlExtractionAndTruncatedStreamFallback" to ::test23_boundedHtmlExtractionAndTruncatedStreamFallback,
            "test24_jsonLdValidInvalidAndHtmlFallbackExtraction" to ::test24_jsonLdValidInvalidAndHtmlFallbackExtraction,
            "test25_duplicateExtractionPreventionAndUrlChangeInvalidation" to ::test25_duplicateExtractionPreventionAndUrlChangeInvalidation,
            "test26_concurrentReadAndHistoryUpdatesPreserveAllEntriesWithO1SetLookup" to ::test26_concurrentReadAndHistoryUpdatesPreserveAllEntriesWithO1SetLookup,
            "test27_articleExtractorCoroutineCancellationAndTimeoutHandling" to ::test27_articleExtractorCoroutineCancellationAndTimeoutHandling,
            "test28_smartSummaryRunsOnBackgroundDispatcherAndPreventsCrossArticleLeak" to ::test28_smartSummaryRunsOnBackgroundDispatcherAndPreventsCrossArticleLeak,
            "test29_lazyTtsLifecycleAndWebProgressThrottling" to ::test29_lazyTtsLifecycleAndWebProgressThrottling,
            "test30_paragraphTagsRemoveInCleanReader" to ::test30_paragraphTagsRemoveInCleanReader,
            "test31_multipleConsecutiveParagraphsSeparatedWithoutRawTags" to ::test31_multipleConsecutiveParagraphsSeparatedWithoutRawTags,
            "test32_nestedHtmlTagsStrippedPreservingParagraphs" to ::test32_nestedHtmlTagsStrippedPreservingParagraphs,
            "test33_htmlEntitiesAndEscapedTagsDecodedCorrectly" to ::test33_htmlEntitiesAndEscapedTagsDecodedCorrectly,
            "test34_bengaliUnicodePunctuationAndValidAngleBracketsPreserved" to ::test34_bengaliUnicodePunctuationAndValidAngleBracketsPreserved,
            "test35_mixedHtmlTagsAndPlainTextExtractedInOrder" to ::test35_mixedHtmlTagsAndPlainTextExtractedInOrder,
            "test36_malformedAndIncompleteParagraphTagsHandledCleanly" to ::test36_malformedAndIncompleteParagraphTagsHandledCleanly,
            "test37_jsonLdLiveDomAndFallbackHtmlTagLeakageRegression" to ::test37_jsonLdLiveDomAndFallbackHtmlTagLeakageRegression,
            "test38_coverageResultsCacheHitTest" to ::test38_coverageResultsCacheHitTest,
            "test39_articleReaderBackCacheRestoreTest" to ::test39_articleReaderBackCacheRestoreTest,
            "test40_scrollPositionPreservationTest" to ::test40_scrollPositionPreservationTest,
            "test41_mainTabSwitchingStatePreservationTest" to ::test41_mainTabSwitchingStatePreservationTest,
            "test42_ttlExpiryWithStaleResultsTest" to ::test42_ttlExpiryWithStaleResultsTest,
            "test43_backgroundRefreshWhileShowingCachedResultsTest" to ::test43_backgroundRefreshWhileShowingCachedResultsTest,
            "test44_refreshFailurePreservesPreviousResultsTest" to ::test44_refreshFailurePreservesPreviousResultsTest,
            "test45_concurrentCoverageRequestDeduplicationTest" to ::test45_concurrentCoverageRequestDeduplicationTest,
            "test46_articleReadStatusUpdateWithoutFullCoverageReloadTest" to ::test46_articleReadStatusUpdateWithoutFullCoverageReloadTest,
            "test47_differentFilterIntlCacheIsolationTest" to ::test47_differentFilterIntlCacheIsolationTest,
            "test48_homeFeedScrollRestorationAfterReaderBack" to ::test48_homeFeedScrollRestorationAfterReaderBack,
            "test49_homeFeedStateRestorationAfterMainTabSwitching" to ::test49_homeFeedStateRestorationAfterMainTabSwitching,
            "test50_separateScrollStateForLatestNewsAndTopStories" to ::test50_separateScrollStateForLatestNewsAndTopStories,
            "test51_categoryAndLocalRegionCacheAndScrollIsolation" to ::test51_categoryAndLocalRegionCacheAndScrollIsolation,
            "test52_homeFeedCacheCorrectnessAfterFavoritesChange" to ::test52_homeFeedCacheCorrectnessAfterFavoritesChange,
            "test53_avoidNetworkFetchOnFreshHomeFeedCacheHit" to ::test53_avoidNetworkFetchOnFreshHomeFeedCacheHit,
            "test54_backgroundRefreshOnStaleHomeFeedCache" to ::test54_backgroundRefreshOnStaleHomeFeedCache,
            "test55_preserveOldHomeFeedDataOnRefreshFailure" to ::test55_preserveOldHomeFeedDataOnRefreshFailure,
            "test56_duplicateClusteringPreventionOnHomeFeedMountAndModeSwitch" to ::test56_duplicateClusteringPreventionOnHomeFeedMountAndModeSwitch,
            "test57_stableLazyColumnKeyUniquenessAndReorderBehavior" to ::test57_stableLazyColumnKeyUniquenessAndReorderBehavior,
            "test58_readStatusUpdateWithoutFullHomeFeedReload" to ::test58_readStatusUpdateWithoutFullHomeFeedReload,
            "test59_sharedHomeFeedRequestCancellationSafety" to ::test59_sharedHomeFeedRequestCancellationSafety
        )
        for ((name, block) in tests) {
            block()
            val msg = "PASS: $name"
            println(msg)
            results.add(msg)
        }
        return results
    }

    private fun sampleCoverageInputItems(
        nowMs: Long,
        headlinePrefix: String = "জাতীয় বাজেট অধিবেশনে নতুন অর্থনৈতিক সংস্কার প্রস্তাব"
    ): List<NewsItem> {
        return listOf(
            NewsItem(
                title = "$headlinePrefix পেশ করেছেন অর্থমন্ত্রী",
                link = "https://www.prothomalo.com/bangladesh/budget-1",
                source = "প্রথম আলো",
                sourceUrl = "https://www.prothomalo.com",
                time = nowMs - 10 * 60 * 1000L
            ),
            NewsItem(
                title = "$headlinePrefix সংসদে উত্থাপন করেছেন অর্থমন্ত্রী",
                link = "https://www.jugantor.com/national/budget-1",
                source = "যুগান্তর",
                sourceUrl = "https://www.jugantor.com",
                time = nowMs - 12 * 60 * 1000L
            ),
            NewsItem(
                title = "$headlinePrefix নিয়ে সংসদে বিস্তারিত আলোচনা শুরু",
                link = "https://www.ittefaq.com.bd/national/budget-1",
                source = "দৈনিক ইত্তেফাক",
                sourceUrl = "https://www.ittefaq.com.bd",
                time = nowMs - 15 * 60 * 1000L
            ),
            NewsItem(
                title = "চট্টগ্রাম বন্দরে নতুন কন্টেইনার টার্মিনাল চালু হচ্ছে আগামী মাসে",
                link = "https://www.kalerkantho.com/national/port-2",
                source = "কালের কণ্ঠ",
                sourceUrl = "https://www.kalerkantho.com",
                time = nowMs - 20 * 60 * 1000L
            ),
            NewsItem(
                title = "চট্টগ্রাম বন্দরে নতুন কন্টেইনার টার্মিনাল আগামী মাসে চালু হবে",
                link = "https://samakal.com/bangladesh/port-2",
                source = "সমকাল",
                sourceUrl = "https://samakal.com",
                time = nowMs - 22 * 60 * 1000L
            )
        )
    }

    @Test
    fun test38_coverageResultsCacheHitTest() = runBlocking {
        val repo = NewsRepository()
        val nowMs = 1_710_000_000_000L
        val sampleItems = sampleCoverageInputItems(nowMs)

        val first = repo.fetchCoverageCached(
            intl = false,
            filterQuery = "",
            forceRefresh = false,
            nowMs = nowMs,
            topicFetcher = { topic, _ ->
                if (topic == "") sampleItems else emptyList()
            }
        )

        check(first.coverageList.size == 2) {
            "Expected 2 coverage clusters (1 many, 1 few), got ${first.coverageList.size}"
        }
        check(first.manyCoverage.size == 1 && first.fewCoverage.size == 1) {
            "Expected 1 manyCoverage (3+ papers) and 1 fewCoverage (2 papers)"
        }
        check(repo.coverageFetchExecutionCounter.get() == 1) {
            "Expected exactly 1 fetch execution on initial load, got ${repo.coverageFetchExecutionCounter.get()}"
        }
        check(repo.coverageBuildExecutionCounter.get() == 1) {
            "Expected exactly 1 buildCoverage execution on initial load, got ${repo.coverageBuildExecutionCounter.get()}"
        }

        // Second call within TTL must hit processed Coverage memory cache with zero network or clustering work
        val second = repo.fetchCoverageCached(
            intl = false,
            filterQuery = "",
            forceRefresh = false,
            nowMs = nowMs + 60_000L,
            topicFetcher = { _, _ ->
                throw AssertionError("Network fetcher must not be called on fresh Coverage cache hit")
            }
        )

        check(second === first) {
            "Expected cached Coverage result instance to be returned directly on cache hit"
        }
        check(repo.coverageFetchExecutionCounter.get() == 1) {
            "Fetch counter must remain 1 after cache hit"
        }
        check(repo.coverageBuildExecutionCounter.get() == 1) {
            "Build counter must remain 1 after cache hit"
        }
    }

    @Test
    fun test39_articleReaderBackCacheRestoreTest() = runBlocking {
        val repo = NewsRepository()
        val nowMs = 1_710_000_000_000L
        val cached = repo.fetchCoverageCached(
            intl = false,
            filterQuery = "",
            forceRefresh = false,
            nowMs = nowMs,
            topicFetcher = { topic, _ ->
                if (topic == "") sampleCoverageInputItems(nowMs) else emptyList()
            }
        )

        // Simulate clicking an article on CoverageScreen to open ReaderScreen
        repo.coverageStateHolder.markNavigatingToReader()
        check(repo.coverageStateHolder.returningFromReader) {
            "Expected returningFromReader flag to be set when opening ReaderScreen"
        }

        // Simulate pressing Back from ReaderScreen to CoverageScreen
        val restoredCached = repo.getCachedCoverageResult(intl = false, filterQuery = "")
        val initialSnapshot = createInitialCoverageSnapshot(restoredCached)
        check(!initialSnapshot.isLoading && !initialSnapshot.isRefreshing) {
            "Expected restored Coverage snapshot on Frame 1 to have isLoading=false and isRefreshing=false"
        }
        check(initialSnapshot.coverageList.size == 2) {
            "Expected restored Coverage snapshot on Frame 1 to contain all 2 clusters immediately"
        }
        check(resolveCoverageBodyState(initialSnapshot) == CoverageBodyState.CONTENT) {
            "Expected CoverageBodyState.CONTENT immediately on Frame 1 when returning from Reader"
        }

        val wasReturning = repo.coverageStateHolder.consumeReturningFromReader()
        check(wasReturning) { "Expected consumeReturningFromReader() to return true on Back" }
        check(!repo.coverageStateHolder.returningFromReader) {
            "Expected consumeReturningFromReader() to reset flag to false"
        }

        // Even if the user spent > 3 minutes reading in ReaderScreen, Back must show cached results without reload
        val actionFresh = resolveCoverageEnterAction(
            cached = restoredCached,
            isCacheFresh = true,
            returningFromReader = wasReturning
        )
        val actionAfterLongRead = resolveCoverageEnterAction(
            cached = restoredCached,
            isCacheFresh = false,
            returningFromReader = true
        )
        check(actionFresh == CoverageEnterAction.SHOW_CACHED_ONLY) {
            "Expected SHOW_CACHED_ONLY on Reader back with fresh cache"
        }
        check(actionAfterLongRead == CoverageEnterAction.SHOW_CACHED_ONLY) {
            "Expected SHOW_CACHED_ONLY on Reader back even after long article reading session"
        }
        check(repo.coverageFetchExecutionCounter.get() == 1 && repo.coverageBuildExecutionCounter.get() == 1) {
            "Expected zero additional network fetches or clustering rebuilds on Reader Back navigation"
        }
        check(cached.coverageList == initialSnapshot.coverageList) {
            "Expected restored Coverage list to match cached list"
        }
    }

    @Test
    fun test40_scrollPositionPreservationTest() {
        val repo = NewsRepository()
        val holder = repo.coverageStateHolder
        val domKey = repo.coverageCacheKey(intl = false, filterQuery = "")
        val intlKey = repo.coverageCacheKey(intl = true, filterQuery = "")

        // Default scroll position is (0, 0)
        val initialDomPos = holder.getScrollPosition(domKey)
        check(initialDomPos.firstVisibleItemIndex == 0 && initialDomPos.firstVisibleItemScrollOffset == 0) {
            "Expected default scroll position (0, 0)"
        }

        // Save scroll position before navigating to ReaderScreen
        holder.saveScrollPosition(domKey, index = 8, offset = 164)
        holder.saveScrollPosition(intlKey, index = 4, offset = 92)

        val restoredDom = holder.getScrollPosition(domKey)
        val restoredIntl = holder.getScrollPosition(intlKey)
        check(restoredDom.firstVisibleItemIndex == 8 && restoredDom.firstVisibleItemScrollOffset == 164) {
            "Expected domestic scroll position (8, 164) to be preserved, got $restoredDom"
        }
        check(restoredIntl.firstVisibleItemIndex == 4 && restoredIntl.firstVisibleItemScrollOffset == 92) {
            "Expected international scroll position (4, 92) to be preserved, got $restoredIntl"
        }
    }

    @Test
    fun test41_mainTabSwitchingStatePreservationTest() = runBlocking {
        val repo = NewsRepository()
        val holder = repo.coverageStateHolder
        val nowMs = 1_710_000_000_000L

        // User switches to International edition in Coverage tab and scrolls to item 5, offset 110
        holder.isIntl = true
        holder.selectedTopic = ""
        val intlKey = repo.coverageCacheKey(intl = true, filterQuery = "")

        val loadedIntl = repo.fetchCoverageCached(
            intl = true,
            filterQuery = "",
            forceRefresh = false,
            nowMs = nowMs,
            topicFetcher = { topic, isIntl ->
                if (isIntl && topic == "") sampleCoverageInputItems(nowMs, "জাতিসংঘ সাধারণ পরিষদে জলবায়ু চুক্তি") else emptyList()
            }
        )
        holder.lastSuccessfulResult = loadedIntl
        holder.saveScrollPosition(intlKey, index = 5, offset = 110)

        // User switches to another Main Tab (e.g., Home / Saved) and comes back to Coverage tab within TTL
        val restoredIsIntl = holder.isIntl
        val restoredTopic = holder.selectedTopic
        val restoredKey = repo.coverageCacheKey(restoredIsIntl, restoredTopic)
        val restoredCached = repo.getCachedCoverageResult(restoredIsIntl, restoredTopic)
        val restoredScroll = holder.getScrollPosition(restoredKey)
        val isFresh = repo.isCoverageCacheFresh(restoredIsIntl, restoredTopic, nowMs = nowMs + 45_000L)
        val enterAction = resolveCoverageEnterAction(restoredCached, isFresh, returningFromReader = false)
        val snapshot = createInitialCoverageSnapshot(restoredCached)

        check(restoredIsIntl) { "Expected International selection to persist across Main Tab switches" }
        check(enterAction == CoverageEnterAction.SHOW_CACHED_ONLY) {
            "Expected SHOW_CACHED_ONLY when returning to Coverage tab within TTL"
        }
        check(resolveCoverageBodyState(snapshot) == CoverageBodyState.CONTENT) {
            "Expected immediate CONTENT state without loading screen when switching back to Coverage tab"
        }
        check(restoredScroll.firstVisibleItemIndex == 5 && restoredScroll.firstVisibleItemScrollOffset == 110) {
            "Expected scroll position (5, 110) to be preserved across Main Tab switches"
        }
        check(repo.coverageFetchExecutionCounter.get() == 1) {
            "Expected no network reload when switching back to Coverage tab within TTL"
        }
    }

    @Test
    fun test42_ttlExpiryWithStaleResultsTest() = runBlocking {
        val repo = NewsRepository()
        val t0 = 1_710_000_000_000L

        val initial = repo.fetchCoverageCached(
            intl = false,
            filterQuery = "",
            forceRefresh = false,
            nowMs = t0,
            topicFetcher = { topic, _ ->
                if (topic == "") sampleCoverageInputItems(t0) else emptyList()
            }
        )

        // At 2 minutes (< 3 min TTL), cache is fresh
        val at2Min = t0 + 2 * 60 * 1000L
        check(repo.isCoverageCacheFresh(intl = false, filterQuery = "", nowMs = at2Min)) {
            "Expected Coverage cache to be fresh at 2 minutes (TTL = 3 minutes)"
        }

        // At 3 minutes + 1 ms (> 3 min TTL), cache is stale, but cached data MUST NOT be deleted
        val afterTtl = t0 + NewsRepository.COVERAGE_CACHE_TTL_MS + 1L
        check(!repo.isCoverageCacheFresh(intl = false, filterQuery = "", nowMs = afterTtl)) {
            "Expected Coverage cache to be marked stale after 3 minutes"
        }
        val staleCached = repo.getCachedCoverageResult(intl = false, filterQuery = "")
        check(staleCached != null && staleCached === initial) {
            "TTL expiry must NEVER delete or evict cached Coverage results"
        }

        val actionOnStaleTabReturn = resolveCoverageEnterAction(
            cached = staleCached,
            isCacheFresh = false,
            returningFromReader = false
        )
        check(actionOnStaleTabReturn == CoverageEnterAction.BACKGROUND_REFRESH) {
            "Expected BACKGROUND_REFRESH (Stale-While-Revalidate) when cache is stale on tab return"
        }
    }

    @Test
    fun test43_backgroundRefreshWhileShowingCachedResultsTest() = runBlocking {
        val repo = NewsRepository()
        val t0 = 1_710_000_000_000L

        val cachedV1 = repo.fetchCoverageCached(
            intl = false,
            filterQuery = "",
            forceRefresh = false,
            nowMs = t0,
            topicFetcher = { topic, _ ->
                if (topic == "") sampleCoverageInputItems(t0, "প্রথম সংস্করণ বাজেট সংবাদ") else emptyList()
            }
        )

        // Frame 1 when returning to stale cache: immediately shows V1 content
        var uiState = createInitialCoverageSnapshot(cachedV1)
        check(resolveCoverageBodyState(uiState) == CoverageBodyState.CONTENT) {
            "Expected stale cached results V1 to be displayed immediately as CONTENT"
        }
        check(uiState.coverageList.first().headline.contains("প্রথম সংস্করণ")) {
            "Expected V1 headline to be visible before background refresh completes"
        }

        // Background refresh starts: V1 remains visible, isLoading=false, isRefreshing=true
        uiState = beginCoverageRefreshState(uiState)
        check(!uiState.isLoading && uiState.isRefreshing) {
            "Expected background refresh to set isLoading=false and isRefreshing=true"
        }
        check(resolveCoverageBodyState(uiState) == CoverageBodyState.CONTENT) {
            "Screen must remain in CONTENT state (never LOADING or blank) during background refresh"
        }
        check(uiState.coverageList.size == cachedV1.coverageList.size) {
            "Existing V1 Coverage cards must remain visible during background refresh"
        }

        // Background refresh finishes with V2
        val t1 = t0 + NewsRepository.COVERAGE_CACHE_TTL_MS + 5_000L
        val cachedV2 = repo.fetchCoverageCached(
            intl = false,
            filterQuery = "",
            forceRefresh = true,
            nowMs = t1,
            topicFetcher = { topic, _ ->
                if (topic == "") sampleCoverageInputItems(t1, "দ্বিতীয় সংস্করণ আপডেট সংবাদ") else emptyList()
            }
        )
        uiState = applyCoverageLoadResult(uiState, newResult = cachedV2)

        check(!uiState.isLoading && !uiState.isRefreshing && uiState.errorMsg == null) {
            "Expected refresh flags to clear after background refresh succeeds"
        }
        check(resolveCoverageBodyState(uiState) == CoverageBodyState.CONTENT) {
            "Expected CONTENT state after background refresh succeeds"
        }
        check(uiState.coverageList.first().headline.contains("দ্বিতীয় সংস্করণ")) {
            "Expected Coverage list to update atomically to V2 after background refresh"
        }
    }

    @Test
    fun test44_refreshFailurePreservesPreviousResultsTest() = runBlocking {
        val repo = NewsRepository()
        val t0 = 1_710_000_000_000L

        val cachedV1 = repo.fetchCoverageCached(
            intl = false,
            filterQuery = "",
            forceRefresh = false,
            nowMs = t0,
            topicFetcher = { topic, _ ->
                if (topic == "") sampleCoverageInputItems(t0) else emptyList()
            }
        )

        var uiState = createInitialCoverageSnapshot(cachedV1)
        uiState = beginCoverageRefreshState(uiState)

        // Simulate background refresh throwing an exception (e.g., offline / timeout)
        var threw = false
        try {
            repo.fetchCoverageCached(
                intl = false,
                filterQuery = "",
                forceRefresh = true,
                nowMs = t0 + 300_000L,
                topicFetcher = { _, _ ->
                    throw IOException("Simulated network failure during background refresh")
                }
            )
        } catch (_: Exception) {
            threw = true
            uiState = applyCoverageLoadResult(
                current = uiState,
                newResult = null,
                errorMessage = COVERAGE_LOAD_ERROR_MESSAGE
            )
        }

        check(threw) { "Expected simulated refresh failure to throw" }
        check(uiState.coverageList == cachedV1.coverageList) {
            "Previous Coverage results must be preserved when background refresh fails"
        }
        check(!uiState.isLoading && !uiState.isRefreshing) {
            "Expected loading/refreshing flags to be false after failed background refresh"
        }
        check(uiState.errorMsg == null) {
            "Expected errorMsg to remain null when cached results are preserved"
        }
        check(resolveCoverageBodyState(uiState) == CoverageBodyState.CONTENT) {
            "Expected screen to stay on CONTENT (never ERROR or blank) when cached results exist"
        }
        check(repo.getCachedCoverageResult(intl = false, filterQuery = "") === cachedV1) {
            "Repository memory cache must still hold V1 after failed refresh"
        }
    }

    @Test
    fun test45_concurrentCoverageRequestDeduplicationTest() = runBlocking {
        val repo = NewsRepository()
        val nowMs = 1_710_000_000_000L

        val deferreds = (1..6).map {
            async(Dispatchers.Default) {
                repo.fetchCoverageCached(
                    intl = false,
                    filterQuery = "",
                    forceRefresh = false,
                    nowMs = nowMs,
                    topicFetcher = { topic, _ ->
                        Thread.sleep(25)
                        if (topic == "") sampleCoverageInputItems(nowMs) else emptyList()
                    }
                )
            }
        }

        val results = deferreds.awaitAll()
        val first = results.first()
        check(results.all { it === first }) {
            "Expected all 6 concurrent callers to receive the exact same shared CachedCoverageResult"
        }
        check(repo.coverageFetchExecutionCounter.get() == 1) {
            "Expected concurrent requests for the same key to execute only 1 underlying fetch batch, got ${repo.coverageFetchExecutionCounter.get()}"
        }
        check(repo.coverageBuildExecutionCounter.get() == 1) {
            "Expected concurrent requests for the same key to execute only 1 buildCoverage pass, got ${repo.coverageBuildExecutionCounter.get()}"
        }
    }

    @Test
    fun test46_articleReadStatusUpdateWithoutFullCoverageReloadTest() = runBlocking {
        val prefs = InMemorySharedPreferences()
        val appRepo = AppStateRepository(prefs = prefs, ioDispatcher = Dispatchers.IO)
        val newsRepo = NewsRepository()
        val nowMs = 1_710_000_000_000L

        val cached = newsRepo.fetchCoverageCached(
            intl = false,
            filterQuery = "",
            forceRefresh = false,
            nowMs = nowMs,
            topicFetcher = { topic, _ ->
                if (topic == "") sampleCoverageInputItems(nowMs) else emptyList()
            }
        )

        val targetCluster = cached.manyCoverage.first()
        val clickedArticle = targetCluster.items[0]
        val siblingUnreadArticle = targetCluster.items[1]

        check(!appRepo.isArticleRead(clickedArticle.link, clickedArticle.title)) {
            "Expected article to be unread before click"
        }

        // User clicks article in CoverageDetailCard -> marks read and navigates to ReaderScreen
        appRepo.markArticleRead(
            url = clickedArticle.link,
            title = clickedArticle.title,
            sourceName = clickedArticle.source,
            paperUrl = clickedArticle.sourceUrl
        )
        newsRepo.coverageStateHolder.markNavigatingToReader()

        // User returns from ReaderScreen -> read status is updated in appRepo.state.value.readArticles
        // while Coverage cache and clusters are untouched (zero refetch or rebuild)
        check(appRepo.isArticleRead(clickedArticle.link, clickedArticle.title)) {
            "Expected clicked article to be marked as read ('• পঠিত') immediately"
        }
        check(!appRepo.isArticleRead(siblingUnreadArticle.link, siblingUnreadArticle.title)) {
            "Expected sibling article in the same Coverage card to remain unread"
        }
        check(newsRepo.coverageFetchExecutionCounter.get() == 1 && newsRepo.coverageBuildExecutionCounter.get() == 1) {
            "Marking an article read and returning from Reader must never trigger Coverage reload or rebuild"
        }
    }

    @Test
    fun test47_differentFilterIntlCacheIsolationTest() = runBlocking {
        val repo = NewsRepository()
        val nowMs = 1_710_000_000_000L

        val domDefault = repo.fetchCoverageCached(
            intl = false,
            filterQuery = "",
            forceRefresh = false,
            nowMs = nowMs,
            topicFetcher = { topic, _ ->
                if (topic == "") sampleCoverageInputItems(nowMs, "বাংলাদেশ জাতীয় বাজেট সংস্কার প্রস্তাব") else emptyList()
            }
        )

        val intlDefault = repo.fetchCoverageCached(
            intl = true,
            filterQuery = "",
            forceRefresh = false,
            nowMs = nowMs,
            topicFetcher = { topic, isIntl ->
                if (isIntl && topic == "") sampleCoverageInputItems(nowMs, "বিশ্ব অর্থনৈতিক সম্মেলনে নতুন ঘোষণা") else emptyList()
            }
        )

        val domSports = repo.fetchCoverageCached(
            intl = false,
            filterQuery = "খেলা",
            forceRefresh = false,
            nowMs = nowMs,
            topicFetcher = { topic, _ ->
                if (topic == "খেলা") sampleCoverageInputItems(nowMs, "বাংলাদেশ ক্রিকেট দলের টেস্ট সিরিজ জয়") else emptyList()
            }
        )

        check(domDefault.cacheKey != intlDefault.cacheKey && domDefault.cacheKey != domSports.cacheKey) {
            "Expected distinct cache keys for (intl=false, q=''), (intl=true, q=''), and (intl=false, q='খেলা')"
        }
        check(repo.getCachedCoverageResult(false, "") === domDefault) {
            "Expected domestic default cache to remain isolated and intact"
        }
        check(repo.getCachedCoverageResult(true, "") === intlDefault) {
            "Expected international default cache to remain isolated and intact"
        }
        check(repo.getCachedCoverageResult(false, "খেলা") === domSports) {
            "Expected domestic 'খেলা' topic cache to remain isolated and intact"
        }
        check(domDefault.coverageList.first().headline.contains("বাংলাদেশ জাতীয় বাজেট")) {
            "Unexpected domestic default headline: ${domDefault.coverageList.first().headline}"
        }
        check(intlDefault.coverageList.first().headline.contains("বিশ্ব অর্থনৈতিক সম্মেলনে")) {
            "Unexpected international default headline: ${intlDefault.coverageList.first().headline}"
        }
        check(domSports.coverageList.first().headline.contains("বাংলাদেশ ক্রিকেট দলের")) {
            "Unexpected domestic sports headline: ${domSports.coverageList.first().headline}"
        }

        // Switching back to any already-cached filter hits memory cache without executing another fetch
        val countBefore = repo.coverageFetchExecutionCounter.get()
        val hitDom = repo.fetchCoverageCached(intl = false, filterQuery = "", forceRefresh = false, nowMs = nowMs + 10_000L)
        val hitIntl = repo.fetchCoverageCached(intl = true, filterQuery = "", forceRefresh = false, nowMs = nowMs + 10_000L)
        val hitSports = repo.fetchCoverageCached(intl = false, filterQuery = "খেলা", forceRefresh = false, nowMs = nowMs + 10_000L)
        check(hitDom === domDefault && hitIntl === intlDefault && hitSports === domSports) {
            "Expected switching between cached editions/filters to return their respective cached instances"
        }
        check(repo.coverageFetchExecutionCounter.get() == countBefore) {
            "Expected zero additional fetch executions when switching among cached filters"
        }
    }

    // =========================================================================
    // PHASE 3.5.3: HOME FEED PERSISTENT STATE, SCROLL RESTORATION & BACK NAV
    // =========================================================================

    @Test
    fun test48_homeFeedScrollRestorationAfterReaderBack() = runBlocking {
        val repo = NewsRepository()
        val holder = repo.homeFeedStateHolder
        val nowMs = 1_710_000_000_000L
        val favs = setOf("https://www.prothomalo.com")

        val loaded = repo.fetchHomeFeedCached(
            categoryKey = "top",
            query = "",
            intl = false,
            feedMode = HomeFeedMode.TOP_STORIES,
            favoriteUrls = favs,
            forceRefresh = false,
            nowMs = nowMs,
            newsFetcher = { _, _, _ -> sampleCoverageInputItems(nowMs) }
        )

        // Simulate user scrolling down to card #50 (index = 50, offset = 184) and tapping an article
        val seenKeys = holder.getOrCreateSeenKeys("top")
        seenKeys.add("feed_url:https://www.prothomalo.com/bangladesh/budget-1")
        holder.selectedCategoryIndex = 0
        holder.saveScrollPosition("top", HomeFeedMode.TOP_STORIES, index = 50, offset = 184)
        holder.markNavigatingToReader()

        checkTrue(holder.returningFromReader, "returningFromReader must be true after opening ReaderScreen")

        // Simulate pressing Back from ReaderScreen -> PersonalHomeScreen mounts on Frame 1
        val frame1Cached = repo.getCachedHomeFeedResult("top", HomeFeedMode.TOP_STORIES, favs)
        checkNotNull(frame1Cached, "Frame 1 must find cached HomeFeedResult immediately")
        checkTrue(frame1Cached === loaded, "Frame 1 cached result must be the exact processed instance")

        val frame1BodyState = resolveHomeBodyState(
            isLoading = (frame1Cached == null),
            newsItems = frame1Cached?.newsItems ?: emptyList(),
            loadedCategoryKey = if (frame1Cached != null) "top" else null,
            effectiveCatKey = "top",
            errorMsg = null
        )
        checkEquals(HomeBodyState.CONTENT, frame1BodyState, "Frame 1 must render HomeBodyState.CONTENT with zero loading skeleton flash")

        val restoredScroll = holder.getScrollPosition("top", HomeFeedMode.TOP_STORIES)
        checkEquals(50, restoredScroll.firstVisibleItemIndex, "Scroll index 50 must be restored on Reader Back")
        checkEquals(184, restoredScroll.firstVisibleItemScrollOffset, "Scroll offset 184 must be restored on Reader Back")
        checkTrue(
            holder.getOrCreateSeenKeys("top").contains("feed_url:https://www.prothomalo.com/bangladesh/budget-1"),
            "Seen item keys must persist across Reader navigation so reveal animations do not replay"
        )

        val wasReturning = holder.consumeReturningFromReader()
        checkTrue(wasReturning, "consumeReturningFromReader() must return true on Back")
        checkFalse(holder.returningFromReader, "consumeReturningFromReader() must reset flag to false")

        // Even if the user spent > 3 minutes reading the article, Back must NOT trigger a reload
        val enterActionAfterLongRead = resolveHomeFeedEnterAction(
            cached = frame1Cached,
            isCacheFresh = false,
            returningFromReader = wasReturning
        )
        checkEquals(
            HomeFeedEnterAction.SHOW_CACHED_ONLY,
            enterActionAfterLongRead,
            "Returning from ReaderScreen must show cached content immediately without reloading even if TTL elapsed"
        )
        checkEquals(1, repo.homeFeedFetchExecutionCounter.get(), "Zero additional fetches on Reader Back")
        checkEquals(1, repo.homeFeedBuildExecutionCounter.get(), "Zero additional clustering builds on Reader Back")
    }

    @Test
    fun test49_homeFeedStateRestorationAfterMainTabSwitching() = runBlocking {
        val repo = NewsRepository()
        val holder = repo.homeFeedStateHolder
        val nowMs = 1_710_000_000_000L
        val favs = emptySet<String>()

        // User selects category index 2 ("sports") and scrolls to (18, 96)
        holder.selectedCategoryIndex = 2
        holder.selectedLocalRegionId = "chattogram"
        val sportsLoaded = repo.fetchHomeFeedCached(
            categoryKey = "sports",
            query = "খেলা",
            intl = false,
            feedMode = HomeFeedMode.TOP_STORIES,
            favoriteUrls = favs,
            forceRefresh = false,
            nowMs = nowMs,
            newsFetcher = { _, _, _ -> sampleCoverageInputItems(nowMs, "বাংলাদেশ ক্রিকেট দলের ঐতিহাসিক টেস্ট জয়") }
        )
        holder.saveScrollPosition("sports", HomeFeedMode.TOP_STORIES, index = 18, offset = 96)

        // User switches to another Main Tab (e.g. Coverage / Saved) and comes back to Home Tab within TTL
        checkEquals(2, holder.selectedCategoryIndex, "Selected category index must persist across Main Tab switch")
        checkEquals("chattogram", holder.selectedLocalRegionId, "Selected local region ID must persist across Main Tab switch")

        val restoredCached = repo.getCachedHomeFeedResult("sports", HomeFeedMode.TOP_STORIES, favs)
        val isFresh = repo.isHomeFeedCacheFresh("sports", HomeFeedMode.TOP_STORIES, favs, nowMs = nowMs + 45_000L)
        val enterAction = resolveHomeFeedEnterAction(
            cached = restoredCached,
            isCacheFresh = isFresh,
            returningFromReader = false
        )
        checkEquals(HomeFeedEnterAction.SHOW_CACHED_ONLY, enterAction, "Returning to Home Tab within TTL must use SHOW_CACHED_ONLY")
        checkTrue(restoredCached === sportsLoaded, "Restored cached result must match sportsLoaded")

        val restoredScroll = holder.getScrollPosition("sports", HomeFeedMode.TOP_STORIES)
        checkEquals(18, restoredScroll.firstVisibleItemIndex, "Category scroll index 18 must persist across Main Tab switch")
        checkEquals(96, restoredScroll.firstVisibleItemScrollOffset, "Category scroll offset 96 must persist across Main Tab switch")
        checkEquals(1, repo.homeFeedFetchExecutionCounter.get(), "Switching back to Home Tab within TTL must not re-fetch")
    }

    @Test
    fun test50_separateScrollStateForLatestNewsAndTopStories() = runBlocking {
        val repo = NewsRepository()
        val holder = repo.homeFeedStateHolder
        val nowMs = 1_710_000_000_000L
        val favs = setOf("https://www.prothomalo.com")

        val topStoriesResult = repo.fetchHomeFeedCached(
            categoryKey = "top",
            query = "",
            intl = false,
            feedMode = HomeFeedMode.TOP_STORIES,
            favoriteUrls = favs,
            forceRefresh = false,
            nowMs = nowMs,
            newsFetcher = { _, _, _ -> sampleCoverageInputItems(nowMs) }
        )
        val latestNewsResult = repo.fetchHomeFeedCached(
            categoryKey = "top",
            query = "",
            intl = false,
            feedMode = HomeFeedMode.LATEST_NEWS,
            favoriteUrls = favs,
            forceRefresh = false,
            nowMs = nowMs + 1_000L,
            newsFetcher = { _, _, _ ->
                throw AssertionError("Switching to LATEST_NEWS with fresh raw category items must not re-fetch network")
            }
        )

        checkTrue(topStoriesResult.cacheKey != latestNewsResult.cacheKey, "TOP_STORIES and LATEST_NEWS must have distinct cache keys")
        checkTrue(topStoriesResult !== latestNewsResult, "TOP_STORIES and LATEST_NEWS must store separate processed CachedHomeFeedResult instances")
        checkEquals(HomeFeedMode.TOP_STORIES, topStoriesResult.feedMode, "TOP_STORIES result must have TOP_STORIES mode")
        checkEquals(HomeFeedMode.LATEST_NEWS, latestNewsResult.feedMode, "LATEST_NEWS result must have LATEST_NEWS mode")
        checkEquals(2, topStoriesResult.coverageList.size, "TOP_STORIES clusters multi-paper stories (2 clusters)")
        checkEquals(2, latestNewsResult.coverageList.size, "LATEST_NEWS clusters stories chronologically (2 clusters)")

        // Save distinct scroll positions for TOP_STORIES and LATEST_NEWS in the same "top" category
        holder.saveScrollPosition("top", HomeFeedMode.TOP_STORIES, index = 35, offset = 140)
        holder.saveScrollPosition("top", HomeFeedMode.LATEST_NEWS, index = 12, offset = 65)

        val topPos = holder.getScrollPosition("top", HomeFeedMode.TOP_STORIES)
        val latestPos = holder.getScrollPosition("top", HomeFeedMode.LATEST_NEWS)

        checkEquals(35, topPos.firstVisibleItemIndex, "TOP_STORIES scroll index must remain 35")
        checkEquals(140, topPos.firstVisibleItemScrollOffset, "TOP_STORIES scroll offset must remain 140")
        checkEquals(12, latestPos.firstVisibleItemIndex, "LATEST_NEWS scroll index must remain 12")
        checkEquals(65, latestPos.firstVisibleItemScrollOffset, "LATEST_NEWS scroll offset must remain 65")
    }

    @Test
    fun test51_categoryAndLocalRegionCacheAndScrollIsolation() = runBlocking {
        val repo = NewsRepository()
        val holder = repo.homeFeedStateHolder
        val nowMs = 1_710_000_000_000L
        val favs = emptySet<String>()

        val categories = listOf(
            Triple("top", "", "জাতীয় শীর্ষ সংবাদ"),
            Triple("sports", "খেলা", "ক্রিকেট ও ফুটবল সংবাদ"),
            Triple("local_dhaka", "ঢাকা", "ঢাকা মহানগরের সংবাদ"),
            Triple("local_sylhet", "সিলেট", "সিলেট বিভাগের সংবাদ")
        )

        for ((idx, triple) in categories.withIndex()) {
            val (catKey, q, prefix) = triple
            repo.fetchHomeFeedCached(
                categoryKey = catKey,
                query = q,
                intl = false,
                feedMode = HomeFeedMode.TOP_STORIES,
                favoriteUrls = favs,
                forceRefresh = false,
                nowMs = nowMs,
                newsFetcher = { _, _, _ -> sampleCoverageInputItems(nowMs, prefix) }
            )
            holder.saveScrollPosition(catKey, HomeFeedMode.TOP_STORIES, index = (idx + 1) * 10, offset = (idx + 1) * 25)
        }

        for ((idx, triple) in categories.withIndex()) {
            val (catKey, _, prefix) = triple
            val cached = repo.getCachedHomeFeedResult(catKey, HomeFeedMode.TOP_STORIES, favs)
            checkNotNull(cached, "Expected isolated cache for $catKey")
            checkTrue(
                cached!!.coverageList.first().headline.contains(prefix),
                "Expected isolated headline for $catKey to contain '$prefix', got '${cached.coverageList.first().headline}'"
            )
            val pos = holder.getScrollPosition(catKey, HomeFeedMode.TOP_STORIES)
            checkEquals((idx + 1) * 10, pos.firstVisibleItemIndex, "Isolated scroll index for $catKey")
            checkEquals((idx + 1) * 25, pos.firstVisibleItemScrollOffset, "Isolated scroll offset for $catKey")
        }

        // Also verify bounded LRU eviction when inserting > MAX_HOME_FEED_CACHE_ENTRIES (24) entries
        for (i in 1..30) {
            repo.fetchHomeFeedCached(
                categoryKey = "cat_$i",
                query = "q_$i",
                intl = false,
                feedMode = HomeFeedMode.TOP_STORIES,
                favoriteUrls = favs,
                forceRefresh = false,
                nowMs = nowMs,
                newsFetcher = { _, _, _ -> sampleCoverageInputItems(nowMs, "সংবাদ $i") }
            )
        }
        checkTrue(
            holder.processedCacheSize() <= NewsRepository.MAX_HOME_FEED_CACHE_ENTRIES,
            "Processed cache must be bounded by MAX_HOME_FEED_CACHE_ENTRIES (${NewsRepository.MAX_HOME_FEED_CACHE_ENTRIES}), actual=${holder.processedCacheSize()}"
        )
    }

    @Test
    fun test52_homeFeedCacheCorrectnessAfterFavoritesChange() = runBlocking {
        val repo = NewsRepository()
        val nowMs = 1_710_000_000_000L
        val favsA = setOf("https://www.prothomalo.com")
        val favsB = setOf("https://www.jugantor.com", "https://www.prothomalo.com")

        // Order-independent signature for the same set of favorites
        val sigB1 = repo.computeFavoritesSignature(listOf("https://www.jugantor.com", "https://www.prothomalo.com"))
        val sigB2 = repo.computeFavoritesSignature(listOf("https://www.prothomalo.com", "https://www.jugantor.com"))
        checkEquals(sigB1, sigB2, "Favorites signature must be deterministic regardless of collection iteration order")

        val topWithFavA = repo.fetchHomeFeedCached(
            categoryKey = "top",
            query = "",
            intl = false,
            feedMode = HomeFeedMode.TOP_STORIES,
            favoriteUrls = favsA,
            forceRefresh = false,
            nowMs = nowMs,
            newsFetcher = { _, _, _ -> sampleCoverageInputItems(nowMs) }
        )
        checkEquals(1, repo.homeFeedFetchExecutionCounter.get(), "Initial load must fetch once")
        checkEquals(1, repo.homeFeedBuildExecutionCounter.get(), "Initial load must build once")

        // Changing favorites in TOP_STORIES must reuse fresh raw category items without network fetch,
        // while re-computing Top Stories clustering for the new favorites set.
        val topWithFavB = repo.fetchHomeFeedCached(
            categoryKey = "top",
            query = "",
            intl = false,
            feedMode = HomeFeedMode.TOP_STORIES,
            favoriteUrls = favsB,
            forceRefresh = false,
            nowMs = nowMs + 5_000L,
            newsFetcher = { _, _, _ ->
                throw AssertionError("Favorites change within raw TTL must reuse raw category items without network fetch")
            }
        )
        checkTrue(topWithFavA.cacheKey != topWithFavB.cacheKey, "Different favorites sets must produce distinct TOP_STORIES cache keys")
        checkEquals(1, repo.homeFeedFetchExecutionCounter.get(), "Network fetch count must remain 1 after favorites change")
        checkEquals(2, repo.homeFeedBuildExecutionCounter.get(), "Clustering build count must increment to 2 for new favorites set")

        // In LATEST_NEWS mode, favorites do not affect chronological ordering so cache key ignores favorites changes
        val latestWithFavA = repo.fetchHomeFeedCached(
            categoryKey = "top",
            query = "",
            intl = false,
            feedMode = HomeFeedMode.LATEST_NEWS,
            favoriteUrls = favsA,
            forceRefresh = false,
            nowMs = nowMs + 6_000L
        )
        val latestWithFavB = repo.fetchHomeFeedCached(
            categoryKey = "top",
            query = "",
            intl = false,
            feedMode = HomeFeedMode.LATEST_NEWS,
            favoriteUrls = favsB,
            forceRefresh = false,
            nowMs = nowMs + 7_000L
        )
        checkTrue(latestWithFavA === latestWithFavB, "LATEST_NEWS cache must be unaffected by favorites changes")
        checkEquals(3, repo.homeFeedBuildExecutionCounter.get(), "LATEST_NEWS should only build once across both favorites sets")
    }

    @Test
    fun test53_avoidNetworkFetchOnFreshHomeFeedCacheHit() = runBlocking {
        val repo = NewsRepository()
        val nowMs = 1_710_000_000_000L
        val favs = setOf("https://www.prothomalo.com")

        val first = repo.fetchHomeFeedCached(
            categoryKey = "top",
            query = "",
            intl = false,
            feedMode = HomeFeedMode.TOP_STORIES,
            favoriteUrls = favs,
            forceRefresh = false,
            nowMs = nowMs,
            newsFetcher = { _, _, _ -> sampleCoverageInputItems(nowMs) }
        )

        val second = repo.fetchHomeFeedCached(
            categoryKey = "top",
            query = "",
            intl = false,
            feedMode = HomeFeedMode.TOP_STORIES,
            favoriteUrls = favs,
            forceRefresh = false,
            nowMs = nowMs + 90_000L, // 1.5 minutes (< 3 min TTL)
            newsFetcher = { _, _, _ ->
                throw AssertionError("rawFetcher must never be invoked on a fresh HomeFeed cache hit")
            }
        )

        checkTrue(first === second, "Fresh cache hit must return the exact same CachedHomeFeedResult instance")
        checkEquals(1, repo.homeFeedFetchExecutionCounter.get(), "Fetch counter must remain 1 on fresh cache hit")
        checkEquals(1, repo.homeFeedBuildExecutionCounter.get(), "Build counter must remain 1 on fresh cache hit")
    }

    @Test
    fun test54_backgroundRefreshOnStaleHomeFeedCache() = runBlocking {
        val repo = NewsRepository()
        val t0 = 1_710_000_000_000L
        val favs = emptySet<String>()

        val cachedV1 = repo.fetchHomeFeedCached(
            categoryKey = "top",
            query = "",
            intl = false,
            feedMode = HomeFeedMode.TOP_STORIES,
            favoriteUrls = favs,
            forceRefresh = false,
            nowMs = t0,
            newsFetcher = { _, _, _ -> sampleCoverageInputItems(t0, "প্রথম সংস্করণ প্রধান সংবাদ") }
        )

        val afterTtl = t0 + NewsRepository.HOME_FEED_CACHE_TTL_MS + 1L
        checkFalse(
            repo.isHomeFeedCacheFresh("top", HomeFeedMode.TOP_STORIES, favs, nowMs = afterTtl),
            "Cache must be marked stale after 3 minutes"
        )

        val staleCached = repo.getCachedHomeFeedResult("top", HomeFeedMode.TOP_STORIES, favs)
        checkTrue(staleCached === cachedV1, "TTL expiry must NEVER delete or evict cached Home Feed results")

        val enterAction = resolveHomeFeedEnterAction(
            cached = staleCached,
            isCacheFresh = false,
            returningFromReader = false
        )
        checkEquals(
            HomeFeedEnterAction.BACKGROUND_REFRESH,
            enterAction,
            "Stale cache on tab return must trigger BACKGROUND_REFRESH (Stale-While-Revalidate)"
        )

        // Frame 1 with stale cache still renders CONTENT immediately (no skeleton loader)
        val bodyStateDuringRefresh = resolveHomeBodyState(
            isLoading = false,
            newsItems = staleCached!!.newsItems,
            loadedCategoryKey = "top",
            effectiveCatKey = "top",
            errorMsg = null
        )
        checkEquals(HomeBodyState.CONTENT, bodyStateDuringRefresh, "Stale cache must stay in CONTENT state while refreshing in background")

        // Background refresh completes with V2
        val cachedV2 = repo.fetchHomeFeedCached(
            categoryKey = "top",
            query = "",
            intl = false,
            feedMode = HomeFeedMode.TOP_STORIES,
            favoriteUrls = favs,
            forceRefresh = false,
            nowMs = afterTtl,
            newsFetcher = { _, _, _ -> sampleCoverageInputItems(afterTtl, "দ্বিতীয় সংস্করণ আপডেট সংবাদ") }
        )
        checkTrue(cachedV2.coverageList.first().headline.contains("দ্বিতীয় সংস্করণ"), "Background refresh must update cache to V2")
    }

    @Test
    fun test55_preserveOldHomeFeedDataOnRefreshFailure() = runBlocking {
        val repo = NewsRepository()
        val t0 = 1_710_000_000_000L
        val favs = emptySet<String>()

        val cachedV1 = repo.fetchHomeFeedCached(
            categoryKey = "top",
            query = "",
            intl = false,
            feedMode = HomeFeedMode.TOP_STORIES,
            favoriteUrls = favs,
            forceRefresh = false,
            nowMs = t0,
            newsFetcher = { _, _, _ -> sampleCoverageInputItems(t0, "সংরক্ষিত পূর্ববর্তী সংবাদ") }
        )

        val afterTtl = t0 + NewsRepository.HOME_FEED_CACHE_TTL_MS + 10_000L
        var refreshThrew = false
        try {
            repo.fetchHomeFeedCached(
                categoryKey = "top",
                query = "",
                intl = false,
                feedMode = HomeFeedMode.TOP_STORIES,
                favoriteUrls = favs,
                forceRefresh = true,
                nowMs = afterTtl,
                newsFetcher = { _, _, _ ->
                    throw IOException("Network unreachable during background refresh")
                }
            )
        } catch (_: IOException) {
            refreshThrew = true
        }
        checkTrue(refreshThrew, "Failed refresh must throw IOException")

        // Verify repository still holds V1 in memory cache
        val preservedInCache = repo.getCachedHomeFeedResult("top", HomeFeedMode.TOP_STORIES, favs)
        checkTrue(preservedInCache === cachedV1, "Failed refresh must preserve previous V1 result in HomeFeedPersistentStateHolder")

        // Verify UI state reducer preserves V1 items, coverageList, and CONTENT state without showing error screen
        val updatedSnapshot = applyHomeLoadResult(
            currentSnapshot = HomeLoadStateSnapshot(
                newsItems = cachedV1.newsItems,
                coverageList = cachedV1.coverageList,
                isLoading = false,
                isManualRefreshing = true,
                errorMsg = null,
                loadedCategoryKey = "top",
                requestVersion = 2
            ),
            completedRequestVersion = 2,
            targetCategoryKey = "top",
            fetchedItems = null,
            builtCoverage = null,
            failure = IOException("Network unreachable")
        )
        checkEquals(cachedV1.newsItems, updatedSnapshot.newsItems, "Previous newsItems must be preserved on refresh failure")
        checkEquals(cachedV1.coverageList, updatedSnapshot.coverageList, "Previous coverageList must be preserved on refresh failure")
        checkNull(updatedSnapshot.errorMsg, "errorMsg must remain null when previous cached items are present")
        checkEquals(
            HomeBodyState.CONTENT,
            resolveHomeBodyState(
                isLoading = updatedSnapshot.isLoading,
                newsItems = updatedSnapshot.newsItems,
                loadedCategoryKey = updatedSnapshot.loadedCategoryKey,
                effectiveCatKey = "top",
                errorMsg = updatedSnapshot.errorMsg
            ),
            "Screen must remain on HomeBodyState.CONTENT when refresh fails with cached data"
        )
    }

    @Test
    fun test56_duplicateClusteringPreventionOnHomeFeedMountAndModeSwitch() = runBlocking {
        val repo = NewsRepository()
        val nowMs = 1_710_000_000_000L
        val favs = setOf("https://www.prothomalo.com")

        // Initial Home Feed load in TOP_STORIES mode must run clustering exactly ONCE (no triple clustering)
        val topResult = repo.fetchHomeFeedCached(
            categoryKey = "top",
            query = "",
            intl = false,
            feedMode = HomeFeedMode.TOP_STORIES,
            favoriteUrls = favs,
            forceRefresh = false,
            nowMs = nowMs,
            newsFetcher = { _, _, _ -> sampleCoverageInputItems(nowMs) }
        )
        checkEquals(1, repo.homeFeedFetchExecutionCounter.get(), "Initial mount must execute exactly 1 network fetch")
        checkEquals(1, repo.homeFeedBuildExecutionCounter.get(), "Initial mount must execute exactly 1 clustering pass")

        // Concurrent duplicate callers for the same activeCacheKey must coalesce into the single build
        val concurrent = (1..5).map {
            async(Dispatchers.Default) {
                repo.fetchHomeFeedCached(
                    categoryKey = "top",
                    query = "বাংলাদেশ",
                    intl = false,
                    feedMode = HomeFeedMode.TOP_STORIES,
                    favoriteUrls = favs,
                    forceRefresh = false,
                    nowMs = nowMs + 1_000L
                )
            }
        }.awaitAll()
        checkTrue(concurrent.all { it === topResult }, "All concurrent callers must receive the cached result")
        checkEquals(1, repo.homeFeedBuildExecutionCounter.get(), "Build counter must remain 1")

        // Switching to LATEST_NEWS reuses raw items (0 new fetches) and runs 1 build for LATEST_NEWS
        val latestResult = repo.fetchHomeFeedCached(
            categoryKey = "top",
            query = "",
            intl = false,
            feedMode = HomeFeedMode.LATEST_NEWS,
            favoriteUrls = favs,
            forceRefresh = false,
            nowMs = nowMs + 2_000L,
            newsFetcher = { _, _, _ ->
                throw AssertionError("Mode switch must reuse fresh raw category items")
            }
        )
        checkEquals(1, repo.homeFeedFetchExecutionCounter.get(), "Fetch counter must stay 1 on mode switch")
        checkEquals(2, repo.homeFeedBuildExecutionCounter.get(), "Build counter must be 2 after building LATEST_NEWS once")

        // Switching back to TOP_STORIES hits the processed TOP_STORIES cache directly (0 fetches, 0 builds)
        val backToTop = repo.fetchHomeFeedCached(
            categoryKey = "top",
            query = "",
            intl = false,
            feedMode = HomeFeedMode.TOP_STORIES,
            favoriteUrls = favs,
            forceRefresh = false,
            nowMs = nowMs + 3_000L
        )
        checkTrue(backToTop === topResult && latestResult.coverageList.isNotEmpty(), "Switching back to TOP_STORIES must hit processed cache")
        checkEquals(2, repo.homeFeedBuildExecutionCounter.get(), "Build counter must remain 2 when switching back to cached TOP_STORIES")
    }

    @Test
    fun test57_stableLazyColumnKeyUniquenessAndReorderBehavior() {
        val itemA = NewsItem(
            title = "সংবাদ ক শিরোনাম",
            link = "https://www.prothomalo.com/bangladesh/story-a/",
            source = "প্রথম আলো",
            sourceUrl = "https://www.prothomalo.com",
            time = 1000L
        )
        val itemB = NewsItem(
            title = "সংবাদ খ শিরোনাম",
            link = "https://www.jugantor.com/national/story-b#comments",
            source = "যুগান্তর",
            sourceUrl = "https://www.jugantor.com",
            time = 2000L
        )
        val itemBlankLink = NewsItem(
            title = "লিঙ্ক ছাড়া বিশেষ প্রতিবেদন",
            link = "   ",
            source = "সমকাল",
            sourceUrl = "https://samakal.com",
            time = 3000L
        )
        val itemDuplicateOfA = NewsItem(
            title = "সংবাদ ক শিরোনাম (কপি)",
            link = "HTTPS://WWW.PROTHOMALO.COM/bangladesh/story-a",
            source = "প্রথম আলো",
            sourceUrl = "https://www.prothomalo.com",
            time = 4000L
        )

        val listOriginal = listOf(
            Coverage(listOf(itemA)),
            Coverage(listOf(itemB)),
            Coverage(listOf(itemBlankLink))
        )
        val listReordered = listOf(
            Coverage(listOf(itemB)),
            Coverage(listOf(itemBlankLink)),
            Coverage(listOf(itemA))
        )

        val keysOriginal = buildStableFeedStoryKeys(listOriginal, prefix = "feed")
        val keysReordered = buildStableFeedStoryKeys(listReordered, prefix = "feed")

        checkEquals(3, keysOriginal.distinct().size, "All generated keys must be unique")
        checkEquals(
            "feed_url:https://www.prothomalo.com/bangladesh/story-a",
            keysOriginal[0],
            "URL key must be normalized without trailing slash"
        )
        checkEquals(
            "feed_url:https://www.jugantor.com/national/story-b",
            keysOriginal[1],
            "URL key must strip #fragment"
        )
        checkTrue(
            keysOriginal[2].startsWith("feed_pub:https://samakal.com|title:"),
            "Blank URL must fall back to deterministic publisher + normalized title key, got ${keysOriginal[2]}"
        )

        // When stories reorder (e.g., itemA moves from index 0 to index 2), its key MUST stay identical!
        checkEquals(keysOriginal[0], keysReordered[2], "Story A must keep its exact key when moved from index 0 to index 2")
        checkEquals(keysOriginal[1], keysReordered[0], "Story B must keep its exact key when moved from index 1 to index 0")
        checkEquals(keysOriginal[2], keysReordered[1], "Blank-link story must keep its exact key when reordered")

        // Verify collision safety if two clusters ever share the same normalized URL
        val listWithCollision = listOf(
            Coverage(listOf(itemA)),
            Coverage(listOf(itemDuplicateOfA)),
            Coverage(listOf(itemDuplicateOfA))
        )
        val collisionKeys = buildStableFeedStoryKeys(listWithCollision, prefix = "feed")
        checkEquals(3, collisionKeys.distinct().size, "Collision keys must be 100% unique so LazyColumn never crashes")
        checkEquals("feed_url:https://www.prothomalo.com/bangladesh/story-a", collisionKeys[0], "First occurrence uses base key")
        checkEquals("feed_url:https://www.prothomalo.com/bangladesh/story-a#dup2", collisionKeys[1], "Second occurrence gets #dup2 suffix")
        checkEquals("feed_url:https://www.prothomalo.com/bangladesh/story-a#dup3", collisionKeys[2], "Third occurrence gets #dup3 suffix")
    }

    @Test
    fun test58_readStatusUpdateWithoutFullHomeFeedReload() = runBlocking {
        val prefs = InMemorySharedPreferences()
        val appRepo = AppStateRepository(prefs = prefs, ioDispatcher = Dispatchers.IO)
        val newsRepo = NewsRepository()
        val nowMs = 1_710_000_000_000L
        val favs = emptySet<String>()

        val cached = newsRepo.fetchHomeFeedCached(
            categoryKey = "top",
            query = "",
            intl = false,
            feedMode = HomeFeedMode.TOP_STORIES,
            favoriteUrls = favs,
            forceRefresh = false,
            nowMs = nowMs,
            newsFetcher = { _, _, _ -> sampleCoverageInputItems(nowMs) }
        )

        val leadArticle = cached.coverageList.first().items.first()
        val secondClusterArticle = cached.coverageList.last().items.first()

        checkFalse(appRepo.isArticleRead(leadArticle.link, leadArticle.title), "Lead article must be unread initially")

        // User taps leadArticle -> marks read and navigates to ReaderScreen
        appRepo.markArticleRead(
            url = leadArticle.link,
            title = leadArticle.title,
            sourceName = leadArticle.source,
            paperUrl = leadArticle.sourceUrl
        )
        newsRepo.homeFeedStateHolder.markNavigatingToReader()

        // Verify read status updates immediately while Home Feed cache and clusters are not reloaded or rebuilt
        checkTrue(appRepo.isArticleRead(leadArticle.link, leadArticle.title), "Tapped article must be marked read immediately")
        checkFalse(appRepo.isArticleRead(secondClusterArticle.link, secondClusterArticle.title), "Other articles must remain unread")
        checkTrue(
            newsRepo.getCachedHomeFeedResult("top", HomeFeedMode.TOP_STORIES, favs) === cached,
            "Cached HomeFeedResult instance must remain untouched after marking an article read"
        )
        checkEquals(1, newsRepo.homeFeedFetchExecutionCounter.get(), "Marking article read must not trigger network fetch")
        checkEquals(1, newsRepo.homeFeedBuildExecutionCounter.get(), "Marking article read must not trigger clustering rebuild")
    }

    @Test
    fun test59_sharedHomeFeedRequestCancellationSafety() = runBlocking {
        val repo = NewsRepository()
        val nowMs = 1_710_000_000_000L
        val favs = emptySet<String>()

        // Part A: 2 callers share an in-flight request; Caller 1 cancels, Caller 2 must still succeed!
        val fetchStarted = CountDownLatch(1)
        val allowFinish = CountDownLatch(1)

        val slowFetcher: suspend (String, Boolean, Boolean) -> List<NewsItem> = { _, _, _ ->
            fetchStarted.countDown()
            withContext(Dispatchers.IO) {
                allowFinish.await(5, TimeUnit.SECONDS)
            }
            sampleCoverageInputItems(nowMs)
        }

        val caller1 = launch(Dispatchers.Default) {
            repo.fetchHomeFeedCached(
                categoryKey = "top",
                query = "",
                intl = false,
                feedMode = HomeFeedMode.TOP_STORIES,
                favoriteUrls = favs,
                forceRefresh = false,
                nowMs = nowMs,
                newsFetcher = slowFetcher
            )
        }

        checkTrue(fetchStarted.await(2, TimeUnit.SECONDS), "Shared in-flight fetch must start")

        val caller2 = async(Dispatchers.Default) {
            repo.fetchHomeFeedCached(
                categoryKey = "top",
                query = "",
                intl = false,
                feedMode = HomeFeedMode.TOP_STORIES,
                favoriteUrls = favs,
                forceRefresh = false,
                nowMs = nowMs,
                newsFetcher = slowFetcher
            )
        }

        // Give caller2 a moment to register on the shared request, then cancel caller1
        delay(30)
        caller1.cancelAndJoin()
        allowFinish.countDown()

        val caller2Result = caller2.await()
        checkTrue(caller2Result.coverageList.isNotEmpty(), "Caller 2 must succeed even after Caller 1 cancelled")
        checkEquals(1, repo.homeFeedFetchExecutionCounter.get(), "Shared request must execute only 1 fetch")
        checkEquals(1, repo.homeFeedBuildExecutionCounter.get(), "Shared request must execute only 1 build")

        // Part B: When the sole/last waiter cancels, the underlying shared job is cancelled and cleaned up
        val soleStarted = CountDownLatch(1)
        val soleCancelled = AtomicBoolean(false)
        val soleJob = launch(Dispatchers.Default) {
            repo.fetchHomeFeedCached(
                categoryKey = "intl",
                query = "বিশ্ব",
                intl = true,
                feedMode = HomeFeedMode.TOP_STORIES,
                favoriteUrls = favs,
                forceRefresh = false,
                nowMs = nowMs,
                newsFetcher = { _, _, _ ->
                    soleStarted.countDown()
                    try {
                        delay(10_000L)
                    } catch (e: CancellationException) {
                        soleCancelled.set(true)
                        throw e
                    }
                    emptyList()
                }
            )
        }
        checkTrue(soleStarted.await(2, TimeUnit.SECONDS), "Sole in-flight request for 'intl' must start")
        soleJob.cancelAndJoin()
        delay(30)
        checkTrue(soleCancelled.get(), "Cancelling the last waiter must cancel the underlying shared fetch coroutine")
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            val suite = NewsFeedAlgorithmTest()
            val results = suite.runAllTests()
            println("ALL ${results.size}/${results.size} TESTS PASSED SUCCESSFULLY.")
        }
    }
}
