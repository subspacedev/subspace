// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.data

import io.kotest.matchers.shouldBe
import org.junit.Test
import space.getsub.core.model.FragmentSettings
import space.getsub.core.model.HttpOutbound
import space.getsub.core.model.Hysteria2Outbound
import space.getsub.core.model.Hysteria2Tls
import space.getsub.core.model.KcpObfuscation
import space.getsub.core.model.NoiseSettings
import space.getsub.core.model.Outbound
import space.getsub.core.model.Security
import space.getsub.core.model.StreamSettings
import space.getsub.core.model.TransportOptions
import space.getsub.core.model.VlessOutbound
import space.getsub.core.model.WireGuardOutbound
import space.getsub.core.parser.PassthroughRejection

/** M9-D1 final review I3: fragment is a TCP mask, so a Hysteria2 row never reports it as applied. */
class ObfuscationReportTest {
    private val hysteria2 =
        Hysteria2Outbound(address = "h.example", port = 443, password = "pw", tls = Hysteria2Tls("h.example"))

    private val vless =
        VlessOutbound(
            address = "v.example",
            port = 443,
            uuid = "1e0f2a2e-6b2b-4b9a-9a3b-000000000000",
            flow = null,
            stream = StreamSettings(network = "tcp", security = Security.None),
        )

    private val rawHysteria =
        """{"outbounds":[{"protocol":"hysteria","tag":"proxy",
        "settings":{"version":2,"address":"h.example","port":443},
        "streamSettings":{"network":"hysteria","hysteriaSettings":{"version":2,"auth":"pw"},"security":"tls"}}]}"""

    private fun row(
        outbound: Outbound,
        kind: ProfileKind = ProfileKind.TYPED,
        rawJson: String? = null,
        rejection: PassthroughRejection? = null,
    ) = StoredProfile(
        id = 1,
        groupId = 1,
        kind = kind,
        name = "n",
        protocol = "x",
        address = outbound.address,
        port = outbound.port,
        transport = "",
        outbound = outbound,
        rawJson = rawJson,
        lastConnectedAt = null,
        lastError = null,
        passthroughRejection = rejection,
    )

    private val userOn =
        resolveObfuscation(
            FragmentSettings.DEFAULT.copy(enabled = true),
            NoiseSettings.DEFAULT.copy(enabled = true),
            emptyMap(),
        )

    private val directiveOn =
        resolveObfuscation(
            FragmentSettings.DEFAULT,
            NoiseSettings.DEFAULT,
            mapOf("fragmentation-enable" to "1"),
        )

    @Test
    fun aTypedHysteria2RowNeverReportsFragmentButKeepsNoise() {
        val report = obfuscationReport(row(hysteria2), userOn)
        report.fragment shouldBe MaskReport(ObfuscationOrigin.None, null)
        report.noise shouldBe MaskReport(ObfuscationOrigin.Yours, null)
        obfuscationReport(row(hysteria2), directiveOn).fragment shouldBe MaskReport(ObfuscationOrigin.None, null)
    }

    @Test
    fun aRawHysteriaRowRunningItsProjectionMatchesTheRawPath() {
        val projected =
            row(hysteria2, ProfileKind.RAW_JSON, rawHysteria, PassthroughRejection.entries.first())
        val asWritten = row(hysteria2, ProfileKind.RAW_JSON, rawHysteria)
        obfuscationReport(projected, userOn).fragment shouldBe obfuscationReport(asWritten, userOn).fragment
        obfuscationReport(projected, userOn).fragment shouldBe MaskReport(ObfuscationOrigin.None, null)
    }

    @Test
    fun aTypedTcpRowStillReportsTheFragment() {
        obfuscationReport(row(vless), userOn).fragment shouldBe MaskReport(ObfuscationOrigin.Yours, null)
        obfuscationReport(row(vless), directiveOn).fragment shouldBe
            MaskReport(ObfuscationOrigin.Subscription, null)
    }

    // ── M9-D2 research §5.3: the report never claims a mask that is not emitted ──

    private val none = MaskReport(ObfuscationOrigin.None, null)
    private val yours = MaskReport(ObfuscationOrigin.Yours, null)

    private val wireGuard = WireGuardOutbound("w.example", 51820, "k", listOf("10.0.0.2/32"), "p", null)
    private val http = HttpOutbound("p.example", 8080, null, null, Security.None)
    private val kcp =
        vless.copy(
            stream =
            StreamSettings("kcp", Security.None, TransportOptions.Kcp(null, null, KcpObfuscation(null, null, false))),
        )
    private val upgrade = vless.copy(stream = StreamSettings("httpupgrade", Security.None))

    @Test
    fun wireGuardAndKcpReportNoiseButNeverFragment() {
        for (outbound in listOf(wireGuard, kcp)) {
            val report = obfuscationReport(row(outbound), userOn)
            report.fragment shouldBe none
            report.noise shouldBe yours
            obfuscationReport(row(outbound), directiveOn).fragment shouldBe none
        }
    }

    @Test
    fun httpAndHttpUpgradeReportFragmentButNeverNoise() {
        for (outbound in listOf(http, upgrade)) {
            val report = obfuscationReport(row(outbound), userOn)
            report.fragment shouldBe yours
            report.noise shouldBe none
        }
    }

    @Test
    fun rawRowsAgreeWithTheTypedTable() {
        val rawWg = """{"outbounds":[{"tag":"proxy","protocol":"wireguard","settings":{}}]}"""
        val rawHttp =
            """{"outbounds":[{"tag":"proxy","protocol":"http","settings":{"address":"p.example","port":1}}]}"""
        val wg = obfuscationReport(row(wireGuard, ProfileKind.RAW_JSON, rawWg), userOn)
        wg.fragment shouldBe none
        wg.noise shouldBe yours
        val h = obfuscationReport(row(http, ProfileKind.RAW_JSON, rawHttp), userOn)
        h.fragment shouldBe yours
        h.noise shouldBe none
    }
}
