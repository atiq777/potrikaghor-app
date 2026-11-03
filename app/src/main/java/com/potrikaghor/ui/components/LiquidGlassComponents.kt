package com.potrikaghor.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.potrikaghor.ui.theme.BrandCoral
import com.potrikaghor.ui.theme.DeepTeal
import com.potrikaghor.ui.theme.DeepTealLight

/**
 * Defines visual depth tiers for Liquid Glass cards across PotrikaGhor.
 */
enum class GlassCardTier {
    /** Stronger visual depth, subtle ambient teal/coral tint, and crisp upper highlight for Lead Story */
    Hero,
    /** Compact frosted glass card for Morning Briefing horizontal carousel */
    Briefing,
    /** Lightweight frosted editorial card for high-density LazyColumn news feeds */
    Standard,
    /** Slightly elevated frosted surface for highlighted banners (e.g., Continue Reading) */
    Elevated
}

/**
 * Paints a lightweight, zero-blur editorial ambient backdrop (subtle Deep Teal & Coral radial glow
 * in upper corners over the theme background) using [drawWithCache] so scroll performance is unaffected.
 */
fun Modifier.editorialAmbientBackground(
    backgroundColor: Color,
    isDark: Boolean
): Modifier = this.drawWithCache {
    val tealGlow = if (isDark) {
        DeepTealLight.copy(alpha = 0.11f)
    } else {
        DeepTeal.copy(alpha = 0.055f)
    }
    val coralGlow = if (isDark) {
        BrandCoral.copy(alpha = 0.07f)
    } else {
        BrandCoral.copy(alpha = 0.04f)
    }
    val topRightBrush = Brush.radialGradient(
        colors = listOf(tealGlow, Color.Transparent),
        center = Offset(size.width * 0.92f, size.height * 0.06f),
        radius = size.minDimension * 0.72f
    )
    val topLeftBrush = Brush.radialGradient(
        colors = listOf(coralGlow, Color.Transparent),
        center = Offset(size.width * 0.08f, size.height * 0.14f),
        radius = size.minDimension * 0.62f
    )
    onDrawBehind {
        drawRect(color = backgroundColor)
        drawRect(brush = topRightBrush)
        drawRect(brush = topLeftBrush)
    }
}

/**
 * Draws a subtle upper-edge specular highlight line inside a glass surface using [drawWithCache].
 */
fun Modifier.glassTopSpecularHighlight(
    highlightColor: Color,
    horizontalInsetDp: Dp = 14.dp
): Modifier = this.drawWithCache {
    val insetPx = horizontalInsetDp.toPx()
    val strokePx = 1.dp.toPx()
    val lineBrush = Brush.horizontalGradient(
        colors = listOf(
            Color.Transparent,
            highlightColor,
            highlightColor,
            Color.Transparent
        ),
        startX = insetPx,
        endX = (size.width - insetPx).coerceAtLeast(insetPx + 1f)
    )
    onDrawWithContent {
        drawContent()
        if (size.width > insetPx * 2) {
            drawLine(
                brush = lineBrush,
                start = Offset(insetPx, strokePx * 0.75f),
                end = Offset(size.width - insetPx, strokePx * 0.75f),
                strokeWidth = strokePx
            )
        }
    }
}

