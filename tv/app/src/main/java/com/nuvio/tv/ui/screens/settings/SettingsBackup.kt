package com.nuvio.tv.ui.screens.settings

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.R
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.core.util.readAppLogoBytes
import com.nuvio.tv.core.server.DeviceIpAddress
import com.nuvio.tv.core.server.SettingsBackupServer
import com.nuvio.tv.core.sync.ProfileSettingsSyncService
import com.nuvio.tv.core.util.withAppLocale
import com.nuvio.tv.ui.screens.addon.QrCodeOverlay
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

data class SettingsBackupState(
    val isActive: Boolean = false,
    val qrCodeBitmap: Bitmap? = null,
    val serverUrl: String? = null,
    val pin: String? = null,
    val message: String? = null
)

@HiltViewModel
class SettingsBackupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsSync: ProfileSettingsSyncService
) : ViewModel() {
    private val logoBytes: ByteArray? by lazy { context.readAppLogoBytes() }
    private val mutableState = MutableStateFlow(SettingsBackupState())
    val state: StateFlow<SettingsBackupState> = mutableState.asStateFlow()
    private var server: SettingsBackupServer? = null

    fun start() {
        stopServer()
        val strings = context.withAppLocale()
        val ip = DeviceIpAddress.get(context)
        if (ip == null) {
            mutableState.value = SettingsBackupState(isActive = true, message = strings.getString(R.string.error_network_required))
            return
        }
        // The server answers on its own worker threads, so blocking there is fine.
        val started = SettingsBackupServer.startOnAvailablePort(
            context = context,
            exportBackup = { includePrivate ->
                runCatching { runBlocking { settingsSync.exportBackupJson(includePrivate) } }.getOrNull()
            },
            importBackup = { text ->
                val restored = runCatching { runBlocking { settingsSync.importBackupJson(text) } }.getOrDefault(false)
                if (restored) {
                    mutableState.value = mutableState.value.copy(message = strings.getString(R.string.backup_restored))
                }
                restored
            },
            logoProvider = { logoBytes }
        )
        if (started == null) {
            mutableState.value = SettingsBackupState(isActive = true, message = strings.getString(R.string.error_server_ports_unavailable))
            return
        }
        server = started
        val url = "http://$ip:${started.listeningPort}"
        mutableState.value = SettingsBackupState(
            isActive = true,
            // The PIN rides in the fragment, which browsers never send over the network.
            qrCodeBitmap = QrCodeGenerator.generate("$url/#pin=${started.pin}", 512),
            serverUrl = url,
            pin = started.pin
        )
    }

    fun stop() {
        stopServer()
        mutableState.value = SettingsBackupState()
    }

    private fun stopServer() {
        server?.stop()
        server = null
    }

    override fun onCleared() {
        stopServer()
    }
}

/** The "Backup and restore" card in advanced settings, and the QR overlay it opens. */
@Composable
internal fun SettingsBackupCard(viewModel: SettingsBackupViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsGroupCard(
        modifier = Modifier.fillMaxWidth(),
        title = stringResource(R.string.backup_title)
    ) {
        SettingsActionRow(
            title = stringResource(R.string.backup_open),
            subtitle = stringResource(R.string.backup_open_sub),
            onClick = viewModel::start
        )
    }
    if (state.isActive) {
        Dialog(
            onDismissRequest = viewModel::stop,
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            QrCodeOverlay(
                qrBitmap = state.qrCodeBitmap,
                serverUrl = state.serverUrl,
                instruction = state.message
                    ?: state.pin?.let { stringResource(R.string.backup_qr_with_pin, it) }
                    ?: stringResource(R.string.backup_qr),
                onClose = viewModel::stop
            )
        }
    }
}
