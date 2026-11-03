package com.potrikaghor.ui.components

import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.Indication
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.potrikaghor.R
import com.potrikaghor.data.ImagePipelineDiagnostic
import com.potrikaghor.data.NewsRepository
import com.potrikaghor.model.NewsItem
import com.potrikaghor.model.Paper
import com.potrikaghor.ui.theme.AmoledCard
import com.potrikaghor.ui.theme.FlagRed
import com.potrikaghor.ui.theme.InkColor
import com.potrikaghor.ui.util.BengaliUtils
import kotlinx.coroutines.delay

/**
 * Consistent, subtle motion tokens across PotrikaGhor.
 * Fast interaction: ~150–200ms
 * Normal transition: ~200–300ms
 * Content reveal: ~250–350ms
 * Branded breathing cycle: 1200ms (within 1000–1400ms target)
 */
object MotionTokens {
    const val FAST_MS = 160
    const val NORMAL_MS = 220
    const val REVEAL_MS = 280
    const val BREATHING_MS = 1200
}

/**
 * Detects whether the user has enabled Reduced Motion / disabled animator duration scale in system settings.
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        try {
            val scale = Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            )
            scale == 0f
        } catch (_: Exception) {
            false
        }
    }
}

/**
 * Applies a subtle, draw-phase press scale effect to any existing [InteractionSource]
 * without causing layout shifts or extra recompositions.
 */
fun Modifier.pressScaleEffect(
    interactionSource: InteractionSource,
    pressedScale: Float = 0.98f
): Modifier = composed {
    val reducedMotion = rememberReducedMotion()
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed && !reducedMotion) pressedScale else 1f,
        animationSpec = tween(
            durationMillis = MotionTokens.FAST_MS,
            easing = FastOutSlowInEasing
        ),
        label = "pressScale"
    )
    this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * Combines standard Material ripple clickable behavior with a subtle scale press feedback.
 */
fun Modifier.subtleClickable(
    enabled: Boolean = true,
    pressedScale: Float = 0.982f,
    indication: Indication? = null,
    onClick: () -> Unit
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val activeIndication = indication ?: LocalIndication.current
    this
        .pressScaleEffect(interactionSource = interactionSource, pressedScale = pressedScale)
        .clickable(
            interactionSource = interactionSource,
            indication = activeIndication,
            enabled = enabled,
            onClick = onClick
        )
}

/**
 * Performs a subtle, one-time fade + upward settle animation in the draw phase (`graphicsLayer`).
 * Does not cause layout jumping and respects Reduced Motion preferences.
 */
@Composable
fun SubtleContentReveal(
    visibleKey: Any? = Unit,
    delayMillis: Int = 0,
    durationMillis: Int = MotionTokens.REVEAL_MS,
    initialOffsetYDp: Dp = 7.dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val reducedMotion = rememberReducedMotion()
    val offsetPx = with(LocalDensity.current) { initialOffsetYDp.toPx() }
    var appeared by remember(visibleKey) { mutableStateOf(reducedMotion) }

    LaunchedEffect(visibleKey, reducedMotion) {
        if (reducedMotion) {
            appeared = true
        } else if (!appeared) {
            if (delayMillis > 0) {
                delay(delayMillis.toLong())
            }
            appeared = true
        }
    }

    val alpha by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (reducedMotion) 0 else durationMillis,
            easing = LinearOutSlowInEasing
        ),
        label = "revealAlpha"
    )
    val translationY by animateFloatAsState(
        targetValue = if (appeared || reducedMotion) 0f else offsetPx,
        animationSpec = tween(
            durationMillis = if (reducedMotion) 0 else durationMillis,
            easing = FastOutSlowInEasing
        ),
        label = "revealOffsetY"
    )

    Box(
        modifier = modifier.graphicsLayer {
            this.alpha = alpha
            this.translationY = translationY
        }
    ) {
        content()
    }
}

