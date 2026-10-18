// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.parser

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.Test
import space.getsub.core.model.HttpOutbound
import space.getsub.core.model.KcpHeader
import space.getsub.core.model.Profile
import space.getsub.core.model.Security
import space.getsub.core.model.TransportOptions
import space.getsub.core.model.VlessOutbound
import space.getsub.core.model.WireGuardOutbound
import space.getsub.core.model.WireGuardUnsupported

class ClashAndJsonWireGuardHttpTest {
    private val sk = "a".repeat(43) + "="
    private val pk = "c".repeat(43) + "="
    private val psk = "d".repeat(43) + "="
    private val uuid = "70cc48c5-b2f4-4a1e-9f3d-0123456789ab"

    private fun clash(entry: String): ParseOutcome {
        val body = entry.trimIndent().prependIndent("  ")
        return parseClashYaml("proxies:\n$body")
    }

    private fun clashProfile(entry: String): Profile {
        val outcome = clash(entry)
        outcome.failures shouldBe emptyList()
        return outcome.profiles.single()
    }

    private fun wg(entry: String): WireGuardOutbound {
        val profile = clashProfile(entry)
        return profile.outbound.shouldBeInstanceOf<WireGuardOutbound>()
    }

    // ---------------------------------------------------------------- Clash WireGuard

    @Test
    fun `clash wireguard single peer form`() {
        val o =
            wg(
                """
                - name: WG
                  type: wireguard
                  server: 198.51.100.7
                  port: 51820
                  ip: 10.0.0.2
                  ipv6: fd00::2
                  private-key: $sk
                  public-key: $pk
                  pre-shared-key: $psk
                  reserved: [1, 2, 3]
                  mtu: 1280
                  persistent-keepalive: 25
                  allowed-ips: ['0.0.0.0/0']
                  udp: true
                  remote-dns-resolve: true
                  dns: [1.1.1.1]
                """,
            )
        o.address shouldBe "198.51.100.7"
        o.port shouldBe 51820
        o.secretKey shouldBe sk
        o.peerPublicKey shouldBe pk
        o.preSharedKey shouldBe psk
        o.localAddresses shouldBe listOf("10.0.0.2", "fd00::2")
        o.reserved shouldBe listOf(1, 2, 3)
        o.mtu shouldBe 1280
        o.keepAlive shouldBe 25
        o.allowedIps shouldBe listOf("0.0.0.0/0")
        o.notConnectableReason shouldBe null
    }

    @Test
    fun `clash wireguard reserved as base64 string and defaults`() {
        val o =
            wg(
                """
                - {name: WG, type: wireguard, server: h.example, port: 2408, ip: 10.0.0.2/32,
                   private-key: "$sk", public-key: "$pk", reserved: "AQID"}
                """,
            )
        o.reserved shouldBe listOf(1, 2, 3)
        o.mtu shouldBe 1420
        o.preSharedKey shouldBe null
        o.allowedIps shouldBe listOf("0.0.0.0/0", "::/0")
    }

    // Ruling D2-R15: wireguard-go reads each allowed IP with netip.ParsePrefix when the tunnel starts.
    @Test
    fun `clash wireguard bare allowed ips get a host prefix and garbage is a typed failure`() {
        fun entry(allowed: String) =
            """
            - {name: WG, type: wireguard, server: h.example, port: 2408, ip: 10.0.0.2,
               private-key: "$sk", public-key: "$pk", allowed-ips: $allowed}
            """
        wg(entry("['10.1.2.3', 'fd00::1', '192.168.0.0/16']")).allowedIps shouldBe
            listOf("10.1.2.3/32", "fd00::1/128", "192.168.0.0/16")
        val failure = clash(entry("['10.0.0.0/8', 'example.com']")).failures.single()
        failure.detail shouldBe FailureDetail.Malformed(DetailField.AllowedIps)
        failure.reason shouldBe ParseFailureReason.MalformedYaml
    }

