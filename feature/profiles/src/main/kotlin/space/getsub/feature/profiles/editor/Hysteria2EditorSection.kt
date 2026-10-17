// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
@file:Suppress("MagicNumber", "TooManyFunctions")

package space.getsub.feature.profiles.editor

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import space.getsub.core.model.Hysteria2Unsupported
import space.getsub.core.ui.component.BannerTone
import space.getsub.core.ui.component.TonalBanner
import space.getsub.core.ui.icon.SubspaceIcons
import space.getsub.core.ui.theme.mono
import space.getsub.feature.profiles.R

/** Identifies the "certificate checks stay on, add the pin" note. */
internal const val EDITOR_HYSTERIA2_CHECKS_STAY_ON_TEST_TAG = "editor-hysteria2-checks-stay-on"

/** Identifies the "this build cannot run what the panel set" banner. */
internal const val EDITOR_HYSTERIA2_NOT_CONNECTABLE_TEST_TAG = "editor-hysteria2-not-connectable"

/**
 * The Hysteria2 half of the typed editor: certificate, port hopping, obfuscation, congestion. Address,
 * port and password sit in the shared Server and Credentials cards. Stateless: every edit hands a whole
 * new [Hysteria2Draft] to [onChanged]; the rules are in [Hysteria2Draft], not here.
 */
@Composable
internal fun Hysteria2Sections(
    state: EditorState,
    onChanged: (Hysteria2Draft) -> Unit,
) {
    val draft = state.hysteria2
    val errors = state.hysteria2Errors
    draft.notConnectableReason?.let { NotConnectableBanner(it) }

    SectionCard(title = stringResource(R.string.editor_hysteria2_section_tls)) {
        CertificateFields(draft, errors, onChanged)
    }
    SectionCard(title = stringResource(R.string.editor_hysteria2_section_hopping)) {
        HoppingFields(draft, errors, onChanged)
    }
    SectionCard(title = stringResource(R.string.editor_hysteria2_section_obfuscation)) {
        ObfuscationFields(draft, errors, onChanged)
    }
    SectionCard(title = stringResource(R.string.editor_hysteria2_section_congestion)) {
        CongestionFields(draft, errors, onChanged)
    }
}

@Composable
private fun NotConnectableBanner(reason: Hysteria2Unsupported) {
    TonalBanner(
        icon = SubspaceIcons.Warning,
        text =
        stringResource(
            when (reason) {
                Hysteria2Unsupported.UnknownMask -> R.string.editor_hysteria2_unknown_mask
                Hysteria2Unsupported.UnknownCongestion -> R.string.editor_hysteria2_unknown_congestion
            },
        ),
        tone = BannerTone.Warning,
        modifier = Modifier.testTag(EDITOR_HYSTERIA2_NOT_CONNECTABLE_TEST_TAG),
    )
}

@Composable
private fun CertificateFields(
    draft: Hysteria2Draft,
    errors: Set<Hysteria2Field>,
    onChanged: (Hysteria2Draft) -> Unit,
) {
    Hysteria2TextField(
        label = stringResource(R.string.editor_hysteria2_sni_label),
        value = draft.serverName,
        onValueChange = { onChanged(draft.copy(serverName = it)) },
        mono = true,
    )
    Hysteria2TextField(
        label = stringResource(R.string.editor_hysteria2_pins_label),
        value = draft.pinsText,
        onValueChange = { onChanged(draft.copy(pinsText = it)) },
        mono = true,
        singleLine = false,
        error = errorIf(Hysteria2Field.Pins in errors, R.string.editor_hysteria2_pins_error),
    )
    if (draft.checksStayOnWithoutPin) {
        TonalBanner(
            icon = SubspaceIcons.Info,
            text = stringResource(R.string.editor_hysteria2_checks_stay_on),
            tone = BannerTone.Neutral,
            modifier = Modifier.testTag(EDITOR_HYSTERIA2_CHECKS_STAY_ON_TEST_TAG),
        )
    }
}

@Composable
private fun HoppingFields(
    draft: Hysteria2Draft,
    errors: Set<Hysteria2Field>,
    onChanged: (Hysteria2Draft) -> Unit,
) {
    Hysteria2TextField(
        label = stringResource(R.string.editor_hysteria2_hop_ports_label),
        value = draft.hopPorts,
        onValueChange = { onChanged(draft.copy(hopPorts = it)) },
        mono = true,
        error = errorIf(Hysteria2Field.HopPorts in errors, R.string.editor_hysteria2_hop_ports_error),
    )
    // Without ports there is no hopping and the interval is ignored, so it is not offered.
    if (draft.hops) {
        Hysteria2TextField(
            label = stringResource(R.string.editor_hysteria2_hop_interval_label),
            value = draft.hopInterval,
            onValueChange = { onChanged(draft.copy(hopInterval = it)) },
            keyboardType = KeyboardType.Number,
            error = errorIf(Hysteria2Field.HopInterval in errors, R.string.editor_hysteria2_hop_interval_error),
        )
    }
}

