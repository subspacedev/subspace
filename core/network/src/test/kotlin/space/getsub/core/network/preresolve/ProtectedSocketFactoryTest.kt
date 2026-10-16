// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.network.preresolve

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory

class ProtectedSocketFactoryTest {
    private val events = mutableListOf<String>()
    private val socket = object : Socket() {
        override fun bind(bindpoint: java.net.SocketAddress?) {
            events += "bind"
        }

        override fun close() {
            events += "close"
        }
    }
    private val base = object : SocketFactory() {
        override fun createSocket(): Socket = socket
        override fun createSocket(host: String?, port: Int): Socket = socket
        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket = socket
        override fun createSocket(host: InetAddress?, port: Int): Socket = socket
        override fun createSocket(
            address: InetAddress?,
            port: Int,
            localAddress: InetAddress?,
            localPort: Int,
        ): Socket = socket
    }

    private fun recording(result: Boolean) =
        SocketProtect {
            events += "protect"
            result
        }

    @Test
    fun bindsThenProtectsAndReturnsTheSocket() {
        val result = ProtectedSocketFactory(base, recording(true)).createSocket()
        (result === socket) shouldBe true
        events shouldBe listOf("bind", "protect")
    }

    @Test
    fun failedProtectClosesTheSocketAndThrows() {
        val e = shouldThrow<IOException> { ProtectedSocketFactory(base, recording(false)).createSocket() }
        e.message shouldBe "socket could not be protected"
        events shouldBe listOf("bind", "protect", "close")
    }
}