    @Test
    fun `clash wireguard peers form with one peer`() {
        val o =
            wg(
                """
                - name: WG
                  type: wireguard
                  ip: 10.0.0.2
                  private-key: $sk
                  peers:
                    - {server: peer.example, port: 51821, public-key: $pk, pre-shared-key: $psk, reserved: [4, 5, 6]}
                """,
            )
        o.address shouldBe "peer.example"
        o.port shouldBe 51821
        o.peerPublicKey shouldBe pk
        o.reserved shouldBe listOf(4, 5, 6)
        o.notConnectableReason shouldBe null
    }

    @Test
    fun `clash wireguard with several peers imports not connectable`() {
        val o =
            wg(
                """
                - name: WG
                  type: wireguard
                  ip: 10.0.0.2
                  private-key: $sk
                  peers:
                    - {server: a.example, port: 1, public-key: $pk}
                    - {server: b.example, port: 2, public-key: $pk}
                """,
            )
        o.notConnectableReason shouldBe WireGuardUnsupported.MultiplePeers
    }

    @Test
    fun `clash wireguard bad key is a typed failure that never carries the key`() {
        val outcome =
            clash(
                """
                - {name: WG, type: wireguard, server: h.example, port: 1, ip: 10.0.0.2,
                   private-key: "not-a-key-SECRETVALUE", public-key: "$pk"}
                """,
            )
        outcome.profiles shouldBe emptyList()
        outcome.failures.single().toString() shouldNotContain "SECRETVALUE"
    }

    @Test
    fun `clash wireguard missing public key or address is a failure`() {
        clash("- {name: WG, type: wireguard, server: h.example, port: 1, ip: 10.0.0.2, private-key: '$sk'}")
            .failures.size shouldBe 1
        clash("- {name: WG, type: wireguard, server: h.example, port: 1, private-key: '$sk', public-key: '$pk'}")
            .failures.size shouldBe 1
    }

    @Test
    fun `clash wireguard amnezia option is an unsupported protocol failure, not a row`() {
        val outcome =
            clash(
                """
                - {name: WG, type: wireguard, server: h.example, port: 1, ip: 10.0.0.2,
                   private-key: "$sk", public-key: "$pk", amnezia-wg-option: {jc: 4}}
                """,
            )
        outcome.profiles.size shouldBe 0
        val failure = outcome.failures.single()
        failure.reason shouldBe ParseFailureReason.UnknownScheme
        failure.detail shouldBe FailureDetail.Unsupported(DetailField.Scheme)
    }

    // ---------------------------------------------------------------- Clash HTTP

    @Test
    fun `clash http plain with credentials`() {
        val o =
            clashProfile("- {name: H, type: http, server: p.example, port: 3128, username: u, password: pw}")
                .outbound
                .shouldBeInstanceOf<HttpOutbound>()
        o.username shouldBe "u"
        o.password shouldBe "pw"
        o.security shouldBe Security.None
    }

    @Test
    fun `clash http tls records skip-cert-verify and ignores the certificate pin fingerprint`() {
        val o =
            clashProfile(
                "- {name: H, type: http, server: p.example, port: 443, tls: true, sni: s.example, " +
                    "skip-cert-verify: true, fingerprint: 'ab:cd'}",
            ).outbound.shouldBeInstanceOf<HttpOutbound>()
        val tls = o.security.shouldBeInstanceOf<Security.Tls>()
        tls.serverName shouldBe "s.example"
        tls.allowInsecure shouldBe true
        tls.fingerprint shouldBe "chrome"
    }

    // ---------------------------------------------------------------- Clash ws-opts v2ray-http-upgrade

    @Test
    fun `clash ws-opts v2ray-http-upgrade imports as HTTPUpgrade not WebSocket`() {
        val p =
            clashProfile(
                """
                - name: U
                  type: vless
                  server: h.example
                  port: 443
                  uuid: $uuid
                  tls: true
                  servername: sni.example
                  network: ws
                  ws-opts:
                    path: /up
                    headers: {Host: front.example}
                    v2ray-http-upgrade: true
                    v2ray-http-upgrade-fast-open: true
                """,
            )
        val stream = p.outbound.shouldBeInstanceOf<VlessOutbound>().stream
        stream.network shouldBe "httpupgrade"
        stream.transport shouldBe TransportOptions.HttpUpgrade("front.example", "/up")
    }

