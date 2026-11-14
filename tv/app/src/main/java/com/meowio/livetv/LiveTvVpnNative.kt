package com.meowio.livetv

/**
 * The Live TV VPN library (native/livetv-vpn): a WireGuard tunnel that runs inside the app and
 * offers itself only as a password-protected proxy on 127.0.0.1. It never becomes a system VPN,
 * so nothing but the Live TV relay can send traffic through it.
 *
 * The phone and TV apps keep identical copies of this file; the class name is fixed because the
 * native library looks it up by name.
 */
object LiveTvVpnNative {
    /** False when this build does not include the library. */
    val available: Boolean by lazy {
        runCatching { System.loadLibrary("meowiovpn") }.isSuccess
    }

    /** Connects with a WireGuard config. Returns a handle, or 0 with the reason in [lastError]. */
    @JvmStatic external fun start(config: String): Long

    /** Port of the tunnel's proxy on 127.0.0.1. */
    @JvmStatic external fun port(handle: Long): Int

    /** Password the proxy asks for. */
    @JvmStatic external fun token(handle: Long): String

    @JvmStatic external fun lastError(): String

    @JvmStatic external fun stop(handle: Long)
}
