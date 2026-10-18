// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.xray

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import space.getsub.core.model.ProxyCredentials
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import kotlin.concurrent.thread

/**
 * The authenticated loopback inbounds against the real libXray v26.10.10 core (M9-E spec §5.3).
 *
 * [InboundAuthTest] proves what the JSON says; only the core proves it is enforced. ARCHITECTURE.md
 * §10.5's failure mode is exactly this one: a SOCKS `accounts` block without `"auth": "password"` is
 * accepted and ignored, and the inbound stays open. So each test talks to the running core over a
 * raw socket and checks the refusal as well as the success.
 *
 * The config carries only a `freedom` outbound, so a granted `CONNECT` reaches the loopback echo
 * server below and nothing leaves the device. Camel-case names: DEX 040 restricts synthetic class
 * names in instrumented tests.
 */
class InboundAuthXrayTest {
    private val creds = ProxyCredentials("inbound-user", "inbound-pass")
    private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")
    private val echo = ServerSocket(0, BACKLOG, loopback)
    private var socksPort = 0
    private var httpPort = 0

    init {
        thread(isDaemon = true, name = "inbound-auth-echo") {
            while (!echo.isClosed) {
                // accept() throws once tearDown closes the socket; that ends the loop.
                val client = runCatching { echo.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    client.use { s ->
                        runCatching { s.getInputStream().copyTo(s.getOutputStream()) }
                    }
                }
            }
        }
    }

    @After
    fun tearDown() {
        // Every other test in this module expects no managed instance; stopXray with none running succeeds.
        LibXrayInvoke.call("stopXray")
        echo.close()
    }

    private suspend fun startCore() {
        val controller = XrayController()
        val ports = controller.allocatePorts(2)
        socksPort = ports[0]
        httpPort = ports[1]
        val config =
            buildString {
                appendLine("{")
                appendLine("""  "log": { "access": "none", "loglevel": "warning" },""")
                appendLine("""  "inbounds": [""")
                appendLine(socksInboundJson(socksPort, sniffing = null, credentials = creds) + ",")
                appendLine(httpInboundJson(httpPort, creds))
                appendLine("  ],")
                appendLine("""  "outbounds": [ { "tag": "direct", "protocol": "freedom" } ]""")
                appendLine("}")
            }
        controller.validate(config)
        controller.start(config) { true }
    }

    private fun dial(port: Int): Socket {
        var last: IOException? = null
        repeat(CONNECT_TRIES) {
            try {
                return Socket().apply {
                    soTimeout = READ_TIMEOUT_MILLIS
                    connect(InetSocketAddress(loopback, port), READ_TIMEOUT_MILLIS)
                }
            } catch (e: IOException) {
                last = e
                Thread.sleep(CONNECT_STEP_MILLIS)
            }
        }
        throw checkNotNull(last)
    }

    private fun InputStream.readExactly(n: Int): ByteArray = ByteArray(n).also { DataInputStream(this).readFully(it) }

    /** Reads up to the end of the response head; never the core's own text in a failure message. */
    private fun InputStream.readHead(): String {
        val out = StringBuilder()
        while (!out.endsWith("\r\n\r\n")) {
            val b = read()
            if (b < 0) break
            out.append(b.toChar())
        }
        return out.toString()
    }

    private fun Socket.assertEchoes() {
        val payload = "ping-through-inbound".toByteArray()
        getOutputStream().write(payload)
        getOutputStream().flush()
        String(getInputStream().readExactly(payload.size)) shouldBe String(payload)
    }

    private fun connectRequest(port: Int): ByteArray =
        byteArrayOf(SOCKS5, CMD_CONNECT, 0, ATYP_IPV4, 127, 0, 0, 1, (port shr 8).toByte(), port.toByte())

