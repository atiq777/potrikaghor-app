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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
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

    val colorScheme: ColorScheme = if (isDark) {
        if (themeStyle == "amoled") {
            darkColorScheme(
                primary = primaryAccent,
                onPrimary = Color.White,
                primaryContainer = primaryAccent.copy(alpha = 0.2f),
                onPrimaryContainer = Color.White,
                secondary = BrandGreen,
                onSecondary = Color.White,
                background = AmoledBackground,
                onBackground = Color(0xFFF3F6F7),
                surface = AmoledSurface,
                onSurface = Color(0xFFF3F6F7),
                surfaceVariant = AmoledCard,
                onSurfaceVariant = Color(0xFFA0AEB1),
                outline = AmoledBorder,
                outlineVariant = AmoledBorder
            )
        } else {
            darkColorScheme(
                primary = primaryAccent,
                onPrimary = Color.White,
                primaryContainer = primaryAccent.copy(alpha = 0.25f),
                onPrimaryContainer = Color.White,
                secondary = BrandGreen,
                onSecondary = Color.White,
                background = Color(0xFF0E1112),
                onBackground = Color(0xFFEFF3F4),
                surface = Color(0xFF161B1D),
                onSurface = Color(0xFFEFF3F4),
                surfaceVariant = Color(0xFF1E2528),
                onSurfaceVariant = Color(0xFFA2AFB2),
                outline = Color(0xFF333E42),
                outlineVariant = Color(0xFF283135)
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
            // Warm Editorial Paper Light Theme
            lightColorScheme(
                primary = primaryAccent,
                onPrimary = Color.White,
                primaryContainer = primaryAccent.copy(alpha = 0.14f),
                onPrimaryContainer = InkColor,
                secondary = BrandGreen,
                onSecondary = Color.White,
                secondaryContainer = BrandGreen.copy(alpha = 0.14f),
                onSecondaryContainer = BrandGreen,
                background = PaperColor,
                onBackground = InkColor,
                surface = Color(0xFFFFFDF9),
                onSurface = InkColor,
                surfaceVariant = Color(0xFFEBE5D8),
                onSurfaceVariant = Color(0xFF565E60),
                outline = Color(0xFFB5ADA0),
                outlineVariant = Color(0xFFDAD2C5)
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

private tailrec fun Context.findHostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findHostActivity()
    else -> null
}
