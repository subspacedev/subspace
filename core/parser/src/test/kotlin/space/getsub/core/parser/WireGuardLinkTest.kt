// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.parser

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.Test
import space.getsub.core.model.WireGuardOutbound
import space.getsub.core.model.WireGuardUnsupported

class WireGuardLinkTest {
    private val sk = "a".repeat(43) + "="
    private val pk = "c".repeat(43) + "="
    private val psk = "d".repeat(43) + "="

    private fun ok(link: String): Pair<String, WireGuardOutbound> {
        val r = parseShareLink(link, 0)
        r.shouldBeInstanceOf<LinkResult.Ok>()
        return r.profile.name to (r.profile.outbound as WireGuardOutbound)
    }

    private fun bad(link: String): ParseFailure {
        val r = parseShareLink(link, 5)
        r.shouldBeInstanceOf<LinkResult.Bad>()
        return r.failure
    }

    private fun enc(s: String) =
        java.net.URLEncoder
            .encode(s, "UTF-8")
            .replace("+", "%20")

    @Test
    fun `v2rayN grammar`() {
        val (name, o) =
            ok(
                "wireguard://${enc(sk)}@198.51.100.7:51820?publickey=${enc(pk)}&presharedkey=${enc(psk)}" +
                    "&address=10.0.0.2/32,fd00::2/128&reserved=1,2,3&mtu=1280&keepalive=25#My%20WG",
            )
        name shouldBe "My WG"
        o.address shouldBe "198.51.100.7"
        o.port shouldBe 51820
        o.secretKey shouldBe sk
        o.peerPublicKey shouldBe pk
        o.preSharedKey shouldBe psk
        o.localAddresses shouldBe listOf("10.0.0.2/32", "fd00::2/128")
        o.reserved shouldBe listOf(1, 2, 3)
        o.mtu shouldBe 1280
        o.keepAlive shouldBe 25
        o.notConnectableReason shouldBe null
    }

    @Test
    fun `wg grammar with aliases and separators`() {
        val (name, o) =
            ok(
                "wg://host.example:2408/?private_key=${enc(sk)}&Peer-Public-Key=${enc(pk)}&pre_shared_key=${enc(psk)}" +
                    "&local_address=10.9.0.2&reserved=0,0,0",
            )
        name shouldBe "host.example"
        o.secretKey shouldBe sk
        o.peerPublicKey shouldBe pk
        o.preSharedKey shouldBe psk
        o.localAddresses shouldBe listOf("10.9.0.2")
        o.reserved shouldBe listOf(0, 0, 0)
        o.mtu shouldBe 1420
    }

    @Test
    fun `pk is private key and peer_pk is the peer`() {
        val (_, o) = ok("wg://h.example:1?pk=${enc(sk)}&peer_pk=${enc(pk)}&ip=10.0.0.2")
        o.secretKey shouldBe sk
        o.peerPublicKey shouldBe pk
    }

    @Test
    fun `userinfo secret with raw slash and plus survives`() {
        val k = "ab/+" + "a".repeat(39) + "="
        val (_, o) = ok("wireguard://$k@h.example:1?publickey=${enc(pk)}&address=10.0.0.2")
        o.secretKey shouldBe k
    }

    @Test
    fun `values are trimmed`() {
        val (_, o) =
            ok(
                "wg://h.example:1?pk=%20${enc(sk)}%20&pub=${enc(pk)}" +
                    "&address=%2010.0.0.2%20,%2010.0.0.3&reserved=%201,2,3%20",
            )
        o.secretKey shouldBe sk
        o.localAddresses shouldBe listOf("10.0.0.2", "10.0.0.3")
        o.reserved shouldBe listOf(1, 2, 3)
    }

    @Test
    fun `base64 reserved and url-safe keys`() {
        val urlKey = "-" + "a".repeat(42)
        val (_, o) = ok("wg://h.example:1?pk=$urlKey&pub=${enc(pk)}&ip=10.0.0.2&reserved=AQID")
        o.reserved shouldBe listOf(1, 2, 3)
        o.secretKey shouldBe urlKey
    }

    @Test
    fun `ipv6 endpoint`() {
        val (_, o) = ok("wg://[2001:db8::1]:51820?pk=${enc(sk)}&pub=${enc(pk)}&ip=10.0.0.2")
        o.address shouldBe "2001:db8::1"
        o.port shouldBe 51820
    }

    @Test
    fun `dns is ignored`() {
        val (_, o) = ok("wireguard://${enc(sk)}@h.example:1?publickey=${enc(pk)}&address=10.0.0.2&dns=1.1.1.1")
        o.notConnectableReason shouldBe null
    }

    @Test
    fun `a link carrying AmneziaWG parameters is an unsupported protocol failure`() {
        val base = "wireguard://${enc(sk)}@h.example:1?publickey=${enc(pk)}&address=10.0.0.2"
        listOf("jc=4", "Jmin=40", "jmax=70", "s1=15", "s2=18", "h1=1", "h2=2", "h3=3", "h4=4", "J_c=4").forEach { awg ->
            val failure = bad("$base&$awg")
            failure.reason shouldBe ParseFailureReason.UnknownScheme
            failure.detail shouldBe FailureDetail.Unsupported(DetailField.Scheme)
        }
        bad("wg://h.example:1/?pk=${enc(sk)}&peer_pk=${enc(pk)}&ip=10.0.0.2&jc=4").reason shouldBe
            ParseFailureReason.UnknownScheme
    }

    @Test
    fun `a link carrying AmneziaWG 2 parameters is an unsupported protocol failure`() {
        val base = "wireguard://${enc(sk)}@h.example:1?publickey=${enc(pk)}&address=10.0.0.2"
        listOf("s3=5", "S4=6", "i1=x", "i2=x", "i3=x", "i4=x", "I_5=x").forEach { awg ->
            bad("$base&$awg").reason shouldBe ParseFailureReason.UnknownScheme
        }
    }

