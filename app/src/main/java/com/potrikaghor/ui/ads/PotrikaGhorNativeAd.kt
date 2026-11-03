package com.potrikaghor.ui.ads

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.VideoOptions
import com.google.android.gms.ads.nativead.MediaView
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdOptions
import com.google.android.gms.ads.nativead.NativeAdView
import com.potrikaghor.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private const val AD_LOG_TAG = "PotrikaGhorAds"

/**
 * Represents the load state of a single Native Ad slot in the feed.
 */
sealed class NativeAdSlotState {
    data object Idle : NativeAdSlotState()
    data object Loading : NativeAdSlotState()
    data class Loaded(val nativeAd: NativeAd) : NativeAdSlotState()
    data object Failed : NativeAdSlotState()
}

/**
 * Lifecycle-aware manager that caches loaded [NativeAd] instances by slot key for the
 * active screen session. Prevents duplicate ad requests during LazyColumn scroll/recomposition
 * and guarantees all [NativeAd] instances are destroyed when the screen is disposed.
 */
class NativeAdSlotManager(context: Context) {
    private val appContext = context.applicationContext
    private val slotFlows = HashMap<String, MutableStateFlow<NativeAdSlotState>>()
    private val loadedAds = HashMap<String, NativeAd>()
    private var isDisposed = false

    @Synchronized
    fun stateForSlot(slotKey: String): StateFlow<NativeAdSlotState> {
        return slotFlows.getOrPut(slotKey) { MutableStateFlow(NativeAdSlotState.Idle) }
    }

    @Synchronized
    fun requestSlotIfNeeded(slotKey: String) {
        if (isDisposed) return
        val flow = slotFlows.getOrPut(slotKey) { MutableStateFlow(NativeAdSlotState.Idle) }
        if (flow.value !is NativeAdSlotState.Idle) {
            return
        }
        flow.value = NativeAdSlotState.Loading

        AdMobConfig.initializeMobileAdsIfNeeded(appContext)

        val videoOptions = VideoOptions.Builder()
            .setStartMuted(true)
            .build()

        val adOptions = NativeAdOptions.Builder()
            .setAdChoicesPlacement(NativeAdOptions.ADCHOICES_TOP_RIGHT)
            .setVideoOptions(videoOptions)
            .build()

        val adUnitId = AdMobConfig.activeNativeAdUnitId

        val adLoader = AdLoader.Builder(appContext, adUnitId)
            .forNativeAd { nativeAd ->
                synchronized(this@NativeAdSlotManager) {
                    if (isDisposed) {
                        nativeAd.destroy()
                        return@forNativeAd
                    }
                    loadedAds.put(slotKey, nativeAd)?.destroy()
                    slotFlows[slotKey]?.value = NativeAdSlotState.Loaded(nativeAd)
                }
                if (BuildConfig.DEBUG) {
                    Log.d(AD_LOG_TAG, "Native ad loaded for slot=$slotKey (testMode=${AdMobConfig.IS_AD_TEST_MODE})")
                }
            }
            .withAdListener(object : AdListener() {
                override fun onAdFailedToLoad(error: LoadAdError) {
                    synchronized(this@NativeAdSlotManager) {
                        if (!isDisposed) {
                            slotFlows[slotKey]?.value = NativeAdSlotState.Failed
                        }
                    }
                    if (BuildConfig.DEBUG) {
                        Log.d(
                            AD_LOG_TAG,
                            "Native ad failed to load for slot=$slotKey: code=${error.code}, message=${error.message}"
                        )
                    }
                }
            })
            .withNativeAdOptions(adOptions)
            .build()

        adLoader.loadAd(AdRequest.Builder().build())
    }

    @Synchronized
    fun destroyAll() {
        isDisposed = true
        loadedAds.values.forEach { ad ->
            try {
                ad.destroy()
            } catch (_: Exception) {
            }
        }
        loadedAds.clear()
        slotFlows.values.forEach { it.value = NativeAdSlotState.Idle }
        slotFlows.clear()
        if (BuildConfig.DEBUG) {
            Log.d(AD_LOG_TAG, "Destroyed all cached NativeAd instances")
        }
    }
}

/**
 * Remembers a [NativeAdSlotManager] tied to the current composition lifecycle and
 * automatically destroys all native ads when the host screen leaves composition.
 */
@Composable
fun rememberNativeAdSlotManager(): NativeAdSlotManager {
    val context = LocalContext.current
    val manager = remember(context.applicationContext) {
        NativeAdSlotManager(context.applicationContext)
    }
    DisposableEffect(manager) {
        onDispose {
            manager.destroyAll()
        }
    }
    return manager
}

/**
 * Reusable, policy-compliant Google AdMob Native Ad component for PotrikaGhor.
 *
 * - Renders inside Google's official [NativeAdView] via [AndroidView] so the required
 *   AdChoices overlay and asset click tracking work properly.
 * - Clearly labels the card with a prominent "বিজ্ঞাপন • Ad" badge and distinct sponsored
 *   card styling so it never deceptively imitates a normal news article.
 * - Fails silently (renders nothing, no empty placeholder or error message) if an ad is
 *   still loading or fails to load.
 */
