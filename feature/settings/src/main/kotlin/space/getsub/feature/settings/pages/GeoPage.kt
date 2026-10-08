// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.settings.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import space.getsub.core.data.GeoInstallResult
import space.getsub.core.model.GeoDataKind
import space.getsub.core.model.GeoSource
import space.getsub.core.model.isGeoFileName
import space.getsub.core.ui.component.SectionTitle
import space.getsub.core.ui.component.SwitchRow
import space.getsub.core.ui.icon.SubspaceIcons
import space.getsub.core.ui.theme.mono
import space.getsub.feature.settings.GeoRow
import space.getsub.feature.settings.R
import space.getsub.feature.settings.SettingsActions
import space.getsub.feature.settings.SettingsState
import space.getsub.feature.settings.lastFailureMessageRes
import space.getsub.feature.settings.messageRes
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Suppress("MagicNumber") // The one non-token literal in this file — decimal-MB, not binary (QuotaBar.kt's own).
private const val BYTES_PER_MEGABYTE = 1_000_000.0
private val UPDATE_SPINNER_SIZE = 24.dp

private val INSTALLED_AT_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)

/**
 * "Geo databases": one row per install filename with its status and an Update action, a source
 * choice per [GeoDataKind], an add-custom-source form, and the scheduled-refresh metered toggle
 * ([space.getsub.feature.settings.SettingsSource.geoRefreshOnMetered]).
 */
