// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.network.preresolve

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory

/** `VpnService.protect` for a socket; true when protected. Mirrors `:core:xray`'s `TcpSocketProtector`. */
public fun interface SocketProtect {
    public fun protect(socket: Socket): Boolean
}

/**
 * Binds then protects every socket [base] creates, so the DoH query bypasses the VPN during a reconnect
 * (M9-C spec §2.2). Bind first: an unbound socket has no file descriptor to protect; `TcpProbe` does
 * the same. OkHttp only calls the no-argument [createSocket].
 */
public class ProtectedSocketFactory(
    private val base: SocketFactory,
    private val protect: SocketProtect,
) : SocketFactory() {
    override fun createSocket(): Socket =
        base.createSocket().also { socket ->
            try {
                socket.bind(InetSocketAddress(0))
                // An unprotected socket would send the DoH query into the tunnel: refuse it.
                if (!protect.protect(socket)) throw IOException("socket could not be protected")
            } catch (e: IOException) {
                runCatching { socket.close() }
                throw e
            }
        }

    override fun createSocket(host: String?, port: Int): Socket =
        createSocket().apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        createSocket().apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(host: InetAddress?, port: Int): Socket =
        createSocket().apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        createSocket().apply { connect(InetSocketAddress(address, port)) }
}
