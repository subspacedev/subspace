// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.parser

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.Test
import space.getsub.core.model.KcpHeader
import space.getsub.core.model.KcpMask
import space.getsub.core.model.KcpObfuscation
import space.getsub.core.model.KcpRules
import space.getsub.core.model.KcpUnsupported
import space.getsub.core.model.Outbound
import space.getsub.core.model.TransportOptions
import space.getsub.core.model.TrojanOutbound
import space.getsub.core.model.VlessOutbound
import space.getsub.core.model.VmessOutbound

class KcpHttpUpgradeLinkTest {
    private val uuid = "11111111-2222-3333-4444-555555555555"

    private fun transportOf(outbound: Outbound): TransportOptions =
        when (outbound) {
            is VlessOutbound -> outbound.stream.transport
            is TrojanOutbound -> outbound.stream.transport
            is VmessOutbound -> outbound.stream.transport
            else -> error("no stream")
        }

    private fun vless(query: String): TransportOptions {
        val r = parseShareLink("vless://$uuid@h.example:443?security=none&$query", 0)
        r.shouldBeInstanceOf<LinkResult.Ok>()
        return transportOf(r.profile.outbound)
    }

    private fun kcp(query: String): TransportOptions.Kcp = vless("type=kcp&$query") as TransportOptions.Kcp

    private fun failure(link: String): ParseFailure {
        val r = parseShareLink(link, 3)
        r.shouldBeInstanceOf<LinkResult.Bad>()
        return r.failure
    }

    private fun fm(json: String) =
        java.net.URLEncoder
            .encode(json, "UTF-8")
            .replace("+", "%20")

    @Test
    fun `bare kcp link has no obfuscation`() {
        val k = kcp("")
        k shouldBe TransportOptions.Kcp(null, null, KcpObfuscation(null, null, false))
        KcpRules.masks(k.obfuscation) shouldBe emptyList()
    }

    @Test
    fun `mkcp alias and mtu tti are read, network kept as given`() {
        val r = parseShareLink("vless://$uuid@h.example:443?security=none&type=mkcp&mtu=1200&tti=30", 0)
        r.shouldBeInstanceOf<LinkResult.Ok>()
        (r.profile.outbound as VlessOutbound).stream.network shouldBe "mkcp"
        transportOf(r.profile.outbound) shouldBe TransportOptions.Kcp(1200, 30, KcpObfuscation(null, null, false))
    }

    @Test
    fun `seed only`() {
        KcpRules.masks(kcp("seed=abc").obfuscation) shouldBe listOf(KcpMask.Seed("abc"))
    }

    @Test
    fun `whitespace seed is verbatim and empty seed is none`() {
        KcpRules.masks(kcp("seed=%20").obfuscation) shouldBe listOf(KcpMask.Seed(" "))
        KcpRules.masks(kcp("seed=").obfuscation) shouldBe emptyList()
    }

    @Test
    fun `header only is original plus header`() {
        val o = kcp("headerType=wechat-video").obfuscation
        o.legacyOriginal shouldBe true
        KcpRules.masks(o) shouldBe listOf(KcpMask.Original, KcpMask.Header(KcpHeader.WECHAT))
    }

    @Test
    fun `seed and header`() {
        KcpRules.masks(kcp("seed=s&headerType=SRTP").obfuscation) shouldBe
            listOf(KcpMask.Seed("s"), KcpMask.Header(KcpHeader.SRTP))
    }

    @Test
    fun `headerType none is no header`() {
        kcp("headerType=none").obfuscation shouldBe KcpObfuscation(null, null, false)
    }

    @Test
    fun `headerType http is not connectable with a named reason`() {
        val o = kcp("headerType=http&seed=zzz").obfuscation
        o.unsupported shouldBe KcpUnsupported.HttpHeader
    }

    @Test
    fun `unknown header is not connectable`() {
        kcp("headerType=bogus").obfuscation.unsupported shouldBe KcpUnsupported.UnknownHeader
    }

    @Test
    fun `bad mtu and tti are typed failures that echo nothing`() {
        val f = failure("vless://$uuid@h.example:443?type=kcp&tti=5")
        f.detail shouldBe FailureDetail.Range(DetailField.Tti, 10, 1000, 5)
        failure("vless://$uuid@h.example:443?type=kcp&tti=1001").detail.shouldBeInstanceOf<FailureDetail.Range>()
        failure("vless://$uuid@h.example:443?type=kcp&mtu=20").detail.shouldBeInstanceOf<FailureDetail.Range>()
        failure("vless://$uuid@h.example:443?type=kcp&mtu=abc").detail shouldBe
            FailureDetail.Malformed(DetailField.Mtu)
        kcp("mtu=21&tti=10").mtu shouldBe 21
        kcp("mtu=100000").mtu shouldBe 100000
        kcp("tti=1000").tti shouldBe 1000
    }

