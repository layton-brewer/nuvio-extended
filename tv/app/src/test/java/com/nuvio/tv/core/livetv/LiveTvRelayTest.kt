package com.nuvio.tv.core.livetv

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

class LiveTvRelayTest {
    private lateinit var server: MockWebServer
    private val client = OkHttpClient()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/dead.m3u8" -> MockResponse().setResponseCode(404)
                "/live/index.m3u8" -> MockResponse()
                    .setHeader("Content-Type", "application/vnd.apple.mpegurl")
                    .setBody("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n#EXTINF:6,\nseg1.ts\n")
                "/live/seg1.ts" -> MockResponse().setBody("SEGMENT for ${request.getHeader("User-Agent")}")
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun get(url: String) = client.newCall(Request.Builder().url(url).build()).execute()

    @Test
    fun `falls back to a backup copy and rewrites the playlist through itself`() {
        val relay = LiveTvRelay(client, wireGuard = null)
        val dead = LiveChannel(id = "1", name = "News", url = server.url("/dead.m3u8").toString())
        val backup = LiveChannel(
            id = "2",
            name = "News HD",
            url = server.url("/live/index.m3u8").toString(),
            headers = mapOf("User-Agent" to "ChannelAgent"),
        )
        val address = relay.open(dead, listOf(backup), LiveVpnSettings())
        assertTrue(address.startsWith("http://127.0.0.1:"))
        assertTrue(address.endsWith("dead.m3u8"))

        val playlist = get(address).use { it.body!!.string() }
        val lines = playlist.lines()
        assertTrue(lines[1].contains("URI=\"http://127.0.0.1:"))
        val segment = lines.first { it.startsWith("http://127.0.0.1:") }
        assertTrue(segment.endsWith("seg1.ts"))
        get(segment).use {
            // The channel's own headers are sent upstream.
            assertEquals("SEGMENT for ChannelAgent", it.body!!.string())
        }
    }

    @Test
    fun `plays straight from the channel when nothing needs relaying`() {
        val relay = LiveTvRelay(client, wireGuard = null)
        val channel = LiveChannel(id = "1", name = "News", url = "http://example.com/a.m3u8")
        assertEquals(channel.url, relay.open(channel, emptyList(), LiveVpnSettings()))
    }

    @Test
    fun `refuses to play around a vpn that does not connect`() {
        val tried = AtomicBoolean(false)
        val relay = LiveTvRelay(client, wireGuard = LiveTvRelay.WireGuardStarter {
            tried.set(true)
            throw IOException("no route")
        })
        val channel = LiveChannel(id = "1", name = "News", url = server.url("/live/index.m3u8").toString())
        val vpn = LiveVpnSettings(
            enabled = true,
            wireGuardConfig = "[Interface]\nPrivateKey = a\n[Peer]\nPublicKey = b\nEndpoint = h:1",
        )
        val address = relay.open(channel, emptyList(), vpn)
        get(address).use { assertEquals(502, it.code) }
        assertTrue(tried.get())
        // Nothing reached the channel's server directly.
        assertEquals(0, server.requestCount)
    }
}
