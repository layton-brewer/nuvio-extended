package com.nuvio.app.core.sync

/**
 * Keeps personal add-on links out of settings backups.
 *
 * A configured add-on link (for example `https://addon.example/<config>/manifest.json`) usually
 * carries the viewer's debrid or service key. Backups leave those links out unless the viewer
 * explicitly asks for them, and restoring such a backup keeps the personal add-ons already
 * installed on this device. Plain links (`https://host/manifest.json`) are kept.
 */
internal object BackupAddonLinks {
    /** True when [url] carries anything beyond a host and `/manifest.json`: a path, query, fragment or login. */
    fun isPrivate(url: String): Boolean {
        val trimmed = url.trim()
        val schemeEnd = trimmed.indexOf("://")
        if (schemeEnd <= 0) return true
        val rest = trimmed.substring(schemeEnd + 3)
        val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
        val authority = rest.substring(0, authorityEnd)
        if (authority.isEmpty() || '@' in authority) return true
        val tail = rest.substring(authorityEnd)
        if ('?' in tail || '#' in tail) return true
        val path = tail.trimEnd('/').let {
            if (it.endsWith("/manifest.json", ignoreCase = true)) it.dropLast("/manifest.json".length) else it
        }.trim('/')
        return path.isNotEmpty()
    }

    /** Restored add-on links followed by this device's personal links that the backup left out. */
    fun mergeKeptPrivate(restored: List<String>, current: List<String>): List<String> =
        restored + current.filter { url -> isPrivate(url) && restored.none { it.equals(url, ignoreCase = true) } }
}
