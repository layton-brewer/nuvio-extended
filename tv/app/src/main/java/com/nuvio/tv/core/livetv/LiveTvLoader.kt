package com.nuvio.tv.core.livetv

enum class LiveTvError {
    /** The address or login is missing or not a web address. */
    INVALID_SOURCE,

    /** The provider could not be reached or answered with an error. */
    NETWORK,

    /** The address answered, but not with a channel playlist. */
    NOT_A_PLAYLIST,

    /** The Xtream provider rejected the username or password. */
    LOGIN_FAILED,

    /** The playlist loaded but holds no channels. */
    NO_CHANNELS,
}

class LiveTvException(val error: LiveTvError) : Exception(error.name)

/**
 * Loads channels and programme guides for a [LiveSource]. Networking is passed in, so the phone
 * and TV apps share this logic and it can be tested without a network.
 *
 * @param fetchText downloads an address and returns its text, or null when the request failed.
 * Guide files compressed with gzip should come back already decompressed.
 * @param streamText downloads an address and hands its text over piece by piece, returning false
 * when that is not possible. Used for guides, which can be far too large to hold in memory.
 */
class LiveTvLoader(
    private val fetchText: suspend (url: String) -> String?,
    private val nowMs: () -> Long,
    private val streamText: suspend (url: String, onChunk: (String) -> Unit) -> Boolean = { _, _ -> false },
) {

    /** Channels for [source]. Throws [LiveTvException] with the reason when they cannot be loaded. */
    suspend fun loadPlaylist(source: LiveSource): LivePlaylist {
        val playlist = when (source.type) {
            LiveSourceType.M3U -> loadM3u(source)
            LiveSourceType.XTREAM -> loadXtream(source)
        }
        val key = source.key
        return playlist.copy(channels = playlist.channels.map { it.copy(sourceId = key) })
    }

    private suspend fun loadM3u(source: LiveSource): LivePlaylist {
        val url = source.url.trim()
        if (!M3uParser.isHttpUrl(url)) throw LiveTvException(LiveTvError.INVALID_SOURCE)
        val body = fetchText(url) ?: throw LiveTvException(LiveTvError.NETWORK)
        if (!M3uParser.looksLikePlaylist(body)) throw LiveTvException(LiveTvError.NOT_A_PLAYLIST)
        if (M3uParser.isSingleStreamManifest(body)) {
            // The address is one stream, not a list. Offer it as a single channel.
            val name = url.substringBefore('?').trimEnd('/').substringAfterLast('/').ifBlank { url }
            return LivePlaylist(channels = listOf(LiveChannel(id = url, name = name, url = url)))
        }
        val playlist = M3uParser.parse(body)
        if (playlist.channels.isEmpty()) throw LiveTvException(LiveTvError.NO_CHANNELS)
        return playlist
    }

    private suspend fun loadXtream(source: LiveSource): LivePlaylist {
        if (XtreamApi.normalizeServer(source.url).isEmpty() || source.username.isBlank() || source.password.isBlank()) {
            throw LiveTvException(LiveTvError.INVALID_SOURCE)
        }
        val login = fetchText(XtreamApi.loginUrl(source)) ?: throw LiveTvException(LiveTvError.NETWORK)
        if (!XtreamApi.isAuthenticated(login)) throw LiveTvException(LiveTvError.LOGIN_FAILED)
        // Categories only add group names, so a failure there should not block the channel list.
        val categories = fetchText(XtreamApi.categoriesUrl(source))?.let(XtreamApi::parseCategories).orEmpty()
        val streams = fetchText(XtreamApi.streamsUrl(source)) ?: throw LiveTvException(LiveTvError.NETWORK)
        val channels = XtreamApi.parseStreams(streams, categories, source)
        if (channels.isEmpty()) throw LiveTvException(LiveTvError.NO_CHANNELS)
        // Providers publish their whole guide as one XMLTV file.
        return LivePlaylist(
            channels = channels,
            guideUrls = listOf(XtreamApi.guideFileUrl(source)),
            account = XtreamApi.parseAccount(login),
        )
    }

    /** True when [playlist] announces so many guide files that they are loaded one group at a time. */
    fun hasGuidePerGroup(source: LiveSource, playlist: LivePlaylist): Boolean =
        !M3uParser.isHttpUrl(source.guideUrl.trim()) && playlist.guideUrls.size > MAX_ANNOUNCED_GUIDE_FILES

    /**
     * What is on now and next for every channel that the guide knows, keyed by [LiveChannel.id].
     * Uses the guide address the user entered, otherwise the ones the playlist announced.
     * Returns an empty map when there is no guide or it cannot be read.
     */
    suspend fun loadGuide(source: LiveSource, playlist: LivePlaylist): Map<String, LiveNowNext> {
        // A playlist that lists dozens of guide files is a worldwide list; those load per group.
        if (hasGuidePerGroup(source, playlist)) return emptyMap()
        val guideUrls = source.guideUrl.trim().takeIf(M3uParser::isHttpUrl)?.let(::listOf) ?: playlist.guideUrls
        return readGuides(guideUrls.take(MAX_GUIDE_FILES), playlist.channels)
    }

    /** The guide for the channels of one [group] of a worldwide playlist. */
    suspend fun loadGroupGuide(source: LiveSource, playlist: LivePlaylist, group: String): Map<String, LiveNowNext> {
        if (!hasGuidePerGroup(source, playlist)) return emptyMap()
        val url = LiveTvDefaults.guideUrlForGroup(group, playlist.guideUrls) ?: return emptyMap()
        return readGuides(listOf(url), playlist.channels.filter { it.group == group })
    }

    private suspend fun readGuides(urls: List<String>, channels: List<LiveChannel>): Map<String, LiveNowNext> {
        val wanted = channels.mapNotNull { it.guideId?.lowercase() }.toSet()
        if (urls.isEmpty() || wanted.isEmpty()) return emptyMap()
        val byGuideId = HashMap<String, LiveNowNext>()
        for (url in urls) {
            val reader = XmltvParser.Reader(nowMs(), wanted)
            if (!streamText(url, reader::feed)) reader.feed(fetchText(url) ?: continue)
            reader.result().forEach { (guideId, entry) ->
                if (guideId !in byGuideId) byGuideId[guideId] = entry
            }
        }
        if (byGuideId.isEmpty()) return emptyMap()
        val result = HashMap<String, LiveNowNext>()
        channels.forEach { channel ->
            val entry = channel.guideId?.lowercase()?.let(byGuideId::get) ?: return@forEach
            result[channel.id] = entry
        }
        return result
    }

    /** What is on now and next for one Xtream channel, asked from the provider on demand. */
    suspend fun loadChannelGuide(source: LiveSource, channel: LiveChannel): LiveNowNext? {
        if (source.type != LiveSourceType.XTREAM) return null
        val streamId = channel.xtreamStreamId ?: return null
        val body = fetchText(XtreamApi.shortGuideUrl(source, streamId)) ?: return null
        return LiveNowNext.of(XtreamApi.parseShortGuide(body), nowMs())
    }

    private companion object {
        const val MAX_GUIDE_FILES = 2
        const val MAX_ANNOUNCED_GUIDE_FILES = 6
    }
}
