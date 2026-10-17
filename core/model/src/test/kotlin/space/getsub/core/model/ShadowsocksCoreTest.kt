// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.model

import io.kotest.matchers.shouldBe
import org.junit.Test
import java.util.Base64

class ShadowsocksCoreTest {
    private fun b64(size: Int): String = Base64.getEncoder().encodeToString(ByteArray(size) { SEVEN })

    @Test
    fun `offer list is the seven methods in editor order`() {
        SHADOWSOCKS_CORE_METHODS shouldBe
            listOf(
                "2022-blake3-aes-128-gcm",
                "2022-blake3-aes-256-gcm",
                "2022-blake3-chacha20-poly1305",
                "aes-128-gcm",
                "aes-256-gcm",
                "chacha20-ietf-poly1305",
                "xchacha20-ietf-poly1305",
            )
    }

    @Test
    fun `every offered method is accepted with a valid password`() {
        for (method in SHADOWSOCKS_CORE_METHODS) {
            val password = shadowsocks2022KeyLength(method)?.let { b64(it) } ?: "pw"
            shadowsocksCoreRefusal(method, password) shouldBe null
        }
    }

    @Test
    fun `unknown ciphers are refused as Cipher`() {
        for (method in listOf("aes-192-gcm", "none", "plain", "")) {
            shadowsocksCoreRefusal(method, "pw") shouldBe ShadowsocksRefusal.Cipher
        }
    }

    @Test
    fun `cipher names are case-insensitive and aliases are accepted`() {
        for (method in listOf("AES-256-GCM", "chacha20-poly1305", "xchacha20-poly1305", "aead_aes_128_gcm")) {
            shadowsocksCoreRefusal(method, "pw") shouldBe null
        }
        shadowsocksCoreRefusal("2022-BLAKE3-AES-128-GCM", b64(16)) shouldBe null
    }

    @Test
    fun `2022 key lengths`() {
        shadowsocks2022KeyLength("2022-blake3-aes-128-gcm") shouldBe 16
        shadowsocks2022KeyLength("2022-blake3-aes-256-gcm") shouldBe 32
        shadowsocks2022KeyLength("2022-blake3-chacha20-poly1305") shouldBe 32
        shadowsocks2022KeyLength("aes-256-gcm") shouldBe null
        shadowsocks2022KeyLength("") shouldBe null
    }

    @Test
    fun `2022 base64 key must match the method length`() {
        shadowsocksCoreRefusal("2022-blake3-aes-128-gcm", b64(16)) shouldBe null
        shadowsocksCoreRefusal("2022-blake3-aes-256-gcm", b64(16)) shouldBe ShadowsocksRefusal.Key
        shadowsocksCoreRefusal("2022-blake3-aes-256-gcm", b64(32)) shouldBe null
        shadowsocksCoreRefusal("2022-blake3-chacha20-poly1305", b64(32)) shouldBe null
        shadowsocksCoreRefusal("2022-blake3-aes-128-gcm", b64(32)) shouldBe ShadowsocksRefusal.Key
    }

    @Test
    fun `2022 psk list needs every part valid`() {
        val good = b64(32)
        shadowsocksCoreRefusal("2022-blake3-aes-256-gcm", "$good:$good") shouldBe null
        shadowsocksCoreRefusal("2022-blake3-aes-256-gcm", "$good:${b64(16)}") shouldBe ShadowsocksRefusal.Key
        shadowsocksCoreRefusal("2022-blake3-aes-256-gcm", "${b64(16)}:$good") shouldBe ShadowsocksRefusal.Key
        shadowsocksCoreRefusal("2022-blake3-aes-256-gcm", "$good:") shouldBe ShadowsocksRefusal.Key
        shadowsocksCoreRefusal("2022-blake3-aes-256-gcm", "") shouldBe ShadowsocksRefusal.Key
    }

    @Test
    fun `2022 raw ascii key falls back to byte length when it is not base64`() {
        // A '-' is outside the standard alphabet, so Go's StdEncoding fails and ParseKey keeps the raw bytes.
        shadowsocksCoreRefusal("2022-blake3-aes-256-gcm", "0123456789abcdef-123456789abcdef") shouldBe null
        shadowsocksCoreRefusal("2022-blake3-aes-128-gcm", "0123456789abcde-") shouldBe null
        shadowsocksCoreRefusal("2022-blake3-aes-256-gcm", "short") shouldBe ShadowsocksRefusal.Key
    }

    @Test
    fun `2022 raw ascii key that happens to be valid base64 is decoded, not kept raw`() {
        // 32 alphanumerics are valid base64 and decode to 24 bytes, so the raw fallback never runs.
        shadowsocksCoreRefusal("2022-blake3-aes-256-gcm", "0123456789abcdef0123456789abcdef") shouldBe
            ShadowsocksRefusal.Key
    }

    @Test
    fun `2022 unpadded base64 is not base64`() {
        val unpadded = b64(32).trimEnd('=')
        unpadded.length shouldBe 43
        shadowsocksCoreRefusal("2022-blake3-aes-256-gcm", unpadded) shouldBe ShadowsocksRefusal.Key
    }

    @Test
    fun `2022 url-safe base64 alphabet is not decoded`() {
        // 32 bytes of 0xFB encode to "-" and "_" in the URL alphabet; the standard decoder rejects them.
        val urlSafe = Base64.getUrlEncoder().encodeToString(ByteArray(32) { 0xFB.toByte() })
        shadowsocksCoreRefusal("2022-blake3-aes-256-gcm", urlSafe) shouldBe ShadowsocksRefusal.Key
    }

    @Test
    fun `non-2022 methods ignore the password shape`() {
        shadowsocksCoreRefusal("aes-256-gcm", "x") shouldBe null
        shadowsocksCoreRefusal("aes-256-gcm", "") shouldBe null
    }

    private companion object {
        const val SEVEN: Byte = 7
    }
}
