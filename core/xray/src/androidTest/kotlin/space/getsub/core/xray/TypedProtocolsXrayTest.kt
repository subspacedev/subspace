// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.xray

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import space.getsub.core.model.Applied
import space.getsub.core.model.FragmentPackets
import space.getsub.core.model.FragmentSpec
import space.getsub.core.model.NoiseItem
import space.getsub.core.model.NoisePayload
import space.getsub.core.model.NoiseSpec
import space.getsub.core.model.Obfuscation
import space.getsub.core.model.Outbound
import space.getsub.core.model.Profile
import space.getsub.core.model.SHADOWSOCKS_CORE_METHODS
import space.getsub.core.model.Security
import space.getsub.core.model.ShadowsocksOutbound
import space.getsub.core.model.SocksOutbound
import space.getsub.core.model.StreamSettings
import space.getsub.core.model.TransportOptions
import space.getsub.core.model.TrojanOutbound
import space.getsub.core.model.VlessOutbound
import space.getsub.core.model.VmessOutbound
import space.getsub.core.model.XrayRange
import space.getsub.core.model.refusedWithoutTransportSecurity
import space.getsub.core.model.shadowsocks2022KeyLength
import space.getsub.core.model.shadowsocksCoreRefusal
import java.util.Base64

/**
 * Feeds the generator's VMess, Trojan, Shadowsocks and SOCKS configs to the real libXray v26.10.10
 * core (`testXray`, which runs `core.New`, so outbound constructors such as the Shadowsocks 2022
 * `NewClient` key parse run too), and proves the `:core:model` Shadowsocks mirror agrees with it.
 *
 * ARCHITECTURE.md §10.5: the JVM goldens prove what we emit; only the core proves it is accepted.
 * Failure messages carry a case label of our own making, never the core's message, which can quote
 * the config back (ARCHITECTURE.md §5.6). Each test collects every failing label before asserting,
 * so one run shows the whole picture rather than the first casualty.
 *
 * Camel-case names, no backticks: DEX 040 restricts synthetic class names in instrumented tests.
 */
class TypedProtocolsXrayTest {
    private val uuid = "70cc48c5-b2f4-4a1e-9f3d-0123456789ab"
    private val tls = Security.Tls(serverName = "example.com", fingerprint = "chrome", allowInsecure = false)
    private val reality =
        Security.Reality(
            serverName = "www.microsoft.com",
            publicKey = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8",
            shortId = "0123abcd",
            fingerprint = "chrome",
            spiderX = "/",
        )

    private val ws = TransportOptions.WebSocket("/chat", mapOf("Host" to "cdn.example"))
    private val grpc = TransportOptions.Grpc("GunService")
    private val xhttpBare = TransportOptions.Xhttp("/", host = null, mode = null)
    private val xhttpFull = TransportOptions.Xhttp("/down", "cdn.example", "stream-up")

    private class Shape(
        val label: String,
        val address: String,
        val stream: StreamSettings,
    )

    /**
     * The brief's transport matrix. Plaintext xhttp dials a private address so it says nothing about
     * the no-TLS rule; plaintext tcp dials a public one, which VMess (unlike VLESS/Trojan) may do.
     */
    private val vmessShapes =
        listOf(
            Shape("tcp+tls", "example.com", StreamSettings("tcp", tls)),
            Shape("ws+tls", "example.com", StreamSettings("ws", tls, ws)),
            Shape("grpc+reality", "example.com", StreamSettings("grpc", reality, grpc)),
            Shape("xhttp+none private", "10.0.0.1", StreamSettings("xhttp", Security.None, xhttpBare)),
            Shape("tcp+none public", "1.1.1.1", StreamSettings("tcp", Security.None)),
        )

    private val trojanShapes =
        listOf(
            Shape("tcp+tls", "example.com", StreamSettings("tcp", tls)),
            Shape("ws+tls", "example.com", StreamSettings("ws", tls, ws)),
            Shape("grpc+reality", "example.com", StreamSettings("grpc", reality, grpc)),
            Shape("xhttp+tls", "example.com", StreamSettings("xhttp", tls, xhttpFull)),
            Shape("tcp+none private", "10.0.0.1", StreamSettings("tcp", Security.None)),
            Shape("xhttp+none private", "10.0.0.1", StreamSettings("xhttp", Security.None, xhttpBare)),
        )

