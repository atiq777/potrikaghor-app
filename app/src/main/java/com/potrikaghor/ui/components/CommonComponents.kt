package com.potrikaghor.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.potrikaghor.data.ImagePipelineDiagnostic
import com.potrikaghor.data.NewsRepository
import com.potrikaghor.model.NewsItem
import com.potrikaghor.model.Paper
import com.potrikaghor.ui.theme.AmoledCard
import com.potrikaghor.ui.theme.FlagRed
import com.potrikaghor.ui.theme.InkColor
import com.potrikaghor.ui.util.BengaliUtils

private object SharedCoilLoaderHolder {
    @Volatile
    private var instance: ImageLoader? = null

    fun get(context: android.content.Context, newsRepo: NewsRepository): ImageLoader {
        return instance ?: synchronized(this) {
            instance ?: ImageLoader.Builder(context.applicationContext)
                .okHttpClient(newsRepo.imageHttpClient)
                .respectCacheHeaders(false)
                .crossfade(true)
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
                        .crossfade(true)
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
 * - Uses memory + disk caching via Coil and NewsRepository.
 * - Falls back cleanly to the newspaper's logo (or a neutral placeholder if unavailable) on missing/broken URLs.
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
                    if (isCoilLoading) {
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
                                .crossfade(true)
                                .build()
                        },
                        imageLoader = imageLoader,
                        contentDescription = item.title,
                        modifier = Modifier.fillMaxSize(),
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
                    if (isCoilLoading) {
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
                                .crossfade(true)
                                .build()
                        },
                        imageLoader = imageLoader,
                        contentDescription = item.title,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(cornerShape),
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

    val baseColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
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
    val cs = MaterialTheme.colorScheme
    val isDark = cs.background.red < 0.2f
    val barBg = if (isDark) AmoledCard else InkColor

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .shadow(16.dp, RoundedCornerShape(32.dp)),
        shape = RoundedCornerShape(32.dp),
        color = barBg,
        tonalElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEachIndexed { index, item ->
                val selected = index == selectedIndex
                val tint = if (selected) cs.onPrimary else Color(0xFFCFC6B4)

                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(22.dp))
                        .clickable { onSelectIndex(index) },
                    shape = RoundedCornerShape(22.dp),
                    color = if (selected) cs.primary else Color.Transparent
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = if (selected) 14.dp else 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        if (item.badge > 0) {
                            BadgedBox(
                                badge = {
                                    Badge(containerColor = FlagRed, contentColor = Color.White) {
                                        Text(BengaliUtils.bnDigits(item.badge), fontSize = 10.sp)
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = if (selected) item.selectedIcon else item.icon,
                                    contentDescription = item.label,
                                    tint = tint,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        } else {
                            Icon(
                                imageVector = if (selected) item.selectedIcon else item.icon,
                                contentDescription = item.label,
                                tint = tint,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        if (selected) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = item.label,
                                color = cs.onPrimary,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
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
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(64.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (actionLabel != null && onAction != null) {
            Spacer(modifier = Modifier.height(20.dp))
            Button(onClick = onAction) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
fun SkeletonCard(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.2f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "skeletonAlpha"
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.4f)
                    .height(14.dp)
                    .background(Color.Gray.copy(alpha = alpha), RoundedCornerShape(4.dp))
            )
            Spacer(modifier = Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .height(18.dp)
                    .background(Color.Gray.copy(alpha = alpha), RoundedCornerShape(4.dp))
            )
            Spacer(modifier = Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .height(18.dp)
                    .background(Color.Gray.copy(alpha = alpha), RoundedCornerShape(4.dp))
            )
        }
    }
}