@Composable
fun PotrikaGhorNativeAd(
    slotKey: String,
    slotManager: NativeAdSlotManager,
    modifier: Modifier = Modifier
) {
    val slotStateFlow = remember(slotKey, slotManager) {
        slotManager.stateForSlot(slotKey)
    }
    val slotState by slotStateFlow.collectAsState()

    LaunchedEffect(slotKey, slotManager) {
        slotManager.requestSlotIfNeeded(slotKey)
    }

    val loadedAd = (slotState as? NativeAdSlotState.Loaded)?.nativeAd ?: return

    val colorScheme = MaterialTheme.colorScheme
    val cardContainerColor = colorScheme.surfaceVariant.copy(alpha = 0.55f)
    val borderColor = colorScheme.outlineVariant
    val primaryColor = colorScheme.primary.toArgb()
    val onPrimaryColor = colorScheme.onPrimary.toArgb()
    val primaryContainerColor = colorScheme.primaryContainer.toArgb()
    val onPrimaryContainerColor = colorScheme.onPrimaryContainer.toArgb()
    val textPrimaryColor = colorScheme.onSurface.toArgb()
    val textSecondaryColor = colorScheme.onSurfaceVariant.toArgb()
    val mediaBgColor = colorScheme.surface.toArgb()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 7.dp)
            .testTag("potrikaghor_native_ad_$slotKey")
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(
                containerColor = cardContainerColor
            ),
            border = BorderStroke(1.dp, borderColor)
        ) {
            AndroidView(
                modifier = Modifier.fillMaxWidth(),
                factory = { ctx ->
                    createNativeAdViewWithHolder(ctx)
                },
                update = { nativeAdView ->
                    val holder = nativeAdView.tag as? NativeAdViewHolder ?: return@AndroidView
                    bindNativeAdView(
                        nativeAdView = nativeAdView,
                        holder = holder,
                        nativeAd = loadedAd,
                        primaryColor = primaryColor,
                        onPrimaryColor = onPrimaryColor,
                        primaryContainerColor = primaryContainerColor,
                        onPrimaryContainerColor = onPrimaryContainerColor,
                        textPrimaryColor = textPrimaryColor,
                        textSecondaryColor = textSecondaryColor,
                        mediaBgColor = mediaBgColor
                    )
                }
            )
        }
    }
}

private class NativeAdViewHolder(
    val badgeView: TextView,
    val iconView: ImageView,
    val advertiserView: TextView,
    val headlineView: TextView,
    val bodyView: TextView,
    val mediaView: MediaView,
    val metaView: TextView,
    val ctaButton: Button
) {
    var boundAd: NativeAd? = null
}

private fun Context.dpToPx(dp: Float): Int {
    return TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        dp,
        resources.displayMetrics
    ).toInt()
}

private fun createNativeAdViewWithHolder(context: Context): NativeAdView {
    val nativeAdView = NativeAdView(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    val rootPaddingH = context.dpToPx(12f)
    val rootPaddingV = context.dpToPx(11f)

    val container = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        setPadding(rootPaddingH, rootPaddingV, rootPaddingH, rootPaddingV)
    }

    // 1. Top Row: "বিজ্ঞাপন • Ad" badge + Advertiser Icon + Advertiser Name
    // Right padding reserves space for Google's top-right AdChoices overlay icon
    val headerRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            rightMargin = context.dpToPx(28f)
        }
    }

    val badgeView = TextView(context).apply {
        text = "বিজ্ঞাপন • Ad"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(
            context.dpToPx(8f),
            context.dpToPx(2.5f),
            context.dpToPx(8f),
            context.dpToPx(2.5f)
        )
        includeFontPadding = false
    }
    headerRow.addView(badgeView)

    val iconSize = context.dpToPx(20f)
    val iconView = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
            leftMargin = context.dpToPx(8f)
        }
        visibility = View.GONE
    }
    headerRow.addView(iconView)

    val advertiserView = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
        typeface = Typeface.DEFAULT_BOLD
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        layoutParams = LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1f
        ).apply {
            leftMargin = context.dpToPx(6f)
        }
        visibility = View.GONE
    }
    headerRow.addView(advertiserView)

    container.addView(headerRow)

    // 2. Headline View
    val headlineView = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        setLineSpacing(context.dpToPx(2f).toFloat(), 1.05f)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = context.dpToPx(7f)
        }
    }
    container.addView(headlineView)

    // 3. Body View (optional)
    val bodyView = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        setLineSpacing(context.dpToPx(1.5f).toFloat(), 1.05f)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = context.dpToPx(4f)
        }
        visibility = View.GONE
    }
    container.addView(bodyView)

    // 4. MediaView (optional image/video asset)
    val mediaView = MediaView(context).apply {
        setImageScaleType(ImageView.ScaleType.CENTER_CROP)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            context.dpToPx(164f)
        ).apply {
            topMargin = context.dpToPx(8f)
        }
        clipToOutline = true
        visibility = View.GONE
    }
    container.addView(mediaView)

    // 5. Footer Row: Star rating / Store metadata on left, CTA button on right
    val footerRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = context.dpToPx(9f)
        }
    }

    val metaView = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        layoutParams = LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1f
        ).apply {
            rightMargin = context.dpToPx(8f)
        }
    }
    footerRow.addView(metaView)

    val ctaButton = Button(context).apply {
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        typeface = Typeface.DEFAULT_BOLD
        minHeight = context.dpToPx(36f)
        minimumHeight = context.dpToPx(36f)
        setPadding(
            context.dpToPx(14f),
            context.dpToPx(6f),
            context.dpToPx(14f),
            context.dpToPx(6f)
        )
        stateListAnimator = null
        elevation = 0f
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }
    footerRow.addView(ctaButton)

    container.addView(footerRow)
    nativeAdView.addView(container)

    // Register asset views with NativeAdView
    nativeAdView.headlineView = headlineView
    nativeAdView.bodyView = bodyView
    nativeAdView.iconView = iconView
    nativeAdView.advertiserView = advertiserView
    nativeAdView.mediaView = mediaView
    nativeAdView.callToActionView = ctaButton

    nativeAdView.tag = NativeAdViewHolder(
        badgeView = badgeView,
        iconView = iconView,
        advertiserView = advertiserView,
        headlineView = headlineView,
        bodyView = bodyView,
        mediaView = mediaView,
        metaView = metaView,
        ctaButton = ctaButton
    )

    return nativeAdView
}

