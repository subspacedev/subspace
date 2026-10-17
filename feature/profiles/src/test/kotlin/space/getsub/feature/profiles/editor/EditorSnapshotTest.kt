// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.profiles.editor

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test
import space.getsub.core.data.ProfileKind
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
    fun rawJsonWithAdvisory() = shoot("editor-raw-advisory", rawWithAdvisory)

    @Test
    fun rawJsonRejected() = shoot("editor-raw-rejected", rawRejected)
}
