package com.nuvio.app.features.livetv

/** Device storage for Live TV settings, kept per profile and never synced. */
internal expect object LiveTvPlatformStorage {
    fun load(key: String): String?
    fun save(key: String, value: String?)
}

/** Unpacks gzip data, reading at most [maxBytes] of output. Null when the data cannot be unpacked. */
internal expect fun liveTvGunzip(bytes: ByteArray, maxBytes: Int): ByteArray?

/** The device's two-letter country code, or null when it is not known. */
internal expect fun liveTvCountryCode(): String?

/**
 * Downloads [url] and hands its text over piece by piece, unpacking gzip. Returns false when the
 * platform cannot do this, in which case the caller falls back to a plain download.
 */
internal expect suspend fun liveTvStreamText(url: String, onChunk: (String) -> Unit): Boolean

/**
 * Opens a stream briefly to see whether it is there: true or false, or null when the platform
 * cannot tell.
 */
internal expect suspend fun liveTvStreamAnswers(url: String, headers: Map<String, String>): Boolean?

/** What the player opens for a Live TV channel. */
data class LiveTvPlaybackTarget(
    val url: String,
    val headers: Map<String, String>,
)

/**
 * Where to play [channel] from: the channel itself, or, where the platform can, a relay on the
 * device that falls back on [backups] and sends Live TV (only Live TV) through the Live TV VPN.
 */
internal expect fun liveTvPlaybackTarget(
    channel: LiveChannel,
    backups: List<LiveChannel>,
    vpn: LiveVpnSettings,
): LiveTvPlaybackTarget

/** True when this platform can run a WireGuard tunnel for the Live TV VPN. */
internal expect fun liveTvWireGuardAvailable(): Boolean
