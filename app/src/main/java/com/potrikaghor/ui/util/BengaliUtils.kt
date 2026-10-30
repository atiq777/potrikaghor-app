package com.potrikaghor.ui.util

import androidx.compose.ui.graphics.Color
import java.util.Calendar

object BengaliUtils {
    private val digits = charArrayOf('০', '১', '২', '৩', '৪', '৫', '৬', '৭', '৮', '৯')
    private val weekdays = listOf(
        "রবিবার", "সোমবার", "মঙ্গলবার", "বুধবার", "বৃহস্পতিবার", "শুক্রবার", "শনিবার"
    )
    private val months = listOf(
        "জানুয়ারি", "ফেব্রুয়ারি", "মার্চ", "এপ্রিল", "মে", "জুন",
        "জুলাই", "আগস্ট", "সেপ্টেম্বর", "অক্টোবর", "নভেম্বর", "ডিসেম্বর"
    )

    fun bnDigits(v: Any): String {
        return v.toString().map { ch ->
            if (ch in '0'..'9') digits[ch - '0'] else ch
        }.joinToString("")
    }

    fun bnToday(): String {
        val cal = Calendar.getInstance()
        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK) - 1 // 0 for Sunday
        val day = cal.get(Calendar.DAY_OF_MONTH)
        val month = cal.get(Calendar.MONTH) // 0-based
        val year = cal.get(Calendar.YEAR)

        val wStr = weekdays.getOrElse(dayOfWeek) { "" }
        val mStr = months.getOrElse(month) { "" }
        return "$wStr, ${bnDigits(day)} $mStr ${bnDigits(year)}"
    }

    fun bnAgo(millis: Long): String {
        val diff = System.currentTimeMillis() - millis
        val minutes = diff / (1000 * 60)
        val hours = diff / (1000 * 60 * 60)
        val days = diff / (1000 * 60 * 60 * 24)

        return when {
            minutes < 1 -> "এইমাত্র"
            hours < 1 -> "${bnDigits(minutes)} মিনিট আগে"
            days < 1 -> "${bnDigits(hours)} ঘণ্টা আগে"
            days < 2 -> "গতকাল"
            days < 30 -> "${bnDigits(days)} দিন আগে"
            else -> "${bnDigits(days / 30)} মাস আগে"
        }
    }

    fun avatarColor(seed: String, isDark: Boolean): Color {
        var h = 0
        for (c in seed) {
            h = (h * 31 + c.code) % 360
        }
        val lightness = if (isDark) 0.35f else 0.40f
        return hslToColor(h.toFloat(), 0.50f, lightness)
    }

    private fun hslToColor(hue: Float, saturation: Float, lightness: Float): Color {
        val c = (1f - kotlin.math.abs(2f * lightness - 1f)) * saturation
        val x = c * (1f - kotlin.math.abs((hue / 60f) % 2f - 1f))
        val m = lightness - c / 2f
        var r = 0f
        var g = 0f
        var b = 0f
        when {
            hue < 60f -> { r = c; g = x; b = 0f }
            hue < 120f -> { r = x; g = c; b = 0f }
            hue < 180f -> { r = 0f; g = c; b = x }
            hue < 240f -> { r = 0f; g = x; b = c }
            hue < 300f -> { r = x; g = 0f; b = c }
            else -> { r = c; g = 0f; b = x }
        }
        return Color(r + m, g + m, b + m, 1f)
    }
}
