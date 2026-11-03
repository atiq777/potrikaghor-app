package com.potrikaghor.ui.ads

import android.content.Context
import android.util.Log
import com.google.android.gms.ads.MobileAds
import com.potrikaghor.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Centralized Google AdMob configuration for PotrikaGhor.
 *
 * IMPORTANT FOR PRODUCTION RELEASE:
 * Set [IS_AD_TEST_MODE] to `false` only when ready to serve live production ads.
 * Keep [IS_AD_TEST_MODE] = `true` during all development, testing, and pre-release verification
 * to comply with Google AdMob policies and prevent invalid traffic on the production ad unit.
 */
object AdMobConfig {
    private const val TAG = "PotrikaGhorAds"

    /**
     * Single toggle for switching between the official Google test Native Ad Unit ID
     * and the PotrikaGhor production Native Ad Unit ID.
     */
    const val IS_AD_TEST_MODE: Boolean = true

    /**
     * PotrikaGhor AdMob App ID (also configured in AndroidManifest.xml).
     */
    const val ADMOB_APP_ID: String = "ca-app-pub-9071376132400765~4638216523"

    /**
     * Official Google Test Native Advanced Ad Unit ID for safe development/testing.
     */
    const val TEST_NATIVE_AD_UNIT_ID: String = "ca-app-pub-3940256099942544/2247696110"

    /**
     * PotrikaGhor Production Native Ad Unit ID.
     */
    const val PRODUCTION_NATIVE_AD_UNIT_ID: String = "ca-app-pub-9071376132400765/9950427964"

    /**
     * Active Native Ad Unit ID resolved from [IS_AD_TEST_MODE].
     */
    val activeNativeAdUnitId: String
        get() = if (IS_AD_TEST_MODE) TEST_NATIVE_AD_UNIT_ID else PRODUCTION_NATIVE_AD_UNIT_ID

    /**
     * Minimum number of vertical news feed items required before any Native Ad is inserted.
     * Prevents ads from appearing in very small lists.
     */
    const val MIN_FEED_ITEMS_FOR_ADS: Int = 6

    /**
     * Insert the first Native Ad after the 5th news item in the vertical feed.
     */
    const val FIRST_AD_AFTER_NEWS_COUNT: Int = 5

    /**
     * Insert subsequent Native Ads once every 9 news items (within the 8–10 news items range).
     */
    const val SUBSEQUENT_AD_INTERVAL: Int = 9

    /**
     * Maximum number of cached native ad slots per feed category to keep memory usage bounded.
     */
    const val MAX_AD_SLOTS_PER_FEED: Int = 4

    private val isInitialized = AtomicBoolean(false)
    private val initScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Initializes the Google Mobile Ads SDK once on a background thread without blocking the UI.
     */
    fun initializeMobileAdsIfNeeded(context: Context) {
        if (!isInitialized.compareAndSet(false, true)) {
            return
        }
        val appContext = context.applicationContext
        initScope.launch {
            try {
                MobileAds.initialize(appContext) { initializationStatus ->
                    if (BuildConfig.DEBUG) {
                        Log.d(
                            TAG,
                            "MobileAds initialized (testMode=$IS_AD_TEST_MODE, adapters=${initializationStatus.adapterStatusMap.size})"
                        )
                    }
                }
            } catch (e: Exception) {
                isInitialized.set(false)
                if (BuildConfig.DEBUG) {
                    Log.w(TAG, "MobileAds initialization failed: ${e.message}")
                }
            }
        }
    }

    /**
     * Determines whether a Native Ad slot should be placed immediately after the given
     * 0-based [feedItemIndex] in the main vertical news feed.
     */
    fun shouldShowAdAfterFeedIndex(feedItemIndex: Int, totalFeedItems: Int): Boolean {
        if (totalFeedItems < MIN_FEED_ITEMS_FOR_ADS) return false
        val itemsShownCount = feedItemIndex + 1
        if (itemsShownCount < FIRST_AD_AFTER_NEWS_COUNT) return false
        val offsetFromFirst = itemsShownCount - FIRST_AD_AFTER_NEWS_COUNT
        if (offsetFromFirst % SUBSEQUENT_AD_INTERVAL != 0) return false
        val slotIndex = offsetFromFirst / SUBSEQUENT_AD_INTERVAL
        return slotIndex < MAX_AD_SLOTS_PER_FEED
    }

    /**
     * Returns the 0-based ad slot index for a given 0-based [feedItemIndex].
     */
    fun adSlotIndexForFeedIndex(feedItemIndex: Int): Int {
        val itemsShownCount = feedItemIndex + 1
        return (itemsShownCount - FIRST_AD_AFTER_NEWS_COUNT) / SUBSEQUENT_AD_INTERVAL
    }
}
