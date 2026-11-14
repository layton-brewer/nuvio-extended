package com.nuvio.app.features.livetv

import androidx.compose.foundation.background
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.Chip
import com.nuvio.app.core.ui.NuvioInputField
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioSurfaceCard
import com.nuvio.app.core.ui.PlatformBackHandler
import com.nuvio.app.core.format.formatLocalDateTime
import com.nuvio.app.core.format.formatLocalTime
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.delay
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.live_tv_not_responding
import nuvio.composeapp.generated.resources.app_brand_name
import nuvio.composeapp.generated.resources.live_tv_all
import nuvio.composeapp.generated.resources.live_tv_cancel
import nuvio.composeapp.generated.resources.live_tv_empty
import nuvio.composeapp.generated.resources.live_tv_error_invalid
import nuvio.composeapp.generated.resources.live_tv_error_login
import nuvio.composeapp.generated.resources.live_tv_error_network
import nuvio.composeapp.generated.resources.live_tv_error_no_channels
import nuvio.composeapp.generated.resources.live_tv_error_not_playlist
import nuvio.composeapp.generated.resources.live_tv_favorites
import nuvio.composeapp.generated.resources.live_tv_field_guide
import nuvio.composeapp.generated.resources.live_tv_field_password
import nuvio.composeapp.generated.resources.live_tv_field_playlist
import nuvio.composeapp.generated.resources.live_tv_field_server
import nuvio.composeapp.generated.resources.live_tv_field_username
import nuvio.composeapp.generated.resources.live_tv_loading
import nuvio.composeapp.generated.resources.live_tv_next
import nuvio.composeapp.generated.resources.live_tv_recent
import nuvio.composeapp.generated.resources.live_tv_refresh
import nuvio.composeapp.generated.resources.live_tv_retry
import nuvio.composeapp.generated.resources.live_tv_save
import nuvio.composeapp.generated.resources.live_tv_search
import nuvio.composeapp.generated.resources.live_tv_setup_body
import nuvio.composeapp.generated.resources.live_tv_setup_title
import nuvio.composeapp.generated.resources.live_tv_source
import nuvio.composeapp.generated.resources.live_tv_type_m3u
import nuvio.composeapp.generated.resources.live_tv_type_xtream
import nuvio.composeapp.generated.resources.live_tv_guide
import nuvio.composeapp.generated.resources.live_tv_channels
import nuvio.composeapp.generated.resources.live_tv_settings
import nuvio.composeapp.generated.resources.live_tv_playlists
import nuvio.composeapp.generated.resources.live_tv_playlists_body
import nuvio.composeapp.generated.resources.live_tv_builtin_note
import nuvio.composeapp.generated.resources.live_tv_add_playlist
import nuvio.composeapp.generated.resources.live_tv_edit
import nuvio.composeapp.generated.resources.live_tv_turn_on
import nuvio.composeapp.generated.resources.live_tv_turn_off
import nuvio.composeapp.generated.resources.live_tv_delete
import nuvio.composeapp.generated.resources.live_tv_back
import nuvio.composeapp.generated.resources.live_tv_field_name
import nuvio.composeapp.generated.resources.live_tv_playlist_off
import nuvio.composeapp.generated.resources.live_tv_playlist_failed
import nuvio.composeapp.generated.resources.live_tv_expires
import nuvio.composeapp.generated.resources.live_tv_connections
import nuvio.composeapp.generated.resources.live_tv_some_failed
import nuvio.composeapp.generated.resources.live_tv_guide_no_info
import nuvio.composeapp.generated.resources.live_tv_vpn_title
import nuvio.composeapp.generated.resources.live_tv_vpn_body
import nuvio.composeapp.generated.resources.live_tv_vpn_switch
import nuvio.composeapp.generated.resources.live_tv_vpn_wireguard
import nuvio.composeapp.generated.resources.live_tv_vpn_proxy
import nuvio.composeapp.generated.resources.live_tv_vpn_wireguard_field
import nuvio.composeapp.generated.resources.live_tv_vpn_proxy_field
import nuvio.composeapp.generated.resources.live_tv_vpn_save
import nuvio.composeapp.generated.resources.live_tv_vpn_unavailable
import nuvio.composeapp.generated.resources.live_tv_vpn_invalid
import nuvio.composeapp.generated.resources.live_tv_vpn_switch_on
import nuvio.composeapp.generated.resources.live_tv_vpn_ready
import nuvio.composeapp.generated.resources.nav_live_tv
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private const val GUIDE_REQUEST_DELAY_MS = 450L

