// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
@file:Suppress("MatchingDeclarationName") // The file mirrors the core's Shadowsocks rules; the enum is one part.

package space.getsub.core.model

import java.util.Base64

/** Why Xray-core v26.10.10 would refuse a Shadowsocks outbound; the secret itself is never carried. */
public enum class ShadowsocksRefusal {
    /** The method is neither a 2022 method nor a name `cipherFromString` knows. */
    Cipher,

    /** A 2022 method whose password has a part that `ParseKey` rejects. */
    Key,
}

/**
 * The methods the profile editor offers, in display order: the three 2022 methods first, then the
 * AEAD ciphers under the names Xray-core documents. Every entry is accepted by [shadowsocksCoreRefusal].
 */
public val SHADOWSOCKS_CORE_METHODS: List<String> =
    listOf(
        "2022-blake3-aes-128-gcm",
        "2022-blake3-aes-256-gcm",
        "2022-blake3-chacha20-poly1305",
        "aes-128-gcm",
        "aes-256-gcm",
        "chacha20-ietf-poly1305",
        "xchacha20-ietf-poly1305",
    )

private const val KEY_LENGTH_128 = 16
private const val KEY_LENGTH_256 = 32
private const val CHACHA_2022 = "2022-blake3-chacha20-poly1305"

/**
 * Upstream `proxy/shadowsocks_2022/cipher.go:18-22` (`methods`, v26.10.10): the 2022 methods and
 * their PSK length in bytes. `GetCipherMethod` (cipher.go:24-30) lowercases the name before lookup.
 */
private val METHODS_2022: Map<String, Int> =
    mapOf(
        "2022-blake3-aes-128-gcm" to KEY_LENGTH_128,
        "2022-blake3-aes-256-gcm" to KEY_LENGTH_256,
        CHACHA_2022 to KEY_LENGTH_256,
    )

/**
 * Upstream `infra/conf/shadowsocks.go:15-28` (`cipherFromString`, v26.10.10), lowercased names that
 * map to a known `CipherType`. Anything else is `CipherType_UNKNOWN`, which the build rejects
 * (shadowsocks.go:98 and :242).
 */
private val LEGACY_CIPHERS: Set<String> =
    setOf(
        "aes-128-gcm",
        "aead_aes_128_gcm",
        "aes-256-gcm",
        "aead_aes_256_gcm",
        "chacha20-poly1305",
        "aead_chacha20_poly1305",
        "chacha20-ietf-poly1305",
        "xchacha20-poly1305",
        "aead_xchacha20_poly1305",
        "xchacha20-ietf-poly1305",
    )

/** PSK length in bytes for a 2022 method (16 or 32), or null when [method] is not a 2022 method. */
public fun shadowsocks2022KeyLength(method: String): Int? = METHODS_2022[method.lowercase()]

/**
 * Whether Xray-core v26.10.10 refuses a Shadowsocks outbound with this [method] and [password].
 *
 * Order follows `infra/conf/shadowsocks.go:56` and :242: a 2022 method (`GetCipherMethod`) is tried
 * first; otherwise `cipherFromString` must know the name, else [ShadowsocksRefusal.Cipher]. Only a
 * 2022 method constrains the password, via `ParsePSKList` (`proxy/shadowsocks_2022/kdf.go:27-38`):
 * split on `:` and every part must pass `ParseKey`, else [ShadowsocksRefusal.Key]. `NewClient`
 * (`proxy/shadowsocks_2022/outbound.go:48-50`) then refuses more than one key for
 * `2022-blake3-chacha20-poly1305` ("multi-key is not supported"), also [ShadowsocksRefusal.Key].
 *
 * An empty password is outside the mirror: the core refuses it for every method, but no importer or
 * the editor produces a Shadowsocks row without one, so only a 2022 method answers for it here.
 */
public fun shadowsocksCoreRefusal(
    method: String,
    password: String,
): ShadowsocksRefusal? {
    val keyLength = shadowsocks2022KeyLength(method)
    return when {
        keyLength != null -> {
            val parts = password.split(':')
            val multiKeyChaCha = parts.size > 1 && method.lowercase() == CHACHA_2022
            if (!multiKeyChaCha && parts.all { isAcceptedKey(it, keyLength) }) null else ShadowsocksRefusal.Key
        }
        method.lowercase() in LEGACY_CIPHERS -> null
        else -> ShadowsocksRefusal.Cipher
    }
}

/**
 * Upstream `ParseKey` (`proxy/shadowsocks_2022/kdf.go:16-25`): the part is base64-decoded with
 * `StdEncoding`; if that fails the raw UTF-8 bytes are used instead. Either way the byte length must
 * equal [keyLength].
 *
 * Go's `StdEncoding` demands padding and skips `\r` and `\n`; Java's basic decoder accepts missing
 * padding, so a part whose (newline-stripped) length is not a multiple of four is treated as a
 * decode failure before Java is asked.
 */
private fun isAcceptedKey(
    part: String,
    keyLength: Int,
): Boolean {
    val stripped = part.filter { it != '\r' && it != '\n' }
    val decoded =
        if (stripped.length % BASE64_QUANTUM == 0) {
            runCatching { Base64.getDecoder().decode(stripped) }.getOrNull()
        } else {
            null
        }
    return (decoded?.size ?: part.toByteArray(Charsets.UTF_8).size) == keyLength
}

private const val BASE64_QUANTUM = 4
