package com.nuvio.tv.core.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupAddonLinksTest {
    private val plain = "https://v3-cinemeta.strem.io"
    private val configured = "https://torrentio.strem.fun/realdebrid=SECRET"

    private fun stringPref(value: String) = buildJsonObject {
        put("type", JsonPrimitive("string"))
        put("value", JsonPrimitive(value))
    }

    private fun feature(vararg urls: String) = buildJsonObject {
        put(BackupAddonLinks.ORDERED_URLS, stringPref(JsonArray(urls.map(::JsonPrimitive)).toString()))
        put(BackupAddonLinks.ENABLED_STATES, stringPref(JsonObject(urls.associateWith { JsonPrimitive(true) }).toString()))
        put("other", stringPref("kept"))
    }

    private fun urls(feature: JsonObject): List<String> =
        (Json.parseToJsonElement((feature[BackupAddonLinks.ORDERED_URLS] as JsonObject)["value"]!!.jsonPrimitive.content) as JsonArray)
            .map { it.jsonPrimitive.content }

    @Test
    fun `plain links are public and configured links are private`() {
        assertFalse(BackupAddonLinks.isPrivate(plain))
        assertFalse(BackupAddonLinks.isPrivate("https://example.com/manifest.json"))
        assertTrue(BackupAddonLinks.isPrivate(configured))
        assertTrue(BackupAddonLinks.isPrivate("https://example.com/a/manifest.json"))
        assertTrue(BackupAddonLinks.isPrivate("https://example.com/manifest.json?key=1"))
        assertTrue(BackupAddonLinks.isPrivate("https://user:pass@example.com"))
        assertTrue(BackupAddonLinks.isPrivate("not a url"))
    }

    @Test
    fun `redact leaves out private links everywhere`() {
        val (redacted, removed) = BackupAddonLinks.redact(feature(plain, configured))
        assertEquals(1, removed)
        assertEquals(listOf(plain), urls(redacted))
        assertFalse(redacted.toString().contains("SECRET"))
        assertEquals("kept", (redacted["other"] as JsonObject)["value"]!!.jsonPrimitive.content)
    }

    @Test
    fun `restoring a redacted backup keeps this device's private add-ons`() {
        val (redacted, _) = BackupAddonLinks.redact(feature(plain))
        val merged = BackupAddonLinks.mergeKeptPrivate(redacted, feature("https://other.example", configured))
        assertEquals(listOf(plain, configured), urls(merged))
        assertTrue(merged.toString().contains("SECRET"))
    }
}