/**
 * Reusable translucent Liquid Glass container surface.
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(16.dp),
    containerColor: Color? = null,
    borderBrush: Brush? = null,
    shadowElevation: Dp = 2.dp,
    showTopHighlight: Boolean = true,
    content: @Composable BoxScope.() -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val isDark = cs.background.luminance() < 0.25f

    val resolvedBg = containerColor ?: if (isDark) {
        cs.surface.copy(alpha = 0.88f)
    } else {
        cs.surface.copy(alpha = 0.92f)
    }

    val resolvedBorderBrush = borderBrush ?: remember(isDark, cs.outlineVariant, cs.primary) {
        if (isDark) {
            Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.16f),
                    cs.outlineVariant.copy(alpha = 0.55f),
                    cs.outlineVariant.copy(alpha = 0.32f)
                )
            )
        } else {
            Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.92f),
                    cs.outlineVariant.copy(alpha = 0.68f),
                    cs.outlineVariant.copy(alpha = 0.48f)
                )
            )
        }
    }

    val specularColor = if (isDark) {
        Color.White.copy(alpha = 0.14f)
    } else {
        Color.White.copy(alpha = 0.85f)
    }

    Box(
        modifier = modifier
            .then(
                if (shadowElevation > 0.dp) {
                    Modifier.shadow(
                        elevation = shadowElevation,
                        shape = shape,
                        ambientColor = if (isDark) Color.Black.copy(alpha = 0.55f) else DeepTeal.copy(alpha = 0.14f),
                        spotColor = if (isDark) Color.Black.copy(alpha = 0.65f) else DeepTeal.copy(alpha = 0.18f)
                    )
                } else {
                    Modifier
                }
            )
            .clip(shape)
            .background(color = resolvedBg, shape = shape)
            .border(width = 0.9.dp, brush = resolvedBorderBrush, shape = shape)
            .then(
                if (showTopHighlight) {
                    Modifier.glassTopSpecularHighlight(specularColor)
                } else {
                    Modifier
                }
            ),
        content = content
    )
}

/**
 * Reusable, scroll-performant Liquid Glass Card for PotrikaGhor news feeds and sections.
 * Differentiates [GlassCardTier.Hero], [GlassCardTier.Briefing], [GlassCardTier.Standard],
 * and [GlassCardTier.Elevated] without any expensive per-item RenderEffect blur.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    tier: GlassCardTier = GlassCardTier.Standard,
    shape: Shape = when (tier) {
        GlassCardTier.Hero -> RoundedCornerShape(18.dp)
        GlassCardTier.Briefing -> RoundedCornerShape(15.dp)
        GlassCardTier.Standard -> RoundedCornerShape(15.dp)
        GlassCardTier.Elevated -> RoundedCornerShape(15.dp)
    },
    pressedScale: Float = 0.984f,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val isDark = cs.background.luminance() < 0.25f

    val bgBrush = remember(tier, isDark, cs.surface, cs.surfaceVariant, cs.primary, cs.secondary) {
        when (tier) {
            GlassCardTier.Hero -> {
                if (isDark) {
                    Brush.verticalGradient(
                        colors = listOf(
                            cs.surfaceVariant.copy(alpha = 0.94f),
                            cs.surface.copy(alpha = 0.96f)
                        )
                    )
                } else {
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.97f),
                            cs.surface.copy(alpha = 0.94f)
                        )
                    )
                }
            }
            GlassCardTier.Briefing -> {
                if (isDark) {
                    Brush.verticalGradient(
                        colors = listOf(
                            cs.surface.copy(alpha = 0.92f),
                            cs.surfaceVariant.copy(alpha = 0.78f)
                        )
                    )
                } else {
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.94f),
                            cs.surface.copy(alpha = 0.89f)
                        )
                    )
                }
            }
            GlassCardTier.Elevated -> {
                if (isDark) {
                    Brush.horizontalGradient(
                        colors = listOf(
                            cs.secondaryContainer.copy(alpha = 0.52f),
                            cs.surfaceVariant.copy(alpha = 0.88f)
                        )
                    )
                } else {
                    Brush.horizontalGradient(
                        colors = listOf(
                            cs.secondaryContainer.copy(alpha = 0.65f),
                            Color.White.copy(alpha = 0.92f)
                        )
                    )
                }
            }
            GlassCardTier.Standard -> {
                if (isDark) {
                    Brush.verticalGradient(
                        colors = listOf(
                            cs.surface.copy(alpha = 0.90f),
                            cs.surface.copy(alpha = 0.84f)
                        )
                    )
                } else {
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.93f),
                            cs.surface.copy(alpha = 0.88f)
                        )
                    )
                }
            }
        }
    }

    val borderBrush = remember(tier, isDark, cs.outlineVariant, cs.primary, cs.secondary) {
        when (tier) {
            GlassCardTier.Hero -> {
                if (isDark) {
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.22f),
                            cs.primary.copy(alpha = 0.30f),
                            cs.outlineVariant.copy(alpha = 0.45f)
                        )
                    )
                } else {
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.White,
                            cs.secondary.copy(alpha = 0.24f),
                            cs.outlineVariant.copy(alpha = 0.60f)
                        )
                    )
                }
            }
            GlassCardTier.Elevated -> {
                if (isDark) {
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.20f),
                            cs.secondary.copy(alpha = 0.36f)
                        )
                    )
                } else {
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.White,
                            cs.secondary.copy(alpha = 0.26f)
                        )
                    )
                }
            }
            else -> {
                if (isDark) {
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.14f),
                            cs.outlineVariant.copy(alpha = 0.52f),
                            cs.outlineVariant.copy(alpha = 0.30f)
                        )
                    )
                } else {
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.95f),
                            cs.outlineVariant.copy(alpha = 0.62f),
                            cs.outlineVariant.copy(alpha = 0.42f)
                        )
                    )
                }
            }
        }
    }

    val elevation = when (tier) {
        GlassCardTier.Hero -> if (isDark) 5.dp else 4.dp
        GlassCardTier.Briefing -> if (isDark) 3.dp else 2.5.dp
        GlassCardTier.Elevated -> if (isDark) 3.dp else 2.5.dp
        GlassCardTier.Standard -> if (isDark) 2.dp else 1.5.dp
    }

    val specularColor = when (tier) {
        GlassCardTier.Hero -> if (isDark) Color.White.copy(alpha = 0.20f) else Color.White.copy(alpha = 0.95f)
        else -> if (isDark) Color.White.copy(alpha = 0.11f) else Color.White.copy(alpha = 0.80f)
    }

    Box(
        modifier = modifier
            .shadow(
                elevation = elevation,
                shape = shape,
                ambientColor = if (isDark) Color.Black.copy(alpha = 0.50f) else DeepTeal.copy(alpha = 0.14f),
                spotColor = if (isDark) Color.Black.copy(alpha = 0.62f) else DeepTeal.copy(alpha = 0.18f)
            )
            .clip(shape)
            .background(brush = bgBrush, shape = shape)
            .border(
                width = if (tier == GlassCardTier.Hero) 1.dp else 0.85.dp,
                brush = borderBrush,
                shape = shape
            )
            .glassTopSpecularHighlight(specularColor)
            .then(
                if (onClick != null) {
                    Modifier.subtleClickable(pressedScale = pressedScale, onClick = onClick)
                } else {
                    Modifier
                }
            ),
        content = content
    )
}

/**
 * Category / filter chip with a Liquid Glass treatment.
 * - Selected state: rich Coral / Primary surface, subtle upper highlight, clear contrast.
 * - Unselected state: subtle translucent surface, thin border.
 */
