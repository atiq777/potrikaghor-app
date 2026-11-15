package com.potrikaghor.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat

@Composable
fun PotrikaghorTheme(
    themeMode: String = "system",
    themeStyle: String = "normal",
    accentIndex: Int = 0,
    textScale: Float = 1.0f,
    content: @Composable () -> Unit
) {
    val isSystemDark = isSystemInDarkTheme()
    val isDark = when (themeMode) {
        "light" -> false
        "dark" -> true
        else -> isSystemDark
    }

    val primaryAccent = AccentColors.getOrElse(accentIndex) { AccentColors[0] }
    val darkPrimary = brightenAccentForDark(primaryAccent)
    val darkOnPrimary = if (darkPrimary.luminance() > 0.36f) Color(0xFF0B1415) else Color.White
    val lightAccentContainer = if (primaryAccent == BrandTeal) {
        Color(0xFFDCEBE9)
    } else {
        lerp(primaryAccent, Color.White, 0.84f)
    }

    val colorScheme: ColorScheme = if (isDark) {
        if (themeStyle == "amoled") {
            darkColorScheme(
                primary = darkPrimary,
                onPrimary = darkOnPrimary,
                primaryContainer = darkPrimary.copy(alpha = 0.2f),
                onPrimaryContainer = Color.White,
                secondary = darkPrimary,
                onSecondary = darkOnPrimary,
                secondaryContainer = darkPrimary.copy(alpha = 0.18f),
                onSecondaryContainer = Color(0xFFF2F6F5),
                tertiary = Color(0xFFFF7A66),
                onTertiary = Color(0xFF2A0A04),
                tertiaryContainer = Color(0xFF4A1A12),
                onTertiaryContainer = Color(0xFFFFD9D2),
                background = AmoledBackground,
                onBackground = Color(0xFFF2F6F5),
                surface = AmoledSurface,
                onSurface = Color(0xFFF2F6F5),
                surfaceVariant = AmoledCard,
                onSurfaceVariant = Color(0xFF9DB1AF),
                outline = AmoledBorder,
                outlineVariant = AmoledBorder
            )
        } else {
            darkColorScheme(
                primary = darkPrimary,
                onPrimary = darkOnPrimary,
                primaryContainer = darkPrimary.copy(alpha = 0.25f),
                onPrimaryContainer = Color.White,
                secondary = darkPrimary,
                onSecondary = darkOnPrimary,
                secondaryContainer = darkPrimary.copy(alpha = 0.18f),
                onSecondaryContainer = Color(0xFFF2F6F5),
                tertiary = Color(0xFFFF7A66),
                onTertiary = Color(0xFF2A0A04),
                tertiaryContainer = Color(0xFF4A1A12),
                onTertiaryContainer = Color(0xFFFFD9D2),
                background = Color(0xFF0B1415),
                onBackground = Color(0xFFF2F6F5),
                surface = Color(0xFF12201F),
                onSurface = Color(0xFFF2F6F5),
                surfaceVariant = Color(0xFF172A29),
                onSurfaceVariant = Color(0xFF9DB1AF),
                outline = Color(0xFF1F3332),
                outlineVariant = Color(0xFF1A2B2A)
            )
        }
    } else {
        if (themeStyle == "sepia") {
            lightColorScheme(
                primary = primaryAccent,
                onPrimary = Color.White,
                primaryContainer = Color(0xFFEADDC2),
                onPrimaryContainer = SepiaText,
                secondary = BrandGreen,
                onSecondary = Color.White,
                background = SepiaBackground,
                onBackground = SepiaText,
                surface = SepiaSurface,
                onSurface = SepiaText,
                surfaceVariant = Color(0xFFE8DAC0),
                onSurfaceVariant = SepiaTextSecondary,
                outline = Color(0xFFC2B18E),
                outlineVariant = Color(0xFFDDD0B3)
            )
        } else {
            // Warm Cream + Deep Teal + Coral Light Theme
            lightColorScheme(
                primary = primaryAccent,
                onPrimary = Color.White,
                primaryContainer = lightAccentContainer,
                onPrimaryContainer = primaryAccent,
                secondary = primaryAccent,
                onSecondary = Color.White,
                secondaryContainer = lightAccentContainer,
                onSecondaryContainer = primaryAccent,
                tertiary = BrandCoral,
                onTertiary = Color.White,
                tertiaryContainer = Color(0xFFFFE0DA),
                onTertiaryContainer = Color(0xFF7A2315),
                background = Color(0xFFFBF4E8),
                onBackground = Color(0xFF1B1F1E),
                surface = Color(0xFFFFFCF6),
                onSurface = Color(0xFF1B1F1E),
                surfaceVariant = Color(0xFFF0E6D3),
                onSurfaceVariant = Color(0xFF5F6B6A),
                outline = Color(0xFFEADFCB),
                outlineVariant = Color(0xFFEADFCB)
            )
        }
    }

    val typography = Typography(
        headlineLarge = TextStyle(
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Bold,
            fontSize = (28 * textScale).sp,
            lineHeight = (34 * textScale).sp
        ),
        headlineMedium = TextStyle(
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Bold,
            fontSize = (22 * textScale).sp,
            lineHeight = (28 * textScale).sp
        ),
        headlineSmall = TextStyle(
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.SemiBold,
            fontSize = (19 * textScale).sp,
            lineHeight = (25 * textScale).sp
        ),
        titleLarge = TextStyle(
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Bold,
            fontSize = (18 * textScale).sp,
            lineHeight = (24 * textScale).sp
        ),
        titleMedium = TextStyle(
            fontWeight = FontWeight.SemiBold,
            fontSize = (16 * textScale).sp,
            lineHeight = (22 * textScale).sp
        ),
        titleSmall = TextStyle(
            fontWeight = FontWeight.Medium,
            fontSize = (14 * textScale).sp,
            lineHeight = (19 * textScale).sp
        ),
        bodyLarge = TextStyle(
            fontWeight = FontWeight.Normal,
            fontSize = (16 * textScale).sp,
            lineHeight = (23 * textScale).sp
        ),
        bodyMedium = TextStyle(
            fontWeight = FontWeight.Normal,
            fontSize = (14 * textScale).sp,
            lineHeight = (20 * textScale).sp
        ),
        bodySmall = TextStyle(
            fontWeight = FontWeight.Normal,
            fontSize = (12 * textScale).sp,
            lineHeight = (16 * textScale).sp
        ),
        labelLarge = TextStyle(
            fontWeight = FontWeight.SemiBold,
            fontSize = (14 * textScale).sp,
            lineHeight = (19 * textScale).sp
        ),
        labelMedium = TextStyle(
            fontWeight = FontWeight.Medium,
            fontSize = (12 * textScale).sp,
            lineHeight = (16 * textScale).sp
        )
    )

    // Keep the status bar icons (clock, battery %, network) readable on top of the real
    // background: dark icons on light surfaces, light icons on dark surfaces.
    val view = LocalView.current
    if (!view.isInEditMode) {
        val useDarkStatusBarIcons = colorScheme.surface.luminance() > 0.5f
        SideEffect {
            val window = view.context.findHostActivity()?.window
            if (window != null) {
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = useDarkStatusBarIcons
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = typography,
        content = content
    )
}

private fun brightenAccentForDark(accent: Color): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(accent.toArgb(), hsl)
    if (hsl[2] < 0.54f) {
        hsl[2] = 0.54f
    }
    return Color(ColorUtils.HSLToColor(hsl))
}

private tailrec fun Context.findHostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findHostActivity()
    else -> null
}