private enum class LiveTvPage { CHANNELS, GUIDE, SETTINGS, EDIT }

@Composable
fun LiveTvScreen(
    modifier: Modifier = Modifier,
    onPlayChannel: (LiveChannel) -> Unit,
) {
    val controller = LiveTvRepository.controller
    val state by controller.state.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf(LiveTvPage.CHANNELS) }
    var editingKey by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var selection by rememberSaveable { mutableStateOf(LiveTvSelection.ALL) }
    val guideScroll = rememberScrollState()

    LaunchedEffect(controller) { controller.ensureChannels() }
    PlatformBackHandler(enabled = page != LiveTvPage.CHANNELS) {
        page = if (page == LiveTvPage.EDIT) LiveTvPage.SETTINGS else LiveTvPage.CHANNELS
    }

    val nothingToShow = state.ready && state.source == null
    val showEdit = state.ready && (page == LiveTvPage.EDIT || (nothingToShow && state.sources.isEmpty()))
    val showSettings = state.ready && !showEdit && (page == LiveTvPage.SETTINGS || nothingToShow)
    val channels = remember(state.channels, state.favorites, state.recent, state.offline, query, selection) {
        LiveTvController.visibleChannels(state, query, selection)
    }
    LaunchedEffect(selection, state.channels) {
        val group = selection
        if (group != LiveTvSelection.ALL && group != LiveTvSelection.FAVORITES && group != LiveTvSelection.RECENT) {
            controller.requestGroupGuide(group)
            controller.requestGroupCheck(group)
        }
    }
    var firstGroupChosen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.groups, state.usingDefault) {
        if (firstGroupChosen || state.groups.isEmpty()) return@LaunchedEffect
        firstGroupChosen = true
        LiveTvController.preferredGroup(state, liveTvCountryCode())?.let { selection = it }
    }
    val play = { channel: LiveChannel ->
        controller.markWatched(channel.id)
        onPlayChannel(channel)
    }

    NuvioScreen(modifier = modifier) {
        item(key = "header") {
            NuvioScreenHeader(
                title = stringResource(Res.string.nav_live_tv),
                includeStatusBarPadding = false,
            ) {
                if (!showEdit && !showSettings && state.source != null) {
                    TextButton(onClick = { page = if (page == LiveTvPage.GUIDE) LiveTvPage.CHANNELS else LiveTvPage.GUIDE }) {
                        Text(stringResource(if (page == LiveTvPage.GUIDE) Res.string.live_tv_channels else Res.string.live_tv_guide))
                    }
                    IconButton(onClick = controller::refresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(Res.string.live_tv_refresh))
                    }
                    IconButton(onClick = { page = LiveTvPage.SETTINGS }) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(Res.string.live_tv_settings))
                    }
                }
            }
        }
        when {
            !state.ready -> item(key = "starting") { LoadingRow() }
            showEdit -> item(key = "form") {
                SourceForm(
                    current = state.sources.firstOrNull { it.key == editingKey },
                    canCancel = state.source != null || state.sources.isNotEmpty(),
                    onSave = { source ->
                        val key = editingKey
                        if (key.isEmpty()) controller.addSource(source) else controller.updateSource(key, source)
                        page = LiveTvPage.SETTINGS
                    },
                    onCancel = { page = LiveTvPage.SETTINGS },
                )
            }
            showSettings -> item(key = "settings") {
                LiveTvSettings(
                    state = state,
                    controller = controller,
                    onAdd = {
                        editingKey = ""
                        page = LiveTvPage.EDIT
                    },
                    onEdit = { key ->
                        editingKey = key
                        page = LiveTvPage.EDIT
                    },
                    onBack = if (state.source != null) ({ page = LiveTvPage.CHANNELS }) else null,
                )
            }
            else -> {
                item(key = "search") {
                    NuvioInputField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = stringResource(Res.string.live_tv_search),
                    )
                }
                item(key = "filters") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item(key = "all") {
                            Chip(
                                label = stringResource(Res.string.live_tv_all),
                                selected = selection == LiveTvSelection.ALL,
                                onClick = { selection = LiveTvSelection.ALL },
                            )
                        }
                        item(key = "favorites") {
                            Chip(
                                label = stringResource(Res.string.live_tv_favorites),
                                selected = selection == LiveTvSelection.FAVORITES,
                                onClick = { selection = LiveTvSelection.FAVORITES },
                            )
                        }
                        if (state.recent.isNotEmpty()) {
                            item(key = "recent") {
                                Chip(
                                    label = stringResource(Res.string.live_tv_recent),
                                    selected = selection == LiveTvSelection.RECENT,
                                    onClick = { selection = LiveTvSelection.RECENT },
                                )
                            }
                        }
                        items(state.groups, key = { "group:$it" }) { group ->
                            Chip(label = group, selected = selection == group, onClick = { selection = group })
                        }
                    }
                }
                if (state.failedSources.isNotEmpty() && !state.loading) {
                    item(key = "partial") {
                        Text(
                            text = stringResource(Res.string.live_tv_some_failed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                when {
                    state.loading -> item(key = "loading") { LoadingRow() }
                    state.error != null -> item(key = "error") {
                        NuvioSurfaceCard {
                            Text(
                                text = stringResource(errorMessage(state.error)),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            NuvioPrimaryButton(
                                text = stringResource(Res.string.live_tv_retry),
                                onClick = controller::refresh,
                            )
                        }
                    }
                    channels.isEmpty() -> item(key = "empty") {
                        Text(
                            text = stringResource(Res.string.live_tv_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        )
                    }
                    page == LiveTvPage.GUIDE -> guideItems(state, controller, channels, guideScroll, play)
                    else -> items(channels, key = { "channel:${it.id}" }) { channel ->
                        // Wait a moment so fast scrolling does not ask about every channel passed.
                        LaunchedEffect(channel.id) {
                            delay(GUIDE_REQUEST_DELAY_MS)
                            controller.requestChannelGuide(channel)
                        }
                        ChannelRow(
                            channel = channel,
                            favorite = channel.id in state.favorites,
                            nowTitle = if (LiveTvController.isOffline(state, channel)) {
                                stringResource(Res.string.live_tv_not_responding)
                            } else {
                                LiveTvController.nowTitle(state, channel)
                            },
                            nextTitle = LiveTvController.nextTitle(state, channel)
                                ?.let { stringResource(Res.string.live_tv_next, it) }
                                ?: LiveTvController.sourceLabel(state, channel),
                            onClick = { play(channel) },
                            onToggleFavorite = { controller.toggleFavorite(channel.id) },
                        )
                    }
                }
            }
        }
    }
}

private const val GUIDE_HOURS = 6
private const val GUIDE_SLOT_MS = 30L * 60 * 1000
private val GUIDE_DP_PER_MINUTE = 4.dp
private val GUIDE_CHANNEL_WIDTH = 120.dp
private val GUIDE_ROW_HEIGHT = 56.dp

@OptIn(ExperimentalTime::class)
private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

/**
 * The programme grid: channels down the side, time across. Every row shares one sideways scroll,
 * so the whole guide moves together.
 */
private fun androidx.compose.foundation.lazy.LazyListScope.guideItems(
    state: LiveTvState,
    controller: LiveTvController,
    channels: List<LiveChannel>,
    scroll: ScrollState,
    onPlay: (LiveChannel) -> Unit,
) {
    val now = nowMs()
    val from = now - now % GUIDE_SLOT_MS
    val to = from + GUIDE_HOURS * 60 * 60 * 1000L
    item(key = "guide-times") {
        Row {
            Spacer(modifier = Modifier.width(GUIDE_CHANNEL_WIDTH))
            Row(modifier = Modifier.horizontalScroll(scroll)) {
                var slot = from
                while (slot < to) {
                    Text(
                        text = formatLocalTime(slot),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(GUIDE_DP_PER_MINUTE * 30).padding(start = 4.dp),
                    )
                    slot += GUIDE_SLOT_MS
                }
            }
        }
    }
    items(channels, key = { "guide:${it.id}" }) { channel ->
        LaunchedEffect(channel.id) {
            delay(GUIDE_REQUEST_DELAY_MS)
            controller.requestChannelGuide(channel)
        }
        val noInfo = stringResource(Res.string.live_tv_guide_no_info)
        Row(modifier = Modifier.height(GUIDE_ROW_HEIGHT).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier.width(GUIDE_CHANNEL_WIDTH).clickable { onPlay(channel) }.padding(end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ChannelLogo(channel, size = 36.dp)
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = channelTitle(channel),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (channel.id in state.favorites) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                modifier = Modifier.horizontalScroll(scroll),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                val programmes = LiveTvController.programmesBetween(state, channel, from, to)
                if (programmes.isEmpty()) {
                    GuideCell(noInfo, null, GUIDE_HOURS * 60, live = false) { onPlay(channel) }
                } else {
                    var cursor = from
                    programmes.forEach { programme ->
                        val start = maxOf(programme.startMs, from)
                        if (start - cursor >= 60_000L) {
                            GuideCell(noInfo, null, ((start - cursor) / 60_000L).toInt(), live = false) { onPlay(channel) }
                        }
                        val stop = minOf(programme.stopMs, to)
                        if (stop > start) {
                            GuideCell(
                                title = programme.title,
                                subtitle = "${formatLocalTime(programme.startMs)} – ${formatLocalTime(programme.stopMs)}",
                                minutes = ((stop - start) / 60_000L).toInt().coerceAtLeast(1),
                                live = programme.startMs <= now && programme.stopMs > now,
                            ) { onPlay(channel) }
                        }
                        cursor = maxOf(cursor, stop)
                    }
                }
            }
        }
    }
}

@Composable
private fun GuideCell(title: String, subtitle: String?, minutes: Int, live: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width((GUIDE_DP_PER_MINUTE * minutes - 2.dp).coerceAtLeast(6.dp))
            .fillMaxHeight()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (live) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (live) FontWeight.SemiBold else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun channelTitle(channel: LiveChannel): String =
    channel.number?.let { "$it  ${channel.name}" } ?: channel.name

@Composable
private fun LiveTvSettings(
    state: LiveTvState,
    controller: LiveTvController,
    onAdd: () -> Unit,
    onEdit: (String) -> Unit,
    onBack: (() -> Unit)?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        NuvioSurfaceCard {
            Text(
                text = stringResource(Res.string.live_tv_playlists),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(Res.string.live_tv_playlists_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.sources.isEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(Res.string.live_tv_builtin_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.sources.forEach { source ->
                Spacer(modifier = Modifier.height(12.dp))
                SourceRow(state, source, controller, onEdit)
            }
            Spacer(modifier = Modifier.height(12.dp))
            NuvioPrimaryButton(text = stringResource(Res.string.live_tv_add_playlist), onClick = onAdd)
            if (onBack != null) {
                TextButton(onClick = onBack) { Text(stringResource(Res.string.live_tv_back)) }
            }
        }
        VpnCard(state.vpn, LiveTvRepository.wireGuardAvailable, controller::setVpn)
    }
}

@Composable
private fun SourceRow(state: LiveTvState, source: LiveSource, controller: LiveTvController, onEdit: (String) -> Unit) {
    val failure = state.failedSources[source.key]
    val account = state.accounts[source.key]
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = source.label,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val details = buildList {
                    add(stringResource(if (source.type == LiveSourceType.XTREAM) Res.string.live_tv_type_xtream else Res.string.live_tv_type_m3u))
                    if (!source.enabled) add(stringResource(Res.string.live_tv_playlist_off))
                    account?.expiresAtMs?.let { add(stringResource(Res.string.live_tv_expires, formatLocalDateTime(it))) }
                    val max = account?.maxConnections
                    val used = account?.activeConnections
                    if (max != null && used != null) add(stringResource(Res.string.live_tv_connections, used, max))
                }
                Text(
                    text = details.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = source.enabled, onCheckedChange = { controller.setSourceEnabled(source.key, it) })
        }
        if (failure != null) {
            Text(
                text = stringResource(Res.string.live_tv_playlist_failed, stringResource(errorMessage(failure))),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Row {
            TextButton(onClick = { onEdit(source.key) }) { Text(stringResource(Res.string.live_tv_edit)) }
            TextButton(onClick = { controller.removeSource(source.key) }) { Text(stringResource(Res.string.live_tv_delete)) }
        }
    }
}

@Composable
private fun VpnCard(current: LiveVpnSettings, wireGuardAvailable: Boolean, onSave: (LiveVpnSettings) -> Unit) {
    var type by remember(current) { mutableStateOf(if (wireGuardAvailable) current.type else LiveVpnType.PROXY) }
    var wireGuard by remember(current) { mutableStateOf(current.wireGuardConfig) }
    var proxy by remember(current) { mutableStateOf(current.proxyUrl) }
    val draft = LiveVpnSettings(enabled = true, type = type, wireGuardConfig = wireGuard, proxyUrl = proxy)
    val usable = draft.copy(wireGuardConfig = LiveVpnGuard.normalizeWireGuard(wireGuard)).ready
    NuvioSurfaceCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(Res.string.live_tv_vpn_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = current.enabled, onCheckedChange = { onSave(draft.copy(enabled = it)) })
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(Res.string.live_tv_vpn_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (wireGuardAvailable) {
                Chip(
                    label = stringResource(Res.string.live_tv_vpn_wireguard),
                    selected = type == LiveVpnType.WIREGUARD,
                    onClick = { type = LiveVpnType.WIREGUARD },
                )
            }
            Chip(
                label = stringResource(Res.string.live_tv_vpn_proxy),
                selected = type == LiveVpnType.PROXY,
                onClick = { type = LiveVpnType.PROXY },
            )
        }
        if (!wireGuardAvailable) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(Res.string.live_tv_vpn_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        if (type == LiveVpnType.WIREGUARD) {
            NuvioInputField(
                value = wireGuard,
                onValueChange = { wireGuard = it },
                placeholder = stringResource(Res.string.live_tv_vpn_wireguard_field),
            )
        } else {
            NuvioInputField(
                value = proxy,
                onValueChange = { proxy = it },
                placeholder = stringResource(Res.string.live_tv_vpn_proxy_field),
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(
                when {
                    usable && current.enabled -> Res.string.live_tv_vpn_ready
                    usable -> Res.string.live_tv_vpn_switch_on
                    else -> Res.string.live_tv_vpn_invalid
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))
        NuvioPrimaryButton(
            text = stringResource(Res.string.live_tv_vpn_save),
            enabled = usable || (wireGuard.isBlank() && proxy.isBlank()),
            onClick = { onSave(draft.copy(enabled = current.enabled && usable)) },
        )
    }
}

@Composable
private fun LoadingRow() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        NuvioLoadingIndicator()
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(Res.string.live_tv_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SourceForm(
    current: LiveSource?,
    canCancel: Boolean,
    onSave: (LiveSource) -> Unit,
    onCancel: () -> Unit,
) {
    var xtream by rememberSaveable { mutableStateOf(current?.type == LiveSourceType.XTREAM) }
    var name by rememberSaveable { mutableStateOf(current?.name.orEmpty()) }
    var url by rememberSaveable { mutableStateOf(current?.url.orEmpty()) }
    var username by rememberSaveable { mutableStateOf(current?.username.orEmpty()) }
    var password by rememberSaveable { mutableStateOf(current?.password.orEmpty()) }
    var guideUrl by rememberSaveable { mutableStateOf(current?.guideUrl.orEmpty()) }
    val canSave = url.isNotBlank() && (!xtream || (username.isNotBlank() && password.isNotBlank()))

    NuvioSurfaceCard {
        Text(
            text = stringResource(Res.string.live_tv_setup_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(Res.string.live_tv_setup_body, stringResource(Res.string.app_brand_name)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(
                label = stringResource(Res.string.live_tv_type_m3u),
                selected = !xtream,
                onClick = { xtream = false },
            )
            Chip(
                label = stringResource(Res.string.live_tv_type_xtream),
                selected = xtream,
                onClick = { xtream = true },
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        NuvioInputField(
            value = name,
            onValueChange = { name = it },
            placeholder = stringResource(Res.string.live_tv_field_name),
        )
        Spacer(modifier = Modifier.height(12.dp))
        NuvioInputField(
            value = url,
            onValueChange = { pasted ->
                // A pasted Xtream playlist link fills in the login by itself.
                val login = if (!xtream) XtreamApi.sourceFromPlaylistLink(pasted) else null
                if (login != null) {
                    xtream = true
                    url = login.url
                    username = login.username
                    password = login.password
                } else {
                    url = pasted
                }
            },
            placeholder = stringResource(
                if (xtream) Res.string.live_tv_field_server else Res.string.live_tv_field_playlist,
            ),
        )
        if (xtream) {
            Spacer(modifier = Modifier.height(12.dp))
            NuvioInputField(
                value = username,
                onValueChange = { username = it },
                placeholder = stringResource(Res.string.live_tv_field_username),
            )
            Spacer(modifier = Modifier.height(12.dp))
            NuvioInputField(
                value = password,
                onValueChange = { password = it },
                placeholder = stringResource(Res.string.live_tv_field_password),
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        NuvioInputField(
            value = guideUrl,
            onValueChange = { guideUrl = it },
            placeholder = stringResource(Res.string.live_tv_field_guide),
        )
        Spacer(modifier = Modifier.height(16.dp))
        NuvioPrimaryButton(
            text = stringResource(Res.string.live_tv_save),
            enabled = canSave,
            onClick = {
                onSave(
                    LiveSource(
                        type = if (xtream) LiveSourceType.XTREAM else LiveSourceType.M3U,
                        url = url,
                        username = username,
                        password = password,
                        guideUrl = guideUrl,
                        name = name,
                        id = current?.id.orEmpty(),
                        enabled = current?.enabled ?: true,
                    ),
                )
            },
        )
        if (canCancel) {
            TextButton(onClick = onCancel) { Text(stringResource(Res.string.live_tv_cancel)) }
        }
    }
}

@Composable
private fun ChannelRow(
    channel: LiveChannel,
    favorite: Boolean,
    nowTitle: String?,
    nextTitle: String?,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChannelLogo(channel)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = channelTitle(channel),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = nowTitle ?: channel.group.orEmpty()
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (nextTitle != null) {
                Text(
                    text = nextTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onToggleFavorite) {
            Icon(
                imageVector = if (favorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                contentDescription = stringResource(Res.string.live_tv_favorites),
                tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ChannelLogo(channel: LiveChannel, size: androidx.compose.ui.unit.Dp = 52.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = channel.name.trim().take(1).uppercase(),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!channel.logo.isNullOrBlank()) {
            AsyncImage(
                model = channel.logo,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

private fun errorMessage(error: LiveTvError?): StringResource = when (error) {
    LiveTvError.INVALID_SOURCE -> Res.string.live_tv_error_invalid
    LiveTvError.NOT_A_PLAYLIST -> Res.string.live_tv_error_not_playlist
    LiveTvError.LOGIN_FAILED -> Res.string.live_tv_error_login
    LiveTvError.NO_CHANNELS -> Res.string.live_tv_error_no_channels
    LiveTvError.NETWORK, null -> Res.string.live_tv_error_network
}