@Composable
fun GlassChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    val isDark = cs.background.luminance() < 0.25f
    val reducedMotion = rememberReducedMotion()
    val interactionSource = remember { MutableInteractionSource() }

    val chipScale by animateFloatAsState(
        targetValue = if (selected && !reducedMotion) 1.02f else 1f,
        animationSpec = tween(
            durationMillis = if (reducedMotion) 0 else MotionTokens.FAST_MS,
            easing = FastOutSlowInEasing
        ),
        label = "glassChipScale"
    )

    val containerColor by animateColorAsState(
        targetValue = if (selected) {
            cs.primary
        } else if (isDark) {
            cs.surface.copy(alpha = 0.82f)
        } else {
            Color.White.copy(alpha = 0.84f)
        },
        animationSpec = tween(
            durationMillis = if (reducedMotion) 0 else MotionTokens.FAST_MS,
            easing = FastOutSlowInEasing
        ),
        label = "glassChipBg"
    )

    val contentColor by animateColorAsState(
        targetValue = if (selected) {
            cs.onPrimary
        } else {
            cs.onSurfaceVariant
        },
        animationSpec = tween(
            durationMillis = if (reducedMotion) 0 else MotionTokens.FAST_MS,
            easing = FastOutSlowInEasing
        ),
        label = "glassChipText"
    )

    val borderColor by animateColorAsState(
        targetValue = if (selected) {
            Color.White.copy(alpha = if (isDark) 0.28f else 0.42f)
        } else if (isDark) {
            cs.outlineVariant.copy(alpha = 0.58f)
        } else {
            cs.outlineVariant.copy(alpha = 0.72f)
        },
        animationSpec = tween(
            durationMillis = if (reducedMotion) 0 else MotionTokens.FAST_MS,
            easing = FastOutSlowInEasing
        ),
        label = "glassChipBorder"
    )

    val shape = RoundedCornerShape(11.dp)

    Surface(
        modifier = modifier
            .height(31.dp)
            .pressScaleEffect(interactionSource, pressedScale = 0.95f)
            .graphicsLayer {
                scaleX = chipScale
                scaleY = chipScale
            }
            .then(
                if (selected) {
                    Modifier.shadow(
                        elevation = 3.dp,
                        shape = shape,
                        ambientColor = cs.primary.copy(alpha = 0.32f),
                        spotColor = cs.primary.copy(alpha = 0.38f)
                    )
                } else {
                    Modifier
                }
            )
            .clip(shape)
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick
            ),
        shape = shape,
        color = containerColor,
        border = BorderStroke(0.85.dp, borderColor)
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .glassTopSpecularHighlight(
                    highlightColor = if (selected) {
                        Color.White.copy(alpha = 0.28f)
                    } else if (isDark) {
                        Color.White.copy(alpha = 0.10f)
                    } else {
                        Color.White.copy(alpha = 0.85f)
                    },
                    horizontalInsetDp = 6.dp
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                fontSize = 12.5.sp,
                lineHeight = 16.sp,
                color = contentColor,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1
            )
        }
    }
}