    @Test
    fun `fm carrying masks makes the row not connectable`() {
        val fm = enc("""{"udp":[{"type":"noise","settings":{}}]}""")
        val (_, o) = ok("wireguard://${enc(sk)}@h.example:1?publickey=${enc(pk)}&address=10.0.0.2&fm=$fm")
        o.notConnectableReason shouldBe WireGuardUnsupported.UnsupportedMask
        val (_, empty) = ok("wireguard://${enc(sk)}@h.example:1?publickey=${enc(pk)}&address=10.0.0.2&fm=%7B%7D")
        empty.notConnectableReason shouldBe null
    }

    @Test
    fun `missing pieces are typed failures`() {
        bad("wireguard://h.example:1?publickey=${enc(pk)}&address=10.0.0.2").reason shouldBe
            ParseFailureReason.MissingCredential
        bad("wireguard://${enc(sk)}@h.example:1?address=10.0.0.2").detail shouldBe
            FailureDetail.Missing(DetailField.PublicKey)
        bad("wireguard://${enc(sk)}@h.example:1?publickey=${enc(pk)}").detail shouldBe
            FailureDetail.Missing(DetailField.LocalAddress)
        bad("wireguard://${enc(sk)}@h.example?publickey=${enc(pk)}&address=10.0.0.2").reason shouldBe
            ParseFailureReason.InvalidPort
        bad("wireguard://${enc(sk)}@h.example:0?publickey=${enc(pk)}&address=10.0.0.2").reason shouldBe
            ParseFailureReason.InvalidPort
    }

    @Test
    fun `bad values are typed failures that never echo the value`() {
        val f = bad("wireguard://nottakey@h.example:1?publickey=${enc(pk)}&address=10.0.0.2")
        f.detail shouldBe FailureDetail.Malformed(DetailField.SecretKey)
        bad("wireguard://${enc(sk)}@h.example:1?publickey=short&address=10.0.0.2").detail shouldBe
            FailureDetail.Malformed(DetailField.PublicKey)
        bad("wireguard://${enc(sk)}@h.example:1?publickey=${enc(pk)}&address=10.0.0.2&reserved=1,2").detail shouldBe
            FailureDetail.Malformed(DetailField.Reserved)
        bad("wireguard://${enc(sk)}@h.example:1?publickey=${enc(pk)}&address=10.0.0.2&mtu=x").detail shouldBe
            FailureDetail.Malformed(DetailField.Mtu)
        bad("wireguard://${enc(sk)}@h.example:1?publickey=${enc(pk)}&address=10.0.0.2&presharedkey=zz").detail shouldBe
            FailureDetail.Malformed(DetailField.Credential)
        f.toString().contains("nottakey") shouldBe false
    }

    @Test
    fun `toString redacts keys`() {
        val (_, o) = ok("wireguard://${enc(sk)}@h.example:1?publickey=${enc(pk)}&address=10.0.0.2")
        o.toString().contains(sk) shouldBe false
    }

    @Test
    fun `a repeated query key keeps the last value`() {
        val (_, o) = ok("wg://h.example:1?pk=${enc(sk)}&pub=${enc(pk)}&ip=10.0.0.2&mtu=1300&mtu=1280")
        o.mtu shouldBe 1280
    }

    @Test
    fun `local addresses must be real literals with a sane prefix`() {
        fun addr(a: String) = "wg://h.example:1?pk=${enc(sk)}&pub=${enc(pk)}&ip=${enc(a)}"
        listOf("...", "::::", "1.2.3", "256.1.1.1", "10.0.0.2/33", "fd00::2/129", "fd00:::2", "1::2::3", "10.0.0.2/")
            .forEach { bad(addr(it)).detail shouldBe FailureDetail.Malformed(DetailField.LocalAddress) }
        listOf("10.0.0.2", "10.0.0.2/0", "10.0.0.2/32", "fd00::2/128", "::1", "2001:db8:0:0:0:0:0:1", "::").forEach {
            ok(addr(it))
        }
    }

    // Ruling D2-R15: wireguard-go reads each allowed IP with netip.ParsePrefix when the tunnel starts.
    @Test
    fun `bare allowed ips get a host prefix and garbage is a typed failure`() {
        fun link(a: String) = "wg://h.example:1?pk=${enc(sk)}&pub=${enc(pk)}&ip=10.0.0.2&allowedips=${enc(a)}"
        ok(link("10.1.2.3,fd00::1,192.168.0.0/16")).second.allowedIps shouldBe
            listOf("10.1.2.3/32", "fd00::1/128", "192.168.0.0/16")
        for (a in listOf("0.0.0.0/0,example.com", "10.0.0.0/33", "01.2.3.4")) {
            bad(link(a)).detail shouldBe FailureDetail.Malformed(DetailField.AllowedIps)
        }
    }

    @Test
    fun `a missing port is an invalid port`() {
        val base = "?pk=${enc(sk)}&pub=${enc(pk)}&ip=10.0.0.2"
        bad("wg://h.example$base").reason shouldBe ParseFailureReason.InvalidPort
        bad("wg://h.example:$base").reason shouldBe ParseFailureReason.InvalidPort
    }

    @Test
    fun `a bracketed ipv6 host without a port is malformed`() {
        val base = "?pk=${enc(sk)}&pub=${enc(pk)}&ip=10.0.0.2"
        bad("wg://[2001:db8::1]$base").reason shouldBe ParseFailureReason.MalformedUri
        bad("wg://[2001:db8::1]:$base").reason shouldBe ParseFailureReason.InvalidPort
    }
}
