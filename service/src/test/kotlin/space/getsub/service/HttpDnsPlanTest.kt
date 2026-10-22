// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.Test
import space.getsub.core.model.DnsResolver
import space.getsub.core.model.HttpOutbound
import space.getsub.core.model.Profile
import space.getsub.core.model.Security
import space.getsub.core.xray.ComposeResult
import space.getsub.core.xray.ConfigResult
import space.getsub.core.xray.DnsPlanner
import space.getsub.core.xray.RawConfigComposer
import space.getsub.core.xray.TunnelSettings
import space.getsub.core.xray.XrayConfigGenerator

/**
 * Ruling D2-R16, as the service applies it: the TCP DNS plan is forced only for a typed row whose outbound
 * cannot carry UDP. A raw row with an `http` first outbound has an [HttpOutbound] projection too, but runs as
 * written with its own `dns`, so it must stay on the pure-passthrough branch exactly as before (re-review I2).
 */
class HttpDnsPlanTest {
    private val http = HttpOutbound("proxy.example", 8080, null, null, Security.None)
    private val settings =
        TunnelSettings(
            socksPort = 41080,
            dnsServer = "1.1.1.1",
            enableSniffing = true,
            proxyCredentials = TEST_CREDENTIALS,
        )

    private val rawHttp =
        """
        {
          "dns": { "servers": ["8.8.8.8"] },
          "outbounds": [ { "tag": "proxy", "protocol": "http",
            "settings": { "servers": [ { "address": "proxy.example", "port": 8080 } ] } } ]
        }
        """.trimIndent()

    private fun planFor(runsAsWritten: Boolean) =
        DnsPlanner.plan(
            profileDns = null,
            setting = DnsResolver.DEFAULT,
            routing = null,
            outboundCarriesUdp = dnsPlanOutboundCarriesUdp(http, runsAsWritten),
        )

    @Test
    fun `a raw http row on default dns settings stays on the pure passthrough branch`() {
        val runsAsWritten = runsAsWrittenAtConnect(storedRunsAsWritten = true, rawJson = rawHttp)
        runsAsWritten shouldBe true
        val plan = planFor(runsAsWritten)
        plan shouldBe null
        passthroughPlanFor(routingActive = false, dnsPlanPresent = plan != null, null, "/flat")
            .overrideApplies shouldBe false
        val json = (RawConfigComposer.compose(rawHttp, settings, "/flat", override = null) as ComposeResult.Ok).json
        json shouldContain "8.8.8.8"
        json shouldNotContain "tcp://"
        json shouldNotContain "dns-out"
    }

    @Test
    fun `a typed http row still gets the forced plan and tcp dns`() {
        val plan = planFor(runsAsWritten = false)
        plan shouldNotBe null
        val result =
            XrayConfigGenerator.generate(Profile("id", "n", http), settings.copy(dns = plan)) as ConfigResult.Ok
        result.json shouldContain "\"tcp://1.1.1.1:53\""
        result.json shouldContain "\"dns-out\""
    }
}