    @Test
    fun `fm wins over seed and header`() {
        val json = """{"udp":[{"type":"mkcp-legacy","settings":{"value":"fromfm"}}]}"""
        KcpRules.masks(kcp("seed=link&headerType=dns&fm=${fm(json)}").obfuscation) shouldBe
            listOf(KcpMask.Seed("fromfm"))
    }

    @Test
    fun `fm with seed and header`() {
        val json =
            """{"udp":[{"type":"mkcp-legacy","settings":{"value":"s"}},""" +
                """{"type":"mkcp-legacy","settings":{"header":"wireguard"}}]}"""
        KcpRules.masks(kcp("fm=${fm(json)}").obfuscation) shouldBe
            listOf(KcpMask.Seed("s"), KcpMask.Header(KcpHeader.WIREGUARD))
    }

    @Test
    fun `fm with another udp mask is not connectable`() {
        val json = """{"udp":[{"type":"salamander","settings":{"password":"pppp"}}]}"""
        kcp("fm=${fm(json)}").obfuscation.unsupported shouldBe KcpUnsupported.UnknownMask
    }

    @Test
    fun `fm that is not json is a typed failure`() {
        failure("vless://$uuid@h.example:443?type=kcp&fm=%7Bnope").reason shouldBe ParseFailureReason.MalformedJson
    }

    @Test
    fun `fm is ignored on other transports`() {
        val json = """{"udp":[{"type":"salamander","settings":{"password":"pppp"}}]}"""
        vless("type=tcp&fm=${fm(json)}") shouldBe TransportOptions.None
        vless("type=ws&path=%2Fp&fm=%7Bnope") shouldBe TransportOptions.WebSocket("/p", emptyMap())
    }

    @Test
    fun `trojan kcp link`() {
        val r = parseShareLink("trojan://pw@h.example:443?security=none&type=kcp&seed=x&mtu=1000", 0)
        r.shouldBeInstanceOf<LinkResult.Ok>()
        transportOf(r.profile.outbound) shouldBe TransportOptions.Kcp(1000, null, KcpObfuscation("x", null, false))
    }

    @Test
    fun `httpupgrade host and path with ed kept`() {
        vless("type=httpupgrade&host=cdn.example&path=%2Fup%3Fed%3D2048") shouldBe
            TransportOptions.HttpUpgrade("cdn.example", "/up?ed=2048")
    }

    @Test
    fun `httpupgrade defaults`() {
        vless("type=httpupgrade") shouldBe TransportOptions.HttpUpgrade("", "/")
    }

    @Test
    fun `trojan httpupgrade`() {
        val r = parseShareLink("trojan://pw@h.example:443?type=httpupgrade&host=a.example&path=%2Fx", 0)
        r.shouldBeInstanceOf<LinkResult.Ok>()
        transportOf(r.profile.outbound) shouldBe TransportOptions.HttpUpgrade("a.example", "/x")
    }

    private fun vmess(json: String): TransportOptions {
        val b64 =
            java.util.Base64
                .getEncoder()
                .encodeToString(json.toByteArray())
        val r = parseShareLink("vmess://$b64", 0)
        r.shouldBeInstanceOf<LinkResult.Ok>()
        return transportOf(r.profile.outbound)
    }

    @Test
    fun `vmess kcp reads type as header and path as seed`() {
        val base = "\"add\":\"h.example\",\"port\":\"443\",\"id\":\"$uuid\",\"aid\":\"0\",\"tls\":\"\""
        val t = vmess("""{$base,"net":"kcp","type":"dtls","path":"sd"}""") as TransportOptions.Kcp
        KcpRules.masks(t.obfuscation) shouldBe listOf(KcpMask.Seed("sd"), KcpMask.Header(KcpHeader.DTLS))
        (vmess("""{$base,"net":"kcp"}""") as TransportOptions.Kcp).obfuscation shouldBe
            KcpObfuscation(null, null, false)
        vmess("""{$base,"net":"httpupgrade","host":"a.example","path":"/p?ed=1"}""") shouldBe
            TransportOptions.HttpUpgrade("a.example", "/p?ed=1")
    }
}