    private val fragmentAndNoise =
        Obfuscation(
            Applied(
                FragmentSpec(FragmentPackets.TlsHello, XrayRange(50, 100), XrayRange(10, 20), XrayRange(10, 10)),
                replaceOwn = false,
            ),
            Applied(
                NoiseSpec(
                    listOf(NoiseItem(NoisePayload.Random(XrayRange(10, 20), XrayRange(0, 255)), XrayRange(10, 16))),
                ),
                replaceOwn = false,
            ),
        )

    private val settings =
        TunnelSettings(
            socksPort = 41080,
            dnsServer = "1.1.1.1",
            enableSniffing = true,
            proxyCredentials = TEST_CREDENTIALS,
        )

    private fun profile(outbound: Outbound): Profile = Profile(id = "id", name = "n", outbound = outbound)

    /** The generated config, or null when the generator did not answer [ConfigResult.Ok]. */
    private fun generate(outbound: Outbound): String? =
        (XrayConfigGenerator.generate(profile(outbound), settings) as? ConfigResult.Ok)?.json

    private suspend fun accepted(json: String): Boolean = runCatching { XrayController().validate(json) }.isSuccess

    /** Generates and validates every case; returns the labels that failed either step. */
    private suspend fun rejectedLabels(cases: List<Pair<String, Outbound>>): List<String> =
        cases.mapNotNull { (label, outbound) ->
            val json = generate(outbound)
            when {
                json == null -> "$label (generator refused)"
                !accepted(json) -> "$label (core refused)"
                else -> null
            }
        }

    private fun b64(bytes: Int): String = Base64.getEncoder().encodeToString(ByteArray(bytes) { it.toByte() })

    @Test
    fun everyEmittedVmessShapeIsAcceptedByTheRealCore() =
        runTest {
            val cases =
                vmessShapes.flatMap { shape ->
                    listOf("auto", "aes-128-gcm", "chacha20-poly1305", "none", "zero").flatMap { security ->
                        listOf(0, 64).map { alterId ->
                            "vmess ${shape.label} security=$security alterId=$alterId" to
                                VmessOutbound(shape.address, 443, uuid, alterId, security, shape.stream)
                        }
                    }
                }
            assertEquals("rejected VMess shapes", emptyList<String>(), rejectedLabels(cases))
        }

    @Test
    fun everyEmittedTrojanShapeIsAcceptedByTheRealCore() =
        runTest {
            val cases =
                trojanShapes.map { shape ->
                    "trojan ${shape.label}" to TrojanOutbound(shape.address, 443, "trojan-secret", shape.stream)
                }
            assertEquals("rejected Trojan shapes", emptyList<String>(), rejectedLabels(cases))
        }

    /** The address list of `XrayControllerTest.thePlaintextRefusalMatchesTheRealCore`, for Trojan. */
    @Test
    fun theTrojanPlaintextRefusalMatchesTheRealCore() =
        runTest {
            val addresses =
                listOf(
                    "example.com",
                    "1.1.1.1",
                    "2606:4700::1111",
                    "10.0.0.1",
                    "192.168.1.1",
                    "fd00::1",
                    "host.example",
                    "router",
                )
            val disagreements =
                addresses.mapNotNull { address ->
                    val plain = TrojanOutbound(address, 443, "trojan-secret", StreamSettings("tcp", Security.None))
                    val json = generate(plain) ?: return@mapNotNull "$address (generator refused)"
                    if (accepted(json) == !plain.refusedWithoutTransportSecurity()) null else address
                }
            assertEquals("mirror and core disagree for", emptyList<String>(), disagreements)
        }

    /**
     * Every method the editor offers plus every alias `cipherFromString` knows and a mixed-case 2022
     * name, each with a password the core takes: a correct-length base64 key for 2022 (and, for the
     * AES ones, an iPSK `a:b` list; the core gives ChaCha20 one key only), any non-empty string
     * otherwise.
     */
    @Test
    fun everyCoreShadowsocksMethodIsAcceptedByTheRealCore() =
        runTest {
            val aliases =
                listOf(
                    "aead_aes_128_gcm",
                    "aead_aes_256_gcm",
                    "chacha20-poly1305",
                    "aead_chacha20_poly1305",
                    "xchacha20-poly1305",
                    "aead_xchacha20_poly1305",
                    "AES-256-GCM",
                    "2022-BLAKE3-AES-128-GCM",
                )
            val cases =
                (SHADOWSOCKS_CORE_METHODS + aliases).flatMap { method ->
                    val keyLength = shadowsocks2022KeyLength(method)
                    if (keyLength == null) {
                        listOf("ss $method" to ShadowsocksOutbound("example.com", 8388, method, "ss-secret"))
                    } else {
                        val single = "ss $method" to ShadowsocksOutbound("example.com", 8388, method, b64(keyLength))
                        val ipsk = "${b64(keyLength)}:${b64(keyLength)}"
                        val multi = "ss $method iPSK" to ShadowsocksOutbound("example.com", 8388, method, ipsk)
                        if ("chacha20" in method.lowercase()) listOf(single) else listOf(single, multi)
                    }
                }
            assertEquals("rejected Shadowsocks methods", emptyList<String>(), rejectedLabels(cases))
        }

