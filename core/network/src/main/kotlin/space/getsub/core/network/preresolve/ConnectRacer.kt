// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.network.preresolve

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/** First candidate to accept a TCP connection on the server's port (M9-C spec §2.3). */
public fun interface ConnectRacer {
    public suspend fun firstToConnect(candidates: List<InetAddress>, port: Int, timeoutMillis: Long): InetAddress?
}

/**
 * Opens one protected socket per candidate in parallel; the first to connect wins and the rest are
 * cancelled and closed. Nothing is written: the server sees an empty connection per candidate.
 * Returns as soon as a winner appears, without waiting for the losing probes to time out.
 */
public class TcpConnectRacer(
    private val protect: SocketProtect,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ConnectRacer {
    override suspend fun firstToConnect(
        candidates: List<InetAddress>,
        port: Int,
        timeoutMillis: Long,
    ): InetAddress? {
        if (candidates.isEmpty()) return null
        val sockets = OpenSockets()
        return try {
            // On [io] so the timeout below is real time even when the caller runs on a virtual-time dispatcher.
            withContext(io) {
                val winners = Channel<InetAddress?>(candidates.size)
                val jobs = candidates.map { address ->
                    async { winners.send(if (connects(sockets, address, port, timeoutMillis)) address else null) }
                }
                val winner = try {
                    withTimeoutOrNull(timeoutMillis + GRACE_MILLIS) {
                        var found: InetAddress? = null
                        var remaining = candidates.size
                        while (found == null && remaining-- > 0) found = winners.receive()
                        found
                    }
                } finally {
                    // A blocking connect ignores coroutine cancellation: closing the sockets is what unblocks
                    // it, also when the caller itself is cancelled while receiving.
                    sockets.closeAll()
                    jobs.forEach { it.cancel() }
                }
                winner
            }
        } finally {
            sockets.closeAll()
        }
    }

    private fun connects(sockets: OpenSockets, address: InetAddress, port: Int, timeoutMillis: Long): Boolean {
        val socket = Socket()
        if (!sockets.add(socket)) return false
        return try {
            // Only a source address is needed to force the fd into existence. A loopback target is bound from
            // loopback: macOS refuses a wildcard-bound socket connecting to 127.0.0.1 (EADDRNOTAVAIL), which
            // would make the loopback tests unrunnable on a Mac. Real servers are never loopback.
            socket.bind(InetSocketAddress(if (address.isLoopbackAddress) address else null, 0))
            // A socket that could not bypass the VPN would connect through the tunnel and pin a wrong IP.
            val connectTimeout = timeoutMillis.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
            protect.protect(socket) && run {
                socket.connect(InetSocketAddress(address, port), connectTimeout)
                true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
            // Any failure (IO, bad port, SecurityException) is "did not connect": pre-resolution never fails a connect.
            false
        } finally {
            runCatching { socket.close() }
        }
    }

    /** Sockets in flight; once [closeAll] ran, late arrivals are refused and closed. */
    private class OpenSockets {
        private val open = mutableListOf<Socket>()
        private var closed = false

        @Synchronized
        fun add(socket: Socket): Boolean {
            if (closed) {
                runCatching { socket.close() }
                return false
            }
            open += socket
            return true
        }

        @Synchronized
        fun closeAll() {
            closed = true
            open.forEach { runCatching { it.close() } }
            open.clear()
        }
    }

    private companion object {
        const val GRACE_MILLIS = 250L
    }
}