@Composable
private fun ObfuscationFields(
    draft: Hysteria2Draft,
    errors: Set<Hysteria2Field>,
    onChanged: (Hysteria2Draft) -> Unit,
) {
    EditorDropdownField(
        label = stringResource(R.string.editor_hysteria2_obfs_label),
        selected = draft.obfsKind.name,
        options = Hysteria2ObfsKind.entries.map { it.name },
        onSelected = { onChanged(draft.copy(obfsKind = Hysteria2ObfsKind.valueOf(it))) },
        optionLabel = { name ->
            if (name == Hysteria2ObfsKind.None.name) stringResource(R.string.editor_hysteria2_obfs_none) else name
        },
    )
    if (draft.obfsKind == Hysteria2ObfsKind.None) return
    Hysteria2TextField(
        label = stringResource(R.string.editor_hysteria2_obfs_password_label),
        value = draft.obfsPassword,
        onValueChange = { onChanged(draft.copy(obfsPassword = it)) },
        error = errorIf(Hysteria2Field.ObfsPassword in errors, R.string.editor_hysteria2_obfs_password_error),
    )
    if (draft.obfsKind == Hysteria2ObfsKind.Gecko) {
        val packetError = errorIf(Hysteria2Field.PacketRange in errors, R.string.editor_hysteria2_packet_error)
        Hysteria2TextField(
            label = stringResource(R.string.editor_hysteria2_min_packet_label),
            value = draft.minPacket,
            onValueChange = { onChanged(draft.copy(minPacket = it)) },
            keyboardType = KeyboardType.Number,
            error = packetError,
        )
        Hysteria2TextField(
            label = stringResource(R.string.editor_hysteria2_max_packet_label),
            value = draft.maxPacket,
            onValueChange = { onChanged(draft.copy(maxPacket = it)) },
            keyboardType = KeyboardType.Number,
            error = packetError,
        )
    }
}

@Composable
private fun CongestionFields(
    draft: Hysteria2Draft,
    errors: Set<Hysteria2Field>,
    onChanged: (Hysteria2Draft) -> Unit,
) {
    EditorDropdownField(
        label = stringResource(R.string.editor_hysteria2_congestion_label),
        selected = draft.congestionKind.name,
        options = Hysteria2CongestionKind.entries.map { it.name },
        onSelected = { onChanged(draft.copy(congestionKind = Hysteria2CongestionKind.valueOf(it))) },
        optionLabel = { name ->
            if (name == Hysteria2CongestionKind.Default.name) {
                stringResource(R.string.editor_hysteria2_congestion_default)
            } else {
                name
            }
        },
    )
    when (draft.congestionKind) {
        Hysteria2CongestionKind.Default -> Unit
        Hysteria2CongestionKind.Bbr ->
            // A closed list: the core fails the whole config on a profile it does not know.
            EditorDropdownField(
                label = stringResource(R.string.editor_hysteria2_bbr_profile_label),
                selected = draft.bbrProfile,
                options = BBR_PROFILE_OPTIONS,
                onSelected = { onChanged(draft.copy(bbrProfile = it)) },
                optionLabel = { profile ->
                    profile.ifEmpty { stringResource(R.string.editor_hysteria2_bbr_profile_default) }
                },
            )
        Hysteria2CongestionKind.Brutal -> {
            Hysteria2TextField(
                label = stringResource(R.string.editor_hysteria2_brutal_up_label),
                value = draft.brutalUp,
                onValueChange = { onChanged(draft.copy(brutalUp = it)) },
                error = errorIf(Hysteria2Field.BrutalUp in errors, R.string.editor_hysteria2_bandwidth_error),
            )
            Hysteria2TextField(
                label = stringResource(R.string.editor_hysteria2_brutal_down_label),
                value = draft.brutalDown,
                onValueChange = { onChanged(draft.copy(brutalDown = it)) },
                error = errorIf(Hysteria2Field.BrutalDown in errors, R.string.editor_hysteria2_bandwidth_error),
            )
        }
    }
}

/** The fixed message for a failed field. It is a resource, so it cannot contain what the user typed. */
@Composable
private fun errorIf(
    failed: Boolean,
    messageRes: Int,
): String? = if (failed) stringResource(messageRes) else null

// Same baseline as EditorField's parameters; [error] is already-resolved fixed text (never typed content).
@Suppress("LongParameterList")
@Composable
private fun Hysteria2TextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    mono: Boolean = false,
    singleLine: Boolean = true,
    error: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 2,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        textStyle = MaterialTheme.typography.bodyLarge.let { if (mono) it.mono() else it },
        shape = MaterialTheme.shapes.large,
        modifier = modifier.fillMaxWidth(),
    )
}