    @Test
    fun `clash v2ray-http-upgrade host falls back to servername`() {
        val p =
            clashProfile(
                """
                - {name: U, type: vmess, server: h.example, port: 443, uuid: $uuid, tls: true,
                   servername: sni.example, network: ws, ws-opts: {v2ray-http-upgrade: true}}
                """,
            )
        val stream =
            p.outbound
                .shouldBeInstanceOf<space.getsub.core.model.VmessOutbound>()
                .stream
        stream.network shouldBe "httpupgrade"
        stream.transport shouldBe TransportOptions.HttpUpgrade("sni.example", "/")
    }

    @Test
    fun `clash ws without the flag or with it false stays WebSocket`() {
        for (flag in listOf("", ", v2ray-http-upgrade: false")) {
            val p =
                clashProfile(
                    "- {name: W, type: vless, server: h.example, port: 443, uuid: $uuid, network: ws, " +
                        "ws-opts: {path: /w$flag}}",
                )
            val stream = p.outbound.shouldBeInstanceOf<VlessOutbound>().stream
            stream.network shouldBe "ws"
            stream.transport.shouldBeInstanceOf<TransportOptions.WebSocket>()
        }
    }

    // ---------------------------------------------------------------- raw JSON WireGuard

    private fun raw(outbound: String): ParseOutcome = parseXrayJson("""{"remarks":"R","outbounds":[$outbound]}""")

    private fun rawWg(settings: String): WireGuardOutbound {
        val outcome = raw("""{"protocol":"wireguard","settings":$settings}""")
        outcome.failures shouldBe emptyList()
        return outcome.profiles
            .single()
            .outbound
            .shouldBeInstanceOf<WireGuardOutbound>()
    }

    @Test
    fun `raw wireguard projection`() {
        val outcome =
            raw(
                """{"protocol":"wireguard","settings":{"secretKey":"$sk","address":["10.0.0.2/32","fd00::2/128"],
                "peers":[{"publicKey":"$pk","preSharedKey":"$psk","endpoint":"198.51.100.7:51820","keepAlive":20,
                "allowedIPs":["0.0.0.0/0"]}],"mtu":1300,"reserved":[1,2,3],"noKernelTun":true}}""",
            )
        outcome.failures shouldBe emptyList()
        val profile = outcome.profiles.single()
        profile.name shouldBe "R"
        (profile.rawJson != null) shouldBe true
        val o = profile.outbound.shouldBeInstanceOf<WireGuardOutbound>()
        o.address shouldBe "198.51.100.7"
        o.port shouldBe 51820
        o.secretKey shouldBe sk
        o.localAddresses shouldBe listOf("10.0.0.2/32", "fd00::2/128")
        o.peerPublicKey shouldBe pk
        o.preSharedKey shouldBe psk
        o.mtu shouldBe 1300
        o.reserved shouldBe listOf(1, 2, 3)
        o.keepAlive shouldBe 20
        o.allowedIps shouldBe listOf("0.0.0.0/0")
        o.notConnectableReason shouldBe null
    }

    @Test
    fun `raw wireguard ipv6 endpoint and base64 reserved`() {
        val o =
            rawWg(
                """{"secretKey":"$sk","address":["10.0.0.2"],"reserved":"AQID",
                "peers":[{"publicKey":"$pk","endpoint":"[2001:db8::1]:51820"}]}""",
            )
        o.address shouldBe "2001:db8::1"
        o.port shouldBe 51820
        o.reserved shouldBe listOf(1, 2, 3)
        o.mtu shouldBe 1420
    }

    @Test
    fun `raw wireguard with two peers is not connectable`() {
        val o =
            rawWg(
                """{"secretKey":"$sk","address":["10.0.0.2"],"peers":[
                {"publicKey":"$pk","endpoint":"a.example:1"},{"publicKey":"$pk","endpoint":"b.example:2"}]}""",
            )
        o.notConnectableReason shouldBe WireGuardUnsupported.MultiplePeers
    }

    @Test
    fun `raw wireguard bad reserved or key keeps the row not connectable`() {
        rawWg(
            """{"secretKey":"$sk","address":["10.0.0.2"],"reserved":[1,2],
            "peers":[{"publicKey":"$pk","endpoint":"a.example:1"}]}""",
        ).notConnectableReason shouldBe WireGuardUnsupported.InvalidReserved
        rawWg(
            """{"secretKey":"nonsense","address":["10.0.0.2"],
            "peers":[{"publicKey":"$pk","endpoint":"a.example:1"}]}""",
        ).notConnectableReason shouldBe WireGuardUnsupported.InvalidKey
    }

