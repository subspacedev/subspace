// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.parser

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.Test
import space.getsub.core.model.Hysteria2Congestion
import space.getsub.core.model.Hysteria2Obfuscation
import space.getsub.core.model.Hysteria2Outbound
import space.getsub.core.model.Hysteria2Unsupported
import space.getsub.core.model.PortHopping

class Hysteria2LinkTest {
    private val pinHex = "ab".repeat(32)

    private fun ok(link: String): Hysteria2Outbound {
        val result = parseHysteria2Link(link, 0)
        result.shouldBeInstanceOf<LinkResult.Ok>()
        return result.profile.outbound as Hysteria2Outbound
    }

    private fun bad(link: String): ParseFailure {
        val result = parseHysteria2Link(link, 7)
        result.shouldBeInstanceOf<LinkResult.Bad>()
        return result.failure
    }

    @Test
    fun `parses a plain link and the hy2 scheme`() {
        val out = ok("hysteria2://s3cret@host.example:8443/?sni=a.example#Berlin")
        out.address shouldBe "host.example"
        out.port shouldBe 8443
        out.password shouldBe "s3cret"
        out.tls.serverName shouldBe "a.example"
        out.tls.alpn shouldBe listOf("h3")
        out.hopping shouldBe null
        out.obfuscation shouldBe Hysteria2Obfuscation.None
        out.congestion shouldBe Hysteria2Congestion.Default
        out.notConnectableReason shouldBe null
        (parseHysteria2Link("hy2://pw@h.example:443#N", 0) as LinkResult.Ok).profile.name shouldBe "N"
        val routed = parseShareLink("hy2://pw@h.example", 0) as LinkResult.Ok
        routed.profile.outbound.shouldBeInstanceOf<Hysteria2Outbound>()
    }

    @Test
    fun `port defaults to 443 and sni to the host`() {
        val out = ok("hysteria2://pw@host.example")
        out.port shouldBe 443
        out.tls.serverName shouldBe "host.example"
    }

    @Test
    fun `userinfo user colon pass stays joined and is percent decoded`() {
        ok("hysteria2://user:p%40ss@host.example:443").password shouldBe "user:p@ss"
    }

    @Test
    fun `missing password is a failure`() {
        val failure = bad("hysteria2://host.example:443")
        failure.reason shouldBe ParseFailureReason.MissingCredential
        failure.index shouldBe 7
    }

    @Test
    fun `ipv6HostBracketed`() {
        val out = ok("hysteria2://pw@[2001:db8::1]:8443?sni=x.example")
        out.address shouldBe "2001:db8::1"
        out.port shouldBe 8443
    }

    @Test
    fun `invalid port is a failure`() {
        bad("hysteria2://pw@host.example:70000").reason shouldBe ParseFailureReason.InvalidPort
        bad("hysteria2://pw@host.example:abc").reason shouldBe ParseFailureReason.InvalidPort
    }

    @Test
    fun `authority port list hops and the first port is the outbound port`() {
        val out = ok("hysteria2://pw@host.example:443,20000-30000?hop-interval=45")
        out.port shouldBe 443
        out.hopping shouldBe PortHopping(listOf(443..443, 20000..30000), 45)
    }

    @Test
    fun `authority range alone hops from its first port`() {
        val out = ok("hysteria2://pw@host.example:20000-30000")
        out.port shouldBe 20000
        out.hopping shouldBe PortHopping(listOf(20000..30000), 30)
    }

    @Test
    fun `singlePortPlusPortsParamHops`() {
        val out = ok("hysteria2://pw@host.example:443?ports=20000-30000")
        out.port shouldBe 20000
        out.hopping shouldBe PortHopping(listOf(20000..30000), 30)
        ok("hysteria2://pw@host.example:443?mport=5000,6000-7000").hopping shouldBe
            PortHopping(listOf(5000..5000, 6000..7000), 30)
    }

    @Test
    fun `authorityRangePlusMportIsAFailure`() {
        bad("hysteria2://pw@host.example:443,20000-30000?mport=1000-2000").reason shouldBe
            ParseFailureReason.InvalidPort
        bad("hysteria2://pw@host.example:20000-30000?ports=1000-2000").reason shouldBe ParseFailureReason.InvalidPort
        bad("hysteria2://pw@host.example:443?ports=1000-2000&mport=3000-4000").reason shouldBe
            ParseFailureReason.InvalidPort
    }

