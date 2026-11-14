package com.nuvio.app.features.livetv

/**
 * Live TV data shared by the phone and TV apps. Both apps keep identical copies of the files in
 * this package (only the package name differs), so the two behave the same.
 */
data class LiveChannel(
    /** Stable identifier used for favorites and the recent channel. */
    val id: String,
    val name: String,
    val url: String,
    val logo: String? = null,
    val group: String? = null,
    /** Identifier that links the channel to the programme guide. */
    val guideId: String? = null,
    /** Extra request headers some providers require (User-Agent, Referer). */
    val headers: Map<String, String> = emptyMap(),
    /** Xtream stream id, used to ask the provider for this channel's programmes. */
    val xtreamStreamId: String? = null,
    /** Channel number from the playlist (`tvg-chno`) or the provider, when it gives one. */
    val number: Int? = null,
    /** [LiveSource.id] of the playlist this channel came from. */
    val sourceId: String = "",
)

data class LivePlaylist(
    val channels: List<LiveChannel>,
    /** Programme guide addresses announced by the playlist itself. */
    val guideUrls: List<String> = emptyList(),
    /** Account details, for Xtream providers. */
    val account: LiveAccountInfo? = null,
)

data class LiveProgramme(
    val title: String,
    val startMs: Long,
    val stopMs: Long,
    /** Short description from the guide, when it has one. */
    val description: String? = null,
)

data class LiveNowNext(
    val now: LiveProgramme? = null,
    val next: LiveProgramme? = null,
    /**
     * Everything the guide lists for the next hours, oldest first, including [now] and [next].
     * The guide grid draws from this.
     */
    val upcoming: List<LiveProgramme> = emptyList(),
) {
    companion object {
        /** Builds the entry for [nowMs] from a channel's programmes. */
        fun of(programmes: List<LiveProgramme>, nowMs: Long): LiveNowNext? {
            val sorted = programmes.filter { it.stopMs > nowMs }.sortedBy { it.startMs }
                .distinctBy { it.startMs }
            if (sorted.isEmpty()) return null
            return LiveNowNext(
                now = sorted.firstOrNull { it.startMs <= nowMs },
                next = sorted.firstOrNull { it.startMs > nowMs },
                upcoming = sorted,
            )
        }
    }
}

enum class LiveSourceType { M3U, XTREAM }

/** Where channels come from. Users can add several; they are saved per profile, on the device only. */
data class LiveSource(
    val type: LiveSourceType,
    /** M3U: the playlist address. Xtream: the server address. */
    val url: String,
    val username: String = "",
    val password: String = "",
    /** Optional programme guide address that overrides the one in the playlist. */
    val guideUrl: String = "",
    /** Name the user gave this playlist. */
    val name: String = "",
    /** Stable identifier; filled in from the address when the playlist is added. */
    val id: String = "",
    /** Switched-off playlists stay saved but are not loaded. */
    val enabled: Boolean = true,
) {
    /** [id], or one worked out from the address and login when it is blank. */
    val key: String
        get() = id.ifBlank {
            (type.name + "|" + url.trim().lowercase() + "|" + username.trim()).hashCode().toUInt().toString(36)
        }

    /** What to call this playlist on screen. */
    val label: String
        get() = name.trim().ifBlank {
            url.substringAfter("://").substringBefore('/').substringBefore('?').ifBlank { url }
        }
}

/** What an Xtream provider said about the account when the playlist loaded. */
data class LiveAccountInfo(
    /** When the subscription ends, in milliseconds, or null when it does not say or never ends. */
    val expiresAtMs: Long? = null,
    val maxConnections: Int? = null,
    val activeConnections: Int? = null,
)

/**
 * Finds other copies of the same channel, such as the HD and SD versions in one playlist or the
 * same channel in another playlist, so playback can move to a copy that works.
 */
object LiveTvVariants {
    private val QUALITY = setOf(
        "hd", "fhd", "uhd", "sd", "4k", "8k", "hevc", "h265", "h264", "hq", "lq", "720p", "1080p",
        "2160p", "1080i", "576p", "480p", "backup", "alt", "raw", "vip", "50fps", "60fps", "hdr",
    )
    private val PREFIX = Regex("""^\s*(?:\[[^\]]{1,12}]|\([^)]{1,12}\)|[a-z]{2,3}\s*[:|]{1,2})\s*""")
    private val BRACKETS = Regex("""[\[(][^\])]{0,20}[\])]""")

    /** A key shared by copies of the same channel, or "" when the name has nothing to compare. */
    fun key(name: String): String {
        var text = name.lowercase()
        // Country or provider tags in front, such as "US: ", "UK | " or "[DE] ".
        repeat(2) { text = text.replace(PREFIX, "") }
        text = text.replace(BRACKETS, " ")
        val words = text.split(Regex("""[^\p{L}\p{N}+]+""")).filter { it.isNotEmpty() && it !in QUALITY }
        return words.joinToString(" ")
    }

    /**
     * Copies of [channel] in [channels], best first: copies from the same playlist, then from the
     * others, leaving out channels known to be off the air. [channel] itself is not included.
     */
    fun alternatesFor(
        channel: LiveChannel,
        channels: List<LiveChannel>,
        offline: Set<String> = emptySet(),
        limit: Int = 5,
    ): List<LiveChannel> {
        val wanted = key(channel.name)
        if (wanted.isEmpty()) return emptyList()
        return channels.asSequence()
            .filter { it.id != channel.id && it.url != channel.url && it.id !in offline && key(it.name) == wanted }
            .sortedBy { if (it.sourceId == channel.sourceId) 0 else 1 }
            .distinctBy { it.url }
            .take(limit)
            .toList()
    }
}

object LiveTvFilters {
    const val ALL = ""