private object SharedCoilLoaderHolder {
    @Volatile
    private var instance: ImageLoader? = null

    fun get(context: android.content.Context, newsRepo: NewsRepository): ImageLoader {
        return instance ?: synchronized(this) {
            instance ?: ImageLoader.Builder(context.applicationContext)
                .okHttpClient(newsRepo.imageHttpClient)
                .respectCacheHeaders(false)
                .crossfade(MotionTokens.NORMAL_MS)
                .build()
                .also { instance = it }
        }
    }
}

@Composable
fun PaperAvatar(
    paper: Paper,
    size: Dp = 44.dp,
    modifier: Modifier = Modifier
) {
    val seed = if (paper.en.isNotEmpty()) paper.en else paper.bn
    val isDark = MaterialTheme.colorScheme.background.red < 0.2f
    val fallbackColor = BengaliUtils.avatarColor(seed, isDark)
    val firstChar = paper.bn.firstOrNull()?.toString() ?: "প"

    Surface(
        modifier = modifier
            .size(size)
            .shadow(4.dp, RoundedCornerShape(size * 0.28f)),
        shape = RoundedCornerShape(size * 0.28f),
        color = Color.White,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(fallbackColor),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = firstChar,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Serif,
                    fontSize = (size.value * 0.46f).sp
                )
            }

            if (paper.logoUrl.isNotEmpty()) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(paper.logoUrl)
                        .memoryCacheKey(paper.logoUrl)
                        .diskCacheKey(paper.logoUrl)
                        .memoryCachePolicy(CachePolicy.ENABLED)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .crossfade(MotionTokens.FAST_MS)
                        .build(),
                    contentDescription = paper.bn,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(4.dp),
                    contentScale = ContentScale.Fit
                )
            }
        }
    }
}

/**
 * Displays a rectangular article cover/thumbnail image for visual news cards (e.g. 16:9 top cards
 * or 4:3 horizontal feed thumbnails).
 * - Maintains consistent dimensions across loading, success, and fallback states to prevent layout jumping.
 * - Shows a subtle shimmer while resolving or loading.
 * - Smoothly crossfades the loaded image over the skeleton with zero layout shift.
 */
