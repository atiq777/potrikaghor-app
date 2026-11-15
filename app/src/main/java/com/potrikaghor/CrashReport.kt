package com.potrikaghor

import android.app.Activity
import android.content.Context
import android.content.Intent
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashReport {
    private const val PREFS_NAME = "potrikaghor_crash_prefs"
    private const val KEY_LAST_CRASH = "last_crash"
    private const val MAX_STACK_LINES = 25

    @Volatile
    private var installed = false

    @Volatile
    private var dialogShownInSession = false

    fun install(context: Context) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            val appContext = context.applicationContext
            val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                try {
                    saveCrash(appContext, throwable)
                } catch (_: Throwable) {
                    // Never block the default crash handler
                } finally {
                    previousHandler?.uncaughtException(thread, throwable)
                }
            }
            installed = true
        }
    }

    private fun saveCrash(context: Context, throwable: Throwable) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).format(Date())
        val stackWriter = StringWriter()
        throwable.printStackTrace(PrintWriter(stackWriter))
        val firstLines = stackWriter.toString()
            .lineSequence()
            .take(MAX_STACK_LINES)
            .joinToString("\n")
        val message = throwable.message?.takeIf { it.isNotBlank() } ?: "(no message)"
        val report = buildString {
            append("Time: ").append(timestamp).append('\n')
            append("Exception: ").append(throwable::class.java.name).append(": ").append(message).append("\n\n")
            append(firstLines)
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_CRASH, report)
            .commit()
    }

    fun getLastCrash(context: Context): String? {
        return context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_CRASH, null)
            ?.takeIf { it.isNotBlank() }
    }

    fun clearLastCrash(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_LAST_CRASH)
            .apply()
    }

    fun shareCrash(context: Context, reportText: String) {
        try {
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, reportText)
            }
            val chooser = Intent.createChooser(sendIntent, "ক্র্যাশ রিপোর্ট শেয়ার করুন")
            if (context !is Activity) {
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (_: Exception) {
        }
    }

    internal fun consumeForSession(context: Context): String? {
        if (dialogShownInSession) return null
        val crash = getLastCrash(context) ?: return null
        dialogShownInSession = true
        return crash
    }
}

@Composable
fun CrashReportDialogHost() {
    val context = LocalContext.current
    var crashText by rememberSaveable { mutableStateOf(CrashReport.consumeForSession(context)) }
    val activeReport = crashText ?: return

    AlertDialog(
        onDismissRequest = {
            CrashReport.clearLastCrash(context)
            crashText = null
        },
        title = {
            Text(
                text = "গত বার অ্যাপ বন্ধ হয়েছিল",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = activeReport,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { CrashReport.shareCrash(context, activeReport) },
                modifier = Modifier.testTag("crash_report_share_button")
            ) {
                Text("শেয়ার করুন")
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    CrashReport.clearLastCrash(context)
                    crashText = null
                },
                modifier = Modifier.testTag("crash_report_close_button")
            ) {
                Text("বন্ধ করুন")
            }
        }
    )
}
