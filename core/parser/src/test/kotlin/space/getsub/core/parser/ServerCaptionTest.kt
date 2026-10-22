// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.parser

import io.kotest.matchers.shouldBe
import org.junit.Test
import space.getsub.core.model.Profile
import java.util.Base64

/** M10-B spec §2: Happ's `#title?serverDescription=<base64>` caption (research note 2026-10-22). */
class ServerCaptionTest {
    private fun trojan(fragment: String): Profile {
        val result = parseTrojanLink("trojan://pw@host.example:443#$fragment", 0)
        return (result as LinkResult.Ok).profile
    }

    private fun b64(text: String): String = Base64.getEncoder().encodeToString(text.toByteArray())

    @Test
    fun `a valid base64 caption is split off the name and decoded`() {
        val profile = trojan("Berlin?serverDescription=SGFwcCB0aGUgYmVzdA==")

        profile.name shouldBe "Berlin"
        profile.caption shouldBe "Happ the best"
    }

    @Test
    fun `an invalid base64 suffix leaves the whole string as the name`() {
        val profile = trojan("Berlin?serverDescription=%%%not-base64")

        profile.name shouldBe "Berlin?serverDescription=%%%not-base64"
        profile.caption shouldBe null
    }

    @Test
    fun `a caption decoding to a replacement character is not a caption`() {
        // 0xFF is not valid UTF-8 on its own; a lenient decoder turns it into U+FFFD.
        val garbled = Base64.getEncoder().encodeToString(byteArrayOf(0x68, 0xFF.toByte(), 0x69))
        val profile = trojan("Berlin?serverDescription=$garbled")

        profile.name shouldBe "Berlin?serverDescription=$garbled"
        profile.caption shouldBe null
    }

    @Test
    fun `a caption containing a control character is not a caption`() {
        val fragment = "Berlin?serverDescription=" + b64("line one\nline two")
        val profile = trojan(fragment)

        profile.name shouldBe fragment
        profile.caption shouldBe null
    }

    @Test
    fun `a caption over 30 characters is truncated to 30`() {
        val profile = trojan("Berlin?serverDescription=" + b64("x".repeat(45)))

        profile.caption shouldBe "x".repeat(30)
    }

    @Test
    fun `a name with a literal question mark and no caption is unchanged`() {
        val profile = trojan("Is this fast?")

        profile.name shouldBe "Is this fast?"
        profile.caption shouldBe null
    }

    @Test
    fun `a link with no fragment has no caption`() {
        (parseTrojanLink("trojan://pw@host.example:443", 0) as LinkResult.Ok).profile.caption shouldBe null
    }

    @Test
    fun `every fragment-named scheme carries the caption`() {
        val frag = "Name?serverDescription=SGFwcCB0aGUgYmVzdA=="
        val links =
            listOf(
                "vless://11111111-1111-1111-1111-111111111111@host.example:443?security=none#$frag",
                "ss://YWVzLTI1Ni1nY206cHc=@host.example:8388#$frag",
                "socks://host.example:1080#$frag",
                "hysteria2://pw12345@host.example:443/?insecure=1#$frag",
            )
        for (link in links) {
            val profile = (parseShareLink(link, 0) as LinkResult.Ok).profile
            profile.name shouldBe "Name"
            profile.caption shouldBe "Happ the best"
        }
    }

    @Test
    fun `vmess takes the caption from the json field`() {
        val json =
            """{"v":"2","ps":"Name","add":"host.example","port":"443",""" +
                """"id":"11111111-1111-1111-1111-111111111111","serverDescription":"Happ the best"}"""
        val profile = (parseShareLink("vmess://" + b64(json), 0) as LinkResult.Ok).profile

        profile.caption shouldBe "Happ the best"
    }
}
