// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.data

import io.kotest.matchers.shouldBe
import org.junit.Test
import space.getsub.core.model.CidrRange
import space.getsub.core.model.MuxSpec
import space.getsub.core.model.Outbound
import space.getsub.core.model.Security
import space.getsub.core.model.StreamSettings
import space.getsub.core.model.TuningOrigin
import space.getsub.core.model.Udp443Mode
import space.getsub.core.model.VlessOutbound
import space.getsub.core.model.VmessOutbound

/** M9-E spec §1/§3: what server details say about sniffing and mux for a row. */
class TuningReportTest {
    private val spec = MuxSpec(8, 16, Udp443Mode.Reject)
    private val tls = Security.Tls("sni", "chrome", false)
    private val vmess = VmessOutbound("a", 443, "u", 0, "auto", StreamSettings("ws", tls))
    private val vision = VlessOutbound("a", 443, "u", "xtls-rprx-vision", StreamSettings("tcp", tls))

    private fun row(
        outbound: Outbound?,
        rawJson: String? = null,
    ) = StoredProfile(
        id = 1,
        groupId = 1,
        kind = if (rawJson == null) ProfileKind.TYPED else ProfileKind.RAW_JSON,
        name = "n",
        protocol = "x",
        address = "a",
        port = 443,
        transport = "",
        outbound = outbound,
        rawJson = rawJson,
        lastConnectedAt = null,
        lastError = null,
    )

    private fun resolved(
        mux: TuningValue<MuxSpec?>,
        sniffing: TuningValue<Boolean> = TuningValue(true, TuningOrigin.Default),
    ) = ResolvedTuning(
        sniffing = sniffing,
        mux = mux,
        tunMtu = TuningValue(8500, TuningOrigin.Default),
        excludedRoutes = TuningValue(emptyList<CidrRange>(), TuningOrigin.Default),
    )

    private val providerOn = resolved(TuningValue(spec, TuningOrigin.Subscription))
    private val yoursOn = resolved(TuningValue(spec, TuningOrigin.Yours))

    @Test fun `a typed eligible row uses the resolved mux`() {
        val report = tuningReport(row(vmess), providerOn)
        report.mux shouldBe TuningValue(spec, TuningOrigin.Subscription)
        report.muxUsed shouldBe true
    }

    @Test fun `a typed Vision row has mux on but not used`() {
        val report = tuningReport(row(vision), yoursOn)
        report.mux.value shouldBe spec
        report.muxUsed shouldBe false
    }

    @Test fun `mux off is never used`() {
        tuningReport(row(vmess), resolved(TuningValue(null, TuningOrigin.Default))).muxUsed shouldBe false
    }

    @Test fun `a missing row reports the resolution with mux unused`() {
        val report = tuningReport(null, providerOn)
        report.mux shouldBe providerOn.mux
        report.muxUsed shouldBe false
    }

    private val rawOwnMux =
        """{"inbounds":[{"protocol":"socks","sniffing":{"enabled":false}}],
            "outbounds":[{"tag":"proxy","protocol":"vmess","mux":{"enabled":true,"concurrency":4}}]}"""

    @Test fun `a raw row keeps its own mux and sniffing against a directive, origin Config`() {
        val report = tuningReport(row(vmess, rawOwnMux), providerOn)
        report.mux shouldBe TuningValue(MuxSpec(4, 0, Udp443Mode.Reject), TuningOrigin.Config)
        report.muxUsed shouldBe true
        report.sniffing shouldBe TuningValue(false, TuningOrigin.Config)
    }

    @Test fun `your settings replace a raw row's own blocks`() {
        val report =
            tuningReport(
                row(vmess, rawOwnMux),
                resolved(TuningValue(spec, TuningOrigin.Yours), TuningValue(true, TuningOrigin.Yours))
            )
        report.mux shouldBe TuningValue(spec, TuningOrigin.Yours)
        report.muxUsed shouldBe true
        report.sniffing shouldBe TuningValue(true, TuningOrigin.Yours)
    }

    @Test fun `your switch On with mux off removes a raw row's own mux`() {
        val report = tuningReport(row(vmess, rawOwnMux), resolved(TuningValue(null, TuningOrigin.Yours)))
        report.mux shouldBe TuningValue(null, TuningOrigin.Yours)
        report.muxUsed shouldBe false
    }

    @Test fun `a raw row without its own blocks takes the resolution`() {
        val raw = """{"outbounds":[{"tag":"proxy","protocol":"trojan"},{"protocol":"freedom"}]}"""
        val report = tuningReport(row(vmess, raw), providerOn)
        report.mux shouldBe providerOn.mux
        report.muxUsed shouldBe true
        report.sniffing shouldBe providerOn.sniffing
    }

    @Test fun `a raw row whose outermost outbound is ineligible does not use mux`() {
        val raw = """{"outbounds":[{"tag":"proxy","protocol":"socks"}]}"""
        val report = tuningReport(row(vmess, raw), providerOn)
        report.mux shouldBe providerOn.mux
        report.muxUsed shouldBe false
    }
}
