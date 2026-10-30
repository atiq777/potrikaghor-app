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

@Composable
fun ShareCardDialog(
    paper: Paper,
    title: String,
    url: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "শেয়ার কার্ড প্রিভিউ",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, contentDescription = "বন্ধ করুন")
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // The News Card (Simulates warm newsprint card)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = PaperColor)
                ) {
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
                                    color = InkColor,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    fontFamily = FontFamily.Serif
                                )
                                Text(
                                    text = paper.host,
                                    color = Color(0xFF6B6358),
                                    fontSize = 11.sp
                                )
                            }
                            Text(
                                text = "পত্রিকাঘর.",
                                color = FlagRed,
                                fontWeight = FontWeight.Black,
                                fontSize = 16.sp,
                                fontFamily = FontFamily.Serif
                            )
                        }

                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 12.dp),
                            color = Color(0xFFD6CEBF)
                        )

                        Text(
                            text = title,
                            color = InkColor,
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
                                color = Color(0xFF6B6358),
                                fontSize = 11.sp
                            )
                            Text(
                                text = "potrikaghor.app",
                                color = Color(0xFF6B6358),
                                fontSize = 11.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

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
                            shareCardAsImage(context, paper.bn, title, url)
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

fun shareCardAsImage(context: Context, paperName: String, title: String, url: String) {
    try {
        val width = 1080
        val height = 760
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Warm paper background
        canvas.drawColor(AndroidColor.parseColor("#F3EEE4"))

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Top Masthead
        paint.color = AndroidColor.parseColor("#D4382C")
        paint.textSize = 42f
        paint.typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        canvas.drawText("পত্রিকাঘর.", 80f, 100f, paint)

        paint.color = AndroidColor.parseColor("#16130F")
        paint.textSize = 34f
        paint.typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        canvas.drawText("• $paperName", 320f, 96f, paint)

        // Divider
        paint.color = AndroidColor.parseColor("#D6CEBF")
        paint.strokeWidth = 3f
        canvas.drawLine(80f, 130f, 1000f, 130f, paint)

        // Headline
        paint.color = AndroidColor.parseColor("#16130F")
        paint.textSize = 50f
        paint.typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)

        val words = title.split(" ")
        var line = ""
        var y = 230f
        for (w in words) {
            val testLine = if (line.isEmpty()) w else "$line $w"
            if (paint.measureText(testLine) > 900f) {
                canvas.drawText(line, 80f, y, paint)
                line = w
                y += 72f
                if (y > 540f) break
            } else {
                line = testLine
            }
        }
        if (line.isNotEmpty() && y <= 560f) {
            canvas.drawText(line, 80f, y, paint)
        }

        // Footer
        paint.color = AndroidColor.parseColor("#6B6358")
        paint.textSize = 28f
        paint.typeface = Typeface.DEFAULT
        canvas.drawText(BengaliUtils.bnToday(), 80f, 680f, paint)
        canvas.drawText(url, 80f, 720f, paint)

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
        // Fallback to text share
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "$title\n$url")
        }
        context.startActivity(Intent.createChooser(intent, "শেয়ার করুন"))
    }
}
