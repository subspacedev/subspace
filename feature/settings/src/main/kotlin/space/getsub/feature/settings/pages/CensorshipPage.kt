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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
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
import space.getsub.core.model.PreResolveSettings
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
        // Read once at connect, like lightweight mode: while connected, say a change waits for the next one.
        FragmentSection(state.fragment, state.sessionConnected, actions.onFragmentSettingsChanged)
        NoiseSection(state.noise, state.sessionConnected, actions.onNoiseSettingsChanged)
        PreResolveSection(state.preResolve, state.sessionConnected, actions.onPreResolveSettingsChanged)
    }
}

/** A field-level change: the ViewModel applies it to the latest stored value, not to this snapshot. */
private typealias Patch<T> = ((T) -> T) -> Unit

/** Bumped by a section's Reset so every draft is discarded, even one already equal to the default. */
private val LocalDraftResets = compositionLocalOf { 0 }

@Composable
private fun FragmentSection(
    value: FragmentSettings,
    connected: Boolean,
    update: Patch<FragmentSettings>,
) {
    var resets by remember { mutableIntStateOf(0) }
    SwitchRow(
        icon = SubspaceIcons.Shield,
        label = stringResource(R.string.settings_fragment_label),
        supporting =
        stringResource(
            if (value.enabled) R.string.settings_fragment_summary else R.string.settings_fragment_off_summary,
        ),
        checked = value.enabled,
        onCheckedChange = { on -> update { it.copy(enabled = on) } },
        note = stringResource(R.string.settings_fragment_connected_note).takeIf { connected },
    )
    if (!value.enabled) return
    CompositionLocalProvider(LocalDraftResets provides resets) { FragmentFields(value, update) }
    TextButton(onClick = {
        resets++
        update { FragmentSettings.DEFAULT.copy(enabled = true) }
    }) {
        Text(stringResource(R.string.settings_obfuscation_reset))
    }
}

@Composable
private fun FragmentFields(
    value: FragmentSettings,
    update: Patch<FragmentSettings>,
) {
    ValidatedField(
        label = R.string.settings_fragment_packets,
        stored = value.packets,
        validate = { ObfuscationValidation.packets(it) },
        hint = R.string.settings_fragment_packets_hint,
    ) { draft -> update { it.copy(packets = draft) } }
    ValidatedField(R.string.settings_fragment_length, value.length, { ObfuscationValidation.length(it) }) { draft ->
        update { it.copy(length = draft) }
    }
    ValidatedField(R.string.settings_fragment_delay, value.delay, { ObfuscationValidation.delay(it) }) { draft ->
        update { it.copy(delay = draft) }
    }
    ValidatedField(
        label = R.string.settings_fragment_max_split,
        stored = value.maxSplit,
        validate = { ObfuscationValidation.maxSplit(it) },
    ) { draft -> update { it.copy(maxSplit = draft) } }
}

@Composable
private fun NoiseSection(
    value: NoiseSettings,
    connected: Boolean,
    update: Patch<NoiseSettings>,
) {
    var resets by remember { mutableIntStateOf(0) }
    SwitchRow(
        icon = SubspaceIcons.Shield,
        label = stringResource(R.string.settings_noise_label),
        supporting =
        stringResource(if (value.enabled) R.string.settings_noise_summary else R.string.settings_noise_off_summary),
        checked = value.enabled,
        onCheckedChange = { on -> update { it.copy(enabled = on) } },
        note = stringResource(R.string.settings_noise_connected_note).takeIf { connected },
    )
    if (!value.enabled) return
    CompositionLocalProvider(LocalDraftResets provides resets) { NoiseFields(value, update) }
    TextButton(onClick = {
        resets++
        update { NoiseSettings.DEFAULT.copy(enabled = true) }
    }) {
        Text(stringResource(R.string.settings_obfuscation_reset))
    }
}

@Composable
private fun PreResolveSection(
    value: PreResolveSettings,
    connected: Boolean,
    update: Patch<PreResolveSettings>,
) {
    var resets by remember { mutableIntStateOf(0) }
    SwitchRow(
        icon = SubspaceIcons.Shield,
        label = stringResource(R.string.settings_preresolve_title),
        supporting =
        stringResource(if (value.enabled) R.string.settings_preresolve_summary else R.string.settings_preresolve_off),
        checked = value.enabled,
        onCheckedChange = { on -> update { it.copy(enabled = on) } },
        note = stringResource(R.string.settings_preresolve_connected_note).takeIf { connected },
    )
    if (!value.enabled) return
    CompositionLocalProvider(LocalDraftResets provides resets) {
        FieldWithError(
            label = R.string.settings_preresolve_doh_url,
            stored = value.dohUrl,
            errorRes = { if (isDohUrl(it)) null else R.string.settings_preresolve_doh_url_error },
        ) { draft ->
            if (draft.trim() != value.dohUrl) update { it.copy(dohUrl = draft.trim()) }
        }
        FieldWithError(
            label = R.string.settings_preresolve_bootstrap,
            stored = value.bootstrapIp,
            errorRes = { if (isIpLiteral(it)) null else R.string.settings_preresolve_bootstrap_error },
        ) { draft ->
            if (draft.trim() != value.bootstrapIp) update { it.copy(bootstrapIp = draft.trim()) }
        }
    }
    TextButton(onClick = {
        resets++
        update { PreResolveSettings.DEFAULT.copy(enabled = true) }
    }) {
        Text(stringResource(R.string.settings_obfuscation_reset))
    }
}

