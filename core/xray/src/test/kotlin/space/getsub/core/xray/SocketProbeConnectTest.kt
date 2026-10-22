// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.xray

import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.After
import org.junit.Test
import space.getsub.core.model.ProxyCredentials
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The probe's raw `CONNECT` exchange against a stand-in for Xray's loopback HTTP inbound. A raw
 * socket so the check URL's scheme never matters (Android checks cleartext policy against the
 * target host, so an `http://` URL made `HttpURLConnection` throw before any traffic).
 */
class SocketProbeConnectTest {
    private val creds = ProxyCredentials(user = "probe-user", pass = "probe-pass")
    private val basic = "Basic " + java.util.Base64.getEncoder().encodeToString("probe-user:probe-pass".toByteArray())
    private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())

    @After
    fun close() = server.close()

    private fun InputStream.readHeader(): String {
        val out = StringBuilder()
        while (!out.endsWith("\r\n\r\n")) {
            val b = read()
            if (b < 0) break
            out.append(b.toChar())
        }
        return out.toString()
    }

    /** Answers one CONNECT with 200, reads the client's byte, then closes — as a failed outbound does. */
    private fun proxyThatClosesAfterTheTunnelOpens(): CompletableFuture<Pair<String, Int>> {
        val seen = CompletableFuture<Pair<String, Int>>()
        thread(isDaemon = true) {
            server.accept().use { client ->
                val header = client.getInputStream().readHeader()
                client.getOutputStream().write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray())
                client.getOutputStream().flush()
                val byte = client.getInputStream().read()
                seen.complete(header to byte)
            }
        }
        return seen
    }

    @Test
    fun anHttpsCheckUrlBecomesAConnectToPort443() {
        val seen = proxyThatClosesAfterTheTunnelOpens()
        val target = checkNotNull(CertificateProbe.connectTarget("https://www.gstatic.com/generate_204"))

        SocketProbeConnect().exchange(server.localPort, target, creds)

        val (header, byte) = seen.get(5, TimeUnit.SECONDS)
        header shouldBe "CONNECT www.gstatic.com:443 HTTP/1.1\r\nHost: www.gstatic.com:443\r\n" +
            "Proxy-Authorization: $basic\r\n\r\n"
        byte shouldBe 0
    }

    @Test
    fun anHttpCheckUrlBecomesAConnectToPort80AndNeedsNoCleartextPermission() {
        val seen = proxyThatClosesAfterTheTunnelOpens()
        val target = checkNotNull(CertificateProbe.connectTarget("http://cp.cloudflare.com/"))

        SocketProbeConnect().exchange(server.localPort, target, creds)

        seen.get(5, TimeUnit.SECONDS).first shouldBe
            "CONNECT cp.cloudflare.com:80 HTTP/1.1\r\nHost: cp.cloudflare.com:80\r\n" +
            "Proxy-Authorization: $basic\r\n\r\n"
    }

    @Test
    fun oneDeadlineBoundsTheWholeExchangeWhenTheProxyNeverAnswers() {
        val held = CompletableFuture<Unit>()
        thread(isDaemon = true) {
            server.accept().use { held.get(10, TimeUnit.SECONDS) }
        }
        val started = System.nanoTime()

        runCatching { SocketProbeConnect(deadlineMillis = 300).exchange(server.localPort, "x.invalid:443", creds) }

        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) shouldBeLessThan 2_000L
        held.complete(Unit)
    }

    @Test
    fun oneDeadlineBoundsTheTunnelPhaseTooWhenTheProxyKeepsItOpen() {
        val held = CompletableFuture<Unit>()
        thread(isDaemon = true) {
            server.accept().use { client ->
                client.getInputStream().readHeader()
                client.getOutputStream().write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray())
                client.getOutputStream().flush()
                held.get(10, TimeUnit.SECONDS)
            }
        }
        val started = System.nanoTime()

        runCatching { SocketProbeConnect(deadlineMillis = 300).exchange(server.localPort, "x.invalid:443", creds) }

        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) shouldBeLessThan 2_000L
        held.complete(Unit)
    }
}
