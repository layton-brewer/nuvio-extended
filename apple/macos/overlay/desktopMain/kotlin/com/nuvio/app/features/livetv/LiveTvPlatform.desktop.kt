package com.nuvio.app.features.livetv

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.ProfileScopedKey
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPInputStream

internal actual object LiveTvPlatformStorage {
    private val store = DesktopStorage.store("nuvio_live_tv")

    actual fun load(key: String): String? =
        store.getString(ProfileScopedKey.of("live_tv_$key"))

    actual fun save(key: String, value: String?) {
        val scopedKey = ProfileScopedKey.of("live_tv_$key")
        if (value == null) store.remove(scopedKey) else store.putString(scopedKey, value)
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
    // The desktop apps have no WireGuard tunnel; the Live TV VPN uses an HTTP proxy here.
    LiveTvRelay(
        baseClient = okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build(),
        wireGuard = null,
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

internal actual fun liveTvWireGuardAvailable(): Boolean = false
