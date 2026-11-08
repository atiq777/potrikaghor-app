package com.potrikaghor.ui.components

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import com.potrikaghor.model.Paper
import com.potrikaghor.ui.theme.FlagRed
import com.potrikaghor.ui.theme.InkColor
import com.potrikaghor.ui.theme.PaperColor
import com.potrikaghor.ui.util.BengaliUtils
import java.io.File
import java.io.FileOutputStream

import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

enum class ShareCardTheme(val label: String) {
    CLASSIC_PAPER("ক্লাসিক পেপার"),
    DARK_EDITORIAL("ডার্ক এডিটোরিয়াল"),
    RED_BREAKING("রেড ব্রেকিং")
}

@Composable
fun ShareCardDialog(
    paper: Paper,
    title: String,
    url: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var selectedTheme by remember { mutableStateOf(ShareCardTheme.CLASSIC_PAPER) }

    val cardBg = when (selectedTheme) {
        ShareCardTheme.CLASSIC_PAPER -> PaperColor
        ShareCardTheme.DARK_EDITORIAL -> Color(0xFF141416)
        ShareCardTheme.RED_BREAKING -> Color(0xFFFFF8F6)
    }
    val headlineColor = when (selectedTheme) {
        ShareCardTheme.CLASSIC_PAPER -> InkColor
        ShareCardTheme.DARK_EDITORIAL -> Color(0xFFF5F2EB)
        ShareCardTheme.RED_BREAKING -> Color(0xFF1C1412)
    }
    val subColor = when (selectedTheme) {
        ShareCardTheme.CLASSIC_PAPER -> Color(0xFF6B6358)
        ShareCardTheme.DARK_EDITORIAL -> Color(0xFFA5A096)
        ShareCardTheme.RED_BREAKING -> Color(0xFF7A5652)
    }
    val dividerColor = when (selectedTheme) {
        ShareCardTheme.CLASSIC_PAPER -> Color(0xFFD6CEBF)
        ShareCardTheme.DARK_EDITORIAL -> Color(0xFF2E2E34)
        ShareCardTheme.RED_BREAKING -> FlagRed.copy(alpha = 0.28f)
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(6.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "ফটো কার্ড শেয়ার",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, contentDescription = "বন্ধ করুন")
                    }
                }

                // 3-theme selector row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ShareCardTheme.entries.forEach { theme ->
                        val isSelected = selectedTheme == theme
                        FilterChip(
                            selected = isSelected,
                            onClick = { selectedTheme = theme },
                            label = {
                                Text(
                                    text = theme.label,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    maxLines = 1
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            shape = RoundedCornerShape(14.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Live Preview News Card
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            width = 1.dp,
                            color = if (selectedTheme == ShareCardTheme.RED_BREAKING) FlagRed else MaterialTheme.colorScheme.outlineVariant,
                            shape = RoundedCornerShape(16.dp)
                        ),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = cardBg)
                ) {
                    Column {
                        if (selectedTheme == ShareCardTheme.RED_BREAKING) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(FlagRed)
                                    .padding(horizontal = 16.dp, vertical = 6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "ব্রেকিং ও বিশেষ সংবাদ",
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.5.sp
                                    )
                                    Text(
                                        text = "পত্রিকাঘর.",
                                        color = Color.White,
                                        fontWeight = FontWeight.Black,
                                        fontSize = 14.sp,
                                        fontFamily = FontFamily.Serif
                                    )
                                }
                            }
                        }

                        Column(modifier = Modifier.padding(18.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                PaperAvatar(paper = paper, size = 36.dp)
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = paper.bn,
                                        color = headlineColor,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        fontFamily = FontFamily.Serif
                                    )
                                    Text(
                                        text = paper.host,
                                        color = subColor,
                                        fontSize = 11.sp
                                    )
                                }
                                if (selectedTheme != ShareCardTheme.RED_BREAKING) {
                                    Text(
                                        text = "পত্রিকাঘর.",
                                        color = FlagRed,
                                        fontWeight = FontWeight.Black,
                                        fontSize = 16.sp,
                                        fontFamily = FontFamily.Serif
                                    )
                                }
                            }

                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 12.dp),
                                color = dividerColor
                            )

                            Text(
                                text = title,
                                color = headlineColor,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontFamily = FontFamily.Serif,
                                    fontWeight = FontWeight.Bold,
                                    lineHeight = 24.sp
                                )
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = BengaliUtils.bnToday(),
                                    color = subColor,
                                    fontSize = 11.sp
                                )
                                Text(
                                    text = "potrikaghor.app",
                                    color = subColor,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, title)
                                putExtra(Intent.EXTRA_TEXT, "$title\n$url\n— পত্রিকাঘর অ্যাপ থেকে")
                            }
                            context.startActivity(Intent.createChooser(shareIntent, "শেয়ার করুন"))
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("লিংক শেয়ার")
                    }

                    Button(
                        onClick = {
                            shareCardAsImage(context, paper.bn, title, url, selectedTheme)
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("কার্ড শেয়ার")
                    }
                }
            }
        }
    }
}