    /** Group names in first-seen order. */
    fun groups(channels: List<LiveChannel>): List<String> =
        channels.mapNotNull { it.group?.takeIf(String::isNotBlank) }.distinct()

    fun filter(
        channels: List<LiveChannel>,
        query: String = "",
        group: String = ALL,
        favoritesOnly: Boolean = false,
        favorites: Set<String> = emptySet(),
    ): List<LiveChannel> {
        val needle = query.trim()
        return channels.filter { channel ->
            (!favoritesOnly || channel.id in favorites) &&
                (group == ALL || channel.group == group) &&
                (needle.isEmpty() || channel.name.contains(needle, ignoreCase = true) ||
                    channel.number?.toString() == needle)
        }
    }
}

enum class LiveVpnType { WIREGUARD, PROXY }

/**
 * The Live TV VPN. It is used for Live TV playback only: it starts when a channel plays and
 * stops when playback ends. Nothing else in the app, debrid above all, is ever sent through it.
 */
data class LiveVpnSettings(
    val enabled: Boolean = false,
    val type: LiveVpnType = LiveVpnType.WIREGUARD,
    /** A WireGuard configuration file (`[Interface]` / `[Peer]`) from the user's VPN provider. */
    val wireGuardConfig: String = "",
    /** An HTTP proxy, `http://user:pass@host:port` or `host:port`. */
    val proxyUrl: String = "",
) {
    /** True when the VPN is switched on and has something usable to connect with. */
    val ready: Boolean
        get() = enabled && when (type) {
            LiveVpnType.WIREGUARD -> LiveVpnGuard.looksLikeWireGuard(wireGuardConfig)
            LiveVpnType.PROXY -> LiveVpnGuard.parseProxy(proxyUrl) != null
        }
}

data class LiveProxy(val host: String, val port: Int, val username: String = "", val password: String = "") {
    /** `http://user:pass@host:port`, as players such as mpv take it. */
    fun asUrl(): String {
        val login = if (username.isEmpty()) "" else "${XtreamApi.percentEncode(username)}:${XtreamApi.percentEncode(password)}@"
        val shownHost = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
        return "http://$login$shownHost:$port"
    }
}

/** Rules that keep the Live TV VPN to Live TV. */
object LiveVpnGuard {
    /**
     * Debrid services and their download hosts. Requests to these never go through the Live TV
     * VPN, even if a playlist links to them.
     */
    private val DEBRID_HOSTS = listOf(
        "real-debrid.com", "rdeb.io", "rdb.so", "alldebrid.com", "alldebrid.fr", "debrid.it",
        "premiumize.me", "premiumize.com", "torbox.app", "tb-cdn.io", "tb-cdn.st", "debrid-link.com",
        "debrid-link.fr", "offcloud.com", "easydebrid.com", "put.io", "linksnappy.com", "mega-debrid.eu",
        "deepbrid.com", "high-way.me", "stremthru.xyz",
    )

    /** True when [url] belongs to a debrid service. */
    fun isDebrid(url: String): Boolean {
        val host = url.substringAfter("://", "").substringBefore('/').substringBefore('?')
            .substringAfterLast('@').substringBefore(':').trim().lowercase()
        if (host.isEmpty()) return false
        return DEBRID_HOSTS.any { host == it || host.endsWith(".$it") } || host.contains("debrid")
    }

    /** True when [url] is something the Live TV VPN may carry. */
    fun mayRoute(url: String): Boolean =
        (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) && !isDebrid(url)

    fun parseProxy(text: String): LiveProxy? {
        var value = text.trim()
        if (value.isEmpty()) return null
        if (value.contains("://")) {
            if (!value.startsWith("http://", ignoreCase = true)) return null
            value = value.substringAfter("://")
        }
        value = value.substringBefore('/')
        val login = if (value.contains('@')) value.substringBeforeLast('@') else ""
        val address = value.substringAfterLast('@')
        val host: String
        val portText: String
        if (address.startsWith("[")) {
            host = address.substringAfter('[').substringBefore(']')
            portText = address.substringAfter("]:", "")
        } else {
            host = address.substringBeforeLast(':', address)
            portText = address.substringAfterLast(':', "")
        }
        val port = portText.toIntOrNull() ?: return null
        if (host.isBlank() || port !in 1..65535) return null
        return LiveProxy(
            host = host,
            port = port,
            username = XtreamApi.percentDecode(login.substringBefore(':')),
            password = XtreamApi.percentDecode(login.substringAfter(':', "")),
        )
    }

    private val WIREGUARD_KEYS = listOf(
        "PrivateKey", "Address", "DNS", "MTU", "ListenPort", "PublicKey", "PresharedKey", "Endpoint",
        "AllowedIPs", "PersistentKeepalive",
    )

    /**
     * Puts a WireGuard file back on separate lines when a TV keyboard or a paste squashed it onto
     * one line, so it can still be read.
     */
    fun normalizeWireGuard(config: String): String {
        val text = config.replace("\r\n", "\n").trim()
        if (text.contains('\n')) return text
        var spaced = text.replace(Regex("""\s*(\[(?:Interface|Peer)])\s*""", RegexOption.IGNORE_CASE), "\n$1\n")
        WIREGUARD_KEYS.forEach { key ->
            spaced = spaced.replace(Regex("""\s+($key\s*=)""", RegexOption.IGNORE_CASE), "\n$1")
        }
        return spaced.trim()
    }

    /** A light check that [config] is a WireGuard file with a key and a server to connect to. */
    fun looksLikeWireGuard(config: String): Boolean {
        val text = config.lowercase()
        return text.contains("[interface]") && text.contains("privatekey") &&
            text.contains("[peer]") && text.contains("publickey") && text.contains("endpoint")
    }
}
