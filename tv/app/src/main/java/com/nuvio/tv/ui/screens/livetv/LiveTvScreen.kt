@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.livetv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nuvio.tv.R
import com.nuvio.tv.core.livetv.LiveChannel
import com.nuvio.tv.core.livetv.LiveSource
import com.nuvio.tv.core.livetv.LiveSourceType
import com.nuvio.tv.core.livetv.LiveTvController
import com.nuvio.tv.core.livetv.LiveTvError
import com.nuvio.tv.core.livetv.LiveTvSelection
import com.nuvio.tv.core.livetv.LiveTvState
import com.nuvio.tv.core.livetv.LiveVpnGuard
import com.nuvio.tv.core.livetv.LiveVpnSettings
import com.nuvio.tv.core.livetv.LiveVpnType
import com.nuvio.tv.core.livetv.XtreamApi
import com.nuvio.tv.ui.components.EmptyScreenState
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.screens.account.InputField
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

private const val GRID_COLUMNS = 3
private const val GUIDE_REQUEST_DELAY_MS = 450L

private enum class LiveTvPage { CHANNELS, GUIDE, SETTINGS, EDIT }

@Composable
fun LiveTvScreen(
    viewModel: LiveTvViewModel = hiltViewModel(),
    showBuiltInHeader: Boolean = true,
    onPlayChannel: (LiveChannel) -> Unit
) {
    val controller = viewModel.controller
    val state by controller.state.collectAsState()
    var page by rememberSaveable { mutableStateOf(LiveTvPage.CHANNELS) }
    /** Key of the playlist being edited, or "" for a new one. */
    var editingKey by rememberSaveable { mutableStateOf("") }
    // Switching pages removes the focused button, so the new page takes focus itself once this
    // count goes above zero.
    var focusHandoff by remember { mutableIntStateOf(0) }
    val goTo = { next: LiveTvPage ->
        page = next
        focusHandoff++
    }

    LaunchedEffect(controller) { controller.ensureChannels() }
    BackHandler(enabled = page == LiveTvPage.EDIT || page == LiveTvPage.SETTINGS || page == LiveTvPage.GUIDE) {
        goTo(if (page == LiveTvPage.EDIT) LiveTvPage.SETTINGS else LiveTvPage.CHANNELS)
    }
    val play = { channel: LiveChannel ->
        controller.markWatched(channel.id)
        val (url, headers) = viewModel.playbackTarget(channel)
        onPlayChannel(channel.copy(url = url, headers = headers))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioTheme.colors.Background)
            .padding(
                start = NuvioTheme.spacing.xxxl,
                end = NuvioTheme.spacing.xxxl,
                top = NuvioTheme.spacing.xl
            )
    ) {
        Text(
            text = stringResource(R.string.nav_live_tv),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (showBuiltInHeader) NuvioTheme.colors.TextPrimary else Color.Transparent
        )
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))
        val nothingToShow = state.ready && state.source == null
        when {
            !state.ready -> CenteredLoading()
            page == LiveTvPage.EDIT || (nothingToShow && state.sources.isEmpty()) -> SourceForm(
                current = state.sources.firstOrNull { it.key == editingKey },
                canCancel = state.source != null || state.sources.isNotEmpty(),
                focusHandoff = focusHandoff,
                onSave = { source ->
                    val key = editingKey
                    if (key.isEmpty()) controller.addSource(source) else controller.updateSource(key, source)
                    goTo(LiveTvPage.SETTINGS)
                },
                onCancel = { goTo(LiveTvPage.SETTINGS) }
            )
            page == LiveTvPage.SETTINGS || nothingToShow -> LiveTvSettingsPage(
                state = state,
                controller = controller,
                wireGuardAvailable = viewModel.wireGuardAvailable,
                focusHandoff = focusHandoff,
                onAdd = {
                    editingKey = ""
                    goTo(LiveTvPage.EDIT)
                },
                onEdit = { key ->
                    editingKey = key
                    goTo(LiveTvPage.EDIT)
                },
                onBack = { goTo(LiveTvPage.CHANNELS) }
            )
            else -> ChannelBrowser(
                state = state,
                controller = controller,
                focusHandoff = focusHandoff,
                showGuide = page == LiveTvPage.GUIDE,
                onToggleGuide = { goTo(if (page == LiveTvPage.GUIDE) LiveTvPage.CHANNELS else LiveTvPage.GUIDE) },
                onOpenSettings = { goTo(LiveTvPage.SETTINGS) },
                onPlayChannel = play
            )
        }
    }
}