@Composable
internal fun GeoPage(
    state: SettingsState,
    actions: SettingsActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PageScaffold(title = stringResource(R.string.settings_section_geo), onBack = onBack, modifier = modifier) {
        state.geoRows.forEach { row ->
            GeoRowItem(
                row = row,
                updating = row.installFileName in state.geoUpdateInFlight,
                result = state.geoUpdateResults[row.installFileName],
                onUpdateNow = { actions.onGeoUpdateNow(row) },
                onRemove = { actions.onRemoveCustomGeoSource(row.installFileName) },
            )
        }

        GeoSourceChoice(
            sources = state.geoSources,
            selectedIds = state.selectedGeoSourceIds,
            onSourceSelected = actions.onGeoSourceSelected,
        )

        GeoCustomSourceForm(onAdd = actions.onAddCustomGeoSource)

        SwitchRow(
            icon = SubspaceIcons.Refresh,
            label = stringResource(R.string.settings_geo_metered_title),
            supporting = stringResource(R.string.settings_geo_metered_summary),
            checked = state.geoRefreshOnMetered,
            onCheckedChange = actions.onGeoRefreshOnMeteredChanged,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

/**
 * One row: the install filename (mono), the source's name (a generic label for a custom source),
 * size, status, install date and licence, an "Update" action that always runs (see
 * [space.getsub.feature.settings.GeoAssetSource.install]'s KDoc), and, for a custom row only, a
 * remove action: a catalogue row always has somewhere to reappear from, so only a custom row can be
 * a typo'd URL with no other way to stop it being retried by a scheduled refresh forever.
 */
// row/updating/result/onUpdateNow/onRemove/modifier is the width of one row's own data and its two
// actions, not a candidate for grouping into a carrier that would just move the same five names one
// level down.
@Suppress("LongParameterList")
@Composable
private fun GeoRowItem(
    row: GeoRow,
    updating: Boolean,
    result: GeoInstallResult?,
    onUpdateNow: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sizeMb = (row.installedBytes ?: row.approximateBytes) / BYTES_PER_MEGABYTE
    val sizeLabel = stringResource(R.string.settings_geo_size_mb, sizeMb)
    val statusLabel = row.statusLabel(updating, result)
    val displayName =
        if (row.isCustom) {
            stringResource(R.string.settings_geo_custom_source_label, row.installFileName)
        } else {
            row.displayName
        }
    val installedAtLabel =
        row.installedAt?.let { millis ->
            stringResource(R.string.settings_geo_installed_at, formatInstalledAt(millis))
        }
    val detail = listOfNotNull(installedAtLabel, row.licence.takeIf { it.isNotBlank() }).joinToString(" · ")
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(painterResource(SubspaceIcons.Public), contentDescription = null, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f)) {
            Text(row.installFileName, style = MaterialTheme.typography.bodyMedium.mono())
            Text(
                text =
                if (row.isCustom) {
                    stringResource(R.string.settings_geo_custom_source_short)
                } else {
                    row.displayName
                },
                style = MaterialTheme.typography.bodyMedium,
                color = muted,
            )
            Text(
                text = stringResource(R.string.settings_geo_row_summary, sizeLabel, statusLabel),
                style = MaterialTheme.typography.bodyMedium,
                color = if (row.hasError && !updating) MaterialTheme.colorScheme.error else muted,
            )
            if (detail.isNotEmpty()) {
                Text(text = detail, style = MaterialTheme.typography.bodySmall, color = muted)
            }
        }
        GeoRowActions(
            isCustom = row.isCustom,
            updating = updating,
            displayName = displayName,
            onUpdateNow = onUpdateNow,
            onRemove = onRemove,
        )
    }
}

@Composable
private fun GeoRowActions(
    isCustom: Boolean,
    updating: Boolean,
    displayName: String,
    onUpdateNow: () -> Unit,
    onRemove: () -> Unit,
) {
    if (updating) {
        CircularProgressIndicator(modifier = Modifier.size(UPDATE_SPINNER_SIZE))
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (isCustom) {
            val removeDescription = stringResource(R.string.settings_geo_remove_custom_source, displayName)
            IconButton(onClick = onRemove) {
                Icon(painterResource(SubspaceIcons.Delete), contentDescription = removeDescription)
            }
        }
        val updateDescription = stringResource(R.string.settings_geo_update_now, displayName)
        OutlinedButton(
            onClick = onUpdateNow,
            modifier = Modifier.semantics { contentDescription = updateDescription },
        ) {
            Text(stringResource(R.string.settings_geo_update_action))
        }
    }
}

/**
 * A fresh in-session [GeoInstallResult] wins over the persisted marker *unless* it is a stale
 * "Installed" masking a failure some other actor (a scheduled refresh, another process) has since
 * recorded — a live failure must never be hidden behind an earlier success this session happened
 * to produce.
 */
@Composable
private fun GeoRow.statusLabel(
    updating: Boolean,
    result: GeoInstallResult?,
): String =
    when {
        updating -> stringResource(R.string.settings_geo_status_updating)
        hasError && result == GeoInstallResult.Installed -> stringResource(lastFailureMessageRes(lastFailure))
        result != null -> stringResource(result.messageRes())
        hasError -> stringResource(lastFailureMessageRes(lastFailure))
        installedAt != null -> stringResource(R.string.settings_geo_status_installed)
        else -> stringResource(R.string.settings_geo_status_not_installed)
    }

private fun formatInstalledAt(millis: Long): String =
    INSTALLED_AT_FORMATTER.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

/**
 * One radio list per [GeoDataKind], letting the user swap which catalogue provider backs that
 * kind's install filename. Choosing a source does not download anything by itself — that is the
 * row's Update action; this only arms which source it would use next.
 *
 * A radio list, not [space.getsub.core.ui.component.ModeTiles]: "runetfreedom (RU)" does not fit
 * on one line in a tile at 360 dp (it wrapped even in the old full-width segmented control).
 */
@Composable
private fun GeoSourceChoice(
    sources: List<GeoSource>,
    selectedIds: Set<String>,
    onSourceSelected: (String) -> Unit,
) {
    GeoDataKind.entries.forEach { kind ->
        val kindSources = sources.filter { it.geoType == kind }
        if (kindSources.isEmpty()) return@forEach

        SectionTitle(stringResource(kind.labelRes()))
        RadioList(
            choices =
            kindSources.map { source ->
                RadioChoice(
                    label = source.displayName,
                    selected = source.id in selectedIds,
                    onSelect = { onSourceSelected(source.id) },
                )
            },
        )
    }
}

/**
 * URL, install filename, and a required [GeoDataKind] choice — the kind cannot default or be
 * inferred from the bytes: the selection starts at `null` and Add stays disabled until the user
 * picks one themselves.
 *
 * The filename is also checked against [isGeoFileName] before Add enables: typing `geoip` with no
 * extension used to reach the repository, which rejects it as `InvalidFileName`, surfacing as
 * "Downloaded file was not a valid geo database" — a message about bytes that were never fetched.
 */
@Composable
private fun GeoCustomSourceForm(
    onAdd: (url: String, fileName: String, geoType: GeoDataKind) -> Unit,
    modifier: Modifier = Modifier,
) {
    var url by remember { mutableStateOf("") }
    var fileName by remember { mutableStateOf("") }
    var selectedKind by remember { mutableStateOf<GeoDataKind?>(null) }

    Column(modifier = modifier) {
        SectionTitle(stringResource(R.string.settings_geo_add_custom_title))

        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text(stringResource(R.string.settings_geo_add_custom_url)) },
            textStyle = MaterialTheme.typography.bodySmall.mono(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = fileName,
            onValueChange = { fileName = it },
            label = { Text(stringResource(R.string.settings_geo_add_custom_filename)) },
            isError = fileName.isNotBlank() && !isGeoFileName(fileName),
            textStyle = MaterialTheme.typography.bodySmall.mono(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )

        RadioList(
            choices =
            GeoDataKind.entries.map { kind ->
                RadioChoice(
                    label = stringResource(kind.labelRes()),
                    selected = selectedKind == kind,
                    onSelect = { selectedKind = kind },
                )
            },
            modifier = Modifier.padding(top = 4.dp),
        )

        val kindForAdd = selectedKind
        Button(
            onClick = {
                if (kindForAdd == null) return@Button
                onAdd(url, fileName, kindForAdd)
                url = ""
                fileName = ""
                selectedKind = null
            },
            enabled = url.isNotBlank() && isGeoFileName(fileName) && kindForAdd != null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.settings_geo_add_custom_action))
        }
    }
}

private fun GeoDataKind.labelRes(): Int =
    when (this) {
        GeoDataKind.DOMAIN -> R.string.settings_geo_kind_domain
        GeoDataKind.IP -> R.string.settings_geo_kind_ip
    }
