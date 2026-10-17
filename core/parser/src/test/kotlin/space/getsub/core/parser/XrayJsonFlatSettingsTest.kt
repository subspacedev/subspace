// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.parser

import io.kotest.matchers.shouldBe
import org.junit.Test
import space.getsub.core.model.Security
import space.getsub.core.model.TransportOptions
import space.getsub.core.model.VlessOutbound

private const val FLAT_UUID = "70cc48c5-b2f4-4a1e-9f3d-0123456789ab"
private const val FLAT_PBK = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"

/**
 * The outbound shapes Xray-core v26.10.10 writes, which libXray's
 * `convertShareLinksToXrayJson` returns: flat VLESS `settings`, the REALITY key as
 * `password`, TCP spelled `raw`.
 *
 * The fixtures are the literal output of `share.ConvertShareLinksToXrayJson` run against the
 * `v26.10.10` tag (2026-10-17) on the links in each test's comment, except
 * [upstreamXhttpReality], which is `expected` in upstream `share/marshal_share_test.go`
 * (`TestMarshalShareConfigProjectsSupportedFields`). See `docs/agent/research/libxray-api.md` §2.
 */
class XrayJsonFlatSettingsTest {
    @Test
    fun `reads a flat reality vless outbound with the key under password and network raw`() {
        // vless://…@example.com:443?encryption=none&flow=xtls-rprx-vision&security=reality
        //   &sni=example.com&fp=chrome&pbk=<key>&sid=ab&spx=%2F#name
        val outcome =
            parseXrayJson(
                """{"outbounds":[{"protocol":"vless","settings":{"address":"example.com","encryption":"none",
                "flow":"xtls-rprx-vision","id":"$FLAT_UUID","port":443},"streamSettings":{"network":"raw",
                "realitySettings":{"fingerprint":"chrome","password":"$FLAT_PBK","serverName":"example.com",
                "shortId":"ab","spiderX":"/"},"security":"reality"},"tag":"name"}]}
                """.replace("\n", ""),
            )

        outcome.failures shouldBe emptyList()
        val profile = outcome.profiles.single()
        profile.name shouldBe "name"
        val vless = profile.outbound as VlessOutbound
        vless.address shouldBe "example.com"
        vless.port shouldBe 443
        vless.uuid shouldBe FLAT_UUID
        vless.flow shouldBe "xtls-rprx-vision"
        vless.stream.network shouldBe "raw"
        vless.stream.security shouldBe
            Security.Reality(
                serverName = "example.com",
                publicKey = FLAT_PBK,
                shortId = "ab",
                fingerprint = "chrome",
                spiderX = "/",
            )
    }

    @Test
    fun `reads a flat ws plus tls outbound`() {
        // vless://…@example.com:443?encryption=none&security=tls&sni=example.com&fp=chrome&alpn=h2
        //   &type=ws&host=example.com&path=%2Fws#ws-tls
        val outcome =
            parseXrayJson(
                """{"outbounds":[{"protocol":"vless","settings":{"address":"example.com","encryption":"none",
                "id":"$FLAT_UUID","port":443},"streamSettings":{"network":"ws","security":"tls",
                "tlsSettings":{"alpn":["h2"],"fingerprint":"chrome","serverName":"example.com"},
                "wsSettings":{"host":"example.com","path":"/ws"}},"tag":"ws-tls"}]}
                """.replace("\n", ""),
            )

        outcome.failures shouldBe emptyList()
        val vless = outcome.profiles.single().outbound as VlessOutbound
        vless.flow shouldBe null
        vless.stream.security shouldBe Security.Tls("example.com", "chrome", allowInsecure = false)
        vless.stream.transport shouldBe
            TransportOptions.WebSocket(path = "/ws", headers = mapOf("Host" to "example.com"))
    }

    @Test
    fun `reads a flat grpc outbound`() {
        // vless://…@example.com:443?encryption=none&security=tls&sni=example.com&type=grpc&serviceName=svc#grpc
        val outcome =
            parseXrayJson(
                """{"outbounds":[{"protocol":"vless","settings":{"address":"example.com","encryption":"none",
                "id":"$FLAT_UUID","port":443},"streamSettings":{"grpcSettings":{"serviceName":"svc"},
                "network":"grpc","security":"tls","tlsSettings":{"serverName":"example.com"}},"tag":"grpc"}]}
                """.replace("\n", ""),
            )

        outcome.failures shouldBe emptyList()
        val vless = outcome.profiles.single().outbound as VlessOutbound
        vless.stream.transport shouldBe TransportOptions.Grpc("svc")
    }

    @Test
    fun `reads upstream's own projected xhttp reality outbound`() {
        val outcome = parseXrayJson(upstreamXhttpReality)

        outcome.failures shouldBe emptyList()
        val profile = outcome.profiles.single()
        profile.name shouldBe "Node name"
        val vless = profile.outbound as VlessOutbound
        vless.address shouldBe "example.com"
        vless.flow shouldBe null
        vless.stream.transport shouldBe
            TransportOptions.Xhttp(path = "/xhttp", host = "cdn.example.com", mode = "stream-up")
        (vless.stream.security as Security.Reality).publicKey shouldBe "A".repeat(43)
    }

    @Test
    fun `a flat outbound without an id is a missing-credential failure, not a crash`() {
        val outcome =
            parseXrayJson(
                """{"outbounds":[{"protocol":"vless","settings":{"address":"example.com","port":443},
                "streamSettings":{"security":"none"}}]}
                """.replace("\n", ""),
            )

        outcome.profiles shouldBe emptyList()
        outcome.failures.single().reason shouldBe ParseFailureReason.MissingCredential
    }

    @Test
    fun `a flat outbound with a bad port is an invalid-port failure`() {
        val outcome =
            parseXrayJson(
                """{"outbounds":[{"protocol":"vless","settings":{"address":"example.com","port":70000,
                "id":"$FLAT_UUID"},"streamSettings":{"security":"none"}}]}
                """.replace("\n", ""),
            )

        outcome.failures.single().reason shouldBe ParseFailureReason.InvalidPort
    }

    @Test
    fun `a reality block with neither publicKey nor password is a missing-key failure`() {
        val outcome =
            parseXrayJson(
                """{"outbounds":[{"protocol":"vless","settings":{"address":"example.com","port":443,
                "id":"$FLAT_UUID"},"streamSettings":{"security":"reality","realitySettings":{"shortId":"ab"}}}]}
                """.replace("\n", ""),
            )

        outcome.failures.single().reason shouldBe ParseFailureReason.InvalidRealityKey
    }

    // Upstream share/marshal_share_test.go, TestMarshalShareConfigProjectsSupportedFields, `expected`.
    private val upstreamXhttpReality =
        """{"outbounds":[{"protocol":"vless","sendThrough":"127.0.0.1","tag":"Node name",
        "settings":{"address":"example.com","port":443,"id":"12345678-abcd-abcd-abcd-123456789abc","encryption":"none"},
        "streamSettings":{"network":"xhttp","security":"reality",
        "xhttpSettings":{"host":"cdn.example.com","path":"/xhttp","mode":"stream-up",
        "extra":{"maxConcurrency":"1-2","empty":null}},
        "realitySettings":{"serverName":"example.com","fingerprint":"chrome",
        "password":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","shortId":"abcd","spiderX":"/"}}}]}
        """.replace("\n", "")
}
