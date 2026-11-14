package com.nuvio.app.features.livetv

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Turns the saved Live TV settings into text and back. */
object LiveTvCodec {

    fun encodeSource(source: LiveSource): String = sourceJson(source).toString()

    fun decodeSource(text: String?): LiveSource? {
        if (text.isNullOrBlank()) return null
        val json = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
        return sourceFrom(json)
    }

    fun encodeSources(sources: List<LiveSource>): String = buildJsonArray {
        sources.forEach { add(sourceJson(it)) }
    }.toString()

    /** Saved playlists in order, without ones that cannot be read or repeats of the same playlist. */
    fun decodeSources(text: String?): List<LiveSource> {
        if (text.isNullOrBlank()) return emptyList()
        val json = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonArray ?: return emptyList()
        return json.mapNotNull { (it as? JsonObject)?.let(::sourceFrom) }.distinctBy(LiveSource::key)
    }

    fun encodeVpn(settings: LiveVpnSettings): String = buildJsonObject {
        put("enabled", settings.enabled.toString())
        put("type", settings.type.name)
        put("wireGuardConfig", settings.wireGuardConfig)
        put("proxyUrl", settings.proxyUrl)
    }.toString()

    fun decodeVpn(text: String?): LiveVpnSettings {
        if (text.isNullOrBlank()) return LiveVpnSettings()
        val json = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return LiveVpnSettings()
        return LiveVpnSettings(
            enabled = json.text("enabled") == "true",
            type = LiveVpnType.entries.firstOrNull { it.name == json.text("type") } ?: LiveVpnType.WIREGUARD,
            wireGuardConfig = json.text("wireGuardConfig"),
            proxyUrl = json.text("proxyUrl"),
        )
    }

    private fun sourceJson(source: LiveSource) = buildJsonObject {
        put("type", source.type.name)
        put("url", source.url)
        put("username", source.username)
        put("password", source.password)
        put("guideUrl", source.guideUrl)
        if (source.name.isNotEmpty()) put("name", source.name)
        if (source.id.isNotEmpty()) put("id", source.id)
        if (!source.enabled) put("enabled", "false")
    }

    private fun sourceFrom(json: JsonObject): LiveSource? {
        val type = LiveSourceType.entries.firstOrNull { it.name == json.text("type") } ?: return null
        val url = json.text("url").trim()
        if (url.isEmpty()) return null
        return LiveSource(
            type = type,
            url = url,
            username = json.text("username"),
            password = json.text("password"),
            guideUrl = json.text("guideUrl"),
            name = json.text("name"),
            id = json.text("id"),
            enabled = json.text("enabled") != "false",
        )
    }

    fun encodeIds(ids: Collection<String>): String = buildJsonArray {
        ids.forEach { add(JsonPrimitive(it)) }
    }.toString()

    /** Identifiers in saved order, without blanks or repeats. */
    fun decodeIds(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        val json = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonArray ?: return emptyList()
        return json.mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }
            .filter(String::isNotBlank)
            .distinct()
    }

    private fun JsonObject.text(key: String): String =
        (this[key] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content.orEmpty()
}