    @Test
    fun `bad port ranges are failures`() {
        bad("hysteria2://pw@host.example:30000-20000").reason shouldBe ParseFailureReason.InvalidPort
        bad("hysteria2://pw@host.example:0-100").reason shouldBe ParseFailureReason.InvalidPort
        bad("hysteria2://pw@host.example:443?ports=1-70000").reason shouldBe ParseFailureReason.InvalidPort
    }

    @Test
    fun `hop interval below 5 or without hopping is a failure`() {
        bad("hysteria2://pw@host.example:443,20000-30000?hop-interval=4").detail shouldBe
            FailureDetail.Malformed(DetailField.HopInterval)
        bad("hysteria2://pw@host.example:443?hop-interval=30").detail shouldBe
            FailureDetail.Malformed(DetailField.HopInterval)
        bad("hysteria2://pw@host.example:443,2000-3000?hop-interval=x").detail shouldBe
            FailureDetail.Malformed(DetailField.HopInterval)
    }

    @Test
    fun `salamander and gecko from the query`() {
        ok("hysteria2://pw@h.example:443?obfs=salamander&obfs-password=hunter2").obfuscation shouldBe
            Hysteria2Obfuscation.Salamander("hunter2")
        ok(
            "hysteria2://pw@h.example:443?obfs=gecko&obfs-password=hunter2" +
                "&obfs-min-packet-size=100&obfs-max-packet-size=900",
        ).obfuscation shouldBe Hysteria2Obfuscation.Gecko("hunter2", 100, 900)
        ok("hysteria2://pw@h.example:443?obfs=none").obfuscation shouldBe Hysteria2Obfuscation.None
    }

    @Test
    fun `shortObfsPasswordIsAFailure`() {
        bad("hysteria2://pw@h.example:443?obfs=salamander&obfs-password=abc").detail shouldBe
            FailureDetail.Malformed(DetailField.Obfuscation)
        bad("hysteria2://pw@h.example:443?obfs=salamander").reason shouldBe ParseFailureReason.MissingCredential
        bad("hysteria2://pw@h.example:443?obfs=mystery&obfs-password=hunter2").detail shouldBe
            FailureDetail.Unsupported(DetailField.Obfuscation)
        bad("hysteria2://pw@h.example:443?obfs-password=hunter2").detail shouldBe
            FailureDetail.Malformed(DetailField.Obfuscation)
        bad("hysteria2://pw@h.example:443?obfs=gecko&obfs-password=hunter2").detail shouldBe
            FailureDetail.Malformed(DetailField.Obfuscation)
        bad(
            "hysteria2://pw@h.example:443?obfs=gecko&obfs-password=hunter2" +
                "&obfs-min-packet-size=0&obfs-max-packet-size=9",
        ).detail shouldBe FailureDetail.Malformed(DetailField.Obfuscation)
    }

    @Test
    fun `up and down become brutal with normalised bandwidth`() {
        ok("hysteria2://pw@h.example:443?up=100&down=200").congestion shouldBe
            Hysteria2Congestion.Brutal("100 mbps", "200 mbps")
        ok("hysteria2://pw@h.example:443?up=50mbps&down=100%20Mbps").congestion shouldBe
            Hysteria2Congestion.Brutal("50mbps", "100 Mbps")
        ok("hysteria2://pw@h.example:443?up=50").congestion shouldBe Hysteria2Congestion.Brutal("50 mbps", "")
    }

    @Test
    fun `unparseable bandwidth is a failure`() {
        bad("hysteria2://pw@h.example:443?up=fast").detail shouldBe FailureDetail.Malformed(DetailField.Bandwidth)
        bad("hysteria2://pw@h.example:443?down=10parsecs").detail shouldBe
            FailureDetail.Malformed(DetailField.Bandwidth)
        bad("hysteria2://pw@h.example:443?up=0").detail shouldBe FailureDetail.Malformed(DetailField.Bandwidth)
        bad("hysteria2://pw@h.example:443?up=-5").detail shouldBe FailureDetail.Malformed(DetailField.Bandwidth)
    }

