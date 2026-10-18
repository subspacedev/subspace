// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.data.serialization

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.Test
import space.getsub.core.data.ProfileKind
import space.getsub.core.data.StoredProfile
import space.getsub.core.data.protocolName
import space.getsub.core.data.transportSummary
import space.getsub.core.model.HttpOutbound
import space.getsub.core.model.Hysteria2Outbound
import space.getsub.core.model.KcpHeader
import space.getsub.core.model.KcpObfuscation
import space.getsub.core.model.KcpUnsupported
import space.getsub.core.model.Outbound
import space.getsub.core.model.Security
import space.getsub.core.model.StreamSettings
import space.getsub.core.model.TransportOptions
import space.getsub.core.model.TrojanOutbound
import space.getsub.core.model.VlessOutbound
import space.getsub.core.model.VmessOutbound
import space.getsub.core.model.WireGuardOutbound
import space.getsub.core.model.WireGuardUnsupported
import java.security.MessageDigest

class D2StorageTest {
    private val key = "A".repeat(43) + "="
    private val key2 = "B".repeat(43) + "="

    private val wg =
        WireGuardOutbound(
            address = "wg.example.com",
            port = 51820,
            secretKey = key,
            localAddresses = listOf("10.0.0.2/32", "fd00::2/128"),
            peerPublicKey = key2,
            preSharedKey = "C".repeat(43) + "=",
            mtu = 1280,
            reserved = listOf(1, 2, 3),
            keepAlive = 25,
            allowedIps = listOf("10.0.0.0/8"),
            notConnectableReason = WireGuardUnsupported.MultiplePeers,
        )

    private val wgPlain =
        WireGuardOutbound(
            address = "wg.example.com",
            port = 51820,
            secretKey = key,
            localAddresses = listOf("10.0.0.2/32"),
            peerPublicKey = key2,
            preSharedKey = null,
        )

    private val http =
        HttpOutbound(
            address = "proxy.example.com",
            port = 8080,
            username = "user",
            password = "p4ss",
            security = Security.None,
        )

    private val kcpFull =
        TransportOptions.Kcp(
            mtu = 1350,
            tti = 20,
            obfuscation = KcpObfuscation("s33d", KcpHeader.WIREGUARD, true, KcpUnsupported.DnsHeaderDomain),
        )

    private val tls = Security.Tls("sni.example", "chrome", false)
    private val reality = Security.Reality("sn", "pk", "ab", "chrome", "/")
    private val cleanKcp = TransportOptions.Kcp(null, null, KcpObfuscation(null, null, false))
    private val upgrade = TransportOptions.HttpUpgrade("", "/")

    private fun roundTrips(o: Outbound) = o.toJson().toOutbound() shouldBe o

    private fun kcpRow(
        network: String,
        security: Security = Security.None,
        kcp: TransportOptions.Kcp = cleanKcp,
    ) = VlessOutbound("a", 443, "u", null, StreamSettings(network, security, kcp))

    private fun vless(
        network: String,
        transport: TransportOptions,
        security: Security = Security.None,
    ) = VlessOutbound("v.example.com", 443, "uuid-1", null, StreamSettings(network, security, transport))

    @Test
    fun `a fully populated wireguard outbound round-trips`() {
        roundTrips(wg)
        roundTrips(wgPlain)
        roundTrips(wg.copy(reserved = emptyList(), keepAlive = null, preSharedKey = null))
        for (r in WireGuardUnsupported.entries) roundTrips(wg.copy(notConnectableReason = r))
    }

    @Test
    fun `http outbound round-trips with every security and optional credentials`() {
        for (s in listOf(Security.None, tls, reality)) roundTrips(http.copy(security = s))
        roundTrips(http.copy(username = null, password = null))
    }

