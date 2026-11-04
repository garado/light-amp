package com.garado.amp.data

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Plex's own LAN discovery (GDM), for finding players on this network.
 * The account's device list at plex.tv can miss a device that's sleeping
 * or just woke; broadcasting on the LAN finds it directly.
 *
 * Sends a broadcast `M-SEARCH` to port 32412; each player replies unicast
 * with HTTP-shaped headers. Replies arrive on an ephemeral port, so this
 * needs no multicast lock or WifiManager.
 */
object PlexGdm {

    private const val CLIENT_PORT = 32412
    private const val SEARCH = "M-SEARCH * HTTP/1.0\r\n\r\n"
    private const val LISTEN_MS = 2_000
    private const val SOCKET_TIMEOUT_MS = 400

    /** Players that answer within a couple of seconds. Runs off the main thread. */
    suspend fun findPlayers(): List<PlexPlayer> = withContext(Dispatchers.IO) {
        val found = LinkedHashMap<String, PlexPlayer>()
        runCatching {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = SOCKET_TIMEOUT_MS
                val payload = SEARCH.toByteArray()
                val target = InetAddress.getByName("255.255.255.255")
                // Twice: UDP drops, and a missed search is a device that
                // simply doesn't appear.
                repeat(2) {
                    runCatching {
                        socket.send(DatagramPacket(payload, payload.size, target, CLIENT_PORT))
                    }
                }
                val until = System.currentTimeMillis() + LISTEN_MS
                val buffer = ByteArray(2048)
                while (System.currentTimeMillis() < until) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    val ok = runCatching { socket.receive(packet); true }.getOrDefault(false)
                    if (!ok) continue
                    val body = String(packet.data, 0, packet.length)
                    val player = parseReply(body, packet.address.hostAddress.orEmpty())
                    if (player != null) found.putIfAbsent(player.id, player)
                }
            }
        }
        found.values.toList()
    }

    /**
     * One reply into a player, or null if it can't play.
     * The body is HTTP-shaped: a status line, then `Header: value` lines.
     * The address used is the socket's, not anything the reply claims.
     */
    internal fun parseReply(body: String, fromAddress: String): PlexPlayer? {
        if (fromAddress.isBlank()) return null
        val headers = body.lineSequence()
            .mapNotNull { line ->
                val at = line.indexOf(':')
                if (at <= 0) null
                else line.substring(0, at).trim().lowercase() to line.substring(at + 1).trim()
            }
            .toMap()
        val id = headers["resource-identifier"]?.takeIf { it.isNotBlank() } ?: return null
        // Controllers and servers answer too; only somewhere sound can go is
        // worth listing.
        if (headers["protocol-capabilities"]?.contains("playback") != true) return null
        val port = headers["port"]?.toIntOrNull() ?: return null
        return PlexPlayer(
            id = id,
            name = headers["name"]?.takeIf { it.isNotBlank() }
                ?: headers["product"]?.takeIf { it.isNotBlank() }
                ?: "Plex player",
            product = headers["product"].orEmpty(),
            directUrl = "http://$fromAddress:$port",
            // The reply carried its own identity.
            identityKnown = true,
        )
    }
}
