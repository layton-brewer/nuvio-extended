package com.nuvio.app.features.livetv

import com.nuvio.app.features.addons.httpRequestRaw
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Content type the player uses for Live TV channels, which are never saved to watch history. */
const val LiveTvContentType = "livetv"

/** Connects the shared Live TV logic to this app's storage, network and profiles. */
@OptIn(ExperimentalTime::class)
object LiveTvRepository {
    private const val MAX_TEXT_BYTES = 32 * 1024 * 1024

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val storage = object : LiveTvStorage {
        override suspend fun read(key: String): String? = LiveTvPlatformStorage.load(key)
        override suspend fun write(key: String, value: String?) = LiveTvPlatformStorage.save(key, value)
    }

    val controller = LiveTvController(
        storage = storage,
        loader = LiveTvLoader(
            fetchText = ::fetchText,
            nowMs = { Clock.System.now().toEpochMilliseconds() },
            streamText = ::liveTvStreamText,
        ),
        scope = scope,
        probe = { channel -> liveTvStreamAnswers(channel.url, channel.headers) },
    )

    fun ensureLoaded() = controller.start()

    /** What the player should open for [channel], with backup copies and the Live TV VPN applied. */
    fun playbackTarget(channel: LiveChannel): LiveTvPlaybackTarget =
        liveTvPlaybackTarget(channel, controller.alternatesFor(channel), controller.state.value.vpn)

    val wireGuardAvailable: Boolean get() = liveTvWireGuardAvailable()

    fun onProfileChanged() {
        controller.reset()
        controller.start()
    }

    fun clearLocalState() = controller.reset()

    private suspend fun fetchText(url: String): String? {
        val response = runCatching {
            httpRequestRaw(
                method = "GET",
                url = url,
                headers = emptyMap(),
                body = "",
                maxResponseBodyBytes = MAX_TEXT_BYTES,
            )
        }.getOrNull() ?: return null
        if (response.status !in 200..299) return null
        val bytes = response.bodyBytes
        val gzip = bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()
        if (!gzip) return response.body
        return liveTvGunzip(bytes, MAX_TEXT_BYTES)?.decodeToString()
    }
}