/**
 * Premium Liquid Glass pill search bar with translucent surface, subtle border,
 * soft inner highlight, and smooth Coral/Deep Teal focus accent.
 */
@Composable
fun GlassSearchBar(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    onClear: (() -> Unit)? = null,
    onSearchAction: (() -> Unit)? = null
) {
    val cs = MaterialTheme.colorScheme
    val isDark = cs.background.luminance() < 0.25f
    val reducedMotion = rememberReducedMotion()
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isFocused && !reducedMotion) 1.006f else 1f,
        animationSpec = tween(
            durationMillis = if (reducedMotion) 0 else MotionTokens.FAST_MS,
            easing = FastOutSlowInEasing
        ),
        label = "glassSearchScale"
    )

    val containerColor by animateColorAsState(
        targetValue = when {
            isFocused && isDark -> cs.surfaceVariant.copy(alpha = 0.94f)
            isFocused && !isDark -> Color.White.copy(alpha = 0.98f)
            isDark -> cs.surface.copy(alpha = 0.86f)
            else -> Color.White.copy(alpha = 0.90f)
        },
        animationSpec = tween(
            durationMillis = if (reducedMotion) 0 else MotionTokens.FAST_MS,
            easing = FastOutSlowInEasing
        ),
        label = "glassSearchBg"
    )

    val borderColor by animateColorAsState(
        targetValue = when {
            isFocused -> cs.primary.copy(alpha = 0.72f)
            isDark -> cs.outlineVariant.copy(alpha = 0.62f)
            else -> cs.outlineVariant.copy(alpha = 0.75f)
        },
        animationSpec = tween(
            durationMillis = if (reducedMotion) 0 else MotionTokens.FAST_MS,
            easing = FastOutSlowInEasing
        ),
        label = "glassSearchBorder"
    )

    val iconTint by animateColorAsState(
        targetValue = if (isFocused) cs.primary else cs.onSurfaceVariant,
        animationSpec = tween(
            durationMillis = if (reducedMotion) 0 else MotionTokens.FAST_MS,
            easing = FastOutSlowInEasing
        ),
        label = "glassSearchIconTint"
    )

    val pillShape = RoundedCornerShape(24.dp)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .shadow(
                elevation = if (isFocused) 4.dp else 2.dp,
                shape = pillShape,
                ambientColor = if (isFocused) cs.primary.copy(alpha = 0.18f) else DeepTeal.copy(alpha = 0.12f),
                spotColor = if (isFocused) cs.primary.copy(alpha = 0.22f) else DeepTeal.copy(alpha = 0.16f)
            ),
        shape = pillShape,
        color = containerColor,
        border = BorderStroke(
            width = if (isFocused) 1.2.dp else 0.9.dp,
            color = borderColor
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .glassTopSpecularHighlight(
                    highlightColor = if (isDark) Color.White.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.90f),
                    horizontalInsetDp = 18.dp
                )
                .padding(horizontal = 15.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterStart
            ) {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 13.5.sp,
                            lineHeight = 18.sp
                        ),
                        color = cs.onSurfaceVariant.copy(alpha = 0.78f),
                        maxLines = 1
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    interactionSource = interactionSource,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 14.sp,
                        lineHeight = 19.sp,
                        color = cs.onSurface
                    ),
                    cursorBrush = SolidColor(cs.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = { onSearchAction?.invoke() }
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            AnimatedVisibility(
                visible = value.isNotEmpty(),
                enter = fadeIn(tween(MotionTokens.FAST_MS)) + scaleIn(tween(MotionTokens.FAST_MS), initialScale = 0.8f),
                exit = fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.8f)
            ) {
                IconButton(
                    onClick = {
                        if (onClear != null) {
                            onClear()
                        } else {
                            onValueChange("")
                        }
                    },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Clear,
                        contentDescription = "মুছুন",
                        tint = cs.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

/**
 * Refined translucent/frosted TopAppBar that visually separates itself from the content
 * while remaining lightweight and keeping icons clearly visible.
 */
@Composable
fun GlassTopAppBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    val cs = MaterialTheme.colorScheme
    val isDark = cs.background.luminance() < 0.25f

    val barBrush = remember(isDark, cs.surface, cs.background) {
        if (isDark) {
            Brush.verticalGradient(
                colors = listOf(
                    cs.surface.copy(alpha = 0.94f),
                    cs.background.copy(alpha = 0.88f)
                )
            )
        } else {
            Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.92f),
                    cs.background.copy(alpha = 0.88f)
                )
            )
        }
    }

    val bottomBorderColor = if (isDark) {
        cs.outlineVariant.copy(alpha = 0.45f)
    } else {
        cs.outlineVariant.copy(alpha = 0.65f)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(brush = barBrush)
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 58.dp)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (navigationIcon != null) {
                navigationIcon()
                Spacer(modifier = Modifier.width(6.dp))
            } else {
                Spacer(modifier = Modifier.width(2.dp))
            }

            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterStart
            ) {
                CompositionLocalProvider(LocalContentColor provides cs.onBackground) {
                    title()
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                CompositionLocalProvider(LocalContentColor provides cs.onBackground) {
                    actions()
                }
            }
        }

        // Subtle luminous hairline divider at the bottom of the top app bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.8.dp)
                .background(bottomBorderColor)
        )
    }
}

