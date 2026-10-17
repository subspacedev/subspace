// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.data

import io.kotest.matchers.shouldBe
import org.junit.Test
import space.getsub.core.model.Security
import space.getsub.core.model.ShadowsocksOutbound
import space.getsub.core.model.SocksOutbound
import space.getsub.core.model.StreamSettings
import space.getsub.core.model.TrojanOutbound
import space.getsub.core.model.VlessOutbound
import space.getsub.core.model.VmessOutbound

/**
 * Covers [StoredProfile.connectable], which has now been wrong in both directions.
 *
 * It first checked only `protocol == "vless"`, which claimed a `ws`/`grpc`/`xhttp` row was
 * connectable while `XrayConfigGenerator` emitted no transport settings for it. Fix round 2,
 * Important finding 3 narrowed it to `network == "tcp"` — which over-corrected: it demoted
 * profiles that did connect, because omitting a transport's settings object makes Xray use
 * that transport's own defaults rather than fail, so an xhttp server on a default path
 * worked. Reported from the field as working servers labelled "not supported by this build
 * yet".
 *
 * The generator now emits `wsSettings`/`grpcSettings`/`xhttpSettings` from
 * `StreamSettings.transport`, so the gate is the set of networks it can emit rather than a
 * single one. These tests pin both edges: every transport the generator handles is
 * connectable, and a transport it does not handle still is not.
 */
class StoredProfileTest {
    private fun profile(
        protocol: String = "vless",
        network: String = "tcp",
        outboundOverride: space.getsub.core.model.Outbound? = null,
    ) = StoredProfile(
        id = 1,
        groupId = 1,
        kind = ProfileKind.TYPED,
        name = "Test",
        protocol = protocol,
        address = "198.51.100.1",
        port = 443,
        transport = network,
        outbound =
        outboundOverride ?: when (protocol) {
            "vless" ->
                VlessOutbound(
                    address = "198.51.100.1",
                    port = 443,
                    uuid = "1e0f2a2e-6b2b-4b9a-9a3b-000000000000",
                    flow = null,
                    stream = StreamSettings(network = network, security = Security.None),
                )

            "trojan" ->
                TrojanOutbound(
                    address = "198.51.100.1",
                    port = 443,
                    password = "unused",
                    stream = StreamSettings(network = network, security = Security.None),
                )

            "vmess" ->
                VmessOutbound(
                    address = "198.51.100.1",
                    port = 443,
                    uuid = "1e0f2a2e-6b2b-4b9a-9a3b-000000000000",
                    alterId = 0,
                    security = "auto",
                    stream = StreamSettings(network = network, security = Security.None),
                )

            else -> error("unhandled protocol $protocol in test fixture")
        },
        rawJson = null,
        lastConnectedAt = null,
        lastError = null,
    )

    @Test
    fun `a tcp vless profile is connectable`() {
        profile(protocol = "vless", network = "tcp").connectable shouldBe true
    }

    @Test
    fun `a ws vless profile is connectable`() {
        profile(protocol = "vless", network = "ws").connectable shouldBe true
    }

    @Test
    fun `a grpc vless profile is connectable`() {
        profile(protocol = "vless", network = "grpc").connectable shouldBe true
    }

    @Test
    fun `an xhttp vless profile is connectable`() {
        // The regression this whole file now guards: this row used to render
        // "Not supported by this build yet" for a server that worked.
        profile(protocol = "vless", network = "xhttp").connectable shouldBe true
    }

    @Test
    fun `a splithttp vless profile is connectable`() {
        // Xray-core v26.7.11 treats it as the same transport as xhttp.
        profile(protocol = "vless", network = "splithttp").connectable shouldBe true
    }

    @Test
    fun `every network name is matched case-insensitively`() {
        // TransportProtocol.Build lowercases before matching, so a config written
        // with "XHTTP" or "WS" is valid to the core and must not be demoted here.
        profile(protocol = "vless", network = "XHTTP").connectable shouldBe true
        profile(protocol = "vless", network = "WS").connectable shouldBe true
    }

    @Test
    fun `a raw alias tcp profile is connectable`() {
        // v26.7.11 accepts "raw" as tcp's own alias.
        profile(protocol = "vless", network = "raw").connectable shouldBe true
    }

    @Test
    fun `a transport the generator cannot emit is not connectable`() {
        // kcp, httpupgrade and hysteria are real Xray transports with no emission
        // here. The gate must stay an allow-list of what the generator handles —
        // if it were a deny-list, adding a transport to the model would silently
        // promise a config this build cannot write.
        profile(protocol = "vless", network = "kcp").connectable shouldBe false
        profile(protocol = "vless", network = "httpupgrade").connectable shouldBe false
        profile(protocol = "vless", network = "hysteria").connectable shouldBe false
    }

    @Test
    fun `an unrecognised network is not connectable`() {
        profile(protocol = "vless", network = "quic").connectable shouldBe false
        profile(protocol = "vless", network = "").connectable shouldBe false
    }

    @Test
    fun `vmess and trojan are connectable over every network the generator emits`() {
        // M9-D2a: the generator now writes these two, with the same transport blocks as VLESS.
        for (protocol in listOf("vmess", "trojan")) {
            for (network in listOf("tcp", "ws", "grpc", "xhttp")) {
                profile(protocol = protocol, network = network).connectable shouldBe true
            }
        }
    }

    @Test
    fun `a vmess profile over kcp is not connectable`() {
        profile(protocol = "vmess", network = "kcp").connectable shouldBe false
    }

    @Test
    fun `a plaintext trojan profile is still connectable here`() {
        // Refused at connect time, not by this predicate.
        profile(protocol = "trojan", network = "tcp").connectable shouldBe true
    }

    private fun shadowsocks(
        method: String,
        password: String,
    ) = profile(
        protocol = "shadowsocks",
        outboundOverride = ShadowsocksOutbound("198.51.100.1", 8388, method, password),
    )

    @Test
    fun `a shadowsocks profile is connectable only when the core accepts its method and key`() {
        shadowsocks("aes-256-gcm", "pw").connectable shouldBe true
        shadowsocks("aes-192-gcm", "pw").connectable shouldBe false
        shadowsocks("none", "pw").connectable shouldBe false
        shadowsocks("plain", "pw").connectable shouldBe false
        // A 2022 method needs a PSK that is 16 bytes once base64-decoded; "pw" is not.
        shadowsocks("2022-blake3-aes-128-gcm", "pw").connectable shouldBe false
        shadowsocks("2022-blake3-aes-128-gcm", "AAAAAAAAAAAAAAAAAAAAAA==").connectable shouldBe true
    }

    @Test
    fun `a socks profile is connectable`() {
        profile(
            protocol = "socks",
            outboundOverride = SocksOutbound("198.51.100.1", 1080, null, null),
        ).connectable shouldBe true
    }

    @Test
    fun `a row whose outbound failed to decode is not connectable`() {
        profile(protocol = "vless", outboundOverride = null).copy(outbound = null).connectable shouldBe false
    }
}