@Composable
private fun CenteredLoading() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            LoadingIndicator()
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))
            Text(
                text = stringResource(R.string.live_tv_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary
            )
        }
    }
}

@Composable
private fun SourceForm(
    current: LiveSource?,
    canCancel: Boolean,
    focusHandoff: Int,
    onSave: (LiveSource) -> Unit,
    onCancel: () -> Unit
) {
    var type by rememberSaveable { mutableStateOf(current?.type ?: LiveSourceType.M3U) }
    var name by rememberSaveable { mutableStateOf(current?.name.orEmpty()) }
    var url by rememberSaveable { mutableStateOf(current?.url.orEmpty()) }
    var username by rememberSaveable { mutableStateOf(current?.username.orEmpty()) }
    var password by rememberSaveable { mutableStateOf(current?.password.orEmpty()) }
    var guideUrl by rememberSaveable { mutableStateOf(current?.guideUrl.orEmpty()) }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(focusHandoff) {
        if (focusHandoff > 0) runCatching { firstFocus.requestFocus() }
    }
    val canSave = url.isNotBlank() &&
        (type == LiveSourceType.M3U || (username.isNotBlank() && password.isNotBlank()))

    Column(
        modifier = Modifier
            .widthIn(max = 720.dp)
            .verticalScroll(rememberScrollState())
            .padding(bottom = NuvioTheme.spacing.xxl),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
    ) {
        Text(
            text = stringResource(R.string.live_tv_setup_title),
            style = MaterialTheme.typography.titleLarge,
            color = NuvioTheme.colors.TextPrimary
        )
        Text(
            text = stringResource(R.string.live_tv_setup_body, stringResource(R.string.app_name)),
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.colors.TextSecondary
        )
        Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
            PillButton(
                label = stringResource(R.string.live_tv_type_m3u),
                selected = type == LiveSourceType.M3U,
                onClick = { type = LiveSourceType.M3U },
                modifier = Modifier.focusRequester(firstFocus)
            )
            PillButton(
                label = stringResource(R.string.live_tv_type_xtream),
                selected = type == LiveSourceType.XTREAM,
                onClick = { type = LiveSourceType.XTREAM }
            )
        }
        InputField(
            value = name,
            onValueChange = { name = it },
            placeholder = stringResource(R.string.live_tv_field_name)
        )
        InputField(
            value = url,
            onValueChange = { pasted ->
                // A pasted Xtream playlist link fills in the login by itself.
                val login = if (type == LiveSourceType.M3U) XtreamApi.sourceFromPlaylistLink(pasted) else null
                if (login != null) {
                    type = LiveSourceType.XTREAM
                    url = login.url
                    username = login.username
                    password = login.password
                } else {
                    url = pasted
                }
            },
            placeholder = stringResource(
                if (type == LiveSourceType.M3U) R.string.live_tv_field_playlist else R.string.live_tv_field_server
            ),
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Done
        )
        if (type == LiveSourceType.XTREAM) {
            InputField(
                value = username,
                onValueChange = { username = it },
                placeholder = stringResource(R.string.live_tv_field_username)
            )
            InputField(
                value = password,
                onValueChange = { password = it },
                placeholder = stringResource(R.string.live_tv_field_password),
                isPassword = true
            )
        }
        InputField(
            value = guideUrl,
            onValueChange = { guideUrl = it },
            placeholder = stringResource(R.string.live_tv_field_guide),
            keyboardType = KeyboardType.Uri
        )
        Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
            PillButton(
                label = stringResource(R.string.live_tv_save),
                selected = true,
                enabled = canSave,
                onClick = {
                    onSave(
                        LiveSource(
                            type = type,
                            url = url,
                            username = username,
                            password = password,
                            guideUrl = guideUrl,
                            name = name,
                            id = current?.id.orEmpty(),
                            enabled = current?.enabled ?: true
                        )
                    )
                }
            )
            if (canCancel) {
                PillButton(label = stringResource(R.string.live_tv_cancel), selected = false, onClick = onCancel)
            }
        }
    }
}

