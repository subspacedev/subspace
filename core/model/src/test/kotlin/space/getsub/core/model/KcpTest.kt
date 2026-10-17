// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.model

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.Test

class KcpTest {
    @Test
    fun headerWireNames() {
        KcpHeader.entries.map { it.wireName } shouldBe listOf("dns", "dtls", "srtp", "utp", "wechat", "wireguard")
    }

    @Test
    fun linkNamesMapCaseInsensitively() {
        KcpRules.headerFromLinkName("wechat-video") shouldBe KcpHeaderResult.Ok(KcpHeader.WECHAT)
        KcpRules.headerFromLinkName("WeChat-Video") shouldBe KcpHeaderResult.Ok(KcpHeader.WECHAT)
        KcpRules.headerFromLinkName("DNS") shouldBe KcpHeaderResult.Ok(KcpHeader.DNS)
        KcpRules.headerFromLinkName(" srtp ") shouldBe KcpHeaderResult.Ok(KcpHeader.SRTP)
        KcpRules.headerFromLinkName("utp") shouldBe KcpHeaderResult.Ok(KcpHeader.UTP)
        KcpRules.headerFromLinkName("dtls") shouldBe KcpHeaderResult.Ok(KcpHeader.DTLS)
        KcpRules.headerFromLinkName("wireguard") shouldBe KcpHeaderResult.Ok(KcpHeader.WIREGUARD)
    }

    @Test
    fun noneAndBlankAreNoHeaderAndHttpOrUnknownAreUnsupported() {
        KcpRules.headerFromLinkName("none") shouldBe KcpHeaderResult.None
        KcpRules.headerFromLinkName("NONE") shouldBe KcpHeaderResult.None
        KcpRules.headerFromLinkName("") shouldBe KcpHeaderResult.None
        KcpRules.headerFromLinkName("  ") shouldBe KcpHeaderResult.None
        KcpRules.headerFromLinkName("http") shouldBe KcpHeaderResult.Unsupported
        KcpRules.headerFromLinkName("HTTP") shouldBe KcpHeaderResult.Unsupported
        KcpRules.headerFromLinkName("quic") shouldBe KcpHeaderResult.Unsupported
    }

    @Test
    fun masksFollowTheCaptureOrder() {
        KcpRules.masks(KcpObfuscation(null, null, false)) shouldBe emptyList()
        KcpRules.masks(KcpObfuscation(null, null, true)) shouldBe listOf(KcpMask.Original)
        KcpRules.masks(KcpObfuscation("s", null, false)) shouldBe listOf(KcpMask.Seed("s"))
        KcpRules.masks(KcpObfuscation("s", null, true)) shouldBe listOf(KcpMask.Seed("s"))
        KcpRules.masks(KcpObfuscation("s", KcpHeader.DNS, false)) shouldBe
            listOf(KcpMask.Seed("s"), KcpMask.Header(KcpHeader.DNS))
        // D2-R3: header-only is [original, header].
        KcpRules.masks(KcpObfuscation(null, KcpHeader.UTP, false)) shouldBe
            listOf(KcpMask.Original, KcpMask.Header(KcpHeader.UTP))
        KcpRules.masks(KcpObfuscation(null, KcpHeader.WECHAT, true)) shouldBe
            listOf(KcpMask.Original, KcpMask.Header(KcpHeader.WECHAT))
    }

    @Test
    fun emptySeedCountsAsNoSeed() {
        KcpRules.masks(KcpObfuscation("", null, false)) shouldBe emptyList()
        KcpRules.masks(KcpObfuscation("", KcpHeader.DNS, false)) shouldBe
            listOf(KcpMask.Original, KcpMask.Header(KcpHeader.DNS))
    }

    @Test
    fun whitespaceSeedIsARealSeedTakenVerbatim() {
        // D2-R6: the core uses the seed as a password; only "" means none.
        KcpRules.masks(KcpObfuscation(" ", null, false)) shouldBe listOf(KcpMask.Seed(" "))
    }

    @Test
    fun unsupportedHeaderMakesTheTransportNotConnectable() {
        val ok = TransportOptions.Kcp(1350, 50, KcpObfuscation("s", KcpHeader.DNS, false))
        val bad = TransportOptions.Kcp(null, null, KcpObfuscation(null, null, false, KcpUnsupported.HttpHeader))
        KcpRules.notConnectableReason(ok) shouldBe null
        KcpRules.notConnectableReason(bad) shouldBe KcpUnsupported.HttpHeader
    }

    @Test
    fun seedIsNeverPrinted() {
        val o = KcpObfuscation("SEED-eeee", KcpHeader.DNS, true)
        o.toString() shouldNotContain "SEED-eeee"
        TransportOptions.Kcp(1, 2, o).toString() shouldNotContain "SEED-eeee"
        KcpMask.Seed("SEED-eeee").toString() shouldNotContain "SEED-eeee"
    }

    @Test
    fun streamSettingsRendersTheNewTransports() {
        val kcp = TransportOptions.Kcp(null, null, KcpObfuscation(null, null, false))
        StreamSettings("kcp", Security.None, kcp).toString() shouldBe
            "StreamSettings(network=kcp, security=None, transport=Kcp)"
        StreamSettings("httpupgrade", Security.None, TransportOptions.HttpUpgrade("h", "/p")).toString() shouldBe
            "StreamSettings(network=httpupgrade, security=None, transport=HttpUpgrade)"
    }
}
