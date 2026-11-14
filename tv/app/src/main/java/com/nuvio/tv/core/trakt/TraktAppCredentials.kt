package com.nuvio.tv.core.trakt

import android.content.Context
import android.content.SharedPreferences
import com.nuvio.tv.BuildConfig

/**
 * The Trakt app (client ID and secret) used for sign-in. Each user signs in with their own Trakt
 * account. When the build has no Trakt app built in, the user creates one at
 * trakt.tv/oauth/applications and pastes its ID and secret in Settings > Trakt. Stored on this
 * device only, never in backups or sync.
 */
object TraktAppCredentials {
    private const val PREFS = "trakt_app_credentials"
    private const val KEY_ID = "client_id"
    private const val KEY_SECRET = "client_secret"

    @Volatile private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }

    private fun own(key: String): String? = prefs?.getString(key, null)?.trim()?.takeIf { it.isNotEmpty() }

    val clientId: String get() = own(KEY_ID) ?: BuildConfig.TRAKT_CLIENT_ID
    val clientSecret: String get() = own(KEY_SECRET) ?: BuildConfig.TRAKT_CLIENT_SECRET

    val isConfigured: Boolean get() = clientId.isNotBlank() && clientSecret.isNotBlank()

    /** True when the user entered their own Trakt app (as opposed to one built into this build). */
    val isUserProvided: Boolean get() = own(KEY_ID) != null

    fun save(clientId: String, clientSecret: String) {
        prefs?.edit()
            ?.putString(KEY_ID, clientId.trim())
            ?.putString(KEY_SECRET, clientSecret.trim())
            ?.apply()
    }

    fun clear() {
        prefs?.edit()?.remove(KEY_ID)?.remove(KEY_SECRET)?.apply()
    }
}