    @Test
    fun `kcp and httpupgrade transports round-trip on vless vmess and trojan`() {
        val kcps =
            listOf(
                kcpFull,
                TransportOptions.Kcp(null, null, KcpObfuscation(null, null, false)),
                TransportOptions.Kcp(1200, 50, KcpObfuscation("", KcpHeader.DNS, false)),
            )
        for (k in kcps) {
            for (n in listOf("kcp", "mkcp")) {
                val s = StreamSettings(n, Security.None, k)
                val outs: List<Outbound> =
                    listOf(
                        VlessOutbound("a", 1, "u", null, s),
                        VmessOutbound("a", 1, "u", 0, "auto", s),
                        TrojanOutbound("a", 1, "pw", s),
                    )
                outs.forEach(::roundTrips)
            }
        }
        for (h in KcpHeader.entries) {
            roundTrips(kcpRow("kcp", kcp = TransportOptions.Kcp(null, null, KcpObfuscation(null, h, false))))
        }
        for (u in KcpUnsupported.entries) {
            roundTrips(kcpRow("kcp", kcp = TransportOptions.Kcp(null, null, KcpObfuscation(null, null, false, u))))
        }
        roundTrips(vless("httpupgrade", TransportOptions.HttpUpgrade("h.example", "/up")))
        roundTrips(vless("httpupgrade", upgrade))
    }

    @Test
    fun `a newer build's unknown kcp names decode as unsupported rather than failing the row`() {
        val json =
            """{"type":"vless","address":"a","port":1,"uuid":"u","stream":{"network":"kcp",""" +
                """"security":{"type":"none"},""" +
                """"transport":{"type":"kcp","header":"future","unsupported":"Future"}}}"""
        val k = ((json.toOutbound() as VlessOutbound).stream.transport as TransportOptions.Kcp).obfuscation
        k.unsupported shouldBe KcpUnsupported.UnknownMask
        kcpRow("kcp").toJson() shouldNotContain "Future"
        val wgJson =
            """{"type":"wireguard","address":"a","port":1,"secretKey":"k","localAddresses":[],""" +
                """"peerPublicKey":"p","unsupported":"Future"}"""
        (wgJson.toOutbound() as WireGuardOutbound).notConnectableReason shouldBe WireGuardUnsupported.UnsupportedMask
    }

    @Test
    fun `rows written before the new variants decode and encode byte-identically`() {
        val ws =
            """{"type":"vless","address":"a.example","port":443,"uuid":"u","flow":null,"stream":{"network":"ws",""" +
                """"security":{"type":"tls","serverName":"s","fingerprint":"chrome","allowInsecure":false},""" +
                """"transport":{"type":"ws","path":"/p","headers":{"Host":"h"}}}}"""
        val decoded = ws.toOutbound()!!
        decoded.toJson() shouldBe ws
        val xhttp =
            """{"type":"trojan","address":"a","port":1,"password":"p","stream":{"network":"xhttp",""" +
                """"security":{"type":"none"},""" +
                """"transport":{"type":"xhttp","path":"/x"}}}"""
        xhttp.toOutbound()!!.toJson() shouldBe
            xhttp.replace("\"path\":\"/x\"}", "\"path\":\"/x\",\"host\":null,\"mode\":null}")
        val digest = MessageDigest.getInstance("SHA-256").digest(("TYPED\u0000" + ws).toByteArray())
        identityHashOf(decoded, ProfileKind.TYPED) shouldBe digest.joinToString("") { "%02x".format(it) }
    }

    @Test
    fun `a stored hysteria2 row decodes to the same outbound and re-encodes identically`() {
        val json =
            """{"type":"hysteria2","address":"hy.example","port":443,"password":"pw",""" +
                """"tls":{"serverName":"sni","alpn":["h3"],"pins":[],"skipChecksRequested":false},""" +
                """"hopping":null,"obfuscation":{"type":"none"},"congestion":{"type":"default"},"unsupported":null}"""
        val o = json.toOutbound() as Hysteria2Outbound
        o.address shouldBe "hy.example"
        o.password shouldBe "pw"
        o.toJson() shouldBe json
    }

    @Test
    fun `stored discriminators match protocolName`() {
        wg.protocolName() shouldBe "wireguard"
        wg.toJson().contains("\"type\":\"wireguard\"") shouldBe true
        http.protocolName() shouldBe "http"
        http.toJson().contains("\"type\":\"http\"") shouldBe true
    }

    private fun id(o: Outbound) = identityHashOf(o, ProfileKind.TYPED)

