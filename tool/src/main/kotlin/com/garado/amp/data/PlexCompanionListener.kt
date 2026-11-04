package com.garado.amp.data

import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The other half of a Companion subscription: a small HTTP server a player
 * can push its timeline to, instead of being polled every second.
 *
 * Polling remains the fallback in
 * [com.garado.amp.playback.PlaybackController] for a player that won't
 * subscribe, or a network that can't route back here.
 */
class PlexCompanionListener(private val onTimeline: (String) -> Unit) {

    private var server: ServerSocket? = null
    private var job: Job? = null

    /** The port a player should push to, or null when it couldn't be opened. */
    val port: Int? get() = server?.localPort

    /** This phone's address on the LAN, for the player to answer at. */
    fun localAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    }.getOrNull()

    /** Open the port and start accepting. Idempotent; returns the port. */
    fun start(scope: CoroutineScope): Int? {
        if (server != null) return port
        val opened = runCatching { ServerSocket(0) }.getOrNull() ?: return null
        server = opened
        job = scope.launch(Dispatchers.IO) {
            while (isActive && !opened.isClosed) {
                val socket = runCatching { opened.accept() }.getOrNull() ?: break
                runCatching { serve(socket) }
                runCatching { socket.close() }
            }
        }
        return opened.localPort
    }

    fun stop() {
        job?.cancel()
        job = null
        runCatching { server?.close() }
        server = null
    }

    /**
     * Reads one request, answers 200, and passes the timeline on.
     * A player may send its state as the request body or as a GET query;
     * both are read the same way.
     */
    private fun serve(socket: Socket) {
        socket.soTimeout = READ_TIMEOUT_MS
        val text = socket.getInputStream().readBytes().decodeToString()
        // Answer first: a player that doesn't get 200 promptly gives up on the subscription.
        socket.getOutputStream().apply {
            write(RESPONSE.toByteArray())
            flush()
        }
        val body = text.substringAfter("\r\n\r\n", missingDelimiterValue = "")
        val payload = if (body.contains("<MediaContainer")) body else text
        if (payload.contains("<Timeline")) onTimeline(payload)
    }

    private companion object {
        const val READ_TIMEOUT_MS = 2_000
        const val RESPONSE = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
    }
}
