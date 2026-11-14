package com.nuvio.tv.core.livetv

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Where Live TV keeps its settings. Each app stores them per profile, on the device only. */
interface LiveTvStorage {
    suspend fun read(key: String): String?
    suspend fun write(key: String, value: String?)
}

data class LiveTvState(
    /** True once the saved settings have been read. */
    val ready: Boolean = false,
    /** Whether the Live TV tab is shown in the app's navigation. */
    val tabVisible: Boolean = true,
    /** The first playlist being shown: the user's own, or the built-in one. Null when there is nothing to show. */
    val source: LiveSource? = null,
    /** Every playlist the user added, in the order they were added, including switched-off ones. */
    val sources: List<LiveSource> = emptyList(),
    /** True while the built-in free channels are shown because the user has not added a source. */
    val usingDefault: Boolean = false,
    val loading: Boolean = false,
    val error: LiveTvError? = null,
    /** Playlists that could not be loaded while others could, by [LiveSource.key]. */
    val failedSources: Map<String, LiveTvError> = emptyMap(),
    /** Account details from Xtream providers, by [LiveSource.key]. */
    val accounts: Map<String, LiveAccountInfo> = emptyMap(),
    val channels: List<LiveChannel> = emptyList(),
    val groups: List<String> = emptyList(),
    val favorites: Set<String> = emptySet(),
    /** Most recently watched first. */
    val recent: List<String> = emptyList(),
    /** What is on now, next and in the hours after, by channel id. */
    val guide: Map<String, LiveNowNext> = emptyMap(),
    /** Built-in channels that did not answer when checked. They are listed last. */
    val offline: Set<String> = emptySet(),
    /** The Live TV VPN. Used for Live TV playback only. */
    val vpn: LiveVpnSettings = LiveVpnSettings(),
)

/** Which channels the list shows. Anything else is a group name. */
object LiveTvSelection {
    const val ALL = LiveTvFilters.ALL
    const val FAVORITES = "\u0000favorites"
    const val RECENT = "\u0000recent"
}

/**
 * Everything Live TV does apart from drawing the screen: saved playlists, channel list,
 * favorites, recent channels, the programme guide and the Live TV VPN settings. The phone and
 * TV apps share this file.
 */
