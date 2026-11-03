package com.potrikaghor.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

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
                primaryContainer = primaryAccent.copy(alpha = 0.22f),
                onPrimaryContainer = Color(0xFFFFEDE9),
                secondary = DeepTealLight,
                onSecondary = Color.White,
                secondaryContainer = DeepTeal.copy(alpha = 0.28f),
                onSecondaryContainer = Color(0xFFD9F4F6),
                tertiary = BrandCoral,
                background = AmoledBackground,
                onBackground = Color(0xFFF3F6F7),
                surface = AmoledSurface,
                onSurface = Color(0xFFF3F6F7),
                surfaceVariant = AmoledCard,
                onSurfaceVariant = Color(0xFFAEC0C4),
                outline = Color(0xFF344146),
                outlineVariant = AmoledBorder
            )
        } else {
            darkColorScheme(
                primary = primaryAccent,
                onPrimary = Color.White,
                primaryContainer = primaryAccent.copy(alpha = 0.24f),
                onPrimaryContainer = Color(0xFFFFF0ED),
                secondary = DeepTealLight,
                onSecondary = Color.White,
                secondaryContainer = DeepTeal.copy(alpha = 0.34f),
                onSecondaryContainer = Color(0xFFDDF5F7),
                tertiary = BrandCoral,
                background = DarkGlassBackground,
                onBackground = Color(0xFFEDF3F4),
                surface = DarkGlassSurface,
                onSurface = Color(0xFFEDF3F4),
                surfaceVariant = DarkGlassSurfaceVariant,
                onSurfaceVariant = Color(0xFFB2C4C8),
                outline = Color(0xFF3B5158),
                outlineVariant = DarkGlassBorder
            )
        }
    } else {
        if (themeStyle == "sepia") {
            lightColorScheme(
                primary = primaryAccent,
                onPrimary = Color.White,
                primaryContainer = Color(0xFFEADDC2),
                onPrimaryContainer = SepiaText,
                secondary = DeepTeal,
                onSecondary = Color.White,
                secondaryContainer = Color(0xFFDCE7E3),
                onSecondaryContainer = DeepTeal,
                tertiary = BrandCoral,
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
            // Premium Editorial Cream/White + Frosted Teal-Coral palette
            lightColorScheme(
                primary = primaryAccent,
                onPrimary = Color.White,
                primaryContainer = primaryAccent.copy(alpha = 0.13f),
                onPrimaryContainer = InkColor,
                secondary = DeepTeal,
                onSecondary = Color.White,
                secondaryContainer = DeepTeal.copy(alpha = 0.11f),
                onSecondaryContainer = DeepTeal,
                tertiary = BrandCoral,
                background = PaperColor,
                onBackground = InkColor,
                surface = Color(0xFFFFFDF9),
                onSurface = InkColor,
                surfaceVariant = Color(0xFFEBE7DD),
                onSurfaceVariant = Color(0xFF535D5F),
                outline = Color(0xFFB5BEB9),
                outlineVariant = Color(0xFFD8DDD8)
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

    MaterialTheme(
        colorScheme = colorScheme,
        typography = typography,
        content = content
    )
}
