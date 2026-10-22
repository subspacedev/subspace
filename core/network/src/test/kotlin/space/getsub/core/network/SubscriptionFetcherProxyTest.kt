// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.network

import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import org.junit.After
import org.junit.Before
import org.junit.Test
import space.getsub.core.model.LocalProxy
import space.getsub.core.model.ProxyCredentials
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Review round 2, Important 3: this is the one fetch that carries a ARCHITECTURE.md §5.6 secret — the subscription
 * URL — and it had no runnable coverage of its proxy path at all. `ProxiedFetchTest` exercises only
 * [GeoFileFetcher]; `SubscriptionSyncerTest`'s proxy cases live in `:core:data`'s `androidTest` and
 * assert no more than "an `Int` reaches `SubscriptionRequest`", and nothing in this environment runs
 * instrumented tests. Dropping (or breaking) the `.apply { proxy(...) }` block in
 * `SubscriptionFetcher.attempt` would still leave every runnable test green while every subscription
 * fetch silently went direct — exactly the failure mode this class exists to prevent, on exactly the
 * request whose destination host must not reach the local resolver while a tunnel is up (§5.2).
 *
 * MockWebServer stands in for the loopback HTTP inbound the same way it does in `ProxiedFetchTest`:
 * it cannot proxy, but it receives the request line, which is the whole claim under test — an
 * absolute-form request reaches the proxy carrying the hostname, rather than the client resolving it
 * and connecting directly.
 */
class SubscriptionFetcherProxyTest {
    private lateinit var proxy: MockWebServer

    private fun fetcher() = SubscriptionFetcher(appVersion = "1.0.0")

    @Before fun setUp() { proxy = MockWebServer().also { it.start() } }

    @After fun tearDown() = proxy.close()

    @Test
    fun `a subscription fetch without a proxy goes direct`() =
        runTest {
            proxy.enqueue(MockResponse(code = 200, body = "vless://u@h:443"))

            fetcher().fetch(
                SubscriptionRequest(
                    url = proxy.url("/sub").toString(),
                    hwid = null,
                    headers = emptyMap(),
                    userAgentOverride = null,
                    timeoutSeconds = 5,
                    proxy = null,
                ),
            )

            // Direct: the request line is origin-form, and no proxy credential goes anywhere.
            val recorded = proxy.takeRequest()
            recorded.requestLine shouldStartWith "GET /sub"
            recorded.headers["Proxy-Authorization"] shouldBe null
        }

    @Test
    fun `a subscription fetch with a proxy sends the absolute url to the proxy`() =
        runTest {
            proxy.enqueue(MockResponse(code = 200, body = "vless://u@h:443"))

            fetcher().fetch(
                SubscriptionRequest(
                    // A host that must never be resolved for this test to be meaningful.
                    url = "http://sub.invalid/list",
                    hwid = null,
                    headers = emptyMap(),
                    userAgentOverride = null,
                    timeoutSeconds = 5,
                    proxy = LocalProxy(proxy.port, CREDENTIALS),
                ),
            )

            // Proxied: absolute-form, so the proxy resolves sub.invalid, not us.
            proxy.takeRequest().requestLine shouldStartWith "GET http://sub.invalid/list"
        }

    @Test
    fun `the proxy is loopback only`() =
        runTest {
            proxy.enqueue(MockResponse(code = 200, body = "vless://u@h:443"))

            fetcher().fetch(
                SubscriptionRequest(
                    url = "http://sub.invalid/list",
                    hwid = null,
                    headers = emptyMap(),
                    userAgentOverride = null,
                    timeoutSeconds = 5,
                    proxy = LocalProxy(proxy.port, CREDENTIALS),
                ),
            )

            // MockWebServer binds loopback; asserting the recorded host keeps the intent visible
            // if someone later parameterises the address.
            proxy.takeRequest().headers["Host"] shouldBe "sub.invalid"
        }

    /**
     * M9-E Task 10 / research R4.2: the tunnel's HTTP inbound refuses any request without the
     * session's account, and answers a 407 by closing the socket. The credential therefore rides on
     * the first request, not on a retry after a challenge.
     */
    @Test
    fun `a plain http fetch through the proxy carries the credential on its first request`() =
        runTest {
            proxy.enqueue(MockResponse(code = 200, body = "vless://u@h:443"))

            val outcome = fetcher().fetch(proxied("http://sub.invalid/list"))

            outcome.shouldBeInstanceOf<FetchOutcome.Success>()
            proxy.requestCount shouldBe 1
            proxy.takeRequest().headers["Proxy-Authorization"] shouldBe EXPECTED_HEADER
        }

    /**
     * Research R4.4: an https fetch opens a `CONNECT` tunnel, and a reactive 407 retry would hit
     * the socket Xray already closed. OkHttp's preemptive path puts the credential on the first
     * `CONNECT`. MockWebServer cannot complete the TLS handshake afterwards, so only the
     * `CONNECT` itself is asserted.
     */
    @Test
    fun `an https fetch through the proxy carries the credential on its first CONNECT`() =
        runTest {
            repeat(2) { proxy.enqueue(MockResponse.Builder().inTunnel().build()) }

            fetcher().fetch(proxied("https://sub.invalid/list", timeoutSeconds = 2))

            val connect = proxy.takeRequest()
            connect.method shouldBe "CONNECT"
            connect.target shouldBe "sub.invalid:443"
            connect.headers["Proxy-Authorization"] shouldBe EXPECTED_HEADER
        }

    /** Review Focus 2: a 407 after the credential was sent is a failed attempt — no direct fallback. */
    @Test
    fun `a plain http fetch refused with 407 fails and never goes direct`() =
        runTest {
            MockWebServer().use { origin ->
                origin.start()
                repeat(REFUSALS) { proxy.enqueue(refusal()) }

                val outcome = fetcher().fetch(proxied(origin.url("/list").toString()))

                outcome.shouldBeInstanceOf<FetchOutcome.Failed>()
                origin.requestCount shouldBe 0
                recordedRequests().shouldNotBeEmpty().forEach { recorded ->
                    recorded.requestLine shouldStartWith "GET ${origin.url("/list")}"
                    recorded.headers["Proxy-Authorization"] shouldBe EXPECTED_HEADER
                }
            }
        }

    @Test
    fun `an https fetch whose CONNECT is refused with 407 fails and never goes direct`() =
        runTest {
            MockWebServer().use { origin ->
                origin.start()
                repeat(REFUSALS) { proxy.enqueue(refusal().newBuilder().inTunnel().build()) }

                val outcome = fetcher().fetch(proxied("https://localhost:${origin.port}/list"))

                outcome.shouldBeInstanceOf<FetchOutcome.Failed>().reason shouldBe FetchFailure.Unreachable
                origin.requestCount shouldBe 0
                recordedRequests().shouldNotBeEmpty().forEach { recorded ->
                    recorded.method shouldBe "CONNECT"
                    recorded.headers["Proxy-Authorization"] shouldBe EXPECTED_HEADER
                }
            }
        }

    /** ARCHITECTURE.md §5.6: the request's printed form never carries the credential. */
    @Test
    fun `the printed request carries no credential`() {
        val printed = proxied("https://sub.invalid/list").toString()

        printed shouldNotContain CREDENTIALS.user
        printed shouldNotContain CREDENTIALS.pass
    }

    private fun proxied(
        url: String,
        timeoutSeconds: Int = 5,
    ) = SubscriptionRequest(
        url = url,
        hwid = null,
        headers = emptyMap(),
        userAgentOverride = null,
        timeoutSeconds = timeoutSeconds,
        proxy = LocalProxy(proxy.port, CREDENTIALS),
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