class LiveTvController(
    private val storage: LiveTvStorage,
    private val loader: LiveTvLoader,
    private val scope: CoroutineScope,
    /** Channels shown until the user adds a source of their own. */
    private val defaultSource: LiveSource? = LiveTvDefaults.source,
    /** Whether a channel's stream answers: true, false, or null when that cannot be told. */
    private val probe: (suspend (LiveChannel) -> Boolean?)? = null,
) {
    private val _state = MutableStateFlow(LiveTvState())
    val state: StateFlow<LiveTvState> = _state.asStateFlow()

    private var startJob: Job? = null
    private var loadJob: Job? = null
    private var channelsRequested = false
    private val guideRequested = HashSet<String>()
    private val groupGuideRequested = HashSet<String>()
    private val groupCheckRequested = HashSet<String>()
    private var playlist: LivePlaylist? = null

    /** Reads the saved settings. Safe to call more than once. */
    fun start() {
        if (startJob != null) return
        startJob = scope.launch {
            val saved = LiveTvCodec.decodeSources(storage.read(KEY_SOURCES)).ifEmpty {
                // Older versions kept a single playlist.
                listOfNotNull(LiveTvCodec.decodeSource(storage.read(KEY_SOURCE))?.let(::cleaned))
            }
            val favorites = LiveTvCodec.decodeIds(storage.read(KEY_FAVORITES)).toSet()
            val recent = LiveTvCodec.decodeIds(storage.read(KEY_RECENT)).take(MAX_RECENT)
            val tabVisible = storage.read(KEY_TAB_VISIBLE) != "false"
            val vpn = LiveTvCodec.decodeVpn(storage.read(KEY_VPN))
            _state.update {
                withSources(it, saved).copy(
                    ready = true,
                    tabVisible = tabVisible,
                    favorites = favorites,
                    recent = recent,
                    vpn = vpn,
                )
            }
            if (channelsRequested) loadChannels()
        }
    }

    /** Forgets everything held in memory, for example when the profile changes. */
    fun reset() {
        startJob?.cancel()
        loadJob?.cancel()
        startJob = null
        loadJob = null
        channelsRequested = false
        guideRequested.clear()
        groupGuideRequested.clear()
        groupCheckRequested.clear()
        playlist = null
        _state.value = LiveTvState()
    }

    /** Loads the channel list the first time the Live TV screen opens. */
    fun ensureChannels() {
        start()
        if (channelsRequested) return
        channelsRequested = true
        if (_state.value.ready) loadChannels()
    }

    fun refresh() {
        channelsRequested = true
        loadChannels()
    }

    /** Replaces every saved playlist with [source]. */
    fun setSource(source: LiveSource) = saveSources(listOf(cleaned(source)))

    /** Adds a playlist, or updates it when the same one is already saved. */
    fun addSource(source: LiveSource) {
        val added = cleaned(source)
        val current = _state.value.sources
        val index = current.indexOfFirst { it.key == added.key }
        saveSources(if (index >= 0) current.toMutableList().also { it[index] = added } else current + added)
    }

    /** Saves [updated] in place of the playlist saved under [key]. */
    fun updateSource(key: String, updated: LiveSource) {
        val current = _state.value.sources
        val index = current.indexOfFirst { it.key == key }
        if (index < 0) {
            addSource(updated)
            return
        }
        // Keep the identifier, so favorites saved for its channels stay attached.
        val cleanedSource = cleaned(updated.copy(id = current[index].key))
        val list = current.toMutableList().also { it[index] = cleanedSource }
        saveSources(list.distinctBy(LiveSource::key))
    }

    fun setSourceEnabled(key: String, enabled: Boolean) {
        saveSources(_state.value.sources.map { if (it.key == key) it.copy(enabled = enabled) else it })
    }

    /** Removes the playlist saved under [key]. */
    fun removeSource(key: String) = saveSources(_state.value.sources.filter { it.key != key })

    /** Drops every playlist of the user's and goes back to the built-in channels, when there are any. */
    fun removeSource() = saveSources(emptyList())

    fun setTabVisible(visible: Boolean) {
        _state.update { it.copy(tabVisible = visible) }
        scope.launch { storage.write(KEY_TAB_VISIBLE, visible.toString()) }
    }

    fun setVpn(settings: LiveVpnSettings) {
        val cleanedSettings = settings.copy(
            wireGuardConfig = LiveVpnGuard.normalizeWireGuard(settings.wireGuardConfig),
            proxyUrl = settings.proxyUrl.trim(),
        )
        _state.update { it.copy(vpn = cleanedSettings) }
        scope.launch { storage.write(KEY_VPN, LiveTvCodec.encodeVpn(cleanedSettings)) }
    }

    fun toggleFavorite(channelId: String) {
        val favorites = _state.value.favorites.let { if (channelId in it) it - channelId else it + channelId }
        _state.update { it.copy(favorites = favorites) }
        scope.launch { storage.write(KEY_FAVORITES, LiveTvCodec.encodeIds(favorites)) }
    }

    /** Remembers [channelId] as the channel watched last. */
    fun markWatched(channelId: String) {
        val recent = (listOf(channelId) + _state.value.recent.filter { it != channelId }).take(MAX_RECENT)
        _state.update { it.copy(recent = recent) }
        scope.launch { storage.write(KEY_RECENT, LiveTvCodec.encodeIds(recent)) }
    }

    /** Other copies of [channel] to fall back on when it does not play, best first. */
    fun alternatesFor(channel: LiveChannel): List<LiveChannel> {
        val state = _state.value
        return LiveTvVariants.alternatesFor(channel, state.channels, state.offline)
    }

    /**
     * Asks an Xtream provider what is on [channel] now, once per channel. Playlists with a guide
     * file are covered by the guide loaded with the channel list instead.
     */
    fun requestChannelGuide(channel: LiveChannel) {
        val source = sourceOf(channel) ?: return
        if (source.type != LiveSourceType.XTREAM || channel.xtreamStreamId == null) return
        if (channel.id in _state.value.guide || !guideRequested.add(channel.id)) return
        scope.launch {
            val entry = runCatching { loader.loadChannelGuide(source, channel) }.getOrNull() ?: return@launch
            if (sourceOf(channel) == source) _state.update { it.copy(guide = it.guide + (channel.id to entry)) }
        }
    }

    /**
     * Loads the guide for one group of a worldwide playlist, such as the built-in channels, the
     * first time that group is opened. Other playlists get their whole guide with the channels.
     */
    fun requestGroupGuide(group: String) {
        if (!_state.value.usingDefault) return
        val source = _state.value.source ?: return
        val loaded = playlist ?: return
        if (!loader.hasGuidePerGroup(source, loaded) || !groupGuideRequested.add(group)) return
        scope.launch {
            val guide = withContext(Dispatchers.Default) {
                runCatching { loader.loadGroupGuide(source, loaded, group) }.getOrDefault(emptyMap())
            }
            if (guide.isNotEmpty() && playlist === loaded) _state.update { it.copy(guide = it.guide + guide) }
        }
    }

    /**
     * Checks which channels of a group answer, the first time that group is opened, so the ones
     * that do not can be listed last. Only the built-in channels are checked: a provider's own
     * list often allows a single connection, and checking it could get the account blocked.
     */
    fun requestGroupCheck(group: String) {
        val check = probe ?: return
        val loaded = playlist ?: return
        if (!_state.value.usingDefault || !groupCheckRequested.add(group)) return
        val channels = loaded.channels.filter { it.group == group }.take(MAX_CHECKED_PER_GROUP)
        if (channels.isEmpty()) return
        scope.launch {
            val gate = Semaphore(CHECK_PARALLELISM)
            channels.map { channel ->
                launch {
                    val answered = gate.withPermit {
                        withContext(Dispatchers.Default) { runCatching { check(channel) }.getOrNull() }
                    }
                    if (answered == false && playlist === loaded) {
                        _state.update { it.copy(offline = it.offline + channel.id) }
                    }
                }
            }
        }
    }

    private fun sourceOf(channel: LiveChannel): LiveSource? {
        val state = _state.value
        if (state.usingDefault) return state.source
        return state.sources.firstOrNull { it.key == channel.sourceId } ?: state.source.takeIf { channel.sourceId.isEmpty() }
    }

    private fun cleaned(source: LiveSource): LiveSource {
        val trimmed = source.copy(
            url = source.url.trim(),
            username = source.username.trim(),
            password = source.password.trim(),
            guideUrl = source.guideUrl.trim(),
            name = source.name.trim(),
        )
        return trimmed.copy(id = trimmed.key)
    }

    private fun withSources(state: LiveTvState, sources: List<LiveSource>): LiveTvState {
        val shown = sources.firstOrNull(LiveSource::enabled)
        val source = shown ?: defaultSource.takeIf { sources.isEmpty() }
        return state.copy(sources = sources, source = source, usingDefault = sources.isEmpty() && source != null)
    }

    private fun saveSources(sources: List<LiveSource>) {
        loadJob?.cancel()
        playlist = null
        _state.update {
            withSources(it, sources).copy(
                loading = false,
                error = null,
                failedSources = emptyMap(),
                accounts = emptyMap(),
                channels = emptyList(),
                groups = emptyList(),
                guide = emptyMap(),
                offline = emptySet(),
            )
        }
        scope.launch {
            storage.write(KEY_SOURCES, if (sources.isEmpty()) null else LiveTvCodec.encodeSources(sources))
            storage.write(KEY_SOURCE, null)
        }
        if (_state.value.source != null) refresh()
    }

    /** The playlists to load: the user's switched-on ones, or the built-in one. */
    private fun sourcesToLoad(state: LiveTvState): List<LiveSource> =
        if (state.usingDefault) listOfNotNull(state.source) else state.sources.filter(LiveSource::enabled)

    private fun loadChannels() {
        val sources = sourcesToLoad(_state.value)
        if (sources.isEmpty()) return
        loadJob?.cancel()
        guideRequested.clear()
        groupGuideRequested.clear()
        groupCheckRequested.clear()
        playlist = null
        _state.update { it.copy(loading = true, error = null, failedSources = emptyMap()) }
        loadJob = scope.launch {
            try {
                val results = withContext(Dispatchers.Default) {
                    sources.map { source ->
                        async {
                            try {
                                Result.success(loader.loadPlaylist(source))
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Result.failure(e)
                            }
                        }
                    }.awaitAll()
                }
                val loaded = ArrayList<Pair<LiveSource, LivePlaylist>>()
                val failed = LinkedHashMap<String, LiveTvError>()
                val seenIds = HashSet<String>()
                results.forEachIndexed { index, result ->
                    val source = sources[index]
                    result.fold(
                        onSuccess = { list ->
                            // The same address or stream id can appear in two playlists.
                            val channels = list.channels.map { channel ->
                                if (seenIds.add(channel.id)) channel else {
                                    val id = "${source.key}|${channel.id}"
                                    seenIds.add(id)
                                    channel.copy(id = id)
                                }
                            }
                            loaded += source to list.copy(channels = channels)
                        },
                        onFailure = { failed[source.key] = (it as? LiveTvException)?.error ?: LiveTvError.NETWORK },
                    )
                }
                if (loaded.isEmpty()) {
                    fail(failed.values.firstOrNull() ?: LiveTvError.NETWORK)
                    return@launch
                }
                val merged = LivePlaylist(
                    channels = loaded.flatMap { it.second.channels },
                    guideUrls = loaded.flatMap { it.second.guideUrls }.distinct(),
                )
                this@LiveTvController.playlist = merged
                val groups = LiveTvFilters.groups(merged.channels)
                val accounts = loaded.mapNotNull { (source, list) -> list.account?.let { source.key to it } }.toMap()
                _state.update {
                    it.copy(
                        loading = false,
                        error = null,
                        failedSources = failed,
                        accounts = accounts,
                        channels = merged.channels,
                        groups = groups,
                        guide = emptyMap(),
                        offline = emptySet(),
                    )
                }
                loaded.forEach { (source, list) ->
                    val guide = withContext(Dispatchers.Default) {
                        runCatching { loader.loadGuide(source, list) }.getOrDefault(emptyMap())
                    }
                    if (guide.isNotEmpty() && playlist === merged) _state.update { it.copy(guide = guide + it.guide) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: LiveTvException) {
                fail(e.error)
            } catch (e: Exception) {
                fail(LiveTvError.NETWORK)
            }
        }
    }

    private fun fail(error: LiveTvError) {
        _state.update { it.copy(loading = false, error = error, channels = emptyList(), groups = emptyList(), guide = emptyMap()) }
    }

    companion object {
        const val MAX_RECENT = 12
        private const val MAX_CHECKED_PER_GROUP = 200
        private const val CHECK_PARALLELISM = 6
        private const val KEY_SOURCE = "source"
        private const val KEY_SOURCES = "sources"
        private const val KEY_VPN = "vpn"
        private const val KEY_FAVORITES = "favorites"
        private const val KEY_RECENT = "recent"
        private const val KEY_TAB_VISIBLE = "tab_visible"

        /** The channels to list for a search text and a [LiveTvSelection]. */
        fun visibleChannels(state: LiveTvState, query: String, selection: String): List<LiveChannel> {
            val channels = matchingChannels(state, query, selection)
            if (state.offline.isEmpty()) return channels
            // A stable sort keeps the playlist order within the working and the silent channels.
            return channels.sortedBy { it.id in state.offline }
        }

        fun isOffline(state: LiveTvState, channel: LiveChannel): Boolean = channel.id in state.offline

        private fun matchingChannels(state: LiveTvState, query: String, selection: String): List<LiveChannel> = when (selection) {
            LiveTvSelection.FAVORITES ->
                LiveTvFilters.filter(state.channels, query, favoritesOnly = true, favorites = state.favorites)
            LiveTvSelection.RECENT -> {
                val byId = state.channels.associateBy(LiveChannel::id)
                LiveTvFilters.filter(state.recent.mapNotNull(byId::get), query)
            }
            else -> LiveTvFilters.filter(state.channels, query, group = selection)
        }

        /** The programmes of a channel that overlap the time from [fromMs] to [toMs], for the guide grid. */
        fun programmesBetween(state: LiveTvState, channel: LiveChannel, fromMs: Long, toMs: Long): List<LiveProgramme> =
            state.guide[channel.id]?.upcoming.orEmpty().filter { it.stopMs > fromMs && it.startMs < toMs }

        /** The playlist a channel came from, for showing its name. */
        fun sourceLabel(state: LiveTvState, channel: LiveChannel): String? =
            if (state.sources.size < 2) null else state.sources.firstOrNull { it.key == channel.sourceId }?.label

        /** The group to open first on the built-in channels: the one for the device's country. */
        fun preferredGroup(state: LiveTvState, countryCode: String?): String? {
            if (!state.usingDefault) return null
            val group = LiveTvDefaults.groupForCountry(countryCode) ?: return null
            return group.takeIf { it in state.groups }
        }

        /** Guide text for a channel row, or null when nothing is known. */
        fun nowTitle(state: LiveTvState, channel: LiveChannel): String? =
            state.guide[channel.id]?.now?.title?.takeIf(String::isNotBlank)

        fun nextTitle(state: LiveTvState, channel: LiveChannel): String? =
            state.guide[channel.id]?.next?.title?.takeIf(String::isNotBlank)
    }
}
