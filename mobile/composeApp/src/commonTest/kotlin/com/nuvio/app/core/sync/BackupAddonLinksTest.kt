package com.nuvio.app.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackupAddonLinksTest {
    @Test
    fun plainLinksArePublicAndConfiguredLinksArePrivate() {
        assertFalse(BackupAddonLinks.isPrivate("https://v3-cinemeta.strem.io/manifest.json"))
        assertFalse(BackupAddonLinks.isPrivate("https://example.com"))
        assertTrue(BackupAddonLinks.isPrivate("https://torrentio.strem.fun/realdebrid=SECRET/manifest.json"))
        assertTrue(BackupAddonLinks.isPrivate("https://example.com/manifest.json?key=1"))
        assertTrue(BackupAddonLinks.isPrivate("https://user:pass@example.com/manifest.json"))
        assertTrue(BackupAddonLinks.isPrivate("not a url"))
    }

    @Test
    fun restoringKeepsThisDevicesPrivateLinks() {
        val plain = "https://example.com/manifest.json"
        val private = "https://addon.example/abc/manifest.json"
        assertEquals(listOf(plain, private), BackupAddonLinks.mergeKeptPrivate(listOf(plain), listOf("https://other.example/manifest.json", private)))
    }
}
