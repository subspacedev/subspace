// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.network.preresolve

import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

class ConnectRacerTest {
    private val loopback = InetAddress.getByName("127.0.0.1")

    private val loopback6 = InetAddress.getByName("::1")

    private fun connectsWithin(address: InetAddress, port: Int, millis: Int): Boolean =
        try {
            Socket().use { it.connect(InetSocketAddress(address, port), millis) }
            true
        } catch (_: IOException) {
            false
        }

    @Test
    fun theListeningAddressWins() = runTest {
        ServerSocket(0, 1, loopback).use { server ->
            val racer = TcpConnectRacer(protect = { true })
            racer.firstToConnect(listOf(loopback6, loopback), server.localPort, 1_500) shouldBe loopback
        }
    }

    @Test
    fun nothingListeningIsNull() = runTest {
        val port = ServerSocket(0, 1, loopback).use { it.localPort } // closed again
        TcpConnectRacer(protect = { true }).firstToConnect(listOf(loopback), port, 500) shouldBe null
    }

    @Test
    fun emptyCandidatesIsNull() = runTest {
        TcpConnectRacer(protect = { true }).firstToConnect(emptyList(), 443, 500) shouldBe null
    }

    @Test
    fun everySocketIsProtectedBeforeConnecting() = runTest {
        val protectedCount = AtomicInteger()
        ServerSocket(0, 1, loopback).use { server ->
            TcpConnectRacer(protect = { protectedCount.incrementAndGet() > 0 })
                .firstToConnect(listOf(loopback), server.localPort, 1_500)
        }
        protectedCount.get() shouldBe 1
    }

    @Test
    fun returnsAsSoonAsTheFirstCandidateConnects() = runBlocking<Unit> {
        // The loser is ::1 with a full accept backlog: further SYNs are dropped, so connect() hangs until
        // its timeout. Unlike a "black hole" IP, a TUN on the dev machine cannot answer for it. The winner
        // is 127.0.0.1 on the same port (the two families do not collide).
        val stalled = runCatching { ServerSocket(0, 1, loopback6) }.getOrNull()
        assumeTrue("no IPv6 loopback", stalled != null)
        val port = stalled!!.localPort
        val fillers = List(4) { Socket().apply { runCatching { connect(InetSocketAddress(loopback6, port), 200) } } }
        try {
            assumeTrue("OS does not stall a full accept backlog", !connectsWithin(loopback6, port, 300))
            ServerSocket(port, 1, loopback).use {
                val start = System.nanoTime()
                val winner = TcpConnectRacer(protect = { true })
                    .firstToConnect(listOf(loopback6, loopback), port, 1_500)
                val elapsedMillis = (System.nanoTime() - start) / 1_000_000
                winner shouldBe loopback
                elapsedMillis shouldBeLessThan 1_000L
            }
        } finally {
            fillers.forEach { runCatching { it.close() } }
            stalled.close()
        }
    }
}
