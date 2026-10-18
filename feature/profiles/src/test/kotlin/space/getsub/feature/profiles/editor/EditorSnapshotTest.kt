// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.profiles.editor

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test
import space.getsub.core.data.ProfileKind
import space.getsub.core.model.Hysteria2Unsupported
import space.getsub.core.model.KcpHeader
import space.getsub.core.model.KcpUnsupported
import space.getsub.core.model.WireGuardUnsupported
import space.getsub.core.parser.OverrideBlocker
import space.getsub.core.parser.PassthroughAdvisory
import space.getsub.core.parser.PassthroughRejection
import space.getsub.core.ui.preview.SubspacePreviewTheme
import space.getsub.core.ui.preview.ThemeVariant

/**
 * M8.6 Part 3 Task 6: the server editor in all four themes — a typed VLESS-Reality profile, a
 * raw-JSON profile carrying advisories (Warning banners), and a rejected raw-JSON profile (the
 * Blocked banner). Drives the stateless [EditorContent] with a hand-built [EditorState].
 */
class EditorSnapshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(screenHeight = 4000))

    private val noActions =
        EditorActions(
            onBack = {},
            onNameChanged = {},
            onGroupChanged = {},
            onAddressChanged = {},
            onPortChanged = {},
            onPrimaryCredentialChanged = {},
            onSecondaryCredentialChanged = {},
            onMethodChanged = {},
            onFlowChanged = {},
            onAlterIdChanged = {},
            onVmessSecurityChanged = {},
            onNetworkChanged = {},
            onSecurityKindChanged = {},
            onTlsServerNameChanged = {},
            onTlsFingerprintChanged = {},
            onRealityServerNameChanged = {},
            onRealityPublicKeyChanged = {},
            onRealityShortIdChanged = {},
            onRealityFingerprintChanged = {},
            onRealitySpiderXChanged = {},
            onWsPathChanged = {},
            onGrpcServiceNameChanged = {},
            onXhttpPathChanged = {},
            onXhttpHostChanged = {},
            onXhttpModeChanged = {},
            onHysteria2Changed = {},
            onWireGuardChanged = {},
            onKcpChanged = {},
            onHttpUpgradeHostChanged = {},
            onHttpUpgradePathChanged = {},
            onSave = {},
            onConvertRouting = {},
        )

    private val vlessReality =
        EditorState(
            loading = false,
            exists = true,
            id = 1L,
            kind = ProfileKind.TYPED,
            protocol = "vless",
            name = "Frankfurt",
            groupId = 10L,
            availableGroups = listOf(EditorGroupOption(10L, "Local configs")),
            address = "198.51.100.7",
            port = "443",
            primaryCredential = "11111111-1111-1111-1111-111111111111",
            flow = "xtls-rprx-vision",
            network = "tcp",
            securityKind = EditorSecurityKind.Reality,
            realityServerName = "www.example.com",
            realityPublicKey = "aBcDeFgHiJkLmNoPqRsTuVwXyZ0123456789-_abcde",
            realityShortId = "6ba85179e30d4fc2",
            realityFingerprint = "chrome",
            realitySpiderX = "/",
        )

    private val hysteria2Full =
        EditorState(
            loading = false,
            exists = true,
            id = 3L,
            kind = ProfileKind.TYPED,
            protocol = "hysteria2",
            name = "Hy",
            groupId = 10L,
            availableGroups = listOf(EditorGroupOption(10L, "Local configs")),
            address = "198.51.100.9",
            port = "443",
            primaryCredential = "hunter2-password",
            hysteria2 =
            Hysteria2Draft(
                serverName = "hy.example",
                skipChecksRequested = true,
                hopPorts = "20000-30000,40000",
                hopInterval = "30",
                obfsKind = Hysteria2ObfsKind.Gecko,
                obfsPassword = "secret",
                minPacket = "100",
                maxPacket = "1200",
                congestionKind = Hysteria2CongestionKind.Brutal,
                brutalUp = "100 mbps",
                brutalDown = "1 gbps",
                notConnectableReason = Hysteria2Unsupported.UnknownMask,
            ),
        )

    private val wireGuardFull =
        EditorState(
            loading = false,
            exists = true,
            id = 4L,
            kind = ProfileKind.TYPED,
            protocol = "wireguard",
            name = "WG",
            groupId = 10L,
            availableGroups = listOf(EditorGroupOption(10L, "Local configs")),
            address = "198.51.100.10",
            port = "51820",
            wireGuard =
            WireGuardDraft(
                secretKey = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=",
                peerPublicKey = "AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI=",
                localAddresses = "10.0.0.2/32\nfd00::2/128",
                mtu = "1280",
                reserved = "1,2,3",
                keepAlive = "25",
                notConnectableReason = WireGuardUnsupported.UnsupportedMask,
            ),
            wireGuardErrors = setOf(WireGuardField.PreSharedKey),
        )

    private val httpTls =
        EditorState(
            loading = false,
            exists = true,
            id = 5L,
            kind = ProfileKind.TYPED,
            protocol = "http",
            name = "Proxy",
            groupId = 10L,
            availableGroups = listOf(EditorGroupOption(10L, "Local configs")),
            address = "proxy.example",
            port = "8443",
            primaryCredential = "alice",
            secondaryCredential = "hunter2",
            securityKind = EditorSecurityKind.Tls,
            tlsServerName = "proxy.example",
            tlsFingerprint = "chrome",
            tlsAllowInsecure = true,
        )

    private val vlessKcp =
        vlessReality.copy(
            id = 6L,
            securityKind = EditorSecurityKind.Tls,
            tlsServerName = "www.example.com",
            tlsFingerprint = "chrome",
            network = "kcp",
            kcp =
            KcpDraft(
                mtu = "1350",
                tti = "20",
                seed = "secret",
                header = KcpHeader.SRTP,
                unsupported = KcpUnsupported.DnsHeaderDomain,
            ),
            kcpErrors = setOf(KcpField.Tti),
        )

    private val vlessHttpUpgradeReality =
        vlessReality.copy(
            id = 7L,
            network = "httpupgrade",
            httpUpgradeHost = "cdn.example",
            httpUpgradePath = "/up?ed=2048",
        )

    private val rawWithAdvisory =
        EditorState(
            loading = false,
            exists = true,
            id = 2L,
            kind = ProfileKind.RAW_JSON,
            protocol = "vless",
            name = "Hand-written config",
            rawJson = """{"outbounds":[{"tag":"proxy","protocol":"vless"}],"routing":{"rules":[]}}""",
            runsAsWritten = true,
            advisories = listOf(PassthroughAdvisory.DanglingRoutingReference),
            overrideBlocker = OverrideBlocker.NoResolvableTarget,
            canConvertRouting = true,
        )

    private val rawRejected =
        rawWithAdvisory.copy(
            runsAsWritten = false,
            passthroughRejection = PassthroughRejection.SeveralServers,
            advisories = emptyList(),
            overrideBlocker = null,
            canConvertRouting = false,
        )

    private fun shoot(
        name: String,
        state: EditorState,
    ) {
        ThemeVariant.entries.forEach { v ->
            paparazzi.snapshot(name = "$name-${v.fileSuffix}") {
                SubspacePreviewTheme(v) { EditorContent(state = state, actions = noActions) }
            }
        }
    }

    @Test
    fun typedVlessReality() = shoot("editor-typed-reality", vlessReality)

    @Test
    fun typedHysteria2() = shoot("editor-typed-hysteria2", hysteria2Full)

    @Test
    fun typedWireGuard() = shoot("editor-typed-wireguard", wireGuardFull)

    @Test
    fun typedHttp() = shoot("editor-typed-http", httpTls)

    @Test
    fun typedMkcp() = shoot("editor-typed-mkcp", vlessKcp)

    @Test
    fun typedHttpUpgradeWithReality() = shoot("editor-typed-httpupgrade-reality", vlessHttpUpgradeReality)

    @Test
    fun rawJsonWithAdvisory() = shoot("editor-raw-advisory", rawWithAdvisory)

    @Test
    fun rawJsonRejected() = shoot("editor-raw-rejected", rawRejected)
}