    @Test
    fun `insecure requests skipping checks but still imports`() {
        val out = ok("hysteria2://pw@h.example:443?insecure=1")
        out.tls.skipChecksRequested shouldBe true
        out.notConnectableReason shouldBe null
        ok("hysteria2://pw@h.example:443?insecure=0").tls.skipChecksRequested shouldBe false
    }

    @Test
    fun `base64PinAccepted`() {
        val b64 =
            java.util.Base64
                .getEncoder()
                .encodeToString(ByteArray(32) { 0xab.toByte() })
        ok("hysteria2://pw@h.example:443?pinSHA256=${b64.replace("=", "%3D")}").tls.pins shouldBe listOf(pinHex)
    }

    @Test
    fun `hex pins with colons and several pins are normalised`() {
        val colons = "AB:".repeat(31) + "AB"
        ok("hysteria2://pw@h.example:443?pinSHA256=$colons,${"cd".repeat(32)}").tls.pins shouldBe
            listOf(pinHex, "cd".repeat(32))
    }

    @Test
    fun `shortPinIsAFailure`() {
        val failure = bad("hysteria2://pw@h.example:443?pinSHA256=abcd")
        failure.detail shouldBe FailureDetail.Malformed(DetailField.Pin)
        failure.toString().contains("abcd") shouldBe false
    }

    @Test
    fun `fp is ignored and alpn is read`() {
        val out = ok("hysteria2://pw@h.example:443?fp=firefox&alpn=h3,hq")
        out.tls.alpn shouldBe listOf("h3", "hq")
    }

    @Test
    fun `security other than tls is a failure`() {
        bad("hysteria2://pw@h.example:443?security=reality").detail shouldBe
            FailureDetail.Unsupported(DetailField.Security)
        ok("hysteria2://pw@h.example:443?security=tls")
    }

    @Test
    fun `duplicateQueryKeyIsAFailure`() {
        bad("hysteria2://pw@h.example:443?sni=a.example&sni=b.example").reason shouldBe ParseFailureReason.MalformedUri
    }

    @Test
    fun `path other than slash is a failure`() {
        bad("hysteria2://pw@h.example:443/extra?sni=a").reason shouldBe ParseFailureReason.MalformedUri
    }

    private fun fm(json: String): String =
        java.net.URLEncoder
            .encode(json, "UTF-8")
            .replace("+", "%20")

    @Test
    fun `fm salamander and udphop masks`() {
        val json =
            """{"udp":[{"type":"salamander","settings":{"password":"hunter2"}},""" +
                """{"type":"udphop","settings":{"mode":"intervalLocal,intervalRemote","interval":20,""" +
                """"remotePorts":"20000-30000"}}]}"""
        val out = ok("hysteria2://pw@h.example:443?fm=${fm(json)}")
        out.obfuscation shouldBe Hysteria2Obfuscation.Salamander("hunter2")
        out.hopping shouldBe PortHopping(listOf(20000..30000), 20)
        out.port shouldBe 443
        out.notConnectableReason shouldBe null
    }

    @Test
    fun `fm gecko through packetSize`() {
        val json = """{"udp":[{"type":"salamander","settings":{"password":"hunter2","packetSize":"100-900"}}]}"""
        ok("hysteria2://pw@h.example:443?fm=${fm(json)}").obfuscation shouldBe
            Hysteria2Obfuscation.Gecko("hunter2", 100, 900)
    }