    @Test
    fun socksRefusesAClientThatOffersOnlyNoAuth() =
        runTest {
            startCore()
            dial(socksPort).use { s ->
                s.getOutputStream().write(byteArrayOf(SOCKS5, 1, METHOD_NO_AUTH))
                // RFC 1928: 0xFF = no acceptable method (proxy/socks/protocol.go:109-117).
                s.getInputStream().readExactly(2).toList() shouldBe listOf(SOCKS5, METHOD_NONE_ACCEPTABLE)
            }
        }

    @Test
    fun socksRefusesAWrongPassword() =
        runTest {
            startCore()
            dial(socksPort).use { s ->
                s.getOutputStream().write(byteArrayOf(SOCKS5, 1, METHOD_USER_PASS))
                s.getInputStream().readExactly(2).toList() shouldBe listOf(SOCKS5, METHOD_USER_PASS)
                s.getOutputStream().write(userPass(creds.user, "wrong"))
                // A closed socket (EOFException) is as good a refusal as a non-zero status.
                val reply = runCatching { s.getInputStream().readExactly(2).toList() }.getOrNull()
                // Either a non-zero status or a closed socket; never a success.
                reply shouldNotBe listOf(AUTH_VERSION, 0.toByte())
            }
        }

    @Test
    fun socksWithTheAccountConnectsToALoopbackEchoServer() =
        runTest {
            startCore()
            dial(socksPort).use { s ->
                val input = s.getInputStream()
                s.getOutputStream().write(byteArrayOf(SOCKS5, 1, METHOD_USER_PASS))
                input.readExactly(2).toList() shouldBe listOf(SOCKS5, METHOD_USER_PASS)
                s.getOutputStream().write(userPass(creds.user, creds.pass))
                input.readExactly(2).toList() shouldBe listOf(AUTH_VERSION, 0.toByte())
                s.getOutputStream().write(connectRequest(echo.localPort))
                val reply = input.readExactly(SOCKS_IPV4_REPLY_LENGTH)
                reply[1] shouldBe 0.toByte()
                s.assertEchoes()
            }
        }

    @Test
    fun httpConnectWithoutProxyAuthorizationIsRefused() =
        runTest {
            startCore()
            dial(httpPort).use { s ->
                val target = "127.0.0.1:${echo.localPort}"
                s.getOutputStream().write("CONNECT $target HTTP/1.1\r\nHost: $target\r\n\r\n".toByteArray())
                s.getInputStream().readHead() shouldStartWith "HTTP/1.1 407"
            }
        }

    @Test
    fun httpConnectWithProxyAuthorizationReachesALoopbackEchoServer() =
        runTest {
            startCore()
            dial(httpPort).use { s ->
                val target = "127.0.0.1:${echo.localPort}"
                val token = Base64.getEncoder().encodeToString("${creds.user}:${creds.pass}".toByteArray())
                s.getOutputStream().write(
                    "CONNECT $target HTTP/1.1\r\nHost: $target\r\nProxy-Authorization: Basic $token\r\n\r\n"
                        .toByteArray(),
                )
                s.getInputStream().readHead() shouldContain " 200 "
                s.assertEchoes()
            }
        }

    private fun userPass(
        user: String,
        pass: String,
    ): ByteArray {
        val u = user.toByteArray()
        val p = pass.toByteArray()
        return byteArrayOf(AUTH_VERSION, u.size.toByte()) + u + byteArrayOf(p.size.toByte()) + p
    }

    private companion object {
        const val BACKLOG = 8
        const val CONNECT_TRIES = 20
        const val CONNECT_STEP_MILLIS = 100L
        const val READ_TIMEOUT_MILLIS = 5_000
        const val SOCKS5: Byte = 5
        const val AUTH_VERSION: Byte = 1
        const val CMD_CONNECT: Byte = 1
        const val ATYP_IPV4: Byte = 1
        const val METHOD_NO_AUTH: Byte = 0
        const val METHOD_USER_PASS: Byte = 2
        const val METHOD_NONE_ACCEPTABLE: Byte = -1
        const val SOCKS_IPV4_REPLY_LENGTH = 10
    }
}
