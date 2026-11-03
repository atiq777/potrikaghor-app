package com.potrikaghor.ui.dialogs

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.potrikaghor.data.PapersData
import com.potrikaghor.model.Paper
import com.potrikaghor.ui.components.GlassPrimaryButton
import com.potrikaghor.ui.components.GlassSecondaryButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddPaperDialog(
    onDismiss: () -> Unit,
    onSave: (Paper) -> Unit
) {
    val context = LocalContext.current
    var bnName by remember { mutableStateOf("") }
    var enName by remember { mutableStateOf("") }
    var urlInput by remember { mutableStateOf("") }
    var selectedCatId by remember { mutableStateOf("online") }
    var isExpanded by remember { mutableStateOf(false) }

    fun normalizeUrl(raw: String): String? {
        var s = raw.trim()
        if (s.isEmpty()) return null
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://$s"
        if (s.endsWith("/")) s = s.substring(0, s.length - 1)
        return if (s.contains(".")) s else null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "নতুন পত্রিকা যোগ করুন",
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Serif
            )
        },
        text = {
            Column {
                OutlinedTextField(
                    value = bnName,
                    onValueChange = { bnName = it },
                    label = { Text("পত্রিকার নাম (বাংলায়)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = enName,
                    onValueChange = { enName = it },
                    label = { Text("ইংরেজি নাম (ঐচ্ছিক)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    label = { Text("ওয়েবসাইটের ঠিকানা / লিংক") },
                    placeholder = { Text("যেমন: example.com") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(10.dp))

                ExposedDropdownMenuBox(
                    expanded = isExpanded,
                    onExpandedChange = { isExpanded = it }
                ) {
                    OutlinedTextField(
                        value = PapersData.catLabel(selectedCatId),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("বিভাগ") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = isExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = isExpanded,
                        onDismissRequest = { isExpanded = false }
                    ) {
                        PapersData.categories.drop(1).forEach { cat ->
                            DropdownMenuItem(
                                text = { Text(cat.label) },
                                onClick = {
                                    selectedCatId = cat.id
                                    isExpanded = false
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            GlassPrimaryButton(
                onClick = {
                    val validUrl = normalizeUrl(urlInput)
                    if (bnName.trim().isEmpty()) {
                        Toast.makeText(context, "দয়া করে পত্রিকার নাম লিখুন", Toast.LENGTH_SHORT).show()
                    } else if (validUrl == null) {
                        Toast.makeText(context, "সঠিক ওয়েব ঠিকানা লিখুন", Toast.LENGTH_SHORT).show()
                    } else {
                        val paper = Paper(
                            bn = bnName.trim(),
                            en = enName.trim(),
                            url = validUrl,
                            cat = selectedCatId,
                            custom = true
                        )
                        onSave(paper)
                        Toast.makeText(context, "পত্রিকাটি সফলভাবে যোগ হয়েছে", Toast.LENGTH_SHORT).show()
                        onDismiss()
                    }
                }
            ) {
                Text("যোগ করুন", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            GlassSecondaryButton(onClick = onDismiss) {
                Text("বাতিল")
            }
        }
    )
}

@Composable
fun FixUrlDialog(
    paper: Paper,
    onDismiss: () -> Unit,
    onSaveFix: (String?) -> Unit
) {
    val context = LocalContext.current
    var currentUrl by remember { mutableStateOf(paper.openUrl) }

    fun normalizeUrl(raw: String): String? {
        var s = raw.trim()
        if (s.isEmpty()) return null
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://$s"
        return if (s.contains(".")) s else null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("লিংক ঠিক করুন — ${paper.bn}", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Serif) },
        text = {
            Column {
                Text(
                    text = "কোনো পত্রিকার লিংক পরিবর্তন হলে বা না খুললে সঠিক লিংক বসিয়ে নিন। আপনার প্রিয় তালিকা অক্ষত থাকবে।",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = currentUrl,
                    onValueChange = { currentUrl = it },
                    label = { Text("সঠিক ওয়েব ঠিকানা") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (paper.fix != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = {
                        onSaveFix(null)
                        Toast.makeText(context, "আগের মূল লিংক পুনঃস্থাপিত হয়েছে", Toast.LENGTH_SHORT).show()
                        onDismiss()
                    }) {
                        Text("মূল লিংকে ফিরে যান")
                    }
                }
            }
        },
        confirmButton = {
            GlassPrimaryButton(
                onClick = {
                    val valid = normalizeUrl(currentUrl)
                    if (valid != null) {
                        onSaveFix(valid)
                        Toast.makeText(context, "লিংক আপডেট করা হয়েছে", Toast.LENGTH_SHORT).show()
                        onDismiss()
                    } else {
                        Toast.makeText(context, "সঠিক ওয়েব লিংক দিন", Toast.LENGTH_SHORT).show()
                    }
                }
            ) {
                Text("সংরক্ষণ", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            GlassSecondaryButton(onClick = onDismiss) {
                Text("বাতিল")
            }
        }
    )
}