    @Test
    fun `fm quicParams congestion`() {
        ok("hysteria2://pw@h.example:443?fm=${fm("""{"quicParams":{"congestion":"bbr","bbrProfile":"aggressive"}}""")}")
            .congestion shouldBe Hysteria2Congestion.Bbr("aggressive")
        ok(
            "hysteria2://pw@h.example:443?fm=${fm(
                """{"quicParams":{"congestion":"brutal","brutalUp":"20 mbps","brutalDown":"100 mbps"}}""",
            )}",
        ).congestion shouldBe Hysteria2Congestion.Brutal("20 mbps", "100 mbps")
    }

    @Test
    fun `oldUdpHopShapeBecomesPortHopping`() {
        val json = """{"quicParams":{"udpHop":{"ports":"20000-30000","interval":15}}}"""
        val out = ok("hysteria2://pw@h.example:443?fm=${fm(json)}")
        out.hopping shouldBe PortHopping(listOf(20000..30000), 15)
        out.notConnectableReason shouldBe null
    }

    @Test
    fun `fm wins over query parameters`() {
        val json =
            """{"udp":[{"type":"salamander","settings":{"password":"fromfm1"}}],""" +
                """"quicParams":{"congestion":"bbr"}}"""
        val out =
            ok(
                "hysteria2://pw@h.example:443?obfs=salamander&obfs-password=fromquery&up=10&down=10&fm=${fm(json)}",
            )
        out.obfuscation shouldBe Hysteria2Obfuscation.Salamander("fromfm1")
        out.congestion shouldBe Hysteria2Congestion.Bbr("")
    }

    @Test
    fun `query fills what fm leaves unset`() {
        val out = ok("hysteria2://pw@h.example:443?obfs=salamander&obfs-password=fromquery&fm=${fm("{}")}")
        out.obfuscation shouldBe Hysteria2Obfuscation.Salamander("fromquery")
    }

    @Test
    fun `unknownFmMaskImportsButNotConnectable`() {
        val json = """{"udp":[{"type":"header-custom","settings":{}}]}"""
        val out = ok("hysteria2://pw@h.example:443?fm=${fm(json)}")
        out.notConnectableReason shouldBe Hysteria2Unsupported.UnknownMask
    }

    @Test
    fun `unknown congestion and loss compensation are not connectable`() {
        ok("hysteria2://pw@h.example:443?fm=${fm("""{"quicParams":{"congestion":"reno"}}""")}")
            .notConnectableReason shouldBe Hysteria2Unsupported.UnknownCongestion
        val lossComp = fm("""{"quicParams":{"congestion":"brutal","brutalDisableLossCompensation":true}}""")
        ok("hysteria2://pw@h.example:443?fm=$lossComp")
            .notConnectableReason shouldBe Hysteria2Unsupported.UnknownCongestion
    }

    @Test
    fun `harmless fm extras are ignored`() {
        val json = """{"quicParams":{"debug":true,"maxIdleTimeout":30},"tcp":[]}"""
        ok("hysteria2://pw@h.example:443?fm=${fm(json)}").notConnectableReason shouldBe null
    }

    @Test
    fun `fm that is not json is a typed failure`() {
        val failure = bad("hysteria2://pw@h.example:443?fm=%7Bnope")
        failure.reason shouldBe ParseFailureReason.MalformedJson
        failure.detail shouldBe FailureDetail.Malformed(DetailField.JsonBody)
        bad("hysteria2://pw@h.example:443?fm=%5B1%5D").reason shouldBe ParseFailureReason.MalformedJson
    }

    @Test
    fun `remnawaveShapedLinkRoundTrip`() {
        val finalMask =
            """{"udp":[{"type":"salamander","settings":{"password":"obfs-pass"}}],""" +
                """"quicParams":{"congestion":"brutal","brutalUp":"50 mbps","brutalDown":"100 mbps",""" +
                """"udpHop":{"ports":"20000-30000","interval":30}}}"""
        val link =
            "hysteria2://${"u%3Aabc"}@node.example:9443/?obfs=salamander&obfs-password=obfs-pass&sni=node.example" +
                "&pinSHA256=$pinHex&fm=${fm(finalMask)}#My%20Node"
        val result = parseHysteria2Link(link, 0) as LinkResult.Ok
        val out = result.profile.outbound as Hysteria2Outbound
        result.profile.name shouldBe "My Node"
        out.password shouldBe "u:abc"
        out.port shouldBe 9443
        out.tls.pins shouldBe listOf(pinHex)
        out.obfuscation shouldBe Hysteria2Obfuscation.Salamander("obfs-pass")
        out.hopping shouldBe PortHopping(listOf(20000..30000), 30)
        out.congestion shouldBe Hysteria2Congestion.Brutal("50 mbps", "100 mbps")
        out.notConnectableReason shouldBe null
    }

    @Test
    fun `failures never echo the secrets`() {
        val failure = bad("hysteria2://topsecret@h.example:443?obfs=salamander&obfs-password=abc")
        failure.toString().contains("topsecret") shouldBe false
        failure.toString().contains("abc") shouldBe false
    }
}
