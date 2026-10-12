// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.network

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Dns
import org.junit.After
import org.junit.Before
import org.junit.Test
import space.getsub.core.model.FragmentPackets
import space.getsub.core.model.FragmentSpec
import space.getsub.core.model.XrayRange

class SubscriptionFetcherRouteTest {
    private lateinit var server: MockWebServer
    private val fetcher = SubscriptionFetcher(HwidProvider { "hwid" }, appVersion = "1.0.0")

    @Before fun setUp() { server = MockWebServer().also { it.start() } }

    @After fun tearDown() { server.close() }

    private fun request(route: FetchRoute, host: String = "front.invalid") =
        SubscriptionRequest(
            url = "http://$host:${server.port}/sub",
            hwidEnabled = false,
            userAgentOverride = null,
            timeoutSeconds = 5,
            route = route,
        )

    @Test
    fun resolveAddressAnIpSendsTheRequestThereWithTheHostHeader() = runTest {
        server.enqueue(MockResponse(code = 200, body = "ok"))

        val outcome = fetcher.fetch(request(FetchRoute(resolveAddress = "127.0.0.1", hostHeader = "real.example")))

        outcome.shouldBeInstanceOf<FetchOutcome.Success>()
        server.takeRequest().headers["Host"] shouldBe "real.example"
    }

    @Test
    fun resolveAddressADomainGoesThroughTheResolver() = runTest {
        server.enqueue(MockResponse(code = 200, body = "ok"))

        fetcher.fetch(request(FetchRoute(resolveAddress = "localhost"))).shouldBeInstanceOf<FetchOutcome.Success>()
    }

    @Test
    fun noRouteKeepsTheDefaultHostHeader() = runTest {
        server.enqueue(MockResponse(code = 200, body = "ok"))

        fetcher.fetch(request(FetchRoute(), host = "127.0.0.1"))

        server.takeRequest().headers["Host"] shouldBe "127.0.0.1:${server.port}"
    }

    @Test
    fun aFragmentSpecInstallsTheFragmentingFactoryAndStillFetches() = runTest {
        val spec = FragmentSpec(FragmentPackets.Records(XrayRange(1, 1)), XrayRange(5, 5), XrayRange(0, 0), null)
        val route = FetchRoute(fragment = spec)
        fetcher.clientFor(request(route)).socketFactory.shouldBeInstanceOf<FragmentingSocketFactory>()

        server.enqueue(MockResponse(code = 200, body = "ok"))
        fetcher.fetch(request(route, host = "127.0.0.1")).shouldBeInstanceOf<FetchOutcome.Success>()
    }

    @Test
    fun throughTheTunnelProxyTheRouteDnsAndFactoryAreSkippedButHostStays() {
        // Plan ruling P1.
        val spec = FragmentSpec(FragmentPackets.TlsHello, XrayRange(5, 5), XrayRange(0, 0), null)
        val proxied = request(FetchRoute("203.0.113.1", "real.example", spec)).copy(proxyPort = 1)
        val client = fetcher.clientFor(proxied)
        (client.socketFactory is FragmentingSocketFactory) shouldBe false
        client.dns shouldBe Dns.SYSTEM
    }

    @Test
    fun aRoutedClientDoesNotUseTheSystemDns() {
        fetcher.clientFor(request(FetchRoute(resolveAddress = "127.0.0.1"))).dns shouldNotBe Dns.SYSTEM
    }

    @Test
    fun toStringRedactsTheRoute() {
        FetchRoute("front.example", "real.example", null).toString().let {
            it shouldNotContain "front.example"
            it shouldNotContain "real.example"
        }
    }
}