/**
 * Frosted circular/rounded icon button for top app bar and card actions.
 */
@Composable
fun GlassIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isActive: Boolean = false,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val isDark = cs.background.luminance() < 0.25f
    val shape = CircleShape

    val containerColor = when {
        isActive -> cs.primaryContainer
        isDark -> cs.surfaceVariant.copy(alpha = 0.72f)
        else -> Color.White.copy(alpha = 0.85f)
    }

    val borderColor = when {
        isActive -> cs.primary.copy(alpha = 0.45f)
        isDark -> Color.White.copy(alpha = 0.12f)
        else -> cs.outlineVariant.copy(alpha = 0.65f)
    }

    Surface(
        modifier = modifier
            .size(38.dp)
            .pressScaleEffect(interactionSource, pressedScale = 0.90f)
            .clip(shape)
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                enabled = enabled,
                onClick = onClick
            ),
        shape = shape,
        color = containerColor,
        border = BorderStroke(0.8.dp, borderColor)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(38.dp)
        ) {
            content()
        }
    }
}

/**
 * Compact frosted glass pill badge with Deep Teal or Coral accent styling.
 */
@Composable
fun GlassPillBadge(
    text: String,
    modifier: Modifier = Modifier,
    useCoralAccent: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailingContent: (@Composable RowScope.() -> Unit)? = null
) {
    val cs = MaterialTheme.colorScheme
    val isDark = cs.background.luminance() < 0.25f
    val shape = RoundedCornerShape(10.dp)

    val bgColor = if (useCoralAccent) {
        if (isDark) cs.primary.copy(alpha = 0.22f) else cs.primary.copy(alpha = 0.12f)
    } else {
        if (isDark) cs.secondaryContainer.copy(alpha = 0.62f) else cs.secondaryContainer.copy(alpha = 0.85f)
    }

    val textColor = if (useCoralAccent) {
        if (isDark) Color(0xFFFFB4A8) else cs.primary
    } else {
        cs.onSecondaryContainer
    }

    val borderColor = if (useCoralAccent) {
        cs.primary.copy(alpha = if (isDark) 0.36f else 0.24f)
    } else {
        cs.secondary.copy(alpha = if (isDark) 0.36f else 0.22f)
    }

    Surface(
        modifier = modifier
            .clip(shape)
            .then(
                if (onClick != null) {
                    Modifier.subtleClickable(pressedScale = 0.96f, onClick = onClick)
                } else {
                    Modifier
                }
            ),
        shape = shape,
        color = bgColor,
        border = BorderStroke(0.75.dp, borderColor)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.5.dp, vertical = 3.5.dp)
        ) {
            Text(
                text = text,
                fontSize = 10.5.sp,
                lineHeight = 14.sp,
                fontWeight = FontWeight.Bold,
                color = textColor,
                maxLines = 1
            )
            if (trailingContent != null) {
                trailingContent()
            }
        }
    }
}

