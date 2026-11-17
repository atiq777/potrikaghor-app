package com.potrikaghor.data

import android.content.Context
import com.potrikaghor.LegalLinks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pure host-matching helper with no Android framework classes.
 * Lower-cases both the host and blocked entries, strips a leading "www.",
 * and returns true when the host equals an entry or is a subdomain (ends with "." + entry).
 */
fun isBlockedHost(host: String, blocked: Set<String>): Boolean {
    if (blocked.isEmpty()) return false
    val cleanHost = host.trim().lowercase(Locale.ROOT).removePrefix("www.").trimEnd('.')
    if (cleanHost.isEmpty()) return false
    for (rawEntry in blocked) {
        val entry = rawEntry.trim().lowercase(Locale.ROOT).removePrefix("www.").trimEnd('.')
        if (entry.isEmpty()) continue
        if (cleanHost == entry || cleanHost.endsWith(".$entry")) {
            return true
        }
    }
    return false
}

object BlocklistRepository {
    private const val PREFS_NAME = "potrikaghor_blocklist_prefs"
    private const val KEY_CACHED_JSON = "blocklist_json"
    private const val TIMEOUT_SECONDS = 5L

    private val json = Json { ignoreUnknownKeys = true }
    private val loadStarted = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var blockedHosts: Set<String> = emptySet()

    private val _blockedHostsFlow = MutableStateFlow<Set<String>>(emptySet())
    val blockedHostsFlow: StateFlow<Set<String>> = _blockedHostsFlow.asStateFlow()

    fun isFeatureEnabled(): Boolean {
        return LegalLinks.BLOCKLIST_URL.trim().startsWith("https://")
    }

    fun currentBlockedHosts(): Set<String> {
        return if (isFeatureEnabled()) blockedHosts else emptySet()
    }

    fun isBlockedHost(host: String, blocked: Set<String>): Boolean {
        return com.potrikaghor.data.isBlockedHost(host, blocked)
    }

    fun isBlockedHost(host: String): Boolean {
        if (!isFeatureEnabled()) return false
        return com.potrikaghor.data.isBlockedHost(host, blockedHosts)
    }

    fun isBlockedUrl(url: String): Boolean {
        if (!isFeatureEnabled()) return false
        val current = blockedHosts
        if (current.isEmpty()) return false
        val host = extractHost(url) ?: return false
        return com.potrikaghor.data.isBlockedHost(host, current)
    }

    internal fun extractHost(url: String): String? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return null
        return try {
            val uri = URI(trimmed)
            val host = uri.host?.takeIf { it.isNotBlank() }
            if (host != null) return host
            if (uri.scheme == null && !trimmed.contains(":")) {
                URI("https://$trimmed").host?.takeIf { it.isNotBlank() }
            } else {
                null
            }
        } catch (_: Exception) {
            try {
                val schemeEnd = trimmed.indexOf("://")
                if (schemeEnd > 0) {
                    val scheme = trimmed.substring(0, schemeEnd).lowercase(Locale.ROOT)
                    if (scheme != "http" && scheme != "https") return null
                    val afterScheme = trimmed.substring(schemeEnd + 3)
                    val authority = afterScheme
                        .substringBefore('/')
                        .substringBefore('?')
                        .substringBefore('#')
                    val hostPart = authority.substringAfter('@').substringBefore(':').trim()
                    hostPart.takeIf { it.isNotBlank() && !it.contains(' ') }
                } else {
                    null
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    internal fun parseBlockedHostsJson(rawJson: String): Set<String>? {
        return try {
            val root = json.parseToJsonElement(rawJson).jsonObject
            val arr = root["blocked_hosts"]?.jsonArray ?: return null
            val parsed = LinkedHashSet<String>(arr.size)
            for (elem in arr) {
                val primitive = elem as? JsonPrimitive ?: continue
                if (!primitive.isString) continue
                val clean = primitive.content.trim().lowercase(Locale.ROOT)
                    .removePrefix("www.")
                    .trimEnd('.')
                if (clean.isNotEmpty()) {
                    parsed.add(clean)
                }
            }
            parsed
        } catch (_: Exception) {
            null
        }
    }

    private fun updateBlockedHosts(newSet: Set<String>) {
        blockedHosts = newSet
        _blockedHostsFlow.value = newSet
    }

    /**
     * Reads cached JSON from SharedPreferences first (for offline blocking), then fetches
     * LegalLinks.BLOCKLIST_URL in the background once per process with 5-second timeouts.
     * Any network or parse failure silently retains the cached blocklist.
     */
    fun loadAtStart(context: Context, baseClient: OkHttpClient) {
        if (!isFeatureEnabled()) return
        if (!loadStarted.compareAndSet(false, true)) return

        val appCtx = context.applicationContext
        val prefs = try {
            appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        } catch (_: Exception) {
            null
        }

        // 1. Load cached JSON synchronously so blocking works immediately offline
        try {
            val cachedJson = prefs?.getString(KEY_CACHED_JSON, null)
            if (!cachedJson.isNullOrBlank()) {
                val cachedHosts = parseBlockedHostsJson(cachedJson)
                if (cachedHosts != null) {
                    updateBlockedHosts(cachedHosts)
                }
            }
        } catch (_: Exception) {
            // Preserve empty default on corrupt cache
        }

        // 2. Refresh in background coroutine with 5s connect & read timeouts
        scope.launch {
            try {
                val url = LegalLinks.BLOCKLIST_URL.trim()
                if (!url.startsWith("https://")) return@launch

                val client = baseClient.newBuilder()
                    .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .callTimeout(TIMEOUT_SECONDS * 2, TimeUnit.SECONDS)
                    .build()

                val request = Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .build()

                val bodyString = client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@launch
                    response.body?.string()
                }

                if (bodyString.isNullOrBlank()) return@launch
                val remoteHosts = parseBlockedHostsJson(bodyString) ?: return@launch

                try {
                    prefs?.edit()?.putString(KEY_CACHED_JSON, bodyString)?.apply()
                } catch (_: Exception) {
                    // Ignore SharedPreferences persistence errors
                }
                updateBlockedHosts(remoteHosts)
            } catch (_: Exception) {
                // Keep cached blocklist on any network/parse failure
            }
        }
    }
}