@Composable
private fun NoiseFields(
    value: NoiseSettings,
    update: Patch<NoiseSettings>,
) {
    ModeTiles(
        options =
        listOf(
            ModeTile(icon = SubspaceIcons.Tune, label = stringResource(R.string.settings_noise_mode_random)),
            ModeTile(icon = SubspaceIcons.Edit, label = stringResource(R.string.settings_noise_mode_fixed)),
        ),
        selected = NoiseMode.entries.indexOf(value.mode),
        onSelect = { index -> update { it.copy(mode = NoiseMode.entries[index]) } },
    )
    when (value.mode) {
        NoiseMode.Random -> {
            ValidatedField(R.string.settings_noise_rand, value.rand, { ObfuscationValidation.rand(it) }) { draft ->
                update { it.copy(rand = draft) }
            }
            ValidatedField(
                label = R.string.settings_noise_rand_range,
                stored = value.randRange,
                validate = { ObfuscationValidation.randRange(it) },
            ) { draft -> update { it.copy(randRange = draft) } }
        }
        NoiseMode.Fixed -> FixedPacket(value, update)
    }
    ValidatedField(R.string.settings_noise_delay, value.delay, { ObfuscationValidation.delay(it) }) { draft ->
        update { it.copy(delay = draft) }
    }
}

@Composable
private fun FixedPacket(
    value: NoiseSettings,
    update: Patch<NoiseSettings>,
) {
    SectionTitle(stringResource(R.string.settings_noise_packet_type))
    RadioList(
        choices =
        NoisePacketType.entries.map { type ->
            RadioChoice(
                label = type.wire,
                selected = type == value.packetType,
                onSelect = { update { it.copy(packetType = type) } },
            )
        },
    )
    // Keyed on the type so a type change re-validates the draft against the new type.
    key(value.packetType) {
        ValidatedField(
            label = R.string.settings_noise_packet,
            stored = value.packet,
            validate = { ObfuscationValidation.packet(value.packetType, it) },
        ) { draft ->
            // Re-checked against the type stored when the patch lands: a dispose commit after a type
            // change must not save a packet that was only valid for the old type.
            update { latest ->
                val valid = ObfuscationValidation.packet(latest.packetType, draft) is Checked.Ok
                if (valid) latest.copy(packet = draft) else latest
            }
        }
    }
}

/**
 * A draft that commits only valid text (M9-A Review Focus 4), on Done, focus loss and leaving the
 * page (M9-A Review Focus 5). The error is shown inline from [validate]'s problem.
 */
@Composable
private fun ValidatedField(
    @StringRes label: Int,
    stored: String,
    validate: (String) -> Checked<*>,
    @StringRes hint: Int? = null,
    commitValue: (String) -> Unit,
) = FieldWithError(
    label = label,
    stored = stored,
    errorRes = { draft -> (validate(draft) as? Checked.Invalid)?.problem?.textRes() },
    hint = hint,
    commitValue = commitValue,
)

/**
 * [ValidatedField]'s body: [errorRes] is null for a valid draft, else the message to show. The tag
 * names the label's resource id so a test can find one field without depending on its rendered text.
 * [LocalDraftResets] discards the draft when Reset is pressed; keying the composable instead would
 * dispose it, and disposing commits.
 */
@Composable
private fun FieldWithError(
    @StringRes label: Int,
    stored: String,
    errorRes: (String) -> Int?,
    @StringRes hint: Int? = null,
    commitValue: (String) -> Unit,
) {
    var draft by remember(stored, LocalDraftResets.current) { mutableStateOf(stored) }
    val error = errorRes(draft)
    val commit = { if (shouldCommitDraft(draft, stored, isValid = error == null)) commitValue(draft) }
    CommitDraftOnDispose(commit)
    OutlinedTextField(
        value = draft,
        onValueChange = { draft = it },
        label = { Text(stringResource(label)) },
        isError = error != null,
        supportingText = {
            when {
                error != null -> Text(stringResource(error))
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