/**
 * Primary button with Deep Teal / Coral branding, subtle upper highlight, and rounded shape.
 */
@Composable
fun GlassPrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    useDeepTeal: Boolean = false,
    content: @Composable RowScope.() -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(14.dp)
    val bgColor = if (useDeepTeal) cs.secondary else cs.primary
    val fgColor = if (useDeepTeal) cs.onSecondary else cs.onPrimary

    Button(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interactionSource,
        shape = shape,
        colors = ButtonDefaults.buttonColors(
            containerColor = bgColor,
            contentColor = fgColor
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        modifier = modifier
            .pressScaleEffect(interactionSource, pressedScale = 0.96f)
            .border(
                width = 0.85.dp,
                color = Color.White.copy(alpha = 0.25f),
                shape = shape
            ),
        content = content
    )
}

/**
 * Secondary button with translucent glass surface and thin luminous border.
 */
@Composable
fun GlassSecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val isDark = cs.background.luminance() < 0.25f
    val interactionSource = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(14.dp)

    Surface(
        modifier = modifier
            .pressScaleEffect(interactionSource, pressedScale = 0.96f)
            .clip(shape)
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                enabled = enabled,
                onClick = onClick
            ),
        shape = shape,
        color = if (isDark) cs.surfaceVariant.copy(alpha = 0.72f) else Color.White.copy(alpha = 0.86f),
        border = BorderStroke(
            width = 0.9.dp,
            color = if (isDark) Color.White.copy(alpha = 0.16f) else cs.outlineVariant.copy(alpha = 0.75f)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            content = content
        )
    }
}
