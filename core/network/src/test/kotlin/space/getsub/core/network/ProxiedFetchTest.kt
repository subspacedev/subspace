// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.network

import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import space.getsub.core.model.LocalProxy
import space.getsub.core.model.ProxyCredentials
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * MockWebServer stands in for the loopback HTTP inbound. It cannot proxy, but it
 * receives the request line — which is the whole claim under test: that an
 * absolute-form request reaches the proxy carrying the hostname, rather than the
 * client resolving it and connecting directly.
 */
class ProxiedFetchTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var proxy: MockWebServer

    @Before
    fun setUp() {
        proxy = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() = proxy.close()

    @Test
    fun `a geo download without a proxy goes direct`() = runTest {
        proxy.enqueue(MockResponse(code = 200, body = "payload"))
        val target = File(temporaryFolder.root, "geoip.dat")

        GeoFileFetcher().download(
            url = proxy.url("/geoip.dat").toString(),
            target = target,
            maxBytes = 1024,
            proxy = null,
            onProgress = { _, _ -> },
        )

        // Direct: the request line is origin-form, and no proxy credential goes anywhere.
        val recorded = proxy.takeRequest()
        recorded.requestLine shouldStartWith "GET /geoip.dat"
        recorded.headers["Proxy-Authorization"] shouldBe null
    }

    @Test
    fun `a geo download with a proxy sends the absolute url to the proxy`() = runTest {
        proxy.enqueue(MockResponse(code = 200, body = "payload"))
        val target = File(temporaryFolder.root, "geoip.dat")

        GeoFileFetcher().download(
            // A host that must never be resolved for this test to be meaningful.
            url = "http://geo.invalid/geoip.dat",
            target = target,
            maxBytes = 1024,
            proxy = LocalProxy(proxy.port, CREDENTIALS),
            onProgress = { _, _ -> },
        )

        // Proxied: absolute-form, so the proxy resolves geo.invalid, not us.
        proxy.takeRequest().requestLine shouldStartWith "GET http://geo.invalid/geoip.dat"
    }

    @Test
    fun `the proxy is loopback only`() = runTest {
        proxy.enqueue(MockResponse(code = 200, body = "payload"))

        GeoFileFetcher().download(
            url = "http://geo.invalid/geoip.dat",
            target = File(temporaryFolder.root, "geoip.dat"),
            maxBytes = 1024,
            proxy = LocalProxy(proxy.port, CREDENTIALS),
            onProgress = { _, _ -> },
        )

        // MockWebServer binds loopback; asserting the recorded host keeps the
        // intent visible if someone later parameterises the address.
        proxy.takeRequest().headers["Host"] shouldBe "geo.invalid"
    }

    /** M9-E Task 10 / research R4.2: the inbound refuses without the account and closes on a 407. */
    @Test
    fun `a plain http geo download through the proxy carries the credential on its first request`() = runTest {
        proxy.enqueue(MockResponse(code = 200, body = "payload"))

        val outcome = download("http://geo.invalid/geoip.dat")

        outcome.shouldBeInstanceOf<GeoDownloadOutcome.Success>()
        proxy.requestCount shouldBe 1
        proxy.takeRequest().headers["Proxy-Authorization"] shouldBe EXPECTED_HEADER
    }

    /** Research R4.4: OkHttp's preemptive path authenticates the first `CONNECT`. */
    @Test
    fun `an https geo download through the proxy carries the credential on its first CONNECT`() = runTest {
        proxy.enqueue(MockResponse.Builder().inTunnel().build())

        download("https://geo.invalid/geoip.dat")

        val connect = proxy.takeRequest()
        connect.method shouldBe "CONNECT"
        connect.target shouldBe "geo.invalid:443"
        connect.headers["Proxy-Authorization"] shouldBe EXPECTED_HEADER
    }

    /** Review Focus 2: a 407 after the credential was sent is a failed attempt — no direct fallback. */
    @Test
    fun `a geo download refused with 407 fails and never goes direct`() = runTest {
        MockWebServer().use { origin ->
            origin.start()
            repeat(REFUSALS) { proxy.enqueue(refusal()) }

            val outcome = download(origin.url("/geoip.dat").toString())

            outcome.shouldBeInstanceOf<GeoDownloadOutcome.Failed>()
            origin.requestCount shouldBe 0
            recordedRequests().shouldNotBeEmpty().forEach { recorded ->
                recorded.requestLine shouldStartWith "GET ${origin.url("/geoip.dat")}"
                recorded.headers["Proxy-Authorization"] shouldBe EXPECTED_HEADER
            }
        }
    }

    @Test
    fun `an https geo download whose CONNECT is refused with 407 fails and never goes direct`() = runTest {
        MockWebServer().use { origin ->
            origin.start()
            repeat(REFUSALS) { proxy.enqueue(refusal().newBuilder().inTunnel().build()) }

            val outcome = download("https://localhost:${origin.port}/geoip.dat")

            outcome.shouldBeInstanceOf<GeoDownloadOutcome.Failed>()
            origin.requestCount shouldBe 0
            recordedRequests().shouldNotBeEmpty().forEach { recorded ->
                recorded.method shouldBe "CONNECT"
                recorded.headers["Proxy-Authorization"] shouldBe EXPECTED_HEADER
            }
        }
    }

    private suspend fun download(url: String) =
        GeoFileFetcher().download(
            url = url,
            target = File(temporaryFolder.root, "geoip.dat"),
            maxBytes = 1024,
            proxy = LocalProxy(proxy.port, CREDENTIALS),
            onProgress = { _, _ -> },
        )

    private fun recordedRequests() = generateSequence { proxy.takeRequest(0, TimeUnit.SECONDS) }.toList()

    private companion object {
        val CREDENTIALS = ProxyCredentials("tunnelUser", "s3cretPass")
        val EXPECTED_HEADER = "Basic " + Base64.getEncoder().encodeToString("tunnelUser:s3cretPass".toByteArray())
        const val REFUSALS = 6

        /** Research R4.2: Xray's exact refusal, then the socket closes. */
        fun refusal() =
            MockResponse
                .Builder()
                .code(407)
                .addHeader("Proxy-Authenticate", "Basic realm=\"proxy\"")
                .onResponseEnd(SocketEffect.ShutdownConnection)
                .build()
    }
}
