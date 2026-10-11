// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.settings.pages

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import space.getsub.core.model.Checked
import space.getsub.core.model.FragmentSettings
import space.getsub.core.model.NoiseMode
import space.getsub.core.model.NoisePacketType
import space.getsub.core.model.NoiseSettings
import space.getsub.core.model.ObfuscationValidation
import space.getsub.core.ui.component.ModeTile
import space.getsub.core.ui.component.ModeTiles
import space.getsub.core.ui.component.SectionTitle
import space.getsub.core.ui.component.SwitchRow
import space.getsub.core.ui.icon.SubspaceIcons
import space.getsub.core.ui.textRes
import space.getsub.core.ui.theme.mono
import space.getsub.feature.settings.R
import space.getsub.feature.settings.SettingsActions
import space.getsub.feature.settings.SettingsState

/**
 * TLS fragmentation and UDP noise (M9-A spec §2.3). Off means "the provider's settings apply", never
 * "off everywhere": a subscription or config can still carry its own, and the Off text says so.
 */
@Composable
internal fun CensorshipPage(
    state: SettingsState,
    actions: SettingsActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PageScaffold(title = stringResource(R.string.settings_censorship_title), onBack = onBack, modifier = modifier) {
        FragmentSection(state.fragment, actions.onFragmentSettingsChanged)
        NoiseSection(state.noise, actions.onNoiseSettingsChanged)
    }
}

@Composable
private fun FragmentSection(
    value: FragmentSettings,
    onChange: (FragmentSettings) -> Unit,
) {
    SwitchRow(
        icon = SubspaceIcons.Shield,
        label = stringResource(R.string.settings_fragment_label),
        supporting =
        stringResource(
            if (value.enabled) R.string.settings_fragment_summary else R.string.settings_fragment_off_summary,
        ),
        checked = value.enabled,
        onCheckedChange = { onChange(value.copy(enabled = it)) },
    )
    if (!value.enabled) return
    ValidatedField(
        label = R.string.settings_fragment_packets,
        stored = value.packets,
        validate = { ObfuscationValidation.packets(it) },
        hint = R.string.settings_fragment_packets_hint,
    ) { onChange(value.copy(packets = it)) }
    ValidatedField(R.string.settings_fragment_length, value.length, { ObfuscationValidation.length(it) }) {
        onChange(value.copy(length = it))
    }
    ValidatedField(R.string.settings_fragment_delay, value.delay, { ObfuscationValidation.delay(it) }) {
        onChange(value.copy(delay = it))
    }
    ValidatedField(R.string.settings_fragment_max_split, value.maxSplit, { ObfuscationValidation.maxSplit(it) }) {
        onChange(value.copy(maxSplit = it))
    }
    TextButton(onClick = { onChange(FragmentSettings.DEFAULT.copy(enabled = true)) }) {
        Text(stringResource(R.string.settings_obfuscation_reset))
    }
}

@Composable
private fun NoiseSection(
    value: NoiseSettings,
    onChange: (NoiseSettings) -> Unit,
) {
    SwitchRow(
        icon = SubspaceIcons.Shield,
        label = stringResource(R.string.settings_noise_label),
        supporting =
        stringResource(if (value.enabled) R.string.settings_noise_summary else R.string.settings_noise_off_summary),
        checked = value.enabled,
        onCheckedChange = { onChange(value.copy(enabled = it)) },
    )
    if (!value.enabled) return
    ModeTiles(
        options =
        listOf(
            ModeTile(icon = SubspaceIcons.Tune, label = stringResource(R.string.settings_noise_mode_random)),
            ModeTile(icon = SubspaceIcons.Edit, label = stringResource(R.string.settings_noise_mode_fixed)),
        ),
        selected = NoiseMode.entries.indexOf(value.mode),
        onSelect = { onChange(value.copy(mode = NoiseMode.entries[it])) },
    )
    when (value.mode) {
        NoiseMode.Random -> {
            ValidatedField(R.string.settings_noise_rand, value.rand, { ObfuscationValidation.rand(it) }) {
                onChange(value.copy(rand = it))
            }
            ValidatedField(
                label = R.string.settings_noise_rand_range,
                stored = value.randRange,
                validate = { ObfuscationValidation.randRange(it) },
            ) { onChange(value.copy(randRange = it)) }
        }
        NoiseMode.Fixed -> FixedPacket(value, onChange)
    }
    ValidatedField(R.string.settings_noise_delay, value.delay, { ObfuscationValidation.delay(it) }) {
        onChange(value.copy(delay = it))
    }
    TextButton(onClick = { onChange(NoiseSettings.DEFAULT.copy(enabled = true)) }) {
        Text(stringResource(R.string.settings_obfuscation_reset))
    }
}

@Composable
private fun FixedPacket(
    value: NoiseSettings,
    onChange: (NoiseSettings) -> Unit,
) {
    SectionTitle(stringResource(R.string.settings_noise_packet_type))
    RadioList(
        choices =
        NoisePacketType.entries.map { type ->
            RadioChoice(
                label = type.wire,
                selected = type == value.packetType,
                onSelect = { onChange(value.copy(packetType = type)) },
            )
        },
    )
    // Keyed on the type so a type change re-validates the draft against the new type.
    key(value.packetType) {
        ValidatedField(
            label = R.string.settings_noise_packet,
            stored = value.packet,
            validate = { ObfuscationValidation.packet(value.packetType, it) },
        ) { onChange(value.copy(packet = it)) }
    }
}

/**
 * A draft that commits only valid text (M9-A Review Focus 4), on Done, focus loss and leaving the
 * page (M9-A Review Focus 5). The error is shown inline from [validate]'s problem. The tag names the
 * label's resource id so a test can find one field without depending on its rendered text.
 */
@Composable
private fun ValidatedField(
    @StringRes label: Int,
    stored: String,
    validate: (String) -> Checked<*>,
    @StringRes hint: Int? = null,
    commitValue: (String) -> Unit,
) {
    var draft by remember(stored) { mutableStateOf(stored) }
    val checked = validate(draft)
    val commit = { if (shouldCommitDraft(draft, stored, isValid = checked is Checked.Ok)) commitValue(draft) }
    CommitDraftOnDispose(commit)
    OutlinedTextField(
        value = draft,
        onValueChange = { draft = it },
        label = { Text(stringResource(label)) },
        isError = checked is Checked.Invalid,
        supportingText = {
            when {
                checked is Checked.Invalid -> Text(stringResource(checked.problem.textRes()))
                hint != null -> Text(stringResource(hint))
            }
        },
        textStyle = MaterialTheme.typography.bodySmall.mono(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commit() }),
        modifier =
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .testTag("field-$label")
            .onFocusChanged { if (!it.hasFocus) commit() },
    )
}
