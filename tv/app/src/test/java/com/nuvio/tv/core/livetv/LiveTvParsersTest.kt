package com.nuvio.tv.core.livetv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTvParsersTest {

    @Test
    fun `reads channels with logo group guide id and name containing commas`() {
        val playlist = M3uParser.parse(
            """
            #EXTM3U url-tvg="https://guide.example/a.xml,https://guide.example/b.xml.gz"
            #EXTINF:-1 tvg-id="news.one" tvg-logo="https://img.example/one.png" group-title="News, World",News One, HD
            https://stream.example/one.m3u8
            #EXTINF:-1,Plain Channel
            http://stream.example/plain.ts
            """.trimIndent()
        )
        assertEquals(listOf("https://guide.example/a.xml", "https://guide.example/b.xml.gz"), playlist.guideUrls)
        assertEquals(2, playlist.channels.size)
        val first = playlist.channels[0]
        assertEquals("News One, HD", first.name)
        assertEquals("https://stream.example/one.m3u8", first.url)
        assertEquals("https://img.example/one.png", first.logo)
        assertEquals("News, World", first.group)
        assertEquals("news.one", first.guideId)
        assertEquals("Plain Channel", playlist.channels[1].name)
        assertNull(playlist.channels[1].group)
    }

    @Test
    fun `collects request headers from vlc options and from the address suffix`() {
        val playlist = M3uParser.parse(
            """
            #EXTM3U
            #EXTINF:-1,With Options
            #EXTVLCOPT:http-user-agent=Custom/1.0
            #EXTVLCOPT:http-referrer=https://site.example/
            https://stream.example/a.m3u8
            #EXTINF:-1,With Suffix
            https://stream.example/b.m3u8|User-Agent=Other/2.0&Referer=https://other.example/
            #EXTINF:-1,No Headers
            https://stream.example/c.m3u8
            """.trimIndent()
        )
        assertEquals(
            mapOf("User-Agent" to "Custom/1.0", "Referer" to "https://site.example/"),
            playlist.channels[0].headers
        )
        assertEquals("https://stream.example/b.m3u8", playlist.channels[1].url)
        assertEquals(
            mapOf("User-Agent" to "Other/2.0", "Referer" to "https://other.example/"),
            playlist.channels[1].headers
        )
        assertTrue(playlist.channels[2].headers.isEmpty())
    }

    @Test
    fun `gives repeated addresses distinct ids and skips lines that are not addresses`() {
        val playlist = M3uParser.parse(
            """
            #EXTM3U
            #EXTINF:-1,A
            https://stream.example/same.m3u8
            #EXTINF:-1,B
            https://stream.example/same.m3u8
            #EXTINF:-1,Broken
            not a url
            #EXTGRP:Sports
            #EXTINF:-1,C
            https://stream.example/c.m3u8
            """.trimIndent()
        )
        assertEquals(listOf("A", "B", "C"), playlist.channels.map { it.name })
        assertEquals(3, playlist.channels.map { it.id }.toSet().size)
    }

    @Test
    fun `tells channel playlists apart from single streams and web pages`() {
        assertTrue(M3uParser.looksLikePlaylist("#EXTM3U\n#EXTINF:-1,A\nhttp://a/b.ts"))
        assertFalse(M3uParser.looksLikePlaylist("<html><body>Not found</body></html>"))
        assertTrue(M3uParser.isSingleStreamManifest("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nlow.m3u8"))
        assertFalse(M3uParser.isSingleStreamManifest("#EXTM3U\n#EXTINF:-1,A\nhttp://a/b.ts"))
    }

    @Test
    fun `filters by search group and favorites together`() {
        val channels = listOf(
            LiveChannel(id = "1", name = "News One", url = "u1", group = "News"),
            LiveChannel(id = "2", name = "Sports One", url = "u2", group = "Sports"),
            LiveChannel(id = "3", name = "News Two", url = "u3", group = "News"),
        )
        assertEquals(listOf("News", "Sports"), LiveTvFilters.groups(channels))
        assertEquals(listOf("1", "3"), LiveTvFilters.filter(channels, group = "News").map { it.id })
        assertEquals(listOf("1", "2"), LiveTvFilters.filter(channels, query = " one ").map { it.id })
        assertEquals(
            listOf("3"),
            LiveTvFilters.filter(channels, group = "News", favoritesOnly = true, favorites = setOf("2", "3")).map { it.id }
        )
    }

    private val xtream = LiveSource(
        type = LiveSourceType.XTREAM,
        url = "provider.example:8080/",
        username = "my user",
        password = "p@ss/word",
    )

    @Test
    fun `builds xtream addresses with a clean server and escaped login`() {
        assertEquals("http://provider.example:8080", XtreamApi.normalizeServer("provider.example:8080/"))
        assertEquals("https://p.example", XtreamApi.normalizeServer(" https://p.example/get.php?x=1 "))
        assertEquals(
            "http://provider.example:8080/player_api.php?username=my%20user&password=p%40ss%2Fword&action=get_live_streams",
            XtreamApi.streamsUrl(xtream)
        )
        assertEquals(
            "http://provider.example:8080/live/my%20user/p%40ss%2Fword/42.m3u8",
            XtreamApi.streamUrl(xtream, "42")
        )
    }

    @Test
    fun `reads the login out of a pasted xtream playlist link`() {
        val source = XtreamApi.sourceFromPlaylistLink(
            "http://provider.example:8080/get.php?username=bob&password=p%40ss&type=m3u_plus&output=ts"
        )
        assertEquals(
            LiveSource(LiveSourceType.XTREAM, "http://provider.example:8080", "bob", "p@ss"),
            source
        )
        assertNull(XtreamApi.sourceFromPlaylistLink("https://example.com/list.m3u"))
    }

    @Test
    fun `maps xtream streams to channels with their category names`() {
        val categories = XtreamApi.parseCategories(
            """[{"category_id":"7","category_name":"Sports"},{"category_id":8,"category_name":"News"}]"""
        )
        assertEquals(mapOf("7" to "Sports", "8" to "News"), categories)
        val channels = XtreamApi.parseStreams(
            """[
              {"stream_id":42,"name":"Sports One","stream_icon":"https://img.example/s.png","category_id":"7","epg_channel_id":"sports.one"},
              {"stream_id":43,"name":"","category_id":"8"},
              {"name":"No id"},
              {"stream_id":"44","name":"News","stream_icon":"","category_id":"8","epg_channel_id":null}
            ]""",
            categories,
            xtream,
        )
        assertEquals(listOf("xtream:42", "xtream:44"), channels.map { it.id })
        assertEquals("Sports", channels[0].group)
        assertEquals("sports.one", channels[0].guideId)
        assertEquals("42", channels[0].xtreamStreamId)
        assertEquals("http://provider.example:8080/live/my%20user/p%40ss%2Fword/42.m3u8", channels[0].url)
        assertNull(channels[1].logo)
        assertNull(channels[1].guideId)
    }

    @Test
    fun `survives a provider that answers with an error page`() {
        assertTrue(XtreamApi.parseCategories("<html>502</html>").isEmpty())
        assertTrue(XtreamApi.parseStreams("{}", emptyMap(), xtream).isEmpty())
        assertFalse(XtreamApi.isAuthenticated("nope"))
        assertTrue(XtreamApi.isAuthenticated("""{"user_info":{"auth":1,"status":"Active"}}"""))
        assertFalse(XtreamApi.isAuthenticated("""{"user_info":{"auth":0}}"""))
    }

    @Test
    fun `decodes the xtream short guide`() {
        val programmes = XtreamApi.parseShortGuide(
            """{"epg_listings":[
              {"title":"TGF0ZSBTaG93","start_timestamp":"1800003600","stop_timestamp":"1800007200"},
              {"title":"RXZlbmluZyBOZXdz","start_timestamp":"1800000000","stop_timestamp":"1800003600"}
            ]}"""
        )
        assertEquals(listOf("Evening News", "Late Show"), programmes.map { it.title })
        assertEquals(1_800_000_000_000L, programmes[0].startMs)
    }

    @Test
    fun `reads xmltv times with and without an offset`() {
        assertEquals(0L, XmltvParser.parseTime("19700101000000 +0000"))
        assertEquals(1_767_225_600_000L, XmltvParser.parseTime("20260101000000"))
        assertEquals(1_767_225_600_000L - 3_600_000L, XmltvParser.parseTime("20260101000000 +0100"))
        assertEquals(1_767_225_600_000L + 5 * 3_600_000L, XmltvParser.parseTime("202601010000 -0500"))
        assertNull(XmltvParser.parseTime("soon"))
    }

    @Test
    fun `keeps only what is on now and next for each channel`() {
        val base = 1_767_225_600_000L // 2026-01-01 00:00 UTC
        val xml = """
            <tv>
              <programme start="20251231230000 +0000" stop="20260101000000 +0000" channel="News.One"><title>Over</title></programme>
              <programme start="20260101000000 +0000" stop="20260101010000 +0000" channel="News.One"><title lang="en">Tom &amp; Friends</title></programme>
              <programme start="20260101020000 +0000" stop="20260101030000 +0000" channel="News.One"><title>Later</title></programme>
              <programme start="20260101010000 +0000" stop="20260101020000 +0000" channel="News.One"><title>Up Next</title></programme>
              <programme start="20260101003000 +0000" stop="20260101013000 +0000" channel="other"><title>Other Show</title></programme>
            </tv>
        """.trimIndent()
        val guide = XmltvParser.parseNowNext(xml, nowMs = base + 10 * 60_000L)
        assertEquals("Tom & Friends", guide["news.one"]?.now?.title)
        assertEquals("Up Next", guide["news.one"]?.next?.title)
        assertNull(guide["other"]?.now)
        assertEquals("Other Show", guide["other"]?.next?.title)
    }

    @Test
    fun `reads a guide handed over in small pieces and keeps only wanted channels`() {
        val base = 1_767_225_600_000L
        val xml = """
            <tv><channel id="a"/>
              <programme start="20260101000000 +0000" stop="20260101010000 +0000" channel="A"><title>On Now</title></programme>
              <programme start="20260101010000 +0000" stop="20260101020000 +0000" channel="A"><title>After</title></programme>
              <programme start="20260101000000 +0000" stop="20260101010000 +0000" channel="B"><title>Skipped</title></programme>
            </tv>
        """.trimIndent()
        val reader = XmltvParser.Reader(nowMs = base + 60_000L, wanted = setOf("a"))
        xml.chunked(7).forEach(reader::feed)
        assertEquals("On Now", reader.result()["a"]?.now?.title)
        assertEquals("After", reader.result()["a"]?.next?.title)
        assertNull(reader.result()["b"])
    }

    @Test
    fun `picks the guide file for a group of the built-in channels`() {
        val urls = listOf("https://host/epg_ripper_AL1.xml.gz", "https://host/epg_ripper_US_LOCALS2.xml.gz", "https://host/epg_ripper_US1.xml.gz", "https://host/epg_ripper_UK1.xml.gz")
        assertEquals("https://host/epg_ripper_US1.xml.gz", LiveTvDefaults.guideUrlForGroup("USA", urls))
        assertEquals("https://host/epg_ripper_UK1.xml.gz", LiveTvDefaults.guideUrlForGroup("UK", urls))
        assertNull(LiveTvDefaults.guideUrlForGroup("News", urls))
        assertEquals("UK", LiveTvDefaults.groupForCountry("gb"))
        assertEquals("USA", LiveTvDefaults.groupForCountry("US"))
    }

    private fun loader(pages: Map<String, String>, nowMs: Long = 0L) =
        LiveTvLoader(fetchText = { url -> pages[url] }, nowMs = { nowMs })

    private suspend fun errorOf(block: suspend () -> Unit): LiveTvError? = try {
        block()
        null
    } catch (e: LiveTvException) {
        e.error
    }

    @Test
    fun `loads an m3u playlist and reports why one cannot be loaded`() = kotlinx.coroutines.test.runTest {
        val m3u = LiveSource(LiveSourceType.M3U, "https://list.example/tv.m3u")
        val ok = loader(mapOf(m3u.url to "#EXTM3U\n#EXTINF:-1,One\nhttps://s.example/1.m3u8"))
        assertEquals(listOf("One"), ok.loadPlaylist(m3u).channels.map { it.name })

        assertEquals(LiveTvError.NETWORK, errorOf { loader(emptyMap()).loadPlaylist(m3u) })
        assertEquals(
            LiveTvError.NOT_A_PLAYLIST,
            errorOf { loader(mapOf(m3u.url to "<html>login</html>")).loadPlaylist(m3u) }
        )
        assertEquals(LiveTvError.NO_CHANNELS, errorOf { loader(mapOf(m3u.url to "#EXTM3U\n")).loadPlaylist(m3u) })
        assertEquals(
            LiveTvError.INVALID_SOURCE,
            errorOf { loader(emptyMap()).loadPlaylist(m3u.copy(url = "tv.m3u")) }
        )
    }

    @Test
    fun `treats a single stream address as one channel`() = kotlinx.coroutines.test.runTest {
        val source = LiveSource(LiveSourceType.M3U, "https://s.example/live/news.m3u8?token=1")
        val playlist = loader(
            mapOf(source.url to "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nlow.m3u8")
        ).loadPlaylist(source)
        assertEquals(1, playlist.channels.size)
        assertEquals("news.m3u8", playlist.channels[0].name)
        assertEquals(source.url, playlist.channels[0].url)
    }

    @Test
    fun `loads xtream channels and rejects a bad login`() = kotlinx.coroutines.test.runTest {
        val pages = mapOf(
            XtreamApi.loginUrl(xtream) to """{"user_info":{"auth":1}}""",
            XtreamApi.categoriesUrl(xtream) to """[{"category_id":"7","category_name":"Sports"}]""",
            XtreamApi.streamsUrl(xtream) to """[{"stream_id":42,"name":"Sports One","category_id":"7"}]""",
        )
        val playlist = loader(pages).loadPlaylist(xtream)
        assertEquals("Sports", playlist.channels.single().group)

        // Group names are optional: the list still loads when categories fail.
        val withoutCategories = loader(pages - XtreamApi.categoriesUrl(xtream)).loadPlaylist(xtream)
        assertNull(withoutCategories.channels.single().group)

        assertEquals(
            LiveTvError.LOGIN_FAILED,
            errorOf { loader(pages + (XtreamApi.loginUrl(xtream) to """{"user_info":{"auth":0}}""")).loadPlaylist(xtream) }
        )
        assertEquals(LiveTvError.INVALID_SOURCE, errorOf { loader(pages).loadPlaylist(xtream.copy(password = "")) })
    }

    @Test
    fun `matches guide entries to channels and prefers the guide address the user entered`() =
        kotlinx.coroutines.test.runTest {
            val base = 1_767_225_600_000L
            fun guide(title: String) =
                """<tv><programme start="20260101000000 +0000" stop="20260101010000 +0000" channel="News.One"><title>$title</title></programme></tv>"""
            val playlist = LivePlaylist(
                channels = listOf(
                    LiveChannel(id = "a", name = "News One", url = "u", guideId = "news.one"),
                    LiveChannel(id = "b", name = "No Guide", url = "v"),
                ),
                guideUrls = listOf("https://guide.example/list.xml"),
            )
            val pages = mapOf(
                "https://guide.example/list.xml" to guide("From Playlist"),
                "https://guide.example/mine.xml" to guide("From User"),
            )
            val source = LiveSource(LiveSourceType.M3U, "https://list.example/tv.m3u")
            val fromPlaylist = loader(pages, nowMs = base + 60_000L).loadGuide(source, playlist)
            assertEquals(setOf("a"), fromPlaylist.keys)
            assertEquals("From Playlist", fromPlaylist["a"]?.now?.title)

            val fromUser = loader(pages, nowMs = base + 60_000L)
                .loadGuide(source.copy(guideUrl = "https://guide.example/mine.xml"), playlist)
            assertEquals("From User", fromUser["a"]?.now?.title)

            assertTrue(loader(emptyMap()).loadGuide(source, playlist).isEmpty())
        }

    @Test
    fun `asks an xtream provider for one channel's programmes`() = kotlinx.coroutines.test.runTest {
        val channel = LiveChannel(id = "xtream:42", name = "Sports One", url = "u", xtreamStreamId = "42")
        val pages = mapOf(
            XtreamApi.shortGuideUrl(xtream, "42") to """{"epg_listings":[
              {"title":"RXZlbmluZyBOZXdz","start_timestamp":"1800000000","stop_timestamp":"1800003600"},
              {"title":"TGF0ZSBTaG93","start_timestamp":"1800003600","stop_timestamp":"1800007200"}
            ]}"""
        )
        val guide = loader(pages, nowMs = 1_800_000_600_000L).loadChannelGuide(xtream, channel)
        assertEquals("Evening News", guide?.now?.title)
        assertEquals("Late Show", guide?.next?.title)
        assertNull(loader(pages, nowMs = 1_900_000_000_000L).loadChannelGuide(xtream, channel))
    }

    @Test
    fun `keeps the next hours of programmes with descriptions for the guide grid`() {
        val base = 1_767_225_600_000L
        val xml = """
            <tv>
              <programme start="20260101000000 +0000" stop="20260101010000 +0000" channel="a"><title>One</title><desc>About one</desc></programme>
              <programme start="20260101010000 +0000" stop="20260101020000 +0000" channel="a"><title>Two</title></programme>
              <programme start="20260101020000 +0000" stop="20260101030000 +0000" channel="a"><title>Three</title></programme>
              <programme start="20260102020000 +0000" stop="20260102030000 +0000" channel="a"><title>Tomorrow</title></programme>
            </tv>
        """.trimIndent()
        val entry = XmltvParser.parseNowNext(xml, nowMs = base + 60_000L)["a"]!!
        assertEquals(listOf("One", "Two", "Three"), entry.upcoming.map { it.title })
        assertEquals("About one", entry.now?.description)
        assertEquals("Two", entry.next?.title)
    }

    @Test
    fun `reads channel numbers and xtream account details`() {
        val playlist = M3uParser.parse("#EXTM3U\n#EXTINF:-1 tvg-chno=\"12\",Twelve\nhttp://a/12.ts")
        assertEquals(12, playlist.channels.single().number)
        val account = XtreamApi.parseAccount("""{"user_info":{"auth":1,"exp_date":"1800000000","max_connections":"2"}}""")
        assertEquals(1_800_000_000_000L, account?.expiresAtMs)
        assertEquals(2, account?.maxConnections)
        assertTrue(XtreamApi.guideFileUrl(xtream).contains("/xmltv.php?username="))
    }

    @Test
    fun `groups copies of a channel and keeps debrid away from the vpn`() {
        assertEquals("espn", LiveTvVariants.key("US: ESPN HD"))
        assertEquals("espn", LiveTvVariants.key("[US] ESPN (FHD)"))
        assertEquals("espn 2", LiveTvVariants.key("ESPN 2 4K"))
        assertEquals("fox news", LiveTvVariants.key("Fox News"))
        val one = LiveChannel(id = "1", name = "ESPN HD", url = "http://a/1", sourceId = "x")
        val channels = listOf(
            one,
            LiveChannel(id = "2", name = "UK | ESPN SD", url = "http://b/2", sourceId = "y"),
            LiveChannel(id = "3", name = "ESPN", url = "http://a/3", sourceId = "x"),
            LiveChannel(id = "4", name = "ESPN 2", url = "http://a/4", sourceId = "x"),
        )
        assertEquals(listOf("3", "2"), LiveTvVariants.alternatesFor(one, channels).map { it.id })

        assertTrue(LiveVpnGuard.isDebrid("https://abc.download.real-debrid.com/d/x"))
        assertTrue(LiveVpnGuard.isDebrid("https://store-1.torbox.app/file"))
        assertFalse(LiveVpnGuard.isDebrid("http://iptv.example:8080/live/u/p/1.m3u8"))
        assertFalse(LiveVpnGuard.mayRoute("https://x.premiumize.me/f"))
        assertTrue(LiveVpnGuard.mayRoute("http://iptv.example/live.ts"))

        assertEquals(LiveProxy("10.0.0.1", 8080), LiveVpnGuard.parseProxy("10.0.0.1:8080"))
        assertEquals(LiveProxy("p.example", 3128, "me", "p@ss"), LiveVpnGuard.parseProxy("http://me:p%40ss@p.example:3128"))
        assertNull(LiveVpnGuard.parseProxy("socks5://p.example:1080"))
        assertNull(LiveVpnGuard.parseProxy("p.example"))
        assertEquals("http://me:p%40ss@p.example:3128", LiveProxy("p.example", 3128, "me", "p@ss").asUrl())
    }
}
