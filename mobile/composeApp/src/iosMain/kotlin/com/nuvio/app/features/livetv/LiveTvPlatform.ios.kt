package com.nuvio.app.features.livetv

import com.nuvio.app.core.storage.ProfileScopedKey
import platform.Foundation.NSLocale
import platform.Foundation.NSLocaleCountryCode
import platform.Foundation.NSUserDefaults
import platform.Foundation.currentLocale

internal actual object LiveTvPlatformStorage {
    actual fun load(key: String): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(ProfileScopedKey.of("live_tv_$key"))

    actual fun save(key: String, value: String?) {
        val scopedKey = ProfileScopedKey.of("live_tv_$key")
        if (value == null) {
            NSUserDefaults.standardUserDefaults.removeObjectForKey(scopedKey)
        } else {
            NSUserDefaults.standardUserDefaults.setObject(value, forKey = scopedKey)
        }
    }
}

// Guide files packed with gzip are skipped on iOS for now; plain guides and playlists work.
internal actual fun liveTvGunzip(bytes: ByteArray, maxBytes: Int): ByteArray? = null

internal actual fun liveTvCountryCode(): String? =
    NSLocale.currentLocale.objectForKey(NSLocaleCountryCode) as? String

internal actual suspend fun liveTvStreamText(url: String, onChunk: (String) -> Unit): Boolean = false

internal actual suspend fun liveTvStreamAnswers(url: String, headers: Map<String, String>): Boolean? = null

/**
 * iOS plays Live TV straight from the channel. When the Live TV VPN (an HTTP proxy here) is on,
 * the proxy is remembered for that address only and the player picks it up for that playback;
 * every other playback, debrid included, is told to use no proxy.
 */
object LiveTvIosProxy {
    private val proxies = HashMap<String, String>()

    fun proxyFor(url: String): String? = proxies[url]

    internal fun remember(url: String, proxy: String?) {
        if (proxy == null) proxies.remove(url) else proxies[url] = proxy
        while (proxies.size > 8) proxies.remove(proxies.keys.first())
    }
}

internal actual fun liveTvPlaybackTarget(
    channel: LiveChannel,
    backups: List<LiveChannel>,
    vpn: LiveVpnSettings,
): LiveTvPlaybackTarget {
    val proxy = if (vpn.ready && vpn.type == LiveVpnType.PROXY && LiveVpnGuard.mayRoute(channel.url)) {
        LiveVpnGuard.parseProxy(vpn.proxyUrl)?.asUrl()
    } else {
        null
    }
    LiveTvIosProxy.remember(channel.url, proxy)
    return LiveTvPlaybackTarget(channel.url, channel.headers)
}

internal actual fun liveTvWireGuardAvailable(): Boolean = false