    @Test
    fun `raw wireguard reserved string must be base64 not a comma list`() {
        val peer = """"peers":[{"publicKey":"$pk","endpoint":"a.example:1"}]"""
        rawWg("""{"secretKey":"$sk","address":["10.0.0.2"],"reserved":"1,2,3",$peer}""")
            .notConnectableReason shouldBe WireGuardUnsupported.InvalidReserved
        rawWg("""{"secretKey":"$sk","address":["10.0.0.2"],"reserved":"AQID",$peer}""")
            .reserved shouldBe listOf(1, 2, 3)
    }

    @Test
    fun `raw wireguard projects mtu as given and flags one the core would reject`() {
        val peer = """"peers":[{"publicKey":"$pk","endpoint":"a.example:1"}]"""
        rawWg("""{"secretKey":"$sk","address":["10.0.0.2"],"mtu":1280,$peer}""").mtu shouldBe 1280
        rawWg("""{"secretKey":"$sk","address":["10.0.0.2"],"mtu":0,$peer}""").mtu shouldBe 1420
        rawWg("""{"secretKey":"$sk","address":["10.0.0.2"],"mtu":-5,$peer}""")
            .notConnectableReason shouldBe WireGuardUnsupported.InvalidMtu
        rawWg("""{"secretKey":"$sk","address":["10.0.0.2"],"mtu":"abc",$peer}""")
            .notConnectableReason shouldBe WireGuardUnsupported.InvalidMtu
    }

    // Ruling D2-R15: the raw JSON runs as written, so a bare address (which the core would fail on at connect,
    // not at `xray -test`) or garbage keeps the row, projected as given, but not connectable.
    @Test
    fun `raw wireguard allowed ip without a prefix is not connectable`() {
        for (bad in listOf("10.1.2.3", "fd00::1", "example.com")) {
            val o =
                rawWg(
                    """{"secretKey":"$sk","address":["10.0.0.2"],
                    "peers":[{"publicKey":"$pk","endpoint":"a.example:1","allowedIPs":["0.0.0.0/0","$bad"]}]}""",
                )
            o.allowedIps shouldBe listOf("0.0.0.0/0", bad)
            o.notConnectableReason shouldBe WireGuardUnsupported.InvalidAllowedIp
        }
    }

    @Test
    fun `raw wireguard explicit empty allowedIPs stays empty`() {
        rawWg(
            """{"secretKey":"$sk","address":["10.0.0.2"],
            "peers":[{"publicKey":"$pk","endpoint":"a.example:1","allowedIPs":[]}]}""",
        ).allowedIps shouldBe emptyList()
    }

    @Test
    fun `raw kcp mtu or tti the core rejects is not connectable`() {
        for (settings in listOf("""{"mtu":20}""", """{"tti":5}""", """{"tti":1001}""")) {
            rawVless("""{"network":"kcp","kcpSettings":$settings}""")
                .transport
                .shouldBeInstanceOf<TransportOptions.Kcp>()
                .obfuscation.unsupported shouldBe space.getsub.core.model.KcpUnsupported.InvalidSetting
        }
        val ok = rawVless("""{"network":"kcp","kcpSettings":{"mtu":21,"tti":10}}""")
        ok.transport
            .shouldBeInstanceOf<TransportOptions.Kcp>()
            .obfuscation.unsupported shouldBe null
    }

    @Test
    fun `raw wireguard hex key is valid for the core`() {
        rawWg(
            """{"secretKey":"${"ab".repeat(32)}","address":["10.0.0.2"],
            "peers":[{"publicKey":"$pk","endpoint":"a.example:1"}]}""",
        ).notConnectableReason shouldBe null
    }

