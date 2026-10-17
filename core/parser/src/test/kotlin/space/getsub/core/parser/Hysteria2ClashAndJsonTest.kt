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

class Hysteria2ClashAndJsonTest {
    private val pinHex = "ab".repeat(32)
    private val otherPin = "cd".repeat(32)

    private fun clash(body: String): ParseOutcome =
        parseClashYaml(
            "proxies:\n  - name: HY\n    type: hysteria2\n    server: h.example\n    port: 8443\n" +
                body.trimIndent().prependIndent("    "),
        )

    private fun clashOk(body: String): Hysteria2Outbound {
        val outcome = clash(body)
        outcome.failures shouldBe emptyList()
        return outcome.profiles
            .single()
            .outbound
            .shouldBeInstanceOf<Hysteria2Outbound>()
    }

    private fun raw(
        hysteria: String = """{"version":2,"auth":"s3cret"}""",
        tls: String = """{"serverName":"sni.example","fingerprint":"firefox","alpn":["h3"]}""",
        finalmask: String? = null,
        extraStream: String = "",
    ): ParseOutcome {
        val mask = finalmask?.let { ""","finalmask":$it""" }.orEmpty()
        return parseXrayJson(
            """{"remarks":"Raw","outbounds":[{"protocol":"hysteria","tag":"proxy",
            "settings":{"version":2,"address":"h.example","port":8443},
            "streamSettings":{"network":"hysteria","hysteriaSettings":$hysteria,
            "security":"tls","tlsSettings":$tls$mask$extraStream}}]}""",
        )
    }

    private fun rawOk(
        hysteria: String = """{"version":2,"auth":"s3cret"}""",
        tls: String = """{"serverName":"sni.example","fingerprint":"firefox","alpn":["h3"]}""",
        finalmask: String? = null,
    ): Hysteria2Outbound {
        val outcome = raw(hysteria, tls, finalmask)
        outcome.failures shouldBe emptyList()
        return outcome.profiles
            .single()
            .outbound
            .shouldBeInstanceOf<Hysteria2Outbound>()
    }

    @Test
    fun `clash hysteria2 reads the basic fields`() {
        val out =
            clashOk(
                """
                password: s3cret
                sni: a.example
                alpn:
                  - h3
                  - h2
                skip-cert-verify: true
                """,
            )
        out.address shouldBe "h.example"
        out.port shouldBe 8443
        out.password shouldBe "s3cret"
        out.tls.serverName shouldBe "a.example"
        out.tls.alpn shouldBe listOf("h3", "h2")
        out.tls.skipChecksRequested shouldBe true
        out.hopping shouldBe null
        out.obfuscation shouldBe Hysteria2Obfuscation.None
        out.congestion shouldBe Hysteria2Congestion.Default
    }

    @Test
    fun `clash hysteria2 defaults sni to the server and alpn to h3`() {
        val out = clashOk("password: pw")
        out.tls.serverName shouldBe "h.example"
        out.tls.alpn shouldBe listOf("h3")
        out.tls.skipChecksRequested shouldBe false
    }

    @Test
    fun `clash hysteria2 reads ports hop-interval and bandwidth`() {
        val out =
            clashOk(
                """
                password: pw
                ports: 20000-30000
                hop-interval: 10
                up: 100
                down: "200 Mbps"
                """,
            )
        out.hopping shouldBe PortHopping(listOf(20000..30000), 10)
        out.congestion shouldBe Hysteria2Congestion.Brutal("100 mbps", "200 Mbps")
    }

    @Test
    fun `clash hysteria2 obfuscation salamander and gecko`() {
        clashOk("password: pw\nobfs: salamander\nobfs-password: hunter22")
            .obfuscation shouldBe Hysteria2Obfuscation.Salamander("hunter22")
        clashOk(
            "password: pw\nobfs: gecko\nobfs-password: hunter22\nobfs-min-packet-size: 100\nobfs-max-packet-size: 900",
        ).obfuscation shouldBe Hysteria2Obfuscation.Gecko("hunter22", 100, 900)
    }

    @Test
    fun `clash hysteria2 fingerprint is a certificate pin and client-fingerprint is ignored`() {
        val out = clashOk("password: pw\nfingerprint: $pinHex\nclient-fingerprint: chrome")
        out.tls.pins shouldBe listOf(pinHex)
    }