private fun bindNativeAdView(
    nativeAdView: NativeAdView,
    holder: NativeAdViewHolder,
    nativeAd: NativeAd,
    primaryColor: Int,
    onPrimaryColor: Int,
    primaryContainerColor: Int,
    onPrimaryContainerColor: Int,
    textPrimaryColor: Int,
    textSecondaryColor: Int,
    mediaBgColor: Int
) {
    val context = nativeAdView.context

    // Style badge
    holder.badgeView.setTextColor(onPrimaryContainerColor)
    holder.badgeView.background = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = context.dpToPx(6f).toFloat()
        setColor(primaryContainerColor)
    }

    // Style text colors for current theme
    holder.advertiserView.setTextColor(textSecondaryColor)
    holder.headlineView.setTextColor(textPrimaryColor)
    holder.bodyView.setTextColor(textSecondaryColor)
    holder.metaView.setTextColor(textSecondaryColor)

    // Style MediaView background
    holder.mediaView.background = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = context.dpToPx(10f).toFloat()
        setColor(mediaBgColor)
    }

    // Style CTA button
    holder.ctaButton.setTextColor(onPrimaryColor)
    holder.ctaButton.background = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = context.dpToPx(9f).toFloat()
        setColor(primaryColor)
    }

    // Populate asset values
    holder.headlineView.text = nativeAd.headline ?: ""

    val bodyText = nativeAd.body
    if (!bodyText.isNullOrBlank()) {
        holder.bodyView.text = bodyText
        holder.bodyView.visibility = View.VISIBLE
    } else {
        holder.bodyView.text = ""
        holder.bodyView.visibility = View.GONE
    }

    val iconDrawable = nativeAd.icon?.drawable
    if (iconDrawable != null) {
        holder.iconView.setImageDrawable(iconDrawable)
        holder.iconView.visibility = View.VISIBLE
    } else {
        holder.iconView.setImageDrawable(null)
        holder.iconView.visibility = View.GONE
    }

    val advertiserText = nativeAd.advertiser
    if (!advertiserText.isNullOrBlank()) {
        holder.advertiserView.text = advertiserText
        holder.advertiserView.visibility = View.VISIBLE
    } else {
        holder.advertiserView.text = ""
        holder.advertiserView.visibility = View.GONE
    }

    val mediaContent = nativeAd.mediaContent
    if (mediaContent != null && (mediaContent.hasVideoContent() || mediaContent.mainImage != null || nativeAd.images.isNotEmpty())) {
        holder.mediaView.mediaContent = mediaContent
        holder.mediaView.visibility = View.VISIBLE
    } else {
        holder.mediaView.visibility = View.GONE
    }

    val metaParts = buildList {
        nativeAd.starRating?.takeIf { it > 0.0 }?.let { rating ->
            add("★ ${String.format(java.util.Locale.US, "%.1f", rating)}")
        }
        nativeAd.store?.takeIf { it.isNotBlank() }?.let { store ->
            add(store)
        }
        nativeAd.price?.takeIf { it.isNotBlank() }?.let { price ->
            add(price)
        }
    }
    holder.metaView.text = if (metaParts.isNotEmpty()) {
        metaParts.joinToString(" • ")
    } else {
        "স্পন্সরড কন্টেন্ট"
    }

    val ctaText = nativeAd.callToAction
    if (!ctaText.isNullOrBlank()) {
        holder.ctaButton.text = ctaText
        holder.ctaButton.visibility = View.VISIBLE
    } else {
        holder.ctaButton.text = ""
        holder.ctaButton.visibility = View.GONE
    }

    if (holder.boundAd !== nativeAd) {
        holder.boundAd = nativeAd
        nativeAdView.setNativeAd(nativeAd)
    }
}