    @Test
    fun `raw wireguard without peers or endpoint is a failure and never throws`() {
        raw("""{"protocol":"wireguard","settings":{"secretKey":"$sk","address":["10.0.0.2"],"peers":[]}}""")
            .failures.size shouldBe 1
        raw("""{"protocol":"wireguard","settings":{"secretKey":"$sk","peers":[{"publicKey":"$pk"}]}}""")
            .failures.size shouldBe 1
        raw("""{"protocol":"wireguard"}""").failures.size shouldBe 1
        raw("""{"protocol":"wireguard","settings":{"secretKey":"$sk","peers":"x"}}""").failures.size shouldBe 1
    }

    // ---------------------------------------------------------------- raw JSON HTTP

    @Test
    fun `raw http flat form`() {
        val outcome =
            raw("""{"protocol":"http","settings":{"address":"p.example","port":3128,"user":"u","pass":"pw"}}""")
        outcome.failures shouldBe emptyList()
        val o =
            outcome.profiles
                .single()
                .outbound
                .shouldBeInstanceOf<HttpOutbound>()
        o.address shouldBe "p.example"
        o.port shouldBe 3128
        o.username shouldBe "u"
        o.password shouldBe "pw"
        o.security shouldBe Security.None
    }

    @Test
    fun `raw http legacy servers form with tls`() {
        val outcome =
            raw(
                """{"protocol":"http","settings":{"servers":[{"address":"p.example","port":443,
                "users":[{"user":"u","pass":"pw"}]}]},
                "streamSettings":{"security":"tls","tlsSettings":{"serverName":"s.example","allowInsecure":true}}}""",
            )
        outcome.failures shouldBe emptyList()
        val o =
            outcome.profiles
                .single()
                .outbound
                .shouldBeInstanceOf<HttpOutbound>()
        o.username shouldBe "u"
        o.password shouldBe "pw"
        val tls = o.security.shouldBeInstanceOf<Security.Tls>()
        tls.serverName shouldBe "s.example"
        tls.allowInsecure shouldBe true
    }

    @Test
    fun `raw http with several servers or a bad port is a failure`() {
        raw("""{"protocol":"http","settings":{"servers":[{"address":"a","port":1},{"address":"b","port":2}]}}""")
            .failures.size shouldBe 1
        raw("""{"protocol":"http","settings":{"address":"a","port":0}}""").failures.size shouldBe 1
        raw("""{"protocol":"http"}""").failures.size shouldBe 1
    }

    // ---------------------------------------------------------------- raw JSON kcp / httpupgrade

    private fun rawVless(stream: String): StreamSettingsView {
        val outcome =
            raw(
                """{"protocol":"vless","settings":{"address":"h.example","port":443,"id":"$uuid"},
                "streamSettings":$stream}""",
            )
        outcome.failures shouldBe emptyList()
        val s =
            outcome.profiles
                .single()
                .outbound
                .shouldBeInstanceOf<VlessOutbound>()
                .stream
        return StreamSettingsView(s.network, s.transport)
    }

    private data class StreamSettingsView(
        val network: String,
        val transport: TransportOptions,
    )

    @Test
    fun `raw kcp with finalmask masks`() {
        val view =
            rawVless(
                """{"network":"kcp","kcpSettings":{"mtu":1200,"tti":30},"finalmask":{"udp":[
                {"type":"mkcp-legacy","settings":{"value":"sd"}},
                {"type":"mkcp-legacy","settings":{"header":"srtp"}}]}}""",
            )
        val kcp = view.transport.shouldBeInstanceOf<TransportOptions.Kcp>()
        kcp.mtu shouldBe 1200
        kcp.tti shouldBe 30
        kcp.obfuscation.seed shouldBe "sd"
        kcp.obfuscation.header shouldBe KcpHeader.SRTP
    }

    @Test
    fun `raw kcp network name is case insensitive and mkcp works`() {
        for (name in listOf("KCP", "mkcp", "mKCP")) {
            val view = rawVless("""{"network":"$name","kcpSettings":{}}""")
            view.network shouldBe name
            view.transport
                .shouldBeInstanceOf<TransportOptions.Kcp>()
                .obfuscation.seed shouldBe null
        }
    }

    @Test
    fun `raw legacy kcp header and seed project to their obfuscation`() {
        val view =
            rawVless("""{"network":"kcp","kcpSettings":{"header":{"type":"wechat-video"},"seed":"legacy-seed"}}""")
        val kcp = view.transport.shouldBeInstanceOf<TransportOptions.Kcp>()
        kcp.obfuscation.seed shouldBe "legacy-seed"
        kcp.obfuscation.header shouldBe KcpHeader.WECHAT
        kcp.obfuscation.unsupported shouldBe null
    }

