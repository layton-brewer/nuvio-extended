package com.nuvio.tv.core.livetv

import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.URI
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * A small web server on 127.0.0.1 that the player opens instead of a channel's own address.
 * It fetches the channel for the player and does two things the player cannot:
 *
 * - **Backup channels.** When the channel does not answer, it moves on to other copies of it
 *   (the HD/SD versions or the same channel in another playlist) before the player notices.
 * - **The Live TV VPN.** It sends Live TV, and only Live TV, through the user's WireGuard tunnel or
 *   proxy. The tunnel is started on the first request and stopped once playback has been idle
 *   for a short while, so the VPN switches itself on and off with Live TV playback. Debrid hosts
 *   are never sent through it, even when a playlist links to one.
 *
 * HLS playlists are rewritten so that every segment and key also comes through here. The phone,
 * TV and desktop apps keep identical copies of this file (only the package name differs).
 */
class LiveTvRelay(
    private val baseClient: OkHttpClient,
    /** Starts a WireGuard tunnel, or null on platforms without one. */
    private val wireGuard: WireGuardStarter?,
    private val log: (String) -> Unit = {},
) {
    /** A running tunnel: the proxy that leads into it and how to stop it. */
    class Tunnel(val proxy: LiveProxy, val close: () -> Unit)

    fun interface WireGuardStarter {
        /** Connects with [config], or throws [IOException] with a reason the user can read. */
        @Throws(IOException::class)
        fun start(config: String): Tunnel
    }

    private class Candidate(val url: String, val headers: Map<String, String>)

    private inner class Session(val candidates: List<Candidate>, val vpn: LiveVpnSettings) {
        @Volatile var chosen = 0
        val client: OkHttpClient by lazy { clientFor(vpn) }
    }

    private val secret = ByteArray(18).also(SecureRandom()::nextBytes).let {
        Base64.getUrlEncoder().withoutPadding().encodeToString(it)
    }
    private val sessions = ConcurrentHashMap<String, Session>()
    private val nextSession = AtomicInteger()
    private val active = AtomicInteger()
    private val lastUse = AtomicLong(System.currentTimeMillis())
    private val workers = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "LiveTvRelay").apply { isDaemon = true }
    }
    private val timer = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "LiveTvRelayIdle").apply { isDaemon = true }
    }
    private var server: ServerSocket? = null
    private var tunnel: Tunnel? = null
    private var tunnelConfig: String? = null
    private var tunnelError: String? = null

    init {
        timer.scheduleWithFixedDelay(::closeWhenIdle, IDLE_CHECK_SECONDS, IDLE_CHECK_SECONDS, TimeUnit.SECONDS)
    }

    /** True when playback of [channel] should go through here rather than straight to the channel. */
    fun shouldRelay(channel: LiveChannel, backups: List<LiveChannel>, vpn: LiveVpnSettings): Boolean =
        isHttp(channel.url) && (vpn.ready || backups.any { isHttp(it.url) })

    /**
     * The address the player should open for [channel], trying [backups] in order when it fails.
     * Returns the channel's own address when nothing needs relaying.
     */
    @Synchronized
    fun open(channel: LiveChannel, backups: List<LiveChannel>, vpn: LiveVpnSettings): String {
        if (!shouldRelay(channel, backups, vpn)) return channel.url
        val port = ensureServer()
        val candidates = (listOf(channel) + backups).filter { isHttp(it.url) }
            .map { Candidate(it.url, it.headers) }
        val id = nextSession.incrementAndGet().toString(36)
        sessions[id] = Session(candidates, vpn.takeIf { it.ready } ?: LiveVpnSettings())
        // Keep only the latest few sessions: only one channel plays at a time.
        sessions.keys.sortedBy { it.toLongOrNull(36) ?: 0L }.dropLast(MAX_SESSIONS).forEach(sessions::remove)
        lastUse.set(System.currentTimeMillis())
        return "http://127.0.0.1:$port/$secret/$id/m/${fileName(channel.url)}"
    }

    private fun ensureServer(): Int {
        server?.takeUnless { it.isClosed }?.let { return it.localPort }
        val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        server = socket
        workers.execute {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (e: IOException) {
                    break
                }
                workers.execute { handle(client) }
            }
        }
        return socket.localPort
    }

    private fun closeWhenIdle() {
        val idleFor = System.currentTimeMillis() - lastUse.get()
        if (active.get() > 0 || idleFor < IDLE_STOP_MS) return
        synchronized(this) {
            if (tunnel != null) log("Live TV playback stopped: switching the Live TV VPN off")
            stopTunnel()
            sessions.clear()
            runCatching { server?.close() }
            server = null
        }
    }

    private fun stopTunnel() {
        tunnel?.let { runCatching { it.close() } }
        tunnel = null
        tunnelConfig = null
        tunnelError = null
    }

    /** The proxy leading into the WireGuard tunnel, starting it the first time. */
    @Synchronized
    private fun wireGuardProxy(config: String): LiveProxy {
        val running = tunnel
        if (running != null && tunnelConfig == config) return running.proxy
        stopTunnel()
        val starter = wireGuard ?: throw IOException("WireGuard is not available on this device")
        log("Live TV playback started: switching the Live TV VPN on")
        val started = try {
            starter.start(config)
        } catch (e: IOException) {
            tunnelError = e.message
            throw e
        }
        tunnel = started
        tunnelConfig = config
        return started.proxy
    }

    private fun clientFor(vpn: LiveVpnSettings): OkHttpClient {
        val builder = baseClient.newBuilder()
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
        if (!vpn.ready) return builder.proxy(Proxy.NO_PROXY).build()
        val fixed = if (vpn.type == LiveVpnType.PROXY) LiveVpnGuard.parseProxy(vpn.proxyUrl) else null
        val route = {
            fixed ?: wireGuardProxy(vpn.wireGuardConfig)
        }
        return builder
            .proxySelector(VpnSelector(route))
            .proxyAuthenticator(Authenticator { _, response ->
                if (response.request.header("Proxy-Authorization") != null) return@Authenticator null
                val proxy = runCatching(route).getOrNull() ?: return@Authenticator null
                if (proxy.username.isEmpty() && proxy.password.isEmpty()) return@Authenticator null
                response.request.newBuilder()
                    .header("Proxy-Authorization", Credentials.basic(proxy.username, proxy.password))
                    .build()
            })
            .build()
    }

    /**
     * Sends everything through the VPN except debrid hosts, which always go direct. When the VPN
     * cannot be reached the request is refused rather than sent around it.
     */
    private class VpnSelector(private val route: () -> LiveProxy) : ProxySelector() {
        override fun select(uri: URI): List<Proxy> {
            if (!LiveVpnGuard.mayRoute(uri.toString())) return listOf(Proxy.NO_PROXY)
            val proxy = try {
                route()
            } catch (e: IOException) {
                return listOf(BLOCKED)
            }
            return listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(proxy.host, proxy.port)))
        }

        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
    }

    private fun handle(socket: Socket) {
        active.incrementAndGet()
        lastUse.set(System.currentTimeMillis())
        try {
            socket.soTimeout = 30_000
            socket.use { serve(it) }
        } catch (e: IOException) {
            // The player hung up, usually because it moved on.
        } catch (e: Exception) {
            log("Live TV relay error: ${e.message}")
        } finally {
            active.decrementAndGet()
            lastUse.set(System.currentTimeMillis())
        }
    }

    private fun serve(socket: Socket) {
        val input = BufferedInputStream(socket.getInputStream())
        val output = socket.getOutputStream()
        val requestLine = readLine(input) ?: return
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        val parts = requestLine.split(' ')
        val method = parts.getOrNull(0).orEmpty()
        val path = parts.getOrNull(1).orEmpty().substringBefore('?')
        if (method != "GET" && method != "HEAD") return reply(output, 405, "Method Not Allowed")
        val segments = path.trimStart('/').split('/')
        if (segments.size < 3 || segments[0] != secret) return reply(output, 404, "Not Found")
        val session = sessions[segments[1]] ?: return reply(output, 404, "Not Found")
        val range = headers["range"]
        val playerAgent = headers["user-agent"]
        when (segments[2]) {
            "m" -> serveMain(output, session, segments[1], method, range, playerAgent)
            "u" -> {
                val target = segments.getOrNull(3)?.let(::decode) ?: return reply(output, 400, "Bad Request")
                val candidate = session.candidates[session.chosen.coerceIn(0, session.candidates.lastIndex)]
                val response = try {
                    fetch(session, target, candidate.headers, method, range, playerAgent)
                } catch (e: IOException) {
                    return reply(output, 502, "Bad Gateway")
                }
                response.use { forward(output, session, segments[1], it, method) }
            }
            else -> reply(output, 404, "Not Found")
        }
    }

    /** The channel itself: tries the chosen copy, then the others, until one answers. */
    private fun serveMain(
        output: OutputStream,
        session: Session,
        sessionId: String,
        method: String,
        range: String?,
        agent: String?,
    ) {
        val count = session.candidates.size
        val first = session.chosen
        for (step in 0 until count) {
            val index = (first + step) % count
            val candidate = session.candidates[index]
            val response = try {
                fetch(session, candidate.url, candidate.headers, method, range, agent)
            } catch (e: IOException) {
                log("Live TV channel did not answer (${e.message}); trying a backup")
                if (tunnelError != null && session.vpn.ready) break
                continue
            }
            if (!response.isSuccessful && response.code != 206) {
                response.close()
                log("Live TV channel answered ${response.code}; trying a backup")
                continue
            }
            if (index != session.chosen) {
                session.chosen = index
                if (step > 0) log("Live TV switched to backup channel ${index + 1} of $count")
            }
            response.use { forward(output, session, sessionId, it, method) }
            return
        }
        reply(output, 502, tunnelError?.let { "VPN: $it" } ?: "Bad Gateway")
    }

    private fun fetch(
        session: Session,
        url: String,
        channelHeaders: Map<String, String>,
        method: String,
        range: String?,
        agent: String?,
    ): Response {
        val builder = Request.Builder().url(url)
        if (method == "HEAD") builder.head()
        channelHeaders.forEach { (name, value) -> runCatching { builder.header(name, value) } }
        if (channelHeaders.keys.none { it.equals("User-Agent", ignoreCase = true) } && !agent.isNullOrBlank()) {
            builder.header("User-Agent", agent)
        }
        range?.let { builder.header("Range", it) }
        return try {
            session.client.newCall(builder.build()).execute()
        } catch (e: IllegalArgumentException) {
            throw IOException("not a web address")
        }
    }

    private fun forward(output: OutputStream, session: Session, sessionId: String, response: Response, method: String) {
        val body = response.body ?: run {
            reply(output, 502, "Bad Gateway")
            return
        }
        val type = response.header("Content-Type").orEmpty()
        val finalUrl = response.request.url.toString()
        if (method == "GET" && looksLikeHls(type, finalUrl)) {
            val bytes = body.byteStream().use { readLimited(it, MAX_PLAYLIST_BYTES) }
            val text = bytes.toString(Charsets.UTF_8)
            if (text.trimStart('﻿', ' ', '\r', '\n').startsWith("#EXTM3U")) {
                val rewritten = rewritePlaylist(text, finalUrl, sessionId).toByteArray(Charsets.UTF_8)
                writeHead(output, response.code, response.message, listOf(
                    "Content-Type" to "application/vnd.apple.mpegurl",
                    "Content-Length" to rewritten.size.toString(),
                    "Cache-Control" to "no-cache",
                ))
                output.write(rewritten)
                output.flush()
                return
            }
            // Not a playlist after all: hand it over unchanged.
            writeHead(output, response.code, response.message, passHeaders(response, bytes.size.toLong()))
            output.write(bytes)
            output.flush()
            return
        }
        writeHead(output, response.code, response.message, passHeaders(response, null))
        if (method == "HEAD") return
        body.byteStream().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                lastUse.set(System.currentTimeMillis())
            }
        }
        output.flush()
    }

    private fun passHeaders(response: Response, length: Long?): List<Pair<String, String>> {
        val list = ArrayList<Pair<String, String>>()
        listOf("Content-Type", "Content-Range", "Accept-Ranges", "Last-Modified").forEach { name ->
            response.header(name)?.let { list += name to it }
        }
        val size = length ?: response.header("Content-Length")?.toLongOrNull()
        if (size != null && response.header("Content-Encoding") == null) list += "Content-Length" to size.toString()
        return list
    }

    /** Points every address in an HLS playlist back at this relay. */
    internal fun rewritePlaylist(text: String, baseUrl: String, sessionId: String): String {
        val base = runCatching { URI(baseUrl) }.getOrNull()
        val port = server?.localPort ?: 0
        fun relay(address: String): String {
            val absolute = runCatching { base?.resolve(address.trim())?.toString() }.getOrNull() ?: address.trim()
            if (!isHttp(absolute)) return address
            return "http://127.0.0.1:$port/$secret/$sessionId/u/${encode(absolute)}/${fileName(absolute)}"
        }
        return text.lineSequence().joinToString("\n") { line ->
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> line
                trimmed.startsWith("#") -> if (trimmed.contains("URI=\"")) {
                    URI_ATTRIBUTE.replace(line) { match -> "URI=\"${relay(match.groupValues[1])}\"" }
                } else {
                    line
                }
                else -> relay(trimmed)
            }
        }
    }

    private fun looksLikeHls(type: String, url: String): Boolean {
        val lower = type.lowercase()
        return lower.contains("mpegurl") || url.substringBefore('?').lowercase().let { it.endsWith(".m3u8") || it.endsWith(".m3u") }
    }

    private fun writeHead(output: OutputStream, code: Int, message: String, headers: List<Pair<String, String>>) {
        val head = StringBuilder()
        head.append("HTTP/1.1 ").append(code).append(' ').append(message.ifBlank { "OK" }).append("\r\n")
        headers.forEach { (name, value) -> head.append(name).append(": ").append(value).append("\r\n") }
        head.append("Connection: close\r\n\r\n")
        output.write(head.toString().toByteArray(Charsets.ISO_8859_1))
    }

    private fun reply(output: OutputStream, code: Int, message: String) {
        val body = message.toByteArray(Charsets.UTF_8)
        writeHead(output, code, if (code == 502) "Bad Gateway" else message, listOf(
            "Content-Type" to "text/plain; charset=utf-8",
            "Content-Length" to body.size.toString(),
        ))
        output.write(body)
        output.flush()
    }

    private fun readLine(input: InputStream): String? {
        val line = StringBuilder()
        while (true) {
            val byte = input.read()
            if (byte < 0) return if (line.isEmpty()) null else line.toString()
            if (byte == '\n'.code) return line.toString().trimEnd('\r')
            line.append(byte.toChar())
            if (line.length > 16 * 1024) return null
        }
    }

    private fun readLimited(stream: InputStream, limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(32 * 1024)
        while (out.size() < limit) {
            val read = stream.read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun isHttp(url: String) =
        url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)

    private fun encode(url: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(url.toByteArray(Charsets.UTF_8))

    private fun decode(text: String): String? =
        runCatching { String(Base64.getUrlDecoder().decode(text), Charsets.UTF_8) }.getOrNull()

    /** The last part of an address, kept so players can still tell an HLS playlist by its name. */
    private fun fileName(url: String): String {
        val name = url.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/')
        val clean = name.filter { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }.takeLast(60)
        return clean.ifEmpty { "stream" }
    }

    private companion object {
        val URI_ATTRIBUTE = Regex("""URI="([^"]+)"""")
        val BLOCKED = Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("127.0.0.1", 1))
        const val MAX_SESSIONS = 4
        const val MAX_PLAYLIST_BYTES = 8 * 1024 * 1024
        const val IDLE_CHECK_SECONDS = 15L
        const val IDLE_STOP_MS = 45_000L
    }
}
