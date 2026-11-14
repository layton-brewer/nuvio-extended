package com.nuvio.tv.core.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Keeps personal add-on links out of settings backups.
 *
 * A configured add-on link (for example `https://addon.example/<config>/manifest.json`) usually
 * carries the viewer's debrid or service key inside it. Backups leave those links out unless the
 * viewer explicitly asks for them, and restoring such a backup keeps the personal add-ons that are
 * already installed on this device. Plain links (`https://host/manifest.json`) are kept.
 *
 * Works on the add-on settings exactly as backups store them: each preference is
 * `{"type": ..., "value": ...}`, and the URL list and the per-URL maps are JSON text.
 */
internal object BackupAddonLinks {
    const val ORDERED_URLS = "installed_addon_urls_ordered"
    const val LEGACY_URLS = "installed_addon_urls"
    const val USER_SET_NAMES = "addon_user_set_names"
    const val ENABLED_STATES = "installed_addon_enabled_states"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

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

    /** The add-on settings without personal links, and how many links were left out. */
    fun redact(feature: JsonObject): Pair<JsonObject, Int> {
        val removed = mutableSetOf<String>()
        val out = buildJsonObject {
            feature.forEach { (key, encoded) ->
                when (key) {
                    ORDERED_URLS -> {
                        val urls = urlList(encoded) ?: return@forEach
                        removed += urls.filter(::isPrivate)
                        put(key, stringPref(JsonArray(urls.filterNot(::isPrivate).map(::JsonPrimitive)).toString()))
                    }
                    LEGACY_URLS -> {
                        val urls = stringSet(encoded) ?: return@forEach
                        removed += urls.filter(::isPrivate)
                        put(key, buildJsonObject {
                            put("type", JsonPrimitive("string_set"))
                            put("value", JsonArray(urls.filterNot(::isPrivate).map(::JsonPrimitive)))
                        })
                    }
                    USER_SET_NAMES, ENABLED_STATES -> {
                        val map = urlMap(encoded) ?: return@forEach
                        removed += map.keys.filter(::isPrivate)
                        put(key, stringPref(JsonObject(map.filterKeys { !isPrivate(it) }).toString()))
                    }
                    else -> put(key, encoded)
                }
            }
        }
        return out to removed.size
    }

    /**
     * Adds this device's personal add-ons ([current], encoded like a backup) to the add-on settings
     * from a backup that left them out ([restored]), so restoring never uninstalls them.
     */
    fun mergeKeptPrivate(restored: JsonObject, current: JsonObject): JsonObject {
        val currentUrls = urlList(current[ORDERED_URLS]) ?: stringSet(current[LEGACY_URLS]).orEmpty()
        val keep = currentUrls.filter(::isPrivate)
        if (keep.isEmpty()) return restored
        val restoredUrls = urlList(restored[ORDERED_URLS]) ?: stringSet(restored[LEGACY_URLS]).orEmpty()
        val merged = restoredUrls + keep.filter { url -> restoredUrls.none { it.equals(url, ignoreCase = true) } }
        return buildJsonObject {
            restored.forEach { (key, value) ->
                if (key != ORDERED_URLS && key != LEGACY_URLS && key != USER_SET_NAMES && key != ENABLED_STATES) put(key, value)
            }
            put(ORDERED_URLS, stringPref(JsonArray(merged.map(::JsonPrimitive)).toString()))
            listOf(USER_SET_NAMES, ENABLED_STATES).forEach { key ->
                val restoredMap = urlMap(restored[key]).orEmpty()
                val keptEntries = urlMap(current[key]).orEmpty().filterKeys { it in keep && it !in restoredMap }
                if (restored[key] != null || keptEntries.isNotEmpty()) {
                    put(key, stringPref(JsonObject(restoredMap + keptEntries).toString()))
                }
            }
        }
    }

    private fun stringPref(value: String): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("string"))
        put("value", JsonPrimitive(value))
    }

    private fun stringValue(encoded: JsonElement?): String? =
        ((encoded as? JsonObject)?.get("value") as? JsonPrimitive)?.contentOrNull

    private fun urlList(encoded: JsonElement?): List<String>? = stringValue(encoded)?.let { text ->
        runCatching { (json.parseToJsonElement(text) as JsonArray).mapNotNull { it.jsonPrimitive.contentOrNull } }.getOrNull()
    }

    private fun stringSet(encoded: JsonElement?): List<String>? =
        ((encoded as? JsonObject)?.get("value") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

    private fun urlMap(encoded: JsonElement?): Map<String, JsonElement>? = stringValue(encoded)?.let { text ->
        runCatching { json.parseToJsonElement(text) as JsonObject }.getOrNull()
    }
}
