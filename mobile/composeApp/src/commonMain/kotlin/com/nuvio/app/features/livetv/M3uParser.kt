package com.nuvio.app.features.livetv

/** Reads M3U / M3U8 channel playlists as published by IPTV providers. */
object M3uParser {

    private val ATTRIBUTE = Regex("""([A-Za-z0-9_-]+)="([^"]*)"""")

    /** True when [text] looks like a channel playlist rather than a web page or an error. */
    fun looksLikePlaylist(text: String): Boolean {
        val head = text.take(4096).trimStart('﻿', ' ', '\n', '\r', '\t')
        return head.startsWith("#EXTM3U", ignoreCase = true) || head.contains("#EXTINF", ignoreCase = true)
    }

    /**
     * True when the playlist is one adaptive video stream (an HLS manifest) rather than a list of
     * channels. Such an address should be played directly.
     */
    fun isSingleStreamManifest(text: String): Boolean {
        val head = text.take(8192)
        return head.contains("#EXT-X-STREAM-INF") ||
            head.contains("#EXT-X-TARGETDURATION") ||
            head.contains("#EXT-X-MEDIA-SEQUENCE")
    }

    fun parse(text: String): LivePlaylist {
        val channels = ArrayList<LiveChannel>()
        val guideUrls = LinkedHashSet<String>()
        val seenIds = HashSet<String>()

        var pendingName: String? = null
        var pendingAttributes: Map<String, String> = emptyMap()
        var pendingGroup: String? = null
        val pendingHeaders = LinkedHashMap<String, String>()

        fun reset() {
            pendingName = null
            pendingAttributes = emptyMap()
            pendingGroup = null
            pendingHeaders.clear()
        }

        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim().trimStart('﻿')
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTM3U", ignoreCase = true) -> {
                    val attributes = attributes(line)
                    listOf("url-tvg", "x-tvg-url", "tvg-url").forEach { key ->
                        attributes[key]?.split(',')?.map(String::trim)?.filter(::isHttpUrl)?.let(guideUrls::addAll)
                    }
                }
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    pendingAttributes = attributes(line)
                    pendingName = displayName(line)
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) ->
                    pendingGroup = line.substringAfter(':').trim().takeIf(String::isNotEmpty)
                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val option = line.substringAfter(':')
                    val value = option.substringAfter('=', "").trim()
                    if (value.isNotEmpty()) {
                        when (option.substringBefore('=').trim().lowercase()) {
                            "http-user-agent" -> pendingHeaders["User-Agent"] = value
                            "http-referrer", "http-referer" -> pendingHeaders["Referer"] = value
                            "http-origin" -> pendingHeaders["Origin"] = value
                        }
                    }
                }
                line.startsWith("#") -> Unit
                else -> {
                    val (url, urlHeaders) = splitUrlHeaders(line)
                    if (isPlayableUrl(url)) {
                        val attributes = pendingAttributes
                        val name = pendingName?.takeIf(String::isNotBlank)
                            ?: attributes["tvg-name"]?.takeIf(String::isNotBlank)
                            ?: url.substringAfterLast('/').substringBefore('?').ifBlank { url }
                        var id = url
                        var duplicate = 1
                        while (!seenIds.add(id)) {
                            duplicate++
                            id = "$url#$duplicate"
                        }
                        channels += LiveChannel(
                            id = id,
                            name = name,
                            url = url,
                            logo = attributes["tvg-logo"]?.takeIf(::isHttpUrl),
                            group = (attributes["group-title"] ?: pendingGroup)?.trim()?.takeIf(String::isNotEmpty),
                            guideId = attributes["tvg-id"]?.trim()?.takeIf(String::isNotEmpty),
                            number = (attributes["tvg-chno"] ?: attributes["channel-number"] ?: attributes["tvg-num"])
                                ?.trim()?.toIntOrNull()?.takeIf { it > 0 },
                            headers = if (pendingHeaders.isEmpty() && urlHeaders.isEmpty()) {
                                emptyMap()
                            } else {
                                LinkedHashMap(pendingHeaders).apply { putAll(urlHeaders) }
                            },
                        )
                    }
                    reset()
                }
            }
        }
        return LivePlaylist(channels = channels, guideUrls = guideUrls.toList())
    }

    private fun attributes(line: String): Map<String, String> =
        ATTRIBUTE.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }

    /** The channel name is whatever follows the first comma that is not inside a quoted attribute. */
    private fun displayName(line: String): String {
        var inQuotes = false
        line.forEachIndexed { index, char ->
            if (char == '"') {
                inQuotes = !inQuotes
            } else if (char == ',' && !inQuotes) {
                return line.substring(index + 1).trim()
            }
        }
        return ""
    }

    /** Some playlists append request headers to the address: `http://host/a.m3u8|User-Agent=X&Referer=Y`. */
    private fun splitUrlHeaders(line: String): Pair<String, Map<String, String>> {
        val bar = line.indexOf('|')
        if (bar < 0) return line to emptyMap()
        val headers = LinkedHashMap<String, String>()
        line.substring(bar + 1).split('&').forEach { part ->
            val key = part.substringBefore('=').trim()
            val value = part.substringAfter('=', "").trim()
            if (key.isNotEmpty() && value.isNotEmpty()) {
                val canonical = when (key.lowercase()) {
                    "user-agent" -> "User-Agent"
                    "referer", "referrer" -> "Referer"
                    "origin" -> "Origin"
                    else -> key
                }
                headers[canonical] = value
            }
        }
        return line.substring(0, bar).trim() to headers
    }

    internal fun isHttpUrl(value: String): Boolean =
        value.startsWith("http://", ignoreCase = true) || value.startsWith("https://", ignoreCase = true)

    private fun isPlayableUrl(value: String): Boolean =
        isHttpUrl(value) ||
            value.startsWith("rtmp://", ignoreCase = true) ||
            value.startsWith("rtsp://", ignoreCase = true) ||
            value.startsWith("udp://", ignoreCase = true) ||
            value.startsWith("rtp://", ignoreCase = true)
}