    /**
     * `shadowsocksCoreRefusal` against the core itself. The generator refuses what the mirror refuses,
     * so the config is generated for an accepted `aes-256-gcm` row and the method and password are
     * substituted in the parsed tree before `validate`.
     */
    @Test
    fun theShadowsocksMirrorMatchesTheRealCore() =
        runTest {
            val template = checkNotNull(generate(ShadowsocksOutbound("example.com", 8388, "aes-256-gcm", "ss-secret")))
            val urlSafe16 = Base64.getUrlEncoder().encodeToString(ByteArray(16) { (0xF8 + it).toByte() })
            check('-' in urlSafe16 || '_' in urlSafe16) { "URL-safe fixture lost its URL-safe characters" }
            val matrix =
                listOf(
                    Triple("legacy aes-192-gcm", "aes-192-gcm", "ss-secret"),
                    Triple("legacy none", "none", "ss-secret"),
                    Triple("legacy plain", "plain", "ss-secret"),
                    Triple("legacy rc4-md5", "rc4-md5", "ss-secret"),
                    Triple("legacy aes-128-cfb", "aes-128-cfb", "ss-secret"),
                    Triple("legacy aes-256-gcm", "aes-256-gcm", "ss-secret"),
                    Triple("legacy chacha20-ietf-poly1305", "chacha20-ietf-poly1305", "ss-secret"),
                    Triple("legacy mixed case", "Chacha20-IETF-Poly1305", "ss-secret"),
                    Triple("2022-128 valid", "2022-blake3-aes-128-gcm", b64(16)),
                    Triple("2022-128 short base64", "2022-blake3-aes-128-gcm", b64(8)),
                    Triple("2022-128 long base64", "2022-blake3-aes-128-gcm", b64(32)),
                    Triple("2022-128 unpadded base64", "2022-blake3-aes-128-gcm", b64(16).trimEnd('=')),
                    Triple("2022-128 URL-safe base64", "2022-blake3-aes-128-gcm", urlSafe16),
                    Triple("2022-128 raw 16 chars", "2022-blake3-aes-128-gcm", "0123456789abcde!"),
                    Triple("2022-128 alphanumeric 16 chars", "2022-blake3-aes-128-gcm", "0123456789abcdef"),
                    Triple("2022-128 base64 with newline", "2022-blake3-aes-128-gcm", b64(16) + "\n"),
                    Triple("2022-128 mixed-case method", "2022-BLAKE3-AES-128-GCM", b64(16)),
                    Triple("2022-256 valid", "2022-blake3-aes-256-gcm", b64(32)),
                    Triple("2022-256 16-byte key", "2022-blake3-aes-256-gcm", b64(16)),
                    Triple("2022-256 raw 32 chars", "2022-blake3-aes-256-gcm", "0123456789abcdef0123456789abcde!"),
                    Triple("2022-256 alphanumeric 32 chars", "2022-blake3-aes-256-gcm", "0123456789abcdef".repeat(2)),
                    Triple("2022-chacha valid", "2022-blake3-chacha20-poly1305", b64(32)),
                    Triple("2022-chacha short", "2022-blake3-chacha20-poly1305", b64(31)),
                    Triple("2022-128 iPSK good:good", "2022-blake3-aes-128-gcm", "${b64(16)}:${b64(16)}"),
                    Triple("2022-128 iPSK good:bad", "2022-blake3-aes-128-gcm", "${b64(16)}:${b64(8)}"),
                    Triple("2022-128 iPSK bad:good", "2022-blake3-aes-128-gcm", "${b64(8)}:${b64(16)}"),
                    Triple("2022-128 iPSK trailing colon", "2022-blake3-aes-128-gcm", "${b64(16)}:"),
                    Triple("2022-256 iPSK good:good", "2022-blake3-aes-256-gcm", "${b64(32)}:${b64(32)}"),
                    Triple("2022-chacha iPSK good:good", "2022-blake3-chacha20-poly1305", "${b64(32)}:${b64(32)}"),
                )
            val disagreements =
                matrix.mapNotNull { (label, method, password) ->
                    val coreAccepts = accepted(substitute(template, method, password))
                    if (coreAccepts == (shadowsocksCoreRefusal(method, password) == null)) null else label
                }
            assertEquals("mirror and core disagree for", emptyList<String>(), disagreements)
        }