@Composable
private fun LiveTvSettingsPage(
    state: LiveTvState,
    controller: LiveTvController,
    wireGuardAvailable: Boolean,
    focusHandoff: Int,
    onAdd: () -> Unit,
    onEdit: (String) -> Unit,
    onBack: () -> Unit
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(focusHandoff) {
        if (focusHandoff > 0) runCatching { firstFocus.requestFocus() }
    }
    val dateFormat = remember { java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM) }
    Column(
        modifier = Modifier
            .widthIn(max = 900.dp)
            .verticalScroll(rememberScrollState())
            .padding(bottom = NuvioTheme.spacing.xxl),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
            PillButton(
                label = stringResource(R.string.live_tv_add_playlist),
                selected = true,
                onClick = onAdd,
                modifier = Modifier.focusRequester(firstFocus)
            )
            if (state.source != null) {
                PillButton(label = stringResource(R.string.live_tv_back), selected = false, onClick = onBack)
            }
        }
        Text(
            text = stringResource(R.string.live_tv_playlists),
            style = MaterialTheme.typography.titleLarge,
            color = NuvioTheme.colors.TextPrimary
        )
        Text(
            text = stringResource(R.string.live_tv_playlists_body),
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.colors.TextSecondary
        )
        if (state.sources.isEmpty()) {
            Text(
                text = stringResource(R.string.live_tv_builtin_note),
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextTertiary
            )
        }
        state.sources.forEach { source ->
            val failure = state.failedSources[source.key]
            val account = state.accounts[source.key]
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(NuvioTheme.radii.md))
                    .background(NuvioTheme.colors.BackgroundCard)
                    .padding(NuvioTheme.spacing.md),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
            ) {
                Text(
                    text = source.label,
                    style = MaterialTheme.typography.titleMedium,
                    color = NuvioTheme.colors.TextPrimary
                )
                val details = buildList {
                    add(stringResource(if (source.type == LiveSourceType.XTREAM) R.string.live_tv_type_xtream else R.string.live_tv_type_m3u))
                    if (!source.enabled) add(stringResource(R.string.live_tv_playlist_off))
                    account?.expiresAtMs?.let { add(stringResource(R.string.live_tv_expires, dateFormat.format(java.util.Date(it)))) }
                    val max = account?.maxConnections
                    val used = account?.activeConnections
                    if (max != null && used != null) add(stringResource(R.string.live_tv_connections, used, max))
                }
                Text(
                    text = details.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary
                )
                if (failure != null) {
                    Text(
                        text = stringResource(R.string.live_tv_playlist_failed, stringResource(errorMessage(failure))),
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.Error
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
                    PillButton(label = stringResource(R.string.live_tv_edit), selected = false, onClick = { onEdit(source.key) })
                    PillButton(
                        label = stringResource(if (source.enabled) R.string.live_tv_turn_off else R.string.live_tv_turn_on),
                        selected = false,
                        onClick = { controller.setSourceEnabled(source.key, !source.enabled) }
                    )
                    PillButton(
                        label = stringResource(R.string.live_tv_delete),
                        selected = false,
                        onClick = { controller.removeSource(source.key) }
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))
        VpnSettings(state.vpn, wireGuardAvailable, onSave = controller::setVpn)
    }
}

@Composable
private fun VpnSettings(
    current: LiveVpnSettings,
    wireGuardAvailable: Boolean,
    onSave: (LiveVpnSettings) -> Unit
) {
    var type by remember(current) {
        mutableStateOf(if (!wireGuardAvailable) LiveVpnType.PROXY else current.type)
    }
    var wireGuard by remember(current) { mutableStateOf(current.wireGuardConfig) }
    var proxy by remember(current) { mutableStateOf(current.proxyUrl) }
    val draft = LiveVpnSettings(enabled = true, type = type, wireGuardConfig = wireGuard, proxyUrl = proxy)
    Text(
        text = stringResource(R.string.live_tv_vpn_title),
        style = MaterialTheme.typography.titleLarge,
        color = NuvioTheme.colors.TextPrimary
    )
    Text(
        text = stringResource(R.string.live_tv_vpn_body),
        style = MaterialTheme.typography.bodyMedium,
        color = NuvioTheme.colors.TextSecondary
    )
    Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
        PillButton(
            label = stringResource(if (current.enabled) R.string.live_tv_vpn_on else R.string.live_tv_vpn_off),
            selected = current.enabled,
            onClick = { onSave(draft.copy(enabled = !current.enabled)) }
        )
        if (wireGuardAvailable) {
            PillButton(
                label = stringResource(R.string.live_tv_vpn_wireguard),
                selected = type == LiveVpnType.WIREGUARD,
                onClick = { type = LiveVpnType.WIREGUARD }
            )
        }
        PillButton(
            label = stringResource(R.string.live_tv_vpn_proxy),
            selected = type == LiveVpnType.PROXY,
            onClick = { type = LiveVpnType.PROXY }
        )
    }
    if (!wireGuardAvailable) {
        Text(
            text = stringResource(R.string.live_tv_vpn_unavailable),
            style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.colors.TextTertiary
        )
    }
    if (type == LiveVpnType.WIREGUARD) {
        InputField(
            value = wireGuard,
            onValueChange = { wireGuard = it },
            placeholder = stringResource(R.string.live_tv_vpn_wireguard_field),
            isPassword = true
        )
    } else {
        InputField(
            value = proxy,
            onValueChange = { proxy = it },
            placeholder = stringResource(R.string.live_tv_vpn_proxy_field),
            keyboardType = KeyboardType.Uri
        )
    }
    val usable = draft.copy(wireGuardConfig = LiveVpnGuard.normalizeWireGuard(wireGuard)).ready
    Text(
        text = stringResource(
            when {
                usable && current.enabled -> R.string.live_tv_vpn_ready
                usable -> R.string.live_tv_vpn_switch_on
                else -> R.string.live_tv_vpn_invalid
            }
        ),
        style = MaterialTheme.typography.bodySmall,
        color = if (usable) NuvioTheme.colors.TextSecondary else NuvioTheme.colors.TextTertiary
    )
    PillButton(
        label = stringResource(R.string.live_tv_vpn_save),
        selected = true,
        enabled = usable || (wireGuard.isBlank() && proxy.isBlank()),
        onClick = { onSave(draft.copy(enabled = current.enabled && usable)) }
    )
}

