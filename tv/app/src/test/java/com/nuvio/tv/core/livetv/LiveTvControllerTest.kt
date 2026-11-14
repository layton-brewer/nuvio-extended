package com.nuvio.tv.core.livetv

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveTvControllerTest {

    private class MemoryStorage : LiveTvStorage {
        val values = HashMap<String, String>()
        override suspend fun read(key: String): String? = values[key]
        override suspend fun write(key: String, value: String?) {
            if (value == null) values.remove(key) else values[key] = value
        }
    }

    private val playlist = """
        #EXTM3U
        #EXTINF:-1 tvg-id="one" group-title="News",News One
        http://example.com/one.m3u8
        #EXTINF:-1 tvg-id="two" group-title="Sport",Sport Two
        http://example.com/two.m3u8
    """.trimIndent()

    private val source = LiveSource(LiveSourceType.M3U, "http://example.com/list.m3u")

    @Test
    fun `source survives being saved and read back`() {
        val xtream = LiveSource(LiveSourceType.XTREAM, "http://host:8080", "me", "p\"w", "http://guide")
        assertEquals(xtream, LiveTvCodec.decodeSource(LiveTvCodec.encodeSource(xtream)))
        assertNull(LiveTvCodec.decodeSource("not json"))
        assertNull(LiveTvCodec.decodeSource(null))
        assertEquals(listOf("a", "b"), LiveTvCodec.decodeIds(LiveTvCodec.encodeIds(listOf("a", "b", "a"))))
    }

    @Test
    fun `saving a source loads its channels and remembers it`() = runTest(UnconfinedTestDispatcher()) {
        val storage = MemoryStorage()
        val controller = LiveTvController(storage, LiveTvLoader({ playlist }, { 0L }), backgroundScope, defaultSource = null)
        controller.ensureChannels()
        assertTrue(controller.state.value.ready)
        assertNull(controller.state.value.source)

        controller.setSource(source.copy(url = "  http://example.com/list.m3u "))
        advanceUntilIdle()
        Thread.sleep(200)

        val state = controller.state.value
        assertEquals(listOf("News One", "Sport Two"), state.channels.map(LiveChannel::name))
        assertEquals(listOf("News", "Sport"), state.groups)
        assertFalse(state.loading)
        assertEquals(source.copy(id = source.key), LiveTvCodec.decodeSources(storage.values["sources"]).single())
    }

    @Test
    fun `a failed load reports why`() = runTest(UnconfinedTestDispatcher()) {
        val controller = LiveTvController(MemoryStorage(), LiveTvLoader({ "<html>" }, { 0L }), backgroundScope, defaultSource = null)
        controller.start()
        controller.setSource(source)
        Thread.sleep(200)
        assertEquals(LiveTvError.NOT_A_PLAYLIST, controller.state.value.error)
        assertTrue(controller.state.value.channels.isEmpty())
    }

    @Test
    fun `favorites recent channels and the tab setting are kept`() = runTest(UnconfinedTestDispatcher()) {
        val storage = MemoryStorage()
        storage.values["source"] = LiveTvCodec.encodeSource(source)
        val first = LiveTvController(storage, LiveTvLoader({ playlist }, { 0L }), backgroundScope, defaultSource = null)
        first.ensureChannels()
        Thread.sleep(200)
        val ids = first.state.value.channels.map(LiveChannel::id)
        first.toggleFavorite(ids[1])
        first.markWatched(ids[0])
        first.markWatched(ids[1])
        first.markWatched(ids[0])
        first.setTabVisible(false)

        val second = LiveTvController(storage, LiveTvLoader({ playlist }, { 0L }), backgroundScope, defaultSource = null)
        second.ensureChannels()
        Thread.sleep(200)
        val state = second.state.value
        assertEquals(setOf(ids[1]), state.favorites)
        assertEquals(listOf(ids[0], ids[1]), state.recent)
        assertFalse(state.tabVisible)
        assertEquals(listOf("Sport Two"), LiveTvController.visibleChannels(state, "", LiveTvSelection.FAVORITES).map(LiveChannel::name))
        assertEquals(listOf("News One", "Sport Two"), LiveTvController.visibleChannels(state, "", LiveTvSelection.RECENT).map(LiveChannel::name))
        assertEquals(listOf("News One"), LiveTvController.visibleChannels(state, "", "News").map(LiveChannel::name))
        assertEquals(listOf("Sport Two"), LiveTvController.visibleChannels(state, "sport", LiveTvSelection.ALL).map(LiveChannel::name))
    }

    @Test
    fun `built-in channels show until the user adds a source and come back when it is removed`() = runTest(UnconfinedTestDispatcher()) {
        val storage = MemoryStorage()
        val builtIn = LiveSource(LiveSourceType.M3U, "http://example.com/free.m3u")
        val controller = LiveTvController(storage, LiveTvLoader({ playlist }, { 0L }), backgroundScope, defaultSource = builtIn)
        controller.ensureChannels()
        Thread.sleep(200)
        assertTrue(controller.state.value.usingDefault)
        assertEquals(2, controller.state.value.channels.size)
        assertNull(storage.values["sources"])

        controller.setSource(source)
        Thread.sleep(200)
        assertFalse(controller.state.value.usingDefault)
        assertEquals(source.copy(id = source.key), controller.state.value.source)

        controller.removeSource()
        Thread.sleep(200)
        assertTrue(controller.state.value.usingDefault)
        assertEquals(builtIn, controller.state.value.source)
        assertEquals(2, controller.state.value.channels.size)
    }

    @Test
    fun `several playlists load together and one that fails does not hide the others`() = runTest(UnconfinedTestDispatcher()) {
        val second = """
            #EXTM3U
            #EXTINF:-1 tvg-chno="7" group-title="News",News One HD
            http://other.example/one.m3u8
            #EXTINF:-1 group-title="Movies",Film Four
            http://example.com/one.m3u8
        """.trimIndent()
        val pages = mapOf(
            "http://example.com/list.m3u" to playlist,
            "http://other.example/list.m3u" to second,
        )
        val storage = MemoryStorage()
        val controller = LiveTvController(storage, LiveTvLoader({ pages[it] }, { 0L }), backgroundScope, defaultSource = null)
        controller.start()
        controller.addSource(source)
        controller.addSource(LiveSource(LiveSourceType.M3U, "http://other.example/list.m3u", name = "Other"))
        controller.addSource(LiveSource(LiveSourceType.M3U, "http://broken.example/list.m3u"))
        Thread.sleep(300)

        val state = controller.state.value
        assertEquals(3, state.sources.size)
        assertEquals(listOf("News One", "Sport Two", "News One HD", "Film Four"), state.channels.map(LiveChannel::name))
        // Every channel keeps its own id, even when two playlists share an address.
        assertEquals(4, state.channels.map(LiveChannel::id).toSet().size)
        assertEquals(setOf(state.sources[2].key), state.failedSources.keys)
        assertEquals(7, state.channels[2].number)
        assertEquals("Other", LiveTvController.sourceLabel(state, state.channels[2]))

        // The HD copy from the other playlist backs up the first channel.
        assertEquals(listOf("News One HD"), controller.alternatesFor(state.channels[0]).map(LiveChannel::name))

        controller.setSourceEnabled(state.sources[1].key, false)
        Thread.sleep(300)
        assertEquals(listOf("News One", "Sport Two"), controller.state.value.channels.map(LiveChannel::name))
        assertEquals(3, LiveTvCodec.decodeSources(storage.values["sources"]).size)

        controller.removeSource(state.sources[0].key)
        Thread.sleep(300)
        assertEquals(2, controller.state.value.sources.size)
    }

    @Test
    fun `a playlist saved by an older version is still read`() = runTest(UnconfinedTestDispatcher()) {
        val storage = MemoryStorage()
        storage.values["source"] = LiveTvCodec.encodeSource(source)
        val controller = LiveTvController(storage, LiveTvLoader({ playlist }, { 0L }), backgroundScope, defaultSource = null)
        controller.ensureChannels()
        Thread.sleep(200)
        assertEquals(listOf(source.copy(id = source.key)), controller.state.value.sources)
        assertEquals(2, controller.state.value.channels.size)
    }

    @Test
    fun `vpn settings are kept and only count as ready with a usable config`() = runTest(UnconfinedTestDispatcher()) {
        val storage = MemoryStorage()
        val controller = LiveTvController(storage, LiveTvLoader({ playlist }, { 0L }), backgroundScope, defaultSource = null)
        controller.start()
        assertFalse(controller.state.value.vpn.enabled)
        val wireGuard = "[Interface]\nPrivateKey = a\nAddress = 10.0.0.2/32\n[Peer]\nPublicKey = b\nEndpoint = vpn.example:51820"
        controller.setVpn(LiveVpnSettings(enabled = true, wireGuardConfig = wireGuard))
        assertTrue(controller.state.value.vpn.ready)
        assertFalse(LiveVpnSettings(enabled = true, wireGuardConfig = "nonsense").ready)
        assertFalse(LiveVpnSettings(enabled = false, wireGuardConfig = wireGuard).ready)

        val again = LiveTvController(storage, LiveTvLoader({ playlist }, { 0L }), backgroundScope, defaultSource = null)
        again.start()
        assertEquals(controller.state.value.vpn, again.state.value.vpn)
    }

    @Test
    fun `the device country picks the first group on the built-in channels`() {
        val state = LiveTvState(usingDefault = true, groups = listOf("Italy", "USA"))
        assertEquals("USA", LiveTvController.preferredGroup(state, "us"))
        assertNull(LiveTvController.preferredGroup(state, "NZ"))
        assertNull(LiveTvController.preferredGroup(state.copy(usingDefault = false), "US"))
    }
}
