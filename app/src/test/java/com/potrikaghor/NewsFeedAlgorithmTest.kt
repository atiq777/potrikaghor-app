package com.potrikaghor

import com.potrikaghor.data.DirectRssFeedSpec
import com.potrikaghor.data.NewsRepository
import com.potrikaghor.model.Coverage
import com.potrikaghor.model.HomeFeedMode
import com.potrikaghor.model.NewsItem
import com.potrikaghor.ui.screens.HOME_LOAD_ERROR_MESSAGE
import com.potrikaghor.ui.screens.HomeBodyState
import com.potrikaghor.ui.screens.HomeLoadStateSnapshot
import com.potrikaghor.ui.screens.applyHomeLoadResult
import com.potrikaghor.ui.screens.resolveHomeBodyState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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
            "test22_precomputedTitleSignatureAvoidsRepeatedTokenization" to ::test22_precomputedTitleSignatureAvoidsRepeatedTokenization
        )
        for ((name, block) in tests) {
            block()
            val msg = "PASS: $name"
            println(msg)
            results.add(msg)
        }
        return results
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
