package com.nuvio.app.features.updater

import platform.Foundation.NSURL
import platform.Foundation.NSUserDefaults
import platform.UIKit.UIApplication

/**
 * iPhone and iPad builds are sideloaded, so the app cannot install an update itself. It still checks the
 * Meowio releases like the Android app does, and "install" opens the release page so the new IPA can be
 * downloaded and re-sideloaded (AltStore, SideStore or Sideloadly).
 */
actual object AppUpdaterPlatform {
    private const val IGNORED_TAG_KEY = "meowio_updater_ignored_tag"
    private const val CHANNEL_KEY = "meowio_updater_channel"
    private const val RELEASES_PAGE = "https://github.com/layton-brewer/Meowio/releases/latest"

    actual val isSupported: Boolean = true
    actual val isDebugBuild: Boolean = false

    actual fun getSupportedAbis(): List<String> = listOf(IOS_IPA_ABI)

    actual fun getIgnoredTag(): String? = NSUserDefaults.standardUserDefaults.stringForKey(IGNORED_TAG_KEY)

    actual fun setIgnoredTag(tag: String?) {
        val defaults = NSUserDefaults.standardUserDefaults
        if (tag == null) defaults.removeObjectForKey(IGNORED_TAG_KEY) else defaults.setObject(tag, forKey = IGNORED_TAG_KEY)
    }

    actual fun getUpdateChannel(): String? = NSUserDefaults.standardUserDefaults.stringForKey(CHANNEL_KEY)

    actual fun setUpdateChannel(channel: String) {
        NSUserDefaults.standardUserDefaults.setObject(channel, forKey = CHANNEL_KEY)
    }

    actual fun deleteDownloadedApk(path: String) = Unit

    /** Nothing is downloaded in the app: the "downloaded path" is the IPA link, opened by [installDownloadedApk]. */
    actual suspend fun downloadApk(
        assetUrl: String,
        assetName: String,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): Result<String> {
        onProgress(1L, 1L)
        return Result.success(assetUrl)
    }

    actual fun canRequestPackageInstalls(): Boolean = true

    actual fun openUnknownSourcesSettings() = Unit

    actual fun installDownloadedApk(path: String): Result<Unit> = runCatching {
        val url = requireNotNull(NSURL.URLWithString(RELEASES_PAGE))
        UIApplication.sharedApplication.openURL(url = url, options = emptyMap<Any?, Any>(), completionHandler = null)
    }
}