    /**
     * Outside the mirror on purpose: `ShadowsocksServerTarget`'s Build refuses an empty password for
     * every method, but every way a row is made (share links, Clash, Xray JSON, the editor) already
     * refuses a missing Shadowsocks password, so `shadowsocksCoreRefusal` never sees one. This pins
     * the core's half of that so the mirror's silence on it stays a choice, not an accident.
     */
    @Test
    fun anEmptyShadowsocksPasswordIsRefusedByTheRealCore() =
        runTest {
            val template = checkNotNull(generate(ShadowsocksOutbound("example.com", 8388, "aes-256-gcm", "ss-secret")))
            assertFalse("core accepted an empty password", accepted(substitute(template, "aes-256-gcm", "")))
        }

    @Test
    fun socksWithAndWithoutAuthIsAcceptedByTheRealCore() =
        runTest {
            val cases =
                listOf(
                    "socks no auth" to SocksOutbound("example.com", 1080, null, null),
                    "socks blank user" to SocksOutbound("example.com", 1080, "", "ignored"),
                    "socks user+pass" to SocksOutbound("example.com", 1080, "user", "socks-secret"),
                    "socks user only" to SocksOutbound("example.com", 1080, "user", null),
                )
            assertEquals("rejected SOCKS shapes", emptyList<String>(), rejectedLabels(cases))
        }

    private fun everyProtocolAt(address: String): List<Pair<String, Outbound>> =
        listOf(
            "vless" to VlessOutbound(address, 443, uuid, "xtls-rprx-vision", StreamSettings("tcp", reality)),
            "vmess" to VmessOutbound(address, 443, uuid, 0, "auto", StreamSettings("tcp", tls)),
            "trojan" to TrojanOutbound(address, 443, "trojan-secret", StreamSettings("tcp", tls)),
            "ss legacy" to ShadowsocksOutbound(address, 8388, "aes-256-gcm", "ss-secret"),
            "ss 2022" to ShadowsocksOutbound(address, 8388, "2022-blake3-aes-256-gcm", b64(32)),
            "socks" to SocksOutbound(address, 1080, "user", "socks-secret"),
        )

    @Test
    fun ipv6ServerIsAcceptedForEveryProtocol() =
        runTest {
            val rejected = rejectedLabels(everyProtocolAt("2606:4700::1111"))
            assertEquals("rejected IPv6 servers", emptyList<String>(), rejected)
        }

    @Test
    fun pingConfigIsAcceptedForEveryProtocol() =
        runTest {
            val rejected =
                everyProtocolAt("example.com").mapNotNull { (label, outbound) ->
                    val json = pingConfig(profile(outbound), obfuscation = fragmentAndNoise)
                    when {
                        json == null -> "$label (no ping config)"
                        !accepted(json) -> "$label (core refused)"
                        else -> null
                    }
                }
            assertEquals("rejected ping configs", emptyList<String>(), rejected)
        }

    /** [json] with the proxy outbound's single Shadowsocks server given [method] and [password]. */
    private fun substitute(
        json: String,
        method: String,
        password: String,
    ): String {
        val root = Json.parseToJsonElement(json).jsonObject
        val outbounds = root.getValue("outbounds").jsonArray
        val proxyIndex = outbounds.indexOfFirst { (it.jsonObject["tag"] as? JsonPrimitive)?.content == "proxy" }
        check(proxyIndex >= 0) { "no proxy outbound" }
        val proxy = outbounds[proxyIndex].jsonObject
        val proxySettings = proxy.getValue("settings").jsonObject
        val servers = proxySettings.getValue("servers").jsonArray
        check(servers.size == 1) { "expected one Shadowsocks server" }
        val credentials = mapOf("method" to JsonPrimitive(method), "password" to JsonPrimitive(password))
        val server = JsonObject(servers[0].jsonObject + credentials)
        val newSettings = JsonObject(proxySettings + ("servers" to JsonArray(listOf(server))))
        val newProxy = JsonObject(proxy + ("settings" to newSettings))
        val newOutbounds = JsonArray(outbounds.toMutableList().also { it[proxyIndex] = newProxy })
        return JsonObject(root + ("outbounds" to newOutbounds)).toString()
    }
}