    @Test
    fun `clash hysteria2 bad values are typed failures`() {
        clash("password: pw\nobfs: salamander\nobfs-password: ab").failures.single().reason shouldBe
            ParseFailureReason.MalformedUri
        clash("password: pw\nup: 0.1").failures.single().detail shouldBe
            FailureDetail.Malformed(DetailField.Bandwidth)
        clash("password: pw\nports: 0-5").failures.single().reason shouldBe ParseFailureReason.InvalidPort
        clash("password: pw\nports: 20000-30000\nhop-interval: 2").failures.single().detail shouldBe
            FailureDetail.Malformed(DetailField.HopInterval)
        clash("password: pw\nfingerprint: nothex").failures.single().detail shouldBe
            FailureDetail.Malformed(DetailField.Pin)
        clash("sni: a.example").failures.single().reason shouldBe ParseFailureReason.MissingCredential
    }

    @Test
    fun `third-party subscription shape projects to a Hysteria2Outbound`() {
        val outcome = raw(finalmask = """{"quicParams":{"congestion":"bbr"}}""")
        outcome.failures shouldBe emptyList()
        val profile = outcome.profiles.single()
        val out = profile.outbound.shouldBeInstanceOf<Hysteria2Outbound>()
        out.address shouldBe "h.example"
        out.port shouldBe 8443
        out.password shouldBe "s3cret"
        out.tls.serverName shouldBe "sni.example"
        out.tls.alpn shouldBe listOf("h3")
        out.congestion shouldBe Hysteria2Congestion.Bbr("")
        out.notConnectableReason shouldBe null
        profile.name shouldBe "Raw"
        profile.rawJson?.contains("\"hysteria\"") shouldBe true
    }

    @Test
    fun rawHysteriaOutboundProjectsToHysteria2() {
        val out = rawOk()
        out.tls.serverName shouldBe "sni.example"
        out.hopping shouldBe null
        out.obfuscation shouldBe Hysteria2Obfuscation.None
        out.congestion shouldBe Hysteria2Congestion.Default
    }

    @Test
    fun `raw udphop and salamander masks project`() {
        val salamander = """{"type":"salamander","settings":{"password":"hunter22"}}"""
        val hop =
            """{"type":"udphop","settings":{"mode":"intervalLocal,intervalRemote",""" +
                """"interval":12,"remotePorts":"20000-30000"}}"""
        val out = rawOk(finalmask = """{"udp":[$salamander,$hop]}""")
        out.obfuscation shouldBe Hysteria2Obfuscation.Salamander("hunter22")
        out.hopping shouldBe PortHopping(listOf(20000..30000), 12)
    }

    @Test
    fun `raw finalmask with an unknown mask imports but is not connectable`() {
        val out = rawOk(finalmask = """{"udp":[{"type":"mystery","settings":{}}]}""")
        out.notConnectableReason shouldBe Hysteria2Unsupported.UnknownMask
    }

    @Test
    fun `raw pins read from a comma-separated string or an array`() {
        rawOk(tls = """{"serverName":"s","pinnedPeerCertSha256":"$pinHex,$otherPin"}""")
            .tls.pins shouldBe listOf(pinHex, otherPin)
        rawOk(tls = """{"serverName":"s","pinnedPeerCertSha256":["$pinHex","${otherPin.uppercase()}"]}""")
            .tls.pins shouldBe listOf(pinHex, otherPin)
        val bad = raw(tls = """{"serverName":"s","pinnedPeerCertSha256":"$pinHex,zz"}""")
        bad.profiles shouldBe emptyList()
        bad.failures.single().detail shouldBe FailureDetail.Malformed(DetailField.Pin)
    }

    @Test
    fun `raw hysteria rejects version 1, missing auth and missing tls`() {
        raw(hysteria = """{"version":1,"auth":"x"}""").failures.single().reason shouldBe
            ParseFailureReason.UnknownScheme
        raw(hysteria = """{"version":2}""").failures.single().reason shouldBe
            ParseFailureReason.MissingCredential
        val noTls =
            parseXrayJson(
                """{"outbounds":[{"protocol":"hysteria","settings":{"version":2,"address":"h.example","port":1},
                "streamSettings":{"network":"hysteria","hysteriaSettings":{"version":2,"auth":"x"}}}]}""",
            )
        noTls.failures.single().detail shouldBe FailureDetail.Unsupported(DetailField.Security)
    }

    @Test
    fun `raw hysteria row is not refused by passthrough analysis`() {
        val json = raw().profiles.single().rawJson!!
        analysePassthrough(json).rejection shouldBe null
    }
}
