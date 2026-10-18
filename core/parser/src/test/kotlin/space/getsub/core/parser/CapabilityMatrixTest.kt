// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.parser

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.Test
import java.util.Base64

private const val MATRIX_UUID = "70cc48c5-b2f4-4a1e-9f3d-0123456789ab"
private const val WG_KEY_ENCODED = "ccccccccccccccccccccccccccccccccccccccccccc%3D"
private const val WG_KEY = "ccccccccccccccccccccccccccccccccccccccccccc="

/**
 * The protocol × container support matrix, as a test rather than a claim.
 *
 * ARCHITECTURE.md §7 lists six link protocols and four container shapes, which
 * reads as twenty-four working combinations. It is twenty. The remaining gaps are
 * not bugs — nobody has written those branches — but ARCHITECTURE.md §7 stated the capability
 * unqualified, so the table there and this test exist to keep the real shape
 * visible and to fail if a cell silently changes.
 *
 * Clash/`vless` used to be the cell that mattered most: VLESS is the only
 * protocol `:core:xray` can actually emit a config for, and it was the one
 * cell Clash lacked, so a Clash import produced profiles that all failed at
 * connect. M3 fills that cell, and fills Clash/`socks5` alongside it, closing
 * out the Clash column entirely — see the matrix in ARCHITECTURE.md §7.
 *
 * Each cell asserts only whether a profile comes out, because that is exactly
 * what the ARCHITECTURE.md §7 table claims. Field-level correctness for the cells that do work
 * is the job of the per-format tests.
 */
class CapabilityMatrixTest {
    private fun yieldsProfile(input: String): Boolean = SubscriptionParser.parse(input).profiles.isNotEmpty()

    private fun base64(plain: String): String = Base64.getEncoder().encodeToString(plain.toByteArray())

    private val links =
        mapOf(
            "vless" to "vless://$MATRIX_UUID@host.example:443",
            "vmess" to "vmess://" + base64("""{"add":"host.example","port":443,"id":"$MATRIX_UUID"}"""),
            "trojan" to "trojan://s3cret@host.example:443",
            "ss" to "ss://" + base64("aes-256-gcm:s3cret") + "@host.example:8388",
            "socks" to "socks://host.example:1080",
            "hysteria2" to "hysteria2://s3cret@host.example:443",
            "wireguard" to
                "wireguard://$WG_KEY_ENCODED@host.example:51820?publickey=$WG_KEY_ENCODED&address=10.0.0.2",
        )

    private val clash =
        mapOf(
            "vless" to "proxies:\n  - {name: N, type: vless, server: host.example, port: 443, uuid: $MATRIX_UUID}",
            "vmess" to "proxies:\n  - {name: N, type: vmess, server: host.example, port: 443, uuid: $MATRIX_UUID}",
            "trojan" to "proxies:\n  - {name: N, type: trojan, server: host.example, port: 443, password: s3cret}",
            "ss" to
                "proxies:\n  - {name: N, type: ss, server: host.example, port: 8388, " +
                "cipher: aes-256-gcm, password: s3cret}",
            "socks" to "proxies:\n  - {name: N, type: socks5, server: host.example, port: 1080}",
            "hysteria2" to
                "proxies:\n  - {name: N, type: hysteria2, server: host.example, port: 443, " +
                "password: s3cret}",
            "wireguard" to
                "proxies:\n  - {name: N, type: wireguard, server: host.example, port: 51820, ip: 10.0.0.2, " +
                "private-key: '$WG_KEY', public-key: '$WG_KEY'}",
            "http" to "proxies:\n  - {name: N, type: http, server: host.example, port: 3128}",
        )

    private val xrayJson =
        mapOf(
            "vless" to
                """{"outbounds":[{"protocol":"vless","settings":{"vnext":[""" +
                """{"address":"host.example","port":443,"users":[{"id":"$MATRIX_UUID"}]}]}}]}""",
            "vmess" to
                """{"outbounds":[{"protocol":"vmess","settings":{"vnext":[""" +
                """{"address":"host.example","port":443,"users":[{"id":"$MATRIX_UUID"}]}]}}]}""",
            "trojan" to
                """{"outbounds":[{"protocol":"trojan","settings":{"servers":[""" +
                """{"address":"host.example","port":443,"password":"s3cret"}]}}]}""",
            "ss" to
                """{"outbounds":[{"protocol":"shadowsocks","settings":{"servers":[""" +
                """{"address":"host.example","port":8388,"method":"aes-256-gcm","password":"s3cret"}]}}]}""",
            "socks" to
                """{"outbounds":[{"protocol":"socks","settings":{"servers":[""" +
                """{"address":"host.example","port":1080}]}}]}""",
            "hysteria2" to
                """{"outbounds":[{"protocol":"hysteria",""" +
                """"settings":{"version":2,"address":"host.example","port":443},""" +
                """"streamSettings":{"network":"hysteria","hysteriaSettings":{"version":2,"auth":"s3cret"},""" +
                """"security":"tls","tlsSettings":{"serverName":"host.example"}}}]}""",
            "wireguard" to
                """{"outbounds":[{"protocol":"wireguard","settings":{"secretKey":"$WG_KEY",""" +
                """"address":["10.0.0.2"],"peers":[{"publicKey":"$WG_KEY","endpoint":"host.example:51820"}]}}]}""",
            "http" to
                """{"outbounds":[{"protocol":"http","settings":{"address":"host.example","port":3128}}]}""",
        )

    @Test
    fun `every protocol parses as a share link`() {
        links.forEach { (protocol, link) ->
            withClue(protocol) { yieldsProfile(link) shouldBe true }
        }
    }

    @Test
    fun `every protocol parses inside a base64 subscription`() {
        // The base64 column can never differ from the link column: the blob is
        // decoded and re-fed through detection, which lands on the link list.
        links.forEach { (protocol, link) ->
            withClue(protocol) { yieldsProfile(base64(link)) shouldBe true }
        }
    }

    @Test
    fun `clash yaml supports exactly the protocols in the support set`() {
        val supported = setOf("vmess", "trojan", "ss", "vless", "socks", "hysteria2", "wireguard", "http")
        clash.forEach { (protocol, yaml) ->
            withClue("clash $protocol") { yieldsProfile(yaml) shouldBe (protocol in supported) }
        }
    }

    @Test
    fun `raw xray json supports vless, hysteria2, wireguard and http only`() {
        xrayJson.forEach { (protocol, json) ->
            val supported = setOf("vless", "hysteria2", "wireguard", "http")
            withClue("xray json $protocol") { yieldsProfile(json) shouldBe (protocol in supported) }
        }
    }

    /**
     * The cell that used to decide whether a Clash import was usable at all.
     * M3 fills it: Clash `vless` now yields a connectable profile, so the
     * inverse of the assertion this test used to make is the one worth
     * pinning going forward.
     */
    @Test
    fun `clash can now express the one protocol the tunnel can use`() {
        val clashVless = clash.getValue("vless")

        yieldsProfile(clashVless) shouldBe true
        SubscriptionParser.parse(clashVless).failures shouldBe emptyList()
    }
}
