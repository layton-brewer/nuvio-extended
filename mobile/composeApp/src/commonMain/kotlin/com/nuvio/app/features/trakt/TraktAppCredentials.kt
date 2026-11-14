package com.nuvio.app.features.trakt

import com.nuvio.app.features.livetv.LiveTvPlatformStorage

/**
 * The Trakt app (client ID and secret) used for sign-in. Everyone signs in with their own Trakt
 * account. When the build has no Trakt app built in, the user creates one at
 * trakt.tv/oauth/applications and enters its ID and secret in Settings. Kept on this device only.
 */
object TraktAppCredentials {
    private const val KEY_ID = "trakt_own_client_id"
    private const val KEY_SECRET = "trakt_own_client_secret"

    /** Redirect addresses to give the Trakt app, one per line, so the same app works on every Meowio device. */
    const val REDIRECT_URIS = "urn:ietf:wg:oauth:2.0:oob\nnuvio://auth/trakt"
    const val APPS_URL = "https://trakt.tv/oauth/applications/new"

    private fun own(key: String): String? =
        runCatching { LiveTvPlatformStorage.load(key) }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    val clientId: String get() = own(KEY_ID) ?: TraktConfig.CLIENT_ID
    val clientSecret: String get() = own(KEY_SECRET) ?: TraktConfig.CLIENT_SECRET

    val isConfigured: Boolean get() = clientId.isNotBlank() && clientSecret.isNotBlank()
    val isUserProvided: Boolean get() = own(KEY_ID) != null

    fun save(clientId: String, clientSecret: String) {
        LiveTvPlatformStorage.save(KEY_ID, clientId.trim().ifEmpty { null })
        LiveTvPlatformStorage.save(KEY_SECRET, clientSecret.trim().ifEmpty { null })
    }

    fun clear() {
        LiveTvPlatformStorage.save(KEY_ID, null)
        LiveTvPlatformStorage.save(KEY_SECRET, null)
    }
}