fun shareCardAsImage(
    context: Context,
    paperName: String,
    title: String,
    url: String,
    theme: ShareCardTheme = ShareCardTheme.CLASSIC_PAPER
) {
    try {
        val width = 1080
        val height = 760
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val bgColor = when (theme) {
            ShareCardTheme.CLASSIC_PAPER -> AndroidColor.parseColor("#F3EEE4")
            ShareCardTheme.DARK_EDITORIAL -> AndroidColor.parseColor("#141416")
            ShareCardTheme.RED_BREAKING -> AndroidColor.parseColor("#FFF8F6")
        }
        val primaryTextColor = when (theme) {
            ShareCardTheme.CLASSIC_PAPER -> AndroidColor.parseColor("#16130F")
            ShareCardTheme.DARK_EDITORIAL -> AndroidColor.parseColor("#F5F2EB")
            ShareCardTheme.RED_BREAKING -> AndroidColor.parseColor("#1C1412")
        }
        val secondaryTextColor = when (theme) {
            ShareCardTheme.CLASSIC_PAPER -> AndroidColor.parseColor("#6B6358")
            ShareCardTheme.DARK_EDITORIAL -> AndroidColor.parseColor("#A5A096")
            ShareCardTheme.RED_BREAKING -> AndroidColor.parseColor("#7A5652")
        }
        val dividerColor = when (theme) {
            ShareCardTheme.CLASSIC_PAPER -> AndroidColor.parseColor("#D6CEBF")
            ShareCardTheme.DARK_EDITORIAL -> AndroidColor.parseColor("#2E2E34")
            ShareCardTheme.RED_BREAKING -> AndroidColor.parseColor("#F2C6C2")
        }
        val redAccent = AndroidColor.parseColor("#D4382C")

        canvas.drawColor(bgColor)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        var contentStartY = 100f
        if (theme == ShareCardTheme.RED_BREAKING) {
            paint.color = redAccent
            canvas.drawRect(0f, 0f, width.toFloat(), 96f, paint)
            paint.color = AndroidColor.WHITE
            paint.textSize = 32f
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            canvas.drawText("ব্রেকিং ও বিশেষ সংবাদ", 80f, 62f, paint)
            paint.textSize = 38f
            paint.typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            canvas.drawText("পত্রিকাঘর.", 830f, 62f, paint)

            paint.color = primaryTextColor
            paint.textSize = 36f
            paint.typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            canvas.drawText(paperName, 80f, 155f, paint)

            paint.color = dividerColor
            paint.strokeWidth = 3f
            canvas.drawLine(80f, 182f, 1000f, 182f, paint)
            contentStartY = 260f
        } else {
            paint.color = redAccent
            paint.textSize = 42f
            paint.typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            canvas.drawText("পত্রিকাঘর.", 80f, 100f, paint)

            paint.color = primaryTextColor
            paint.textSize = 34f
            paint.typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            canvas.drawText("• $paperName", 320f, 96f, paint)

            paint.color = dividerColor
            paint.strokeWidth = 3f
            canvas.drawLine(80f, 130f, 1000f, 130f, paint)
            contentStartY = 230f
        }

        // Headline
        paint.color = primaryTextColor
        paint.textSize = 50f
        paint.typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)

        val words = title.split(" ")
        var line = ""
        var y = contentStartY
        for (w in words) {
            val testLine = if (line.isEmpty()) w else "$line $w"
            if (paint.measureText(testLine) > 900f) {
                canvas.drawText(line, 80f, y, paint)
                line = w
                y += 72f
                if (y > 560f) break
            } else {
                line = testLine
            }
        }
        if (line.isNotEmpty() && y <= 580f) {
            canvas.drawText(line, 80f, y, paint)
        }

        // Footer
        paint.color = secondaryTextColor
        paint.textSize = 28f
        paint.typeface = Typeface.DEFAULT
        canvas.drawText(BengaliUtils.bnToday(), 80f, 680f, paint)
        canvas.drawText("potrikaghor.app", 780f, 680f, paint)
        canvas.drawText(url.take(65), 80f, 720f, paint)

        val imagesFolder = File(context.cacheDir, "images")
        imagesFolder.mkdirs()
        val file = File(imagesFolder, "potrikaghor_${System.currentTimeMillis()}.png")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 95, out)
        }

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, "$title\n$url\n— পত্রিকাঘর অ্যাপ")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "শেয়ার করুন"))
    } catch (_: Exception) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "$title\n$url")
        }
        context.startActivity(Intent.createChooser(intent, "শেয়ার করুন"))
    }
}

