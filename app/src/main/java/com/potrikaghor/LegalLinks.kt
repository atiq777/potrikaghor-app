package com.potrikaghor

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object LegalLinks {
    const val PRIVACY_URL = "https://atiq777.github.io/potrikaghor-legal/privacy-policy.html"
    const val TAKEDOWN_URL = "https://atiq777.github.io/potrikaghor-legal/copyright-takedown.html"
    const val BLOCKLIST_URL = "https://atiq777.github.io/potrikaghor-legal/blocklist.json"
    const val SUBMIT_PAPER_URL = "mailto:meatiqislam2001@gmail.com?subject=%E0%A6%AA%E0%A6%A4%E0%A7%8D%E0%A6%B0%E0%A6%BF%E0%A6%95%E0%A6%BE%20%E0%A6%9C%E0%A6%AE%E0%A6%BE%20%28%E0%A6%AA%E0%A6%A4%E0%A7%8D%E0%A6%B0%E0%A6%BF%E0%A6%95%E0%A6%BE%E0%A6%98%E0%A6%B0%29&body=%E0%A6%AA%E0%A6%A4%E0%A7%8D%E0%A6%B0%E0%A6%BF%E0%A6%95%E0%A6%BE%E0%A6%B0%20%E0%A6%A8%E0%A6%BE%E0%A6%AE%3A%0A%E0%A6%93%E0%A6%AF%E0%A6%BC%E0%A7%87%E0%A6%AC%E0%A6%B8%E0%A6%BE%E0%A6%87%E0%A6%9F%E0%A7%87%E0%A6%B0%20%E0%A6%A0%E0%A6%BF%E0%A6%95%E0%A6%BE%E0%A6%A8%E0%A6%BE%3A%0A%E0%A6%95%E0%A7%8D%E0%A6%AF%E0%A6%BE%E0%A6%9F%E0%A6%BE%E0%A6%97%E0%A6%B0%E0%A6%BF%20%28%E0%A6%9C%E0%A6%BE%E0%A6%A4%E0%A7%80%E0%A6%AF%E0%A6%BC%20%2F%20%E0%A6%B8%E0%A7%8D%E0%A6%A5%E0%A6%BE%E0%A6%A8%E0%A7%80%E0%A6%AF%E0%A6%BC%20%2F%20%E0%A6%85%E0%A6%A8%E0%A6%B2%E0%A6%BE%E0%A6%87%E0%A6%A8%20%2F%20%E0%A6%9F%E0%A6%BF%E0%A6%AD%E0%A6%BF%20%2F%20%E0%A6%AE%E0%A7%8D%E0%A6%AF%E0%A6%BE%E0%A6%97%E0%A6%BE%E0%A6%9C%E0%A6%BF%E0%A6%A8%20%2F%20%E0%A6%87%E0%A6%82%E0%A6%B0%E0%A7%87%E0%A6%9C%E0%A6%BF%20%2F%20%E0%A6%85%E0%A6%A8%E0%A7%8D%E0%A6%AF%E0%A6%BE%E0%A6%A8%E0%A7%8D%E0%A6%AF%29%3A%0A%E0%A6%85%E0%A6%A8%E0%A7%8D%E0%A6%AF%20%E0%A6%95%E0%A6%BF%E0%A6%9B%E0%A7%81%20%E0%A6%9C%E0%A6%BE%E0%A6%A8%E0%A6%BE%E0%A6%A8%E0%A7%8B%E0%A6%B0%20%E0%A6%A5%E0%A6%BE%E0%A6%95%E0%A6%B2%E0%A7%87%3A%0A"

    private const val PREFS_NAME = "potrikaghor_prefs"
    private const val KEY_DISCLAIMER_SEEN = "legal_disclaimer_v1_seen"

    fun isSet(url: String): Boolean = url.startsWith("https://") || url.startsWith("mailto:")

    fun isDisclaimerSeen(context: Context): Boolean {
        return context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_DISCLAIMER_SEEN, false)
    }

    fun markDisclaimerSeen(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DISCLAIMER_SEEN, true)
            .apply()
    }

    fun openBrowserUrl(context: Context, url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            if (context !is Activity) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(context, "লিংকটি খোলা যাচ্ছে না", Toast.LENGTH_SHORT).show()
        }
    }

    fun openMailtoUrl(context: Context, url: String) {
        try {
            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse(url))
            if (context !is Activity) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(context, "ইমেইল অ্যাপ পাওয়া যায়নি", Toast.LENGTH_SHORT).show()
        }
    }
}

private const val DISCLAIMER_BODY =
    "পত্রিকাঘর একটি সংবাদ সংগ্রাহক অ্যাপ। এটি বিভিন্ন সংবাদমাধ্যমের প্রকাশ্য ফিড ও ওয়েবসাইট থেকে শিরোনাম, সংক্ষিপ্ত অংশ ও ছবি একত্র করে সুবিধামতো দেখায়, সবসময় মূল পত্রিকার নাম ও লিংকসহ।\n\n" +
        "১. সব সংবাদপত্রের নাম, লোগো ও লেখার স্বত্ব তাদের নিজ নিজ মালিকের।\n" +
        "২. আমরা নিজেরা কোনো খবর লিখি বা সম্পাদনা করি না।\n" +
        "৩. এই অ্যাপ কোনো সংবাদপত্রের অফিসিয়াল অ্যাপ নয়।\n" +
        "৪. রিডার মোডে পড়ার সুবিধার জন্য মূল পাতার লেখা দেখানো হতে পারে; \"মূল ওয়েবসাইট\" বোতাম সবসময় আছে।\n" +
        "৫. কোনো পত্রিকা তালিকা থেকে বাদ যেতে চাইলে আমাদের জানান, আমরা বাদ দেব।"

@Composable
fun FirstLaunchDisclaimerHost() {
    val context = LocalContext.current
    var showDialog by rememberSaveable { mutableStateOf(!LegalLinks.isDisclaimerSeen(context)) }
    if (!showDialog) return

    val dismiss: () -> Unit = {
        LegalLinks.markDisclaimerSeen(context)
        showDialog = false
    }

    AlertDialog(
        onDismissRequest = dismiss,
        title = {
            Text(
                text = "গুরুত্বপূর্ণ ঘোষণা",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = DISCLAIMER_BODY,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 13.5.sp,
                        lineHeight = 20.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = dismiss,
                modifier = Modifier.testTag("legal_disclaimer_ok_button")
            ) {
                Text("ঠিক আছে")
            }
        },
        dismissButton = if (LegalLinks.isSet(LegalLinks.TAKEDOWN_URL)) {
            {
                TextButton(
                    onClick = {
                        LegalLinks.openBrowserUrl(context, LegalLinks.TAKEDOWN_URL)
                        dismiss()
                    },
                    modifier = Modifier.testTag("legal_disclaimer_details_button")
                ) {
                    Text("বিস্তারিত")
                }
            }
        } else {
            null
        }
    )
}