@Composable
fun ArticleCoverImage(
    item: NewsItem,
    paper: Paper,
    newsRepo: NewsRepository,
    modifier: Modifier = Modifier,
    fallbackItems: List<NewsItem> = emptyList(),
    shape: RoundedCornerShape = RoundedCornerShape(12.dp),
    fallbackAvatarSize: Dp = 36.dp
) {
    val context = LocalContext.current
    val reducedMotion = rememberReducedMotion()
    val imageLoader = remember(context, newsRepo) { SharedCoilLoaderHolder.get(context, newsRepo) }
    val itemKey = remember(item.link, item.title) {
        item.link.ifBlank { "${item.source}:${item.title}" }
    }

    var resolvedImageUrl by remember(itemKey) {
        mutableStateOf(
            newsRepo.getCachedArticleImage(item, fallbackItems)?.takeIf {
                newsRepo.isValidArticleImageUrl(it, item.link)
            }
        )
    }
    var isResolving by remember(itemKey) {
        mutableStateOf(resolvedImageUrl == null && !newsRepo.isLookupFailed(item, fallbackItems))
    }
    var imageLoadFailed by remember(itemKey, resolvedImageUrl) {
        mutableStateOf(false)
    }
    var isCoilLoading by remember(itemKey, resolvedImageUrl) {
        mutableStateOf(true)
    }
    var triedHttpsUpgrade by remember(itemKey) {
        mutableStateOf(false)
    }

    LaunchedEffect(itemKey) {
        if (resolvedImageUrl == null && !newsRepo.isLookupFailed(item, fallbackItems)) {
            isResolving = true
            val fetched = newsRepo.resolveArticleImage(item, fallbackItems)
            resolvedImageUrl = fetched?.takeIf { newsRepo.isValidArticleImageUrl(it, item.link) }
            isResolving = false
        }
    }

    val imageAlpha by animateFloatAsState(
        targetValue = if (!isCoilLoading && !imageLoadFailed && !resolvedImageUrl.isNullOrBlank()) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (reducedMotion) 0 else MotionTokens.NORMAL_MS,
            easing = FastOutSlowInEasing
        ),
        label = "coverImageAlpha"
    )

    Surface(
        modifier = modifier.clip(shape),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        border = BorderStroke(
            width = 0.8.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)
        )
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            when {
                isResolving -> {
                    ThumbnailShimmerBox(modifier = Modifier.fillMaxSize())
                }

                !resolvedImageUrl.isNullOrBlank() && !imageLoadFailed -> {
                    val imageUrl = resolvedImageUrl!!
                    if (isCoilLoading || imageAlpha < 0.99f) {
                        ThumbnailShimmerBox(modifier = Modifier.fillMaxSize())
                    }

                    AsyncImage(
                        model = remember(imageUrl) {
                            ImageRequest.Builder(context)
                                .data(imageUrl)
                                .addHeader("User-Agent", NewsRepository.BROWSER_USER_AGENT)
                                .addHeader("Accept", NewsRepository.IMAGE_ACCEPT_HEADER)
                                .memoryCacheKey(imageUrl)
                                .diskCacheKey(imageUrl)
                                .memoryCachePolicy(CachePolicy.ENABLED)
                                .diskCachePolicy(CachePolicy.ENABLED)
                                .crossfade(MotionTokens.NORMAL_MS)
                                .build()
                        },
                        imageLoader = imageLoader,
                        contentDescription = item.title,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = imageAlpha },
                        contentScale = ContentScale.Crop,
                        onState = { state ->
                            when (state) {
                                is AsyncImagePainter.State.Loading -> {
                                    isCoilLoading = true
                                }
                                is AsyncImagePainter.State.Success -> {
                                    isCoilLoading = false
                                    NewsRepository.logArticleImagePipeline(
                                        ImagePipelineDiagnostic(
                                            title = item.title,
                                            source = item.source,
                                            originalArticleUrl = item.link,
                                            rssImageUrl = item.imageUrl,
                                            extractedImageUrl = imageUrl,
                                            resolvedPublisherUrl = item.link.takeIf { !it.contains("news.google.com") },
                                            isHttp200Valid = true,
                                            httpStatusDetail = "HTTP 200 (source=${state.result.dataSource})",
                                            loadStatus = "COIL_LOAD_SUCCESS"
                                        )
                                    )
                                }
                                is AsyncImagePainter.State.Error -> {
                                    val err = state.result.throwable
                                    if (imageUrl.startsWith("http://") && !triedHttpsUpgrade) {
                                        triedHttpsUpgrade = true
                                        resolvedImageUrl = "https://" + imageUrl.removePrefix("http://")
                                    } else {
                                        isCoilLoading = false
                                        imageLoadFailed = true
                                        NewsRepository.logArticleImagePipeline(
                                            ImagePipelineDiagnostic(
                                                title = item.title,
                                                source = item.source,
                                                originalArticleUrl = item.link,
                                                rssImageUrl = item.imageUrl,
                                                extractedImageUrl = imageUrl,
                                                resolvedPublisherUrl = item.link.takeIf { !it.contains("news.google.com") },
                                                isHttp200Valid = false,
                                                httpStatusDetail = "COIL_ERROR: ${err.javaClass.simpleName}: ${err.message}",
                                                loadStatus = "COIL_LOAD_FAILURE_FALLBACK_TO_LOGO"
                                            )
                                        )
                                    }
                                }
                                else -> {}
                            }
                        }
                    )
                }

                else -> {
                    val hasSourceIdentity = paper.logoUrl.isNotEmpty() ||
                        (paper.bn.isNotBlank() && paper.bn != "অজানা উৎস") ||
                        paper.en.isNotBlank()

                    if (hasSourceIdentity) {
                        PaperAvatar(
                            paper = paper,
                            size = fallbackAvatarSize
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Outlined.Article,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                            modifier = Modifier.size(fallbackAvatarSize * 0.8f)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Displays a compact square thumbnail for secondary rows, falling back to [PaperAvatar].
 */
@Composable
fun ArticleThumbnail(
    item: NewsItem,
    paper: Paper,
    newsRepo: NewsRepository,
    size: Dp = 32.dp,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val reducedMotion = rememberReducedMotion()
    val imageLoader = remember(context, newsRepo) { SharedCoilLoaderHolder.get(context, newsRepo) }
    val itemKey = remember(item.link, item.title) {
        item.link.ifBlank { "${item.source}:${item.title}" }
    }

    var resolvedImageUrl by remember(itemKey) {
        mutableStateOf(
            newsRepo.getCachedArticleImage(item)?.takeIf {
                newsRepo.isValidArticleImageUrl(it, item.link)
            }
        )
    }
    var isResolving by remember(itemKey) {
        mutableStateOf(resolvedImageUrl == null && !newsRepo.isLookupFailed(item))
    }
    var imageLoadFailed by remember(itemKey, resolvedImageUrl) {
        mutableStateOf(false)
    }
    var isCoilLoading by remember(itemKey, resolvedImageUrl) {
        mutableStateOf(true)
    }
    var triedHttpsUpgrade by remember(itemKey) {
        mutableStateOf(false)
    }

    LaunchedEffect(itemKey) {
        if (resolvedImageUrl == null && !newsRepo.isLookupFailed(item)) {
            isResolving = true
            val fetched = newsRepo.resolveArticleImage(item)
            resolvedImageUrl = fetched?.takeIf { newsRepo.isValidArticleImageUrl(it, item.link) }
            isResolving = false
        }
    }

    val imageAlpha by animateFloatAsState(
        targetValue = if (!isCoilLoading && !imageLoadFailed && !resolvedImageUrl.isNullOrBlank()) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (reducedMotion) 0 else MotionTokens.NORMAL_MS,
            easing = FastOutSlowInEasing
        ),
        label = "thumbImageAlpha"
    )

    val cornerShape = RoundedCornerShape(size * 0.28f)

    when {
        isResolving -> {
            ThumbnailShimmerPlaceholder(
                size = size,
                shape = cornerShape,
                modifier = modifier
            )
        }

        !resolvedImageUrl.isNullOrBlank() && !imageLoadFailed -> {
            val imageUrl = resolvedImageUrl!!
            Surface(
                modifier = modifier
                    .size(size)
                    .aspectRatio(1f)
                    .shadow(4.dp, cornerShape),
                shape = cornerShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (isCoilLoading || imageAlpha < 0.99f) {
                        ThumbnailShimmerBox(modifier = Modifier.fillMaxSize())
                    }

                    AsyncImage(
                        model = remember(imageUrl) {
                            ImageRequest.Builder(context)
                                .data(imageUrl)
                                .addHeader("User-Agent", NewsRepository.BROWSER_USER_AGENT)
                                .addHeader("Accept", NewsRepository.IMAGE_ACCEPT_HEADER)
                                .memoryCacheKey(imageUrl)
                                .diskCacheKey(imageUrl)
                                .memoryCachePolicy(CachePolicy.ENABLED)
                                .diskCachePolicy(CachePolicy.ENABLED)
                                .crossfade(MotionTokens.NORMAL_MS)
                                .build()
                        },
                        imageLoader = imageLoader,
                        contentDescription = item.title,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(cornerShape)
                            .graphicsLayer { alpha = imageAlpha },
                        contentScale = ContentScale.Crop,
                        onState = { state ->
                            when (state) {
                                is AsyncImagePainter.State.Loading -> {
                                    isCoilLoading = true
                                }
                                is AsyncImagePainter.State.Success -> {
                                    isCoilLoading = false
                                }
                                is AsyncImagePainter.State.Error -> {
                                    val err = state.result.throwable
                                    if (imageUrl.startsWith("http://") && !triedHttpsUpgrade) {
                                        triedHttpsUpgrade = true
                                        resolvedImageUrl = "https://" + imageUrl.removePrefix("http://")
                                    } else {
                                        isCoilLoading = false
                                        imageLoadFailed = true
                                        NewsRepository.logDiag(
                                            "THUMBNAIL_COIL_ERROR | title=\"${item.title.take(40)}\" | url=\"$imageUrl\" | error=\"${err.message}\""
                                        )
                                    }
                                }
                                else -> {}
                            }
                        }
                    )
                }
            }
        }

        else -> {
            PaperAvatar(
                paper = paper,
                size = size,
                modifier = modifier
            )
        }
    }
}

@Composable
private fun ThumbnailShimmerPlaceholder(
    size: Dp,
    shape: RoundedCornerShape,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .size(size)
            .aspectRatio(1f)
            .shadow(4.dp, shape),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        ThumbnailShimmerBox(modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun ThumbnailShimmerBox(modifier: Modifier = Modifier) {
    val reducedMotion = rememberReducedMotion()
    val baseColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
    if (reducedMotion) {
        Box(modifier = modifier.background(baseColor))
        return
    }

    val transition = rememberInfiniteTransition(label = "thumbShimmer")
    val translateAnim by transition.animateFloat(
        initialValue = 0f,
        targetValue = 320f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "thumbShimmerOffset"
    )

    val highlightColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)

    val brush = Brush.linearGradient(
        colors = listOf(baseColor, highlightColor, baseColor),
        start = Offset(translateAnim - 160f, translateAnim - 160f),
        end = Offset(translateAnim, translateAnim)
    )

    Box(modifier = modifier.background(brush))
}

data class NavItemSpec(
    val icon: ImageVector,
    val selectedIcon: ImageVector,
    val label: String,
    val badge: Int = 0
)

@Composable
fun NavCapsule(
    selectedIndex: Int,
    onSelectIndex: (Int) -> Unit,
    items: List<NavItemSpec>,
    modifier: Modifier = Modifier
) {
    val isDark = MaterialTheme.colorScheme.background.red < 0.2f
    val reducedMotion = rememberReducedMotion()
    val containerColor = if (isDark) {
        AmoledCard.copy(alpha = 0.96f)
    } else {
        InkColor.copy(alpha = 0.94f)
    }
    val borderColor = if (isDark) {
        Color.White.copy(alpha = 0.14f)
    } else {
        Color.White.copy(alpha = 0.18f)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(10.dp, RoundedCornerShape(24.dp)),
            shape = RoundedCornerShape(24.dp),
            color = containerColor,
            border = BorderStroke(0.8.dp, borderColor)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 5.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                items.forEachIndexed { index, item ->
                    val selected = index == selectedIndex
                    val interactionSource = remember { MutableInteractionSource() }
                    val bgColor by animateColorAsState(
                        targetValue = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                        animationSpec = tween(
                            durationMillis = if (reducedMotion) 0 else MotionTokens.NORMAL_MS,
                            easing = FastOutSlowInEasing
                        ),
                        label = "navBg"
                    )
                    val tint by animateColorAsState(
                        targetValue = if (selected) Color.White else Color.White.copy(alpha = 0.72f),
                        animationSpec = tween(
                            durationMillis = if (reducedMotion) 0 else MotionTokens.FAST_MS,
                            easing = FastOutSlowInEasing
                        ),
                        label = "navTint"
                    )
                    val iconScale by animateFloatAsState(
                        targetValue = if (selected && !reducedMotion) 1.05f else 1f,
                        animationSpec = tween(
                            durationMillis = if (reducedMotion) 0 else MotionTokens.FAST_MS,
                            easing = FastOutSlowInEasing
                        ),
                        label = "navIconScale"
                    )

                    Surface(
                        modifier = Modifier
                            .pressScaleEffect(interactionSource = interactionSource, pressedScale = 0.95f)
                            .clip(RoundedCornerShape(18.dp))
                            .clickable(
                                interactionSource = interactionSource,
                                indication = LocalIndication.current
                            ) { onSelectIndex(index) },
                        shape = RoundedCornerShape(18.dp),
                        color = bgColor
                    ) {
                        Row(
                            modifier = Modifier
                                .animateContentSize(
                                    animationSpec = tween(
                                        durationMillis = if (reducedMotion) 0 else MotionTokens.NORMAL_MS,
                                        easing = FastOutSlowInEasing
                                    )
                                )
                                .padding(horizontal = if (selected) 11.dp else 9.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            if (item.badge > 0) {
                                BadgedBox(
                                    badge = {
                                        Badge(containerColor = FlagRed, contentColor = Color.White) {
                                            Text(BengaliUtils.bnDigits(item.badge), fontSize = 9.5.sp)
                                        }
                                    }
                                ) {
                                    Icon(
                                        imageVector = if (selected) item.selectedIcon else item.icon,
                                        contentDescription = item.label,
                                        tint = tint,
                                        modifier = Modifier
                                            .size(19.dp)
                                            .graphicsLayer {
                                                scaleX = iconScale
                                                scaleY = iconScale
                                            }
                                    )
                                }
                            } else {
                                Icon(
                                    imageVector = if (selected) item.selectedIcon else item.icon,
                                    contentDescription = item.label,
                                    tint = tint,
                                    modifier = Modifier
                                        .size(19.dp)
                                        .graphicsLayer {
                                            scaleX = iconScale
                                            scaleY = iconScale
                                        }
                                )
                            }

                            AnimatedVisibility(
                                visible = selected,
                                enter = expandHorizontally(
                                    animationSpec = tween(
                                        durationMillis = if (reducedMotion) 0 else MotionTokens.NORMAL_MS,
                                        easing = FastOutSlowInEasing
                                    )
                                ) + fadeIn(
                                    animationSpec = tween(
                                        durationMillis = if (reducedMotion) 0 else MotionTokens.FAST_MS,
                                        easing = FastOutSlowInEasing
                                    )
                                ),
                                exit = shrinkHorizontally(
                                    animationSpec = tween(
                                        durationMillis = if (reducedMotion) 0 else MotionTokens.FAST_MS,
                                        easing = FastOutSlowInEasing
                                    )
                                ) + fadeOut(
                                    animationSpec = tween(
                                        durationMillis = if (reducedMotion) 0 else 120,
                                        easing = FastOutSlowInEasing
                                    )
                                )
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Text(
                                        text = item.label,
                                        color = Color.White,
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontSize = 11.5.sp,
                                            lineHeight = 15.sp
                                        ),
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1
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

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    SubtleContentReveal(
        visibleKey = title,
        durationMillis = MotionTokens.NORMAL_MS,
        initialOffsetYDp = 6.dp,
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(14.dp)
                        .size(34.dp)
                )
            }
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Serif,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (actionLabel != null && onAction != null) {
                val btnInteraction = remember { MutableInteractionSource() }
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = onAction,
                    interactionSource = btnInteraction,
                    modifier = Modifier.pressScaleEffect(btnInteraction, pressedScale = 0.96f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(actionLabel)
                }
            }
        }
    }
}

@Composable
fun SkeletonCard(modifier: Modifier = Modifier) {
    val reducedMotion = rememberReducedMotion()
    val alpha = if (reducedMotion) {
        0.38f
    } else {
        val transition = rememberInfiniteTransition(label = "skeleton")
        val animatedAlpha by transition.animateFloat(
            initialValue = 0.25f,
            targetValue = 0.6f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 800, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "skeletonAlpha"
        )
        animatedAlpha
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.4f)
                    .height(12.dp)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
            )
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .height(16.dp)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
            )
            Spacer(modifier = Modifier.height(5.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .height(16.dp)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
            )
        }
    }
}

/**
 * Reusable PotrikaGhor Branded Breathing Loading Indicator.
 *
 * - Uses the official PotrikaGhor logo ([R.drawable.potrikaghor_logo]).
 * - Breathing animation:
 *   - Scale: 0.94f -> 1.06f -> 0.94f
 *   - Alpha: 0.65f -> 1.0f -> 0.65f
 *   - Cycle: 1200ms ([MotionTokens.BREATHING_MS]) with [FastOutSlowInEasing]
 *   - Subtle coral halo glow around the logo without altering logo colors
 * - Respects [rememberReducedMotion]: when Reduced Motion is active, infinite animations
 *   are disabled and the logo & loading label remain calm and static.
 */
@Composable
fun BrandedLoadingIndicator(
    message: String = "লোড হচ্ছে...",
    subtitle: String? = null,
    logoSize: Dp = 74.dp,
    modifier: Modifier = Modifier
) {
    val reducedMotion = rememberReducedMotion()
    val brandCoral = Color(0xFFF04E37)

    val logoScale: Float
    val logoAlpha: Float
    val glowAlpha: Float
    val textAlpha: Float

    if (reducedMotion) {
        logoScale = 1.0f
        logoAlpha = 1.0f
        glowAlpha = 0.08f
        textAlpha = 1.0f
    } else {
        val infiniteTransition = rememberInfiniteTransition(label = "potrikaghorBreathingLoader")
        val animatedScale by infiniteTransition.animateFloat(
            initialValue = 0.94f,
            targetValue = 1.06f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = MotionTokens.BREATHING_MS,
                    easing = FastOutSlowInEasing
                ),
                repeatMode = RepeatMode.Reverse
            ),
            label = "logoBreathingScale"
        )
        val animatedAlpha by infiniteTransition.animateFloat(
            initialValue = 0.65f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = MotionTokens.BREATHING_MS,
                    easing = FastOutSlowInEasing
                ),
                repeatMode = RepeatMode.Reverse
            ),
            label = "logoBreathingAlpha"
        )
        val animatedGlow by infiniteTransition.animateFloat(
            initialValue = 0.05f,
            targetValue = 0.14f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = MotionTokens.BREATHING_MS,
                    easing = FastOutSlowInEasing
                ),
                repeatMode = RepeatMode.Reverse
            ),
            label = "logoCoralGlowAlpha"
        )
        val animatedTextAlpha by infiniteTransition.animateFloat(
            initialValue = 0.72f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = MotionTokens.BREATHING_MS,
                    easing = FastOutSlowInEasing
                ),
                repeatMode = RepeatMode.Reverse
            ),
            label = "loadingTextAlpha"
        )
        logoScale = animatedScale
        logoAlpha = animatedAlpha
        glowAlpha = animatedGlow
        textAlpha = animatedTextAlpha
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(logoSize + 28.dp)
                .drawBehind {
                    val radius = size.minDimension * 0.52f
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                brandCoral.copy(alpha = glowAlpha),
                                brandCoral.copy(alpha = glowAlpha * 0.35f),
                                Color.Transparent
                            ),
                            center = center,
                            radius = radius
                        ),
                        radius = radius,
                        center = center
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.potrikaghor_logo),
                contentDescription = "পত্রিকাঘর লোড হচ্ছে",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(logoSize)
                    .graphicsLayer {
                        scaleX = logoScale
                        scaleY = logoScale
                        alpha = logoAlpha
                    }
            )
        }

        if (message.isNotBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontSize = 13.5.sp,
                    lineHeight = 18.sp,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold
                ),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.graphicsLayer { alpha = textAlpha }
            )
        }

        if (!subtitle.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.graphicsLayer { alpha = textAlpha * 0.9f }
            )
        }
    }
}


