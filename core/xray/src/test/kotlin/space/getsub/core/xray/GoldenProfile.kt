// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.xray

import space.getsub.core.model.Profile
import space.getsub.core.model.ProxyCredentials
import space.getsub.core.model.Security
import space.getsub.core.model.ShadowsocksOutbound
import space.getsub.core.model.SocksOutbound
import space.getsub.core.model.StreamSettings
import space.getsub.core.model.TransportOptions
import space.getsub.core.model.TrojanOutbound
import space.getsub.core.model.VlessOutbound
import space.getsub.core.model.VmessOutbound

/**
 * The fixed inbound credentials every unit-test config is built with, so golden files stay
 * byte-stable (M9-E spec §5.3: no test depends on an open inbound).
 */
internal val TEST_CREDENTIALS = ProxyCredentials("testuser", "testpass")

/**
 * The VLESS+REALITY fixture `vless-reality.json` pins byte-for-byte — the M1
 * config proven on hardware. Shared by every generator test suite so the
 * fixture behind the golden files cannot drift between them.
 */
internal val GOLDEN_REALITY =
    Security.Reality(
        serverName = "www.microsoft.com",
        publicKey = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8",
        shortId = "0123abcd",
        fingerprint = "chrome",
        spiderX = "/",
    )

internal val GOLDEN_OUTBOUND =
    VlessOutbound(
        address = "example.com",
        port = 443,
        uuid = "70cc48c5-b2f4-4a1e-9f3d-0123456789ab",
        flow = "xtls-rprx-vision",
        stream = StreamSettings(network = "tcp", security = GOLDEN_REALITY),
    )

internal val GOLDEN_PROFILE = Profile(id = "id", name = "n", outbound = GOLDEN_OUTBOUND)

/** The TLS block the VMess and Trojan goldens share. */
internal val GOLDEN_TLS = Security.Tls(serverName = "example.com", fingerprint = "chrome", allowInsecure = false)

/** `vmess-ws-tls.json`: VMess over WebSocket with TLS (M9-D2a spec §1). */
internal val GOLDEN_VMESS =
    VmessOutbound(
        address = "example.com",
        port = 443,
        uuid = "70cc48c5-b2f4-4a1e-9f3d-0123456789ab",
        alterId = 0,
        security = "auto",
        stream =
        StreamSettings(
            network = "ws",
            security = GOLDEN_TLS,
            transport = TransportOptions.WebSocket(path = "/ws", headers = mapOf("Host" to "example.com")),
        ),
    )

/** `trojan-tcp-tls.json`: Trojan over TCP with TLS (M9-D2a spec §1). */
internal val GOLDEN_TROJAN =
    TrojanOutbound(
        address = "example.com",
        port = 443,
        password = "trojan-password",
        stream = StreamSettings(network = "tcp", security = GOLDEN_TLS),
    )

/** `shadowsocks-aes-256-gcm.json`: a classic AEAD cipher. */
internal val GOLDEN_SS =
    ShadowsocksOutbound(address = "example.com", port = 8388, method = "aes-256-gcm", password = "ss-password")

/** `shadowsocks-2022.json`: a 16-byte base64 PSK, the length `2022-blake3-aes-128-gcm` requires. */
internal val GOLDEN_SS2022 =
    ShadowsocksOutbound(
        address = "example.com",
        port = 8388,
        method = "2022-blake3-aes-128-gcm",
        password = "AAECAwQFBgcICQoLDA0ODw==",
    )

/** `socks-auth.json`; `socks-noauth.json` is this with both credentials null. */
internal val GOLDEN_SOCKS =
    SocksOutbound(address = "example.com", port = 1080, username = "socks-user", password = "socks-pass")