@Composable
private fun ChannelBrowser(
    state: LiveTvState,
    controller: LiveTvController,
    focusHandoff: Int,
    showGuide: Boolean,
    onToggleGuide: () -> Unit,
    onOpenSettings: () -> Unit,
    onPlayChannel: (LiveChannel) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var selection by rememberSaveable { mutableStateOf(LiveTvSelection.ALL) }
    val channels = remember(state.channels, state.favorites, state.recent, state.offline, query, selection) {
        LiveTvController.visibleChannels(state, query, selection)
    }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(focusHandoff) {
        if (focusHandoff > 0) runCatching { firstFocus.requestFocus() }
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
        LiveTvController.preferredGroup(state, java.util.Locale.getDefault().country)?.let { selection = it }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
        ) {
            InputField(
                value = query,
                onValueChange = { query = it },
                placeholder = stringResource(R.string.live_tv_search),
                modifier = Modifier.weight(1f)
            )
            PillButton(
                label = stringResource(if (showGuide) R.string.live_tv_channels else R.string.live_tv_guide),
                selected = false,
                onClick = onToggleGuide
            )
            PillButton(label = stringResource(R.string.live_tv_refresh), selected = false, onClick = controller::refresh)
            PillButton(label = stringResource(R.string.live_tv_settings), selected = false, onClick = onOpenSettings)
        }
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
            item(key = "all") {
                PillButton(
                    label = stringResource(R.string.live_tv_all),
                    selected = selection == LiveTvSelection.ALL,
                    onClick = { selection = LiveTvSelection.ALL },
                    modifier = Modifier.focusRequester(firstFocus)
                )
            }
            item(key = "favorites") {
                PillButton(
                    label = stringResource(R.string.live_tv_favorites),
                    selected = selection == LiveTvSelection.FAVORITES,
                    onClick = { selection = LiveTvSelection.FAVORITES }
                )
            }
            if (state.recent.isNotEmpty()) {
                item(key = "recent") {
                    PillButton(
                        label = stringResource(R.string.live_tv_recent),
                        selected = selection == LiveTvSelection.RECENT,
                        onClick = { selection = LiveTvSelection.RECENT }
                    )
                }
            }
            items(state.groups, key = { "group:$it" }) { group ->
                PillButton(label = group, selected = selection == group, onClick = { selection = group })
            }
        }
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))
        when {
            state.loading -> CenteredLoading()
            state.error != null -> Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = stringResource(errorMessage(state.error)),
                    style = MaterialTheme.typography.titleMedium,
                    color = NuvioTheme.colors.TextPrimary
                )
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))
                PillButton(label = stringResource(R.string.live_tv_retry), selected = true, onClick = controller::refresh)
            }
            channels.isEmpty() -> EmptyScreenState(
                title = stringResource(R.string.live_tv_empty),
                icon = Icons.Default.LiveTv
            )
            else -> {
                Text(
                    text = if (state.failedSources.isNotEmpty()) {
                        stringResource(R.string.live_tv_some_failed)
                    } else {
                        stringResource(R.string.live_tv_favorite_hint)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextTertiary
                )
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
                if (showGuide) {
                    GuideGrid(state, controller, channels, onPlayChannel)
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(GRID_COLUMNS),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            top = NuvioTheme.spacing.xs,
                            bottom = NuvioTheme.spacing.xxl,
                            start = NuvioTheme.spacing.xs,
                            end = NuvioTheme.spacing.xs
                        ),
                        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
                        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
                    ) {
                        items(channels, key = LiveChannel::id) { channel ->
                            // Wait a moment so fast scrolling does not ask about every channel passed.
                            LaunchedEffect(channel.id) {
                                delay(GUIDE_REQUEST_DELAY_MS)
                                controller.requestChannelGuide(channel)
                            }
                            ChannelCard(
                                channel = channel,
                                favorite = channel.id in state.favorites,
                                nowTitle = if (LiveTvController.isOffline(state, channel)) {
                                    stringResource(R.string.live_tv_not_responding)
                                } else {
                                    LiveTvController.nowTitle(state, channel)
                                },
                                nextTitle = LiveTvController.nextTitle(state, channel),
                                sourceLabel = LiveTvController.sourceLabel(state, channel),
                                onClick = { onPlayChannel(channel) },
                                onLongClick = { controller.toggleFavorite(channel.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}

private const val GUIDE_HOURS = 6
private const val GUIDE_SLOT_MS = 30L * 60 * 1000
private val GUIDE_DP_PER_MINUTE = 6.dp
private val GUIDE_CHANNEL_WIDTH = 240.dp
private val GUIDE_ROW_HEIGHT = 64.dp

/**
 * A programme grid: channels down the side, time across the top. Every row scrolls sideways
 * together, so moving through one channel's programmes moves the whole guide.
 */
@Composable
private fun GuideGrid(
    state: LiveTvState,
    controller: LiveTvController,
    channels: List<LiveChannel>,
    onPlayChannel: (LiveChannel) -> Unit
) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L)
            now = System.currentTimeMillis()
        }
    }
    val from = now - now % GUIDE_SLOT_MS
    val to = from + GUIDE_HOURS * 60 * 60 * 1000L
    val horizontal = rememberScrollState()
    val timeFormat = remember { java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT) }
    val noInfo = stringResource(R.string.live_tv_guide_no_info)
    Column(modifier = Modifier.fillMaxSize()) {
        Row {
            Spacer(modifier = Modifier.width(GUIDE_CHANNEL_WIDTH))
            Row(modifier = Modifier.horizontalScroll(horizontal)) {
                var slot = from
                while (slot < to) {
                    Text(
                        text = timeFormat.format(java.util.Date(slot)),
                        style = MaterialTheme.typography.labelMedium,
                        color = NuvioTheme.colors.TextSecondary,
                        modifier = Modifier.width(GUIDE_DP_PER_MINUTE * 30).padding(start = 6.dp)
                    )
                    slot += GUIDE_SLOT_MS
                }
            }
        }
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = NuvioTheme.spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(channels, key = LiveChannel::id) { channel ->
                LaunchedEffect(channel.id) {
                    delay(GUIDE_REQUEST_DELAY_MS)
                    controller.requestChannelGuide(channel)
                }
                Row(modifier = Modifier.height(GUIDE_ROW_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        modifier = Modifier.width(GUIDE_CHANNEL_WIDTH).padding(end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ChannelLogo(channel, size = 44.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = channelTitle(channel),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (channel.id in state.favorites) NuvioTheme.colors.Secondary else NuvioTheme.colors.TextPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Row(
                        modifier = Modifier.horizontalScroll(horizontal),
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        val programmes = LiveTvController.programmesBetween(state, channel, from, to)
                        if (programmes.isEmpty()) {
                            GuideCell(
                                title = noInfo,
                                subtitle = null,
                                minutes = GUIDE_HOURS * 60,
                                live = false,
                                onClick = { onPlayChannel(channel) },
                                onLongClick = { controller.toggleFavorite(channel.id) }
                            )
                        } else {
                            var cursor = from
                            programmes.forEach { programme ->
                                val start = maxOf(programme.startMs, from)
                                if (start - cursor >= 60_000L) {
                                    GuideCell(
                                        title = noInfo,
                                        subtitle = null,
                                        minutes = ((start - cursor) / 60_000L).toInt(),
                                        live = false,
                                        onClick = { onPlayChannel(channel) },
                                        onLongClick = { controller.toggleFavorite(channel.id) }
                                    )
                                }
                                val stop = minOf(programme.stopMs, to)
                                if (stop > start) {
                                    GuideCell(
                                        title = programme.title,
                                        subtitle = "${timeFormat.format(java.util.Date(programme.startMs))} – " +
                                            timeFormat.format(java.util.Date(programme.stopMs)),
                                        minutes = ((stop - start) / 60_000L).toInt().coerceAtLeast(1),
                                        live = programme.startMs <= now && programme.stopMs > now,
                                        onClick = { onPlayChannel(channel) },
                                        onLongClick = { controller.toggleFavorite(channel.id) }
                                    )
                                }
                                cursor = maxOf(cursor, stop)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GuideCell(
    title: String,
    subtitle: String?,
    minutes: Int,
    live: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Card(
        onClick = onClick,
        onLongClick = onLongClick,
        colors = CardDefaults.colors(
            containerColor = if (live) NuvioTheme.colors.BackgroundElevated else NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.FocusBackground
        ),
        shape = CardDefaults.shape(RoundedCornerShape(NuvioTheme.radii.sm)),
        scale = CardDefaults.scale(focusedScale = 1f),
        modifier = Modifier
            .width((GUIDE_DP_PER_MINUTE * minutes - 2.dp).coerceAtLeast(8.dp))
            .fillMaxHeight()
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (live) FontWeight.SemiBold else FontWeight.Normal,
                color = NuvioTheme.colors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = NuvioTheme.colors.TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun channelTitle(channel: LiveChannel): String =
    channel.number?.let { "$it  ${channel.name}" } ?: channel.name

@Composable
private fun ChannelCard(
    channel: LiveChannel,
    favorite: Boolean,
    nowTitle: String?,
    nextTitle: String?,
    sourceLabel: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Card(
        onClick = onClick,
        onLongClick = onLongClick,
        colors = CardDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.FocusBackground
        ),
        shape = CardDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md)),
        scale = CardDefaults.scale(focusedScale = 1.03f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(NuvioTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ChannelLogo(channel)
            Spacer(modifier = Modifier.width(NuvioTheme.spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = channelTitle(channel),
                    style = MaterialTheme.typography.titleSmall,
                    color = NuvioTheme.colors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = nowTitle ?: channel.group.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (nextTitle != null) {
                    Text(
                        text = stringResource(R.string.live_tv_next, nextTitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                } else if (sourceLabel != null) {
                    Text(
                        text = sourceLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (favorite) {
                Icon(
                    imageVector = Icons.Default.Star,
                    contentDescription = stringResource(R.string.live_tv_favorites),
                    tint = NuvioTheme.colors.Secondary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun ChannelLogo(channel: LiveChannel, size: androidx.compose.ui.unit.Dp = 52.dp) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(NuvioTheme.radii.sm))
            .background(NuvioTheme.colors.BackgroundElevated),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = channel.name.trim().take(1).uppercase(),
            style = MaterialTheme.typography.titleMedium,
            color = NuvioTheme.colors.TextSecondary
        )
        if (!channel.logo.isNullOrBlank()) {
            val request = remember(context, channel.logo) {
                ImageRequest.Builder(context).data(channel.logo).crossfade(true).build()
            }
            AsyncImage(
                model = request,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .background(NuvioTheme.colors.BackgroundElevated),
                contentScale = ContentScale.Fit
            )
        }
    }
}

@Composable
private fun PillButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        colors = ButtonDefaults.colors(
            containerColor = if (selected) NuvioTheme.colors.Secondary else NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.SecondaryVariant,
            contentColor = if (selected) NuvioTheme.colors.OnSecondary else NuvioTheme.colors.TextPrimary,
            focusedContentColor = NuvioTheme.colors.OnSecondaryVariant
        ),
        shape = ButtonDefaults.shape(RoundedCornerShape(50))
    ) {
        Text(text = label, maxLines = 1)
    }
}

private fun errorMessage(error: LiveTvError?): Int = when (error) {
    LiveTvError.INVALID_SOURCE -> R.string.live_tv_error_invalid
    LiveTvError.NOT_A_PLAYLIST -> R.string.live_tv_error_not_playlist
    LiveTvError.LOGIN_FAILED -> R.string.live_tv_error_login
    LiveTvError.NO_CHANNELS -> R.string.live_tv_error_no_channels
    LiveTvError.NETWORK, null -> R.string.live_tv_error_network
}
