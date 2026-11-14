package com.nuvio.app.features.livetv

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Addresses and response parsing for Xtream Codes compatible IPTV providers. */
object XtreamApi {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Accepts `host:port`, a full address, or a pasted playlist link and returns `scheme://host[:port]`. */
    fun normalizeServer(input: String): String {
        var value = input.trim()
        if (value.isEmpty()) return ""
        if (!value.contains("://")) value = "http://$value"
        val scheme = value.substringBefore("://")
        val authority = value.substringAfter("://").substringBefore('/').substringBefore('?')
        return if (authority.isEmpty()) "" else "$scheme://$authority"
    }

    /**
     * Pulls the login out of a pasted Xtream playlist link
     * (`http://host/get.php?username=U&password=P&type=m3u_plus`).
     */
    fun sourceFromPlaylistLink(link: String): LiveSource? {
        val trimmed = link.trim()
        if (!trimmed.contains("get.php", ignoreCase = true) && !trimmed.contains("player_api.php", ignoreCase = true)) {
            return null
        }
        val query = trimmed.substringAfter('?', "")
        val params = query.split('&').associate {
            it.substringBefore('=').lowercase() to percentDecode(it.substringAfter('=', ""))
        }
        val username = params["username"].orEmpty()
        val password = params["password"].orEmpty()
        val server = normalizeServer(trimmed)
        if (username.isEmpty() || password.isEmpty() || server.isEmpty()) return null
        return LiveSource(type = LiveSourceType.XTREAM, url = server, username = username, password = password)
    }

    fun categoriesUrl(source: LiveSource): String = apiUrl(source, "get_live_categories")

    fun streamsUrl(source: LiveSource): String = apiUrl(source, "get_live_streams")

    fun shortGuideUrl(source: LiveSource, streamId: String): String =
        apiUrl(source, "get_short_epg") + "&stream_id=${percentEncode(streamId)}&limit=$SHORT_GUIDE_LIMIT"

    /** The provider's whole programme guide as an XMLTV file. */
    fun guideFileUrl(source: LiveSource): String =
        "${normalizeServer(source.url)}/xmltv.php?username=${percentEncode(source.username)}" +
            "&password=${percentEncode(source.password)}"

    /** How many programmes to ask for per channel, enough to fill a few hours of the guide grid. */
    private const val SHORT_GUIDE_LIMIT = 12

    fun streamUrl(source: LiveSource, streamId: String, extension: String = "m3u8"): String =
        "${normalizeServer(source.url)}/live/${percentEncode(source.username)}/" +
            "${percentEncode(source.password)}/$streamId.$extension"

    private fun apiUrl(source: LiveSource, action: String): String =
        "${normalizeServer(source.url)}/player_api.php?username=${percentEncode(source.username)}" +
            "&password=${percentEncode(source.password)}&action=$action"

    /** Category id to name. */
    fun parseCategories(body: String): Map<String, String> {
        val array = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonArray ?: return emptyMap()
        val result = LinkedHashMap<String, String>()
        array.forEach { element ->
            val item = element as? JsonObject ?: return@forEach
            val id = item.string("category_id") ?: return@forEach
            val name = item.string("category_name") ?: return@forEach
            result[id] = name
        }
        return result
    }

    fun parseStreams(body: String, categories: Map<String, String>, source: LiveSource): List<LiveChannel> {
        val array = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val streamId = item.string("stream_id") ?: return@mapNotNull null
            val name = item.string("name")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            LiveChannel(
                id = "xtream:$streamId",
                name = name,
                url = streamUrl(source, streamId),
                logo = item.string("stream_icon")?.takeIf(M3uParser::isHttpUrl),
                group = item.string("category_id")?.let(categories::get),
                guideId = item.string("epg_channel_id")?.takeIf(String::isNotBlank),
                xtreamStreamId = streamId,
                number = item.string("num")?.toIntOrNull()?.takeIf { it > 0 },
            )
        }
    }

    /** True when the provider answered a login check with an active account. */
    fun isAuthenticated(body: String): Boolean {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return false
        val user = root["user_info"] as? JsonObject ?: return false
        return user.string("auth") == "1"
    }

    /** Subscription end date and connection limits from the login answer. */
    fun parseAccount(body: String): LiveAccountInfo? {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return null
        val user = root["user_info"] as? JsonObject ?: return null
        val expires = user.string("exp_date")?.toLongOrNull()?.takeIf { it > 0 }?.times(1000)
        return LiveAccountInfo(
            expiresAtMs = expires,
            maxConnections = user.string("max_connections")?.toIntOrNull(),
            activeConnections = user.string("active_cons")?.toIntOrNull(),
        )
    }

    fun loginUrl(source: LiveSource): String =
        "${normalizeServer(source.url)}/player_api.php?username=${percentEncode(source.username)}" +
            "&password=${percentEncode(source.password)}"

    /** Programmes for one channel from `get_short_epg`, oldest first. Titles arrive base64 encoded. */
    @OptIn(ExperimentalEncodingApi::class)
    fun parseShortGuide(body: String): List<LiveProgramme> {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return emptyList()
        val listings = root["epg_listings"] as? JsonArray ?: return emptyList()
        return listings.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val start = item.string("start_timestamp")?.toLongOrNull() ?: return@mapNotNull null
            val stop = item.string("stop_timestamp")?.toLongOrNull() ?: return@mapNotNull null
            val rawTitle = item.string("title").orEmpty()
            val title = runCatching { Base64.Default.decode(rawTitle).decodeToString() }
                .getOrDefault(rawTitle)
                .trim()
            val rawDescription = item.string("description").orEmpty()
            val description = runCatching { Base64.Default.decode(rawDescription).decodeToString() }
                .getOrDefault(rawDescription)
                .trim()
                .take(XmltvParser.MAX_DESCRIPTION)
                .takeIf(String::isNotEmpty)
            if (title.isEmpty()) {
                null
            } else {
                LiveProgramme(title = title, startMs = start * 1000, stopMs = stop * 1000, description = description)
            }
        }.sortedBy { it.startMs }
    }

    private fun JsonObject.string(key: String): String? = when (val value: JsonElement? = this[key]) {
        null, JsonNull -> null
        is JsonPrimitive -> value.content
        else -> null
    }

    private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

    internal fun percentEncode(value: String): String = buildString {
        value.encodeToByteArray().forEach { byte ->
            val char = byte.toInt().toChar()
            if (byte >= 0 && char in UNRESERVED) {
                append(char)
            } else {
                append('%')
                append((byte.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0'))
            }
        }
    }

    internal fun percentDecode(value: String): String {
        if (!value.contains('%') && !value.contains('+')) return value
        val bytes = ArrayList<Byte>(value.length)
        var index = 0
        while (index < value.length) {
            val char = value[index]
            val hex = if (char == '%' && index + 2 < value.length) value.substring(index + 1, index + 3) else null
            val decoded = hex?.takeIf { it.length == 2 }?.toIntOrNull(16)
            when {
                decoded != null -> {
                    bytes += decoded.toByte()
                    index += 3
                }
                char == '+' -> {
                    bytes += ' '.code.toByte()
                    index++
                }
                else -> {
                    char.toString().encodeToByteArray().forEach { bytes += it }
                    index++
                }
            }
        }
        return bytes.toByteArray().decodeToString()
    }
}
