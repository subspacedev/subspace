// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.data

import io.kotest.matchers.shouldBe
import org.junit.Test
import space.getsub.core.model.FragmentSettings
import space.getsub.core.model.Hysteria2Outbound
import space.getsub.core.model.Hysteria2Tls
import space.getsub.core.model.NoiseSettings
import space.getsub.core.model.Outbound
import space.getsub.core.model.Security
import space.getsub.core.model.StreamSettings
import space.getsub.core.model.VlessOutbound
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
}
