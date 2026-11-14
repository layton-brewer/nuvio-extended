package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.trakt.TraktAppCredentials
import com.nuvio.app.features.trakt.TraktAuthRepository

/**
 * One-time Trakt setup shown when the build has no Trakt app: everyone signs in with their own
 * Trakt account, using a free Trakt app they create in a minute. Shared by phone, iPhone and desktop.
 */
@Composable
internal fun TraktOwnAppSetup(modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    var clientId by rememberSaveable { mutableStateOf("") }
    var clientSecret by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "Set up Trakt (one time)",
            style = MaterialTheme.typography.titleSmall,
            color = Color.White,
        )
        Text(
            text = "You sign in with your own Trakt account. First create a free Trakt app: open " +
                "trakt.tv/oauth/applications, choose New Application, give it any name and paste these two " +
                "lines as the Redirect URI, then save:\n" + TraktAppCredentials.REDIRECT_URIS +
                "\nThen copy its Client ID and Client Secret here. They stay on this device.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.82f),
        )
        OutlinedButton(onClick = { runCatching { uriHandler.openUri(TraktAppCredentials.APPS_URL) } }) {
            Text("Open trakt.tv/oauth/applications")
        }
        OutlinedTextField(
            value = clientId,
            onValueChange = { clientId = it; error = false },
            label = { Text("Client ID") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = clientSecret,
            onValueChange = { clientSecret = it; error = false },
            label = { Text("Client Secret") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        if (error) {
            Text(
                text = "Enter both the Client ID and the Client Secret.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = {
                if (clientId.isBlank() || clientSecret.isBlank()) {
                    error = true
                } else {
                    TraktAuthRepository.saveOwnApp(clientId, clientSecret)
                }
            }) {
                Text("Save and continue")
            }
        }
    }
}