    @Test
    fun `raw legacy kcp header only is original plus header and http header is unsupported`() {
        val dtls = rawVless("""{"network":"kcp","kcpSettings":{"header":{"type":"dtls"}}}""")
        val o = dtls.transport.shouldBeInstanceOf<TransportOptions.Kcp>().obfuscation
        o.header shouldBe KcpHeader.DTLS
        o.legacyOriginal shouldBe true

        val http = rawVless("""{"network":"kcp","kcpSettings":{"header":{"type":"http"}}}""")
        http.transport
            .shouldBeInstanceOf<TransportOptions.Kcp>()
            .obfuscation.unsupported
            .shouldBeInstanceOf<space.getsub.core.model.KcpUnsupported>()
    }

    @Test
    fun `raw kcp finalmask wins over legacy fields`() {
        val view =
            rawVless(
                """{"network":"kcp","kcpSettings":{"seed":"old","header":{"type":"srtp"}},
                "finalmask":{"udp":[{"type":"mkcp-legacy","settings":{}}]}}""",
            )
        val o = view.transport.shouldBeInstanceOf<TransportOptions.Kcp>().obfuscation
        o.seed shouldBe null
        o.header shouldBe null
        o.legacyOriginal shouldBe true
    }

    @Test
    fun `raw bare kcp has no obfuscation`() {
        val o =
            rawVless("""{"network":"kcp","kcpSettings":{"mtu":1350}}""")
                .transport
                .shouldBeInstanceOf<TransportOptions.Kcp>()
                .obfuscation
        o.seed shouldBe null
        o.header shouldBe null
        o.legacyOriginal shouldBe false
    }

    @Test
    fun `raw httpupgrade settings`() {
        val view =
            rawVless("""{"network":"httpupgrade","httpupgradeSettings":{"host":"front.example","path":"/up"}}""")
        view.network shouldBe "httpupgrade"
        view.transport shouldBe TransportOptions.HttpUpgrade("front.example", "/up")
        rawVless("""{"network":"httpupgrade"}""").transport shouldBe TransportOptions.HttpUpgrade("", "/")
    }

    // Ruling D2-R11: a legacy DNS header's domain has no field in the model; rewriting it to
    // `{header:"dns"}` would make the core default to www.baidu.com and change the header length.
    @Test
    fun `raw legacy kcp dns header with a domain is not connectable`() {
        val withDomain =
            rawVless("""{"network":"kcp","kcpSettings":{"header":{"type":"dns","domain":"example.org"},"seed":"s"}}""")
        withDomain.transport
            .shouldBeInstanceOf<TransportOptions.Kcp>()
            .obfuscation.unsupported shouldBe
            space.getsub.core.model.KcpUnsupported.DnsHeaderDomain
        for (header in listOf("""{"type":"dns"}""", """{"type":"dns","domain":""}""")) {
            val plain = rawVless("""{"network":"kcp","kcpSettings":{"header":$header}}""")
            val o = plain.transport.shouldBeInstanceOf<TransportOptions.Kcp>().obfuscation
            o.unsupported shouldBe null
            o.header shouldBe KcpHeader.DNS
        }
    }

    // Ruling D2-R12: noise is client-only junk, so a raw config's own trailing noise does not change the
    // mKCP wire the server sees.
    @Test
    fun `raw kcp with its own trailing noise stays connectable`() {
        val view =
            rawVless(
                """{"network":"kcp","finalmask":{"udp":[
                {"type":"mkcp-legacy","settings":{"value":"sd"}},
                {"type":"mkcp-legacy","settings":{"header":"srtp"}},
                {"type":"noise","settings":{"noise":[{"rand":"10-20","delay":"10-16"}]}}]}}""",
            )
        val o = view.transport.shouldBeInstanceOf<TransportOptions.Kcp>().obfuscation
        o.unsupported shouldBe null
        o.seed shouldBe "sd"
        o.header shouldBe KcpHeader.SRTP
    }
}
