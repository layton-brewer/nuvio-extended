@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import android.view.KeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.ai.AiAssistant
import com.nuvio.tv.core.ai.AiFailure
import com.nuvio.tv.core.ai.AiPick
import com.nuvio.tv.core.ai.AiResult
import com.nuvio.tv.core.ai.AiSearchHandoff
import com.nuvio.tv.core.ai.AiViewerContext
import com.nuvio.tv.data.local.AiSettingsDataStore
import com.nuvio.tv.data.local.LibraryPreferences
import com.nuvio.tv.data.local.WatchProgressPreferences
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import javax.inject.Inject

data class AiAssistantUiState(
    val loading: Boolean = false,
    val picks: List<AiPick> = emptyList(),
    val failure: AiFailure? = null
)

@HiltViewModel
class AiAssistantViewModel @Inject constructor(
    private val settings: AiSettingsDataStore,
    private val watchProgress: WatchProgressPreferences,
    private val library: LibraryPreferences
) : ViewModel() {
    // A client of its own, with no shared interceptors, so the key is never logged or reused.
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /** Null until the stored value has been read. */
    val hasKey: StateFlow<Boolean?> = settings.apiKey
        .map<String, Boolean?> { it.isNotBlank() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val mutableState = MutableStateFlow(AiAssistantUiState())
    val state: StateFlow<AiAssistantUiState> = mutableState.asStateFlow()

    fun saveKey(value: String) {
        viewModelScope.launch {
            settings.setApiKey(value)
            mutableState.value = AiAssistantUiState()
        }
    }

    fun ask(prompt: String) {
        if (prompt.isBlank() || mutableState.value.loading) return
        mutableState.value = AiAssistantUiState(loading = true)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { request(prompt) }
            mutableState.value = when (result) {
                is AiResult.Picks -> AiAssistantUiState(picks = result.picks)
                is AiResult.Failure -> AiAssistantUiState(failure = result.kind)
            }
        }
    }

    private suspend fun request(prompt: String): AiResult {
        val key = settings.currentApiKey()
        if (key.isBlank()) return AiResult.Failure(AiFailure.NO_KEY)
        return try {
            val body = AiAssistant.requestBody(prompt, viewerContext())
            val request = Request.Builder()
                .url(AiAssistant.ENDPOINT)
                .apply { AiAssistant.headers(key).forEach { (name, value) -> header(name, value) } }
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(request).execute().use { response ->
                AiAssistant.parseResponse(response.code, response.body?.string().orEmpty())
            }
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            AiResult.Failure(AiFailure.NETWORK)
        }
    }

    private suspend fun viewerContext(): AiViewerContext = runCatching {
        AiViewerContext(
            recentlyWatched = watchProgress.getAllRawEntries().values
                .sortedByDescending { it.lastWatched }
                .map { it.name },
            library = library.getAllItems().map { it.name }
        )
    }.getOrDefault(AiViewerContext())
}

/** Asks Claude for things to watch, using the viewer's own key. Opened from Profiles settings. */
@Composable
internal fun AiAssistantDialog(onDismiss: () -> Unit, viewModel: AiAssistantViewModel = hiltViewModel()) {
    val hasKey by viewModel.hasKey.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    var prompt by rememberSaveable { mutableStateOf("") }
    var keyDraft by remember { mutableStateOf("") }

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.ai_title),
        subtitle = stringResource(if (hasKey == false) R.string.ai_key_note else R.string.ai_note),
        width = 720.dp
    ) {
        when (hasKey) {
            null -> Text(
                text = stringResource(R.string.live_tv_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary
            )
            false -> {
                AiTextField(
                    value = keyDraft,
                    onValueChange = { keyDraft = it },
                    placeholder = stringResource(R.string.ai_key_placeholder),
                    secret = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
                    Button(onClick = { if (keyDraft.isNotBlank()) viewModel.saveKey(keyDraft) }) {
                        Text(stringResource(R.string.action_save))
                    }
                    Button(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                }
            }
            true -> {
                AiTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    placeholder = stringResource(R.string.ai_prompt_placeholder),
                    onDone = { viewModel.ask(prompt) }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
                    Button(onClick = { viewModel.ask(prompt) }) {
                        Text(stringResource(if (state.loading) R.string.ai_thinking else R.string.ai_ask))
                    }
                    Button(onClick = { keyDraft = ""; viewModel.saveKey("") }) {
                        Text(stringResource(R.string.ai_remove_key))
                    }
                    Button(onClick = onDismiss) { Text(stringResource(R.string.insights_close)) }
                }
                state.failure?.let { failure ->
                    Text(
                        text = stringResource(failure.messageRes()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioTheme.colors.TextSecondary
                    )
                }
                if (state.picks.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
                    ) {
                        state.picks.forEach { pick ->
                            AiPickCard(pick = pick) {
                                AiSearchHandoff.pendingTitle.value = pick.title
                                onDismiss()
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun AiFailure.messageRes(): Int = when (this) {
    AiFailure.NO_KEY, AiFailure.BAD_KEY -> R.string.ai_error_key
    AiFailure.RATE_LIMITED -> R.string.ai_error_busy
    AiFailure.DECLINED -> R.string.ai_error_declined
    AiFailure.NETWORK -> R.string.ai_error_network
    AiFailure.UNEXPECTED -> R.string.ai_error_unexpected
}

@Composable
private fun AiPickCard(pick: AiPick, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundElevated,
            focusedContainerColor = NuvioTheme.colors.FocusBackground
        ),
        border = CardDefaults.border(
            focusedBorder = Border(border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape = shape)
        ),
        shape = CardDefaults.shape(shape),
        scale = CardDefaults.scale(focusedScale = 1f)
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = NuvioTheme.spacing.sm)) {
            val kind = stringResource(if (pick.isSeries) R.string.ai_kind_series else R.string.ai_kind_movie)
            Text(
                text = listOfNotNull(pick.title, pick.year?.toString(), kind).joinToString("  ·  "),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = NuvioTheme.colors.TextPrimary
            )
            if (pick.reason.isNotEmpty()) {
                Text(
                    text = pick.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary
                )
            }
        }
    }
}

@Composable
private fun AiTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    secret: Boolean = false,
    onDone: () -> Unit = {}
) {
    var focused by remember { mutableStateOf(false) }
    val inputFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val shape = RoundedCornerShape(10.dp)
    Card(
        onClick = { inputFocusRequester.requestFocus() },
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused || it.hasFocus },
        colors = CardDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundElevated,
            focusedContainerColor = NuvioTheme.colors.BackgroundElevated
        ),
        border = CardDefaults.border(
            border = Border(border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border), shape = shape),
            focusedBorder = Border(border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape = shape)
        ),
        shape = CardDefaults.shape(shape),
        scale = CardDefaults.scale(focusedScale = 1f)
    ) {
        Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = NuvioTheme.spacing.md)) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(inputFocusRequester)
                    .onKeyEvent { event ->
                        event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER &&
                            event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN
                    },
                singleLine = true,
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { keyboardController?.hide(); onDone() }),
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = NuvioTheme.colors.TextPrimary),
                cursorBrush = SolidColor(if (focused) NuvioTheme.colors.Primary else Color.Transparent),
                decorationBox = { innerTextField ->
                    if (value.isBlank()) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodyMedium,
                            color = NuvioTheme.colors.TextTertiary
                        )
                    }
                    innerTextField()
                }
            )
        }
    }
}
