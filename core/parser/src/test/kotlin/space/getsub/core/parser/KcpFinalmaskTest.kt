// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.parser

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Test
import space.getsub.core.model.KcpHeader
import space.getsub.core.model.KcpMask
import space.getsub.core.model.KcpRules
import space.getsub.core.model.KcpUnsupported

class KcpFinalmaskTest {
    private fun read(json: String) = readKcpFinalmask(Json.parseToJsonElement(json) as JsonObject)

    private fun legacy(settings: String) = """{"type":"mkcp-legacy","settings":$settings}"""

    private fun udp(vararg masks: String) = """{"udp":[${masks.joinToString(",")}]}"""

    @Test
    fun `no udp masks says nothing`() {
        read("{}").obfuscation shouldBe null
        read("""{"tcp":[]}""").obfuscation shouldBe null
        read(udp()).obfuscation shouldBe null
    }

    @Test
    fun `original`() {
        val o = read(udp(legacy("{}"))).obfuscation!!
        o.legacyOriginal shouldBe true
        KcpRules.masks(o) shouldBe listOf(KcpMask.Original)
    }

    @Test
    fun `original with empty fields`() {
        KcpRules.masks(read(udp(legacy("""{"header":"","value":""}"""))).obfuscation!!) shouldBe
            listOf(KcpMask.Original)
    }

    @Test
    fun `missing settings is original`() {
        KcpRules.masks(read(udp("""{"type":"mkcp-legacy"}""")).obfuscation!!) shouldBe listOf(KcpMask.Original)
    }

    @Test
    fun `seed then header`() {
        val o = read(udp(legacy("""{"value":"s"}"""), legacy("""{"header":"Utp"}"""))).obfuscation!!
        KcpRules.masks(o) shouldBe listOf(KcpMask.Seed("s"), KcpMask.Header(KcpHeader.UTP))
    }

    @Test
    fun `original then header round trips`() {
        val o = read(udp(legacy("{}"), legacy("""{"header":"wechat"}"""))).obfuscation!!
        KcpRules.masks(o) shouldBe listOf(KcpMask.Original, KcpMask.Header(KcpHeader.WECHAT))
    }

    @Test
    fun `header alone cannot be reproduced`() {
        read(udp(legacy("""{"header":"dtls"}"""))).obfuscation!!.unsupported shouldBe KcpUnsupported.UnknownMask
    }

    @Test
    fun `header before seed cannot be reproduced`() {
        read(udp(legacy("""{"header":"dtls"}"""), legacy("""{"value":"s"}"""))).obfuscation!!.unsupported shouldBe
            KcpUnsupported.UnknownMask
    }

    @Test
    fun `dns header with a domain is not connectable`() {
        val o = read(udp(legacy("{}"), legacy("""{"header":"dns","value":"example.com"}"""))).obfuscation!!
        o.unsupported shouldBe KcpUnsupported.DnsHeaderDomain
        o.toString().contains("example.com") shouldBe false
    }

    @Test
    fun `unknown header`() {
        read(udp(legacy("""{"header":"nope"}"""))).obfuscation!!.unsupported shouldBe KcpUnsupported.UnknownHeader
    }

    @Test
    fun `noise and other masks are not connectable`() {
        read(udp(legacy("""{"value":"s"}"""), """{"type":"noise","settings":{}}""")).obfuscation!!.unsupported shouldBe
            KcpUnsupported.UnknownMask
        read(udp("""{"type":"xdns","settings":{"domain":"a"}}""")).obfuscation!!.unsupported shouldBe
            KcpUnsupported.UnknownMask
        read("""{"udp":["junk"]}""").obfuscation!!.unsupported shouldBe KcpUnsupported.UnknownMask
    }

    @Test
    fun `two seeds or a header with a seed value are not reproducible`() {
        read(udp(legacy("""{"value":"a"}"""), legacy("""{"value":"b"}"""))).obfuscation!!.unsupported shouldBe
            KcpUnsupported.UnknownMask
        read(udp(legacy("""{"header":"srtp","value":"b"}"""))).obfuscation!!.unsupported shouldBe
            KcpUnsupported.UnknownMask
    }
}
