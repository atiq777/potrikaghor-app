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
                primaryContainer = primaryAccent.copy(alpha = 0.25f),
                onPrimaryContainer = Color.White,
                background = AmoledBackground,
                onBackground = Color.White,
                surface = AmoledSurface,
                onSurface = Color.White,
                surfaceVariant = AmoledCard,
                onSurfaceVariant = Color(0xFFB0B0B0),
                outline = Color(0xFF333333),
                outlineVariant = Color(0xFF222222)
            )
        } else {
            darkColorScheme(
                primary = primaryAccent,
                onPrimary = Color.White,
                primaryContainer = primaryAccent.copy(alpha = 0.25f),
                onPrimaryContainer = Color.White,
                background = Color(0xFF121614),
                onBackground = Color(0xFFE8ECE9),
                surface = Color(0xFF181F1B),
                onSurface = Color(0xFFE8ECE9),
                surfaceVariant = Color(0xFF222A25),
                onSurfaceVariant = Color(0xFFB8C2BC),
                outline = Color(0xFF3F4A44),
                outlineVariant = Color(0xFF2E3832)
            )
        }
    } else {
        if (themeStyle == "sepia") {
            lightColorScheme(
                primary = primaryAccent,
                onPrimary = Color.White,
                primaryContainer = Color(0xFFE8DCC2),
                onPrimaryContainer = SepiaText,
                background = SepiaBackground,
                onBackground = SepiaText,
                surface = SepiaSurface,
                onSurface = SepiaText,
                surfaceVariant = Color(0xFFE5D7B7),
                onSurfaceVariant = SepiaTextSecondary,
                outline = Color(0xFFC4B390),
                outlineVariant = Color(0xFFDDD0B3)
            )
        } else {
            // Normal warm newsprint look
            lightColorScheme(
                primary = primaryAccent,
                onPrimary = Color.White,
                primaryContainer = Color(0xFFE8E2D4),
                onPrimaryContainer = InkColor,
                background = PaperColor,
                onBackground = InkColor,
                surface = Color(0xFFFAF6EE),
                onSurface = InkColor,
                surfaceVariant = Color(0xFFE9E2D4),
                onSurfaceVariant = Color(0xFF6B6358),
                outline = Color(0xFFB8AE9D),
                outlineVariant = Color(0xFFD6CEBF)
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