    @Test
    fun `wireguard identity is address port peer key and secret key only`() {
        val base = id(wgPlain)
        id(wgPlain.copy(address = "o")) shouldNotBe base
        id(wgPlain.copy(port = 1)) shouldNotBe base
        id(wgPlain.copy(peerPublicKey = key)) shouldNotBe base
        id(wgPlain.copy(secretKey = key2)) shouldNotBe base
        id(wg) shouldBe base
        id(wgPlain.copy(mtu = 1000, reserved = listOf(9, 9, 9), keepAlive = 5)) shouldBe base
        id(wgPlain.copy(allowedIps = listOf("1.1.1.1/32"), localAddresses = listOf("10.9.9.9/32"))) shouldBe base
        id(wgPlain.copy(preSharedKey = key)) shouldBe base
        identityHashOf(wgPlain, ProfileKind.RAW_JSON) shouldNotBe base
        id(http.copy(address = "wg.example.com", port = 51820, username = null)) shouldNotBe base
    }

    @Test
    fun `http identity is address port and username only`() {
        val base = id(http)
        id(http.copy(address = "o")) shouldNotBe base
        id(http.copy(port = 1)) shouldNotBe base
        id(http.copy(username = "other")) shouldNotBe base
        id(http.copy(password = "other", security = tls)) shouldBe base
        id(http.copy(username = null, password = null)) shouldNotBe base
    }

    @Test
    fun `labels`() {
        wg.transportSummary() shouldBe "UDP · 51820"
        http.transportSummary() shouldBe "TCP · 8080"
        http.copy(security = tls).transportSummary() shouldBe "TCP · TLS · 8080"
        kcpRow("kcp").transportSummary() shouldBe "mKCP · 443"
        kcpRow("mkcp").transportSummary() shouldBe "mKCP · 443"
        vless("httpupgrade", upgrade).copy(port = 80).transportSummary() shouldBe "HTTPUpgrade · 80"
        vless("ws", TransportOptions.None, tls).transportSummary() shouldBe "ws · tls · 443"
    }

    @Test
    fun `wireguard connectable follows its reason`() {
        stored(wgPlain).connectable shouldBe true
        stored(wg).connectable shouldBe false
    }

    @Test
    fun `http connectable unless reality`() {
        stored(http).connectable shouldBe true
        stored(http.copy(security = tls)).connectable shouldBe true
        stored(http.copy(security = reality)).connectable shouldBe false
    }

    @Test
    fun `kcp and httpupgrade rows are connectable case-insensitively`() {
        for (n in listOf("kcp", "mkcp", "KCP", "MKCP")) {
            stored(kcpRow(n)).connectable shouldBe true
            stored(TrojanOutbound("a", 1, "p", StreamSettings(n, Security.None, cleanKcp))).connectable shouldBe true
        }
        stored(vless("httpupgrade", upgrade)).connectable shouldBe true
        stored(vless("HTTPUpgrade", upgrade)).connectable shouldBe true
    }

    @Test
    fun `kcp with an unsupported obfuscation is not connectable`() {
        stored(kcpRow("kcp", kcp = kcpFull)).connectable shouldBe false
        val httpHeader = TransportOptions.Kcp(null, null, KcpObfuscation(null, null, false, KcpUnsupported.HttpHeader))
        stored(kcpRow("kcp", kcp = httpHeader)).connectable shouldBe false
    }

    @Test
    fun `reality over kcp or httpupgrade is not connectable`() {
        stored(kcpRow("kcp", reality)).connectable shouldBe false
        val vmess = VmessOutbound("a", 1, "u", 0, "auto", StreamSettings("mkcp", reality, cleanKcp))
        stored(vmess).connectable shouldBe false
        stored(vless("httpupgrade", upgrade, reality)).connectable shouldBe false
        stored(vless("tcp", TransportOptions.None, reality)).connectable shouldBe true
    }

    @Test
    fun `secrets stay out of toString`() {
        val rendered = listOf(wg.toString(), http.toString(), kcpFull.toString())
        rendered.forEach { r ->
            for (s in listOf(key, key2, "p4ss", "s33d")) r shouldNotContain s
        }
    }

    private fun stored(o: Outbound) =
        StoredProfile(
            id = 1,
            groupId = 1,
            kind = ProfileKind.TYPED,
            name = "T",
            protocol = o.protocolName(),
            address = o.address,
            port = o.port,
            transport = o.transportSummary(),
            outbound = o,
            rawJson = null,
            lastConnectedAt = null,
            lastError = null,
        )
}
