package com.nuvio.app.features.livetv

import android.content.Context
import android.content.SharedPreferences
import com.nuvio.app.core.storage.ProfileScopedKey
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPInputStream

internal actual object LiveTvPlatformStorage {
    private const val preferencesName = "nuvio_live_tv"

    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

    actual fun load(key: String): String? =
        preferences?.getString(ProfileScopedKey.of("live_tv_$key"), null)

    actual fun save(key: String, value: String?) {
        val editor = preferences?.edit() ?: return
        val scopedKey = ProfileScopedKey.of("live_tv_$key")
        if (value == null) editor.remove(scopedKey) else editor.putString(scopedKey, value)
        editor.apply()
    }
}

internal actual fun liveTvGunzip(bytes: ByteArray, maxBytes: Int): ByteArray? {
    val out = ByteArrayOutputStream()
    try {
        GZIPInputStream(ByteArrayInputStream(bytes)).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (out.size() < maxBytes) {
                val count = input.read(buffer, 0, minOf(buffer.size, maxBytes - out.size()))
                if (count < 0) break
                out.write(buffer, 0, count)
            }
        }
    } catch (e: IOException) {
        // A cut-off download still holds usable programmes.
        if (out.size() == 0) return null
    }
    return out.toByteArray()
}

internal actual fun liveTvCountryCode(): String? =
    java.util.Locale.getDefault().country.takeIf(String::isNotBlank)

internal actual suspend fun liveTvStreamText(url: String, onChunk: (String) -> Unit): Boolean =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 30_000
            connection.readTimeout = 120_000
            connection.instanceFollowRedirects = true
            try {
                if (connection.responseCode !in 200..299) return@runCatching false
                connection.inputStream.use { raw ->
                    val pushback = java.io.PushbackInputStream(raw, 2)
                    val magic = ByteArray(2)
                    val read = pushback.read(magic, 0, 2)
                    if (read > 0) pushback.unread(magic, 0, read)
                    val gzip = read == 2 && magic[0] == 0x1f.toByte() && magic[1] == 0x8b.toByte()
                    val reader = (if (gzip) GZIPInputStream(pushback) else pushback).reader(Charsets.UTF_8)
                    val buffer = CharArray(64 * 1024)
                    var total = 0L
                    try {
                        while (total < 600L * 1024 * 1024) {
                            val count = reader.read(buffer)
                            if (count < 0) break
                            onChunk(String(buffer, 0, count))
                            total += count
                        }
                    } catch (e: IOException) {
                        // Keep what was read from a download that was cut off.
                        if (total == 0L) throw e
                    }
                }
                true
            } finally {
                connection.disconnect()
            }
        }.getOrDefault(false)
    }

internal actual suspend fun liveTvStreamAnswers(url: String, headers: Map<String, String>): Boolean? =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) return@withContext null
        try {
            val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 6_000
            connection.readTimeout = 6_000
            connection.instanceFollowRedirects = true
            headers.forEach { (name, value) -> runCatching { connection.setRequestProperty(name, value) } }
            try {
                // Only a missing stream or a failing server counts as down; other refusals (401, 403)
                // often come from servers that dislike this check and still play in the player.
                val code = connection.responseCode
                when {
                    code in 200..399 -> true
                    code == 404 || code == 410 || code >= 500 -> false
                    else -> null
                }
            } finally {
                connection.disconnect()
            }
        } catch (e: IOException) {
            false
        } catch (e: Exception) {
            null
        }
    }

private val liveTvRelay by lazy {
    LiveTvRelay(
        baseClient = okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build(),
        wireGuard = LiveTvRelay.WireGuardStarter { config ->
            if (!com.meowio.livetv.LiveTvVpnNative.available) {
                throw IOException("WireGuard is not included in this build")
            }
            val handle = com.meowio.livetv.LiveTvVpnNative.start(config)
            if (handle == 0L) {
                throw IOException(com.meowio.livetv.LiveTvVpnNative.lastError().ifBlank { "the VPN did not connect" })
            }
            LiveTvRelay.Tunnel(
                proxy = LiveProxy(
                    "127.0.0.1",
                    com.meowio.livetv.LiveTvVpnNative.port(handle),
                    "meowio",
                    com.meowio.livetv.LiveTvVpnNative.token(handle),
                ),
                close = { com.meowio.livetv.LiveTvVpnNative.stop(handle) },
            )
        },
        log = { android.util.Log.i("LiveTv", it) },
    )
}

internal actual fun liveTvPlaybackTarget(
    channel: LiveChannel,
    backups: List<LiveChannel>,
    vpn: LiveVpnSettings,
): LiveTvPlaybackTarget {
    val url = runCatching { liveTvRelay.open(channel, backups, vpn) }.getOrElse { channel.url }
    return if (url == channel.url) LiveTvPlaybackTarget(url, channel.headers) else LiveTvPlaybackTarget(url, emptyMap())
}

internal actual fun liveTvWireGuardAvailable(): Boolean = com.meowio.livetv.LiveTvVpnNative.available
