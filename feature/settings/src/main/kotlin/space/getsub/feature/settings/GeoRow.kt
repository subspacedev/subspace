// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.settings

import androidx.annotation.StringRes
import space.getsub.core.data.GeoInstallResult
import space.getsub.core.data.InstalledGeoAsset
import space.getsub.core.model.GeoDataKind
import space.getsub.core.model.GeoSource
import space.getsub.core.model.GeoSourceCatalogue

/**
 * One geo database as the UI displays it: a catalogue [GeoSource]'s shape, joined against
 * whatever [InstalledGeoAsset] (if any) that source's `installFileName` currently has on disk.
 *
 * @property approximateBytes the catalogue's measured download size — shown *before* a download,
 *   per this task's brief, since [installedBytes] does not exist yet for a never-installed row.
 * @property installedBytes the real installed size once known, which supersedes the estimate.
 * @property hasError §A.3.1's persistent error marker: true whenever the most recent install
 *   attempt (this session or a prior one, scheduled or manual) recorded a [InstalledGeoAsset.lastFailure].
 * @property lastFailure the raw closed-vocabulary name behind [hasError] — `null` unless [hasError]
 *   is true. §10.4 (branch review, Finding 3): kept distinct from the four in-session
 *   [GeoInstallResult] outcomes so a permanent `NotGeoData` (retrying will never help) does not read
 *   identically to a transient `DownloadFailed` or a `RecoveryFailed` whose backups are stranded in
 *   staging awaiting recovery.
 * @property isCustom true when this row was built from an installed asset that does not match any
 *   currently selected catalogue source — a user-added source (§6, M5 spec §4.1).
 */
internal data class GeoRow(
    val sourceId: String,
    val displayName: String,
    val installFileName: String,
    val downloadUrl: String,
    val geoType: GeoDataKind,
    val licence: String,
    val approximateBytes: Long,
    val installedBytes: Long?,
    val installedAt: Long?,
    val hasError: Boolean,
    val lastFailure: String? = null,
    val isCustom: Boolean = false,
)

/**
 * Builds one display row from a catalogue (or synthetic custom) [source] and the matching
 * [installed] asset, if any. A pure function on purpose — [SettingsGeoTest] exercises it directly,
 * with no ViewModel, no Compose, and no repository in the loop.
 *
 * This function trusts whatever [source]/[installed] pair its caller hands it — it does not decide
 * *which* source belongs with which asset. [geoRowsFor] is that decision; see its own KDoc for why
 * the two are kept separate.
 */
internal fun geoRowFor(
    source: GeoSource,
    installed: InstalledGeoAsset?,
    isCustom: Boolean = false,
): GeoRow =
    GeoRow(
        sourceId = source.id,
        displayName = source.displayName,
        installFileName = source.installFileName,
        downloadUrl = source.downloadUrl,
        geoType = source.geoType,
        licence = source.licence,
        approximateBytes = source.approximateBytes,
        installedBytes = installed?.sizeBytes,
        installedAt = installed?.installedAt,
        hasError = installed?.lastFailure != null,
        lastFailure = installed?.lastFailure,
        isCustom = isCustom,
    )

/**
 * A synthetic [GeoSource] standing in for an installed asset that came from a user-added URL
 * rather than [GeoSourceCatalogue]. [GeoSource.licence] is left blank — a custom source's licence
 * is unknown to this app — and [GeoSource.approximateBytes] falls back to the real installed size,
 * since there is no catalogue estimate to show instead.
 */
internal fun InstalledGeoAsset.asCustomSource(): GeoSource =
    GeoSource(
        id = "custom:$fileName",
        displayName = fileName,
        downloadUrl = sourceUrl,
        installFileName = fileName,
        geoType = geoType,
        licence = "",
        approximateBytes = sizeBytes ?: 0L,
    )

/**
 * Builds the full "Geo databases" row list, one row per install filename.
 *
 * A branch review found the previous version of this assembly: it always built a filename's row
 * from *whichever catalogue source the picker currently named* for that filename, joined against
 * whatever asset happened to share the filename. Two bugs fell out of that — both fixed here by
 * changing what "ground truth" for a row means:
 *
 * 1. **A custom source could be silently destroyed.** `GeoSourceCatalogue.defaults()` always
 *    claims `geoip.dat`/`geosite.dat` by default, so a custom source installed under one of those
 *    names (the add-custom-source form's own filename hint invites exactly `geoip.dat`) was
 *    rendered as the *catalogue* row, and "Update now" on it downloaded the catalogue source over
 *    the user's data with no warning.
 * 2. **The estimate shown before a download could be stale.** Picking a new, larger source for an
 *    already-installed filename kept showing the *old* installed size until the download finished,
 *    understating the cost §A.5 requires the user see beforehand.
 *
 * The fix: an installed asset is ground truth for its filename **unless** [selectedIds] explicitly
 * names a different source for that filename right now — in which case that selection is a
 * *pending* switch, previewed with its own estimate (no installed-only *size*, per the §A.5 fix
 * above) but **not** stripped of the rest of the installed asset's state. Branch review, Finding 2:
 * an earlier version of this also erased [InstalledGeoAsset.installedAt] and
 * [InstalledGeoAsset.lastFailure] the instant a selection was armed, so the row read "Not installed
 * yet" for a file that was still genuinely installed and still active for routing — the Routing
 * screen, which reads [InstalledGeoAsset] directly rather than through a pending selection, kept
 * disagreeing with this one the whole time — and, worse, a persisted failure on that same file
 * became invisible for as long as the selection stayed armed, the exact masking class Task 17's own
 * Finding 3 closed through a different door. Only once "Update now" actually downloads the pending
 * selection does the row become the new ground truth outright, because at that point the two agree.
 * A filename with neither an installed asset nor a matching selection falls back to
 * [GeoSourceCatalogue.defaults] — the two starter rows a user who never opens the picker still
 * sees.
 */
internal fun geoRowsFor(
    sources: List<GeoSource>,
    selectedIds: Set<String>,
    installed: List<InstalledGeoAsset>,
): List<GeoRow> {
    val selectedSources = selectedIds.mapNotNull { id -> sources.firstOrNull { it.id == id } }
    val defaultSources = GeoSourceCatalogue.defaults()
    val installedFileNames = installed.map { it.fileName }
    val selectedFileNames = selectedSources.map { it.installFileName }
    val defaultFileNames = defaultSources.map { it.installFileName }
    val fileNames = (installedFileNames + selectedFileNames + defaultFileNames).distinct()

    return fileNames.mapNotNull { fileName ->
        rowForFileName(fileName, sources, selectedSources, defaultSources, installed)
    }
}

/**
 * The source id in effect for every install filename: what [geoRowsFor] resolves to, so the stored
 * choice, an installed asset that is ground truth, or [GeoSourceCatalogue.defaults] when the user
 * never chose. This is what the Geo page's radio lists mark as selected; [selectedIds] alone is
 * empty on a fresh install, which left every radio unchecked while the hub and the downloads used
 * the default. No second copy of the default lives here — it comes out of [geoRowsFor].
 */
internal fun effectiveGeoSourceIds(
    sources: List<GeoSource>,
    selectedIds: Set<String>,
    installed: List<InstalledGeoAsset>,
): Set<String> = geoRowsFor(sources, selectedIds, installed).map { it.sourceId }.toSet()

@Suppress("ReturnCount") // Three independent, mutually-exclusive precedence rules — see the KDoc above.
private fun rowForFileName(
    fileName: String,
    sources: List<GeoSource>,
    selectedSources: List<GeoSource>,
    defaultSources: List<GeoSource>,
    installed: List<InstalledGeoAsset>,
): GeoRow? {
    val installedAsset = installed.firstOrNull { it.fileName == fileName }
    val pendingSource = selectedSources.firstOrNull { it.installFileName == fileName }
    val agreesWithInstalled = pendingSource == null || pendingSource.downloadUrl == installedAsset?.sourceUrl

    if (installedAsset != null && agreesWithInstalled) {
        val matched =
            sources.firstOrNull { it.installFileName == fileName && it.downloadUrl == installedAsset.sourceUrl }
        return geoRowFor(matched ?: installedAsset.asCustomSource(), installedAsset, isCustom = matched == null)
    }
    if (pendingSource != null) {
        // The estimate must be the pending source's (§A.5), but everything else about an existing
        // installed asset — install date, error marker — must survive an armed-but-not-yet-updated
        // selection (branch review, Finding 2). sizeBytes = null is what keeps GeoRowItem's
        // `installedBytes ?: approximateBytes` from showing the stale on-disk size instead.
        return geoRowFor(pendingSource, installed = installedAsset?.copy(sizeBytes = null))
    }
    val defaultSource = defaultSources.firstOrNull { it.installFileName == fileName } ?: return null
    return geoRowFor(defaultSource, installed = null)
}

/**
 * §10.4: every one of [GeoInstallResult]'s four members gets its own string — never collapsed to
 * a pass/fail boolean, so the user can tell a failed download from a rejected file from a failed
 * local install. `internal` (not `private`) so [SettingsGeoTest] can pin that all four resolve to
 * distinct resource ids — a copy-paste pointing two members at the same string would otherwise
 * compile and pass silently.
 */
internal fun GeoInstallResult.messageRes(): Int =
    when (this) {
        GeoInstallResult.Installed -> R.string.settings_geo_result_installed
        GeoInstallResult.DownloadFailed -> R.string.settings_geo_result_download_failed
        GeoInstallResult.Rejected -> R.string.settings_geo_result_rejected
        GeoInstallResult.InstallFailed -> R.string.settings_geo_result_install_failed
    }

/**
 * [InstalledGeoAsset.lastFailure] is a closed-vocabulary name from one of two `:core:data`/`:core:model`
 * enums its own KDoc names but does not expose together as one importable type:
 * [space.getsub.core.data.GeoAssetRepository]'s `GeoAssetFailure` (`InvalidFileName`,
 * `DownloadFailed`, `InstallFailed`, `RecoveryFailed` — `internal` to `:core:data`, so it cannot be
 * imported here at all) and [space.getsub.core.model.GeoValidation] (`Unreadable`,
 * `NotGeoData`, both public). Matched as plain strings for both, one flat `when` rather than a
 * partial import for the two that happen to have a public type.
 *
 * §10.4 (branch review, Finding 3): distinct from [GeoInstallResult.messageRes] on purpose — this
 * is the *persisted* marker a scheduled refresh's own failure leaves behind, which
 * [SettingsViewModel] never learns about as a fresh [GeoInstallResult] at all, so it is the only
 * failure channel a user normally sees for that case. A permanent `NotGeoData` and a transient
 * `DownloadFailed` must not read the same, and `RecoveryFailed` specifically means backups are
 * stranded in staging awaiting recovery — worth a different message than "try again".
 */
internal fun lastFailureMessageRes(lastFailure: String?): Int =
    when (lastFailure) {
        "InvalidFileName" -> R.string.settings_geo_persisted_invalid_filename
        "DownloadFailed" -> R.string.settings_geo_persisted_download_failed
        "InstallFailed" -> R.string.settings_geo_persisted_install_failed
        "RecoveryFailed" -> R.string.settings_geo_persisted_recovery_failed
        "Unreadable" -> R.string.settings_geo_persisted_unreadable
        "NotGeoData" -> R.string.settings_geo_persisted_not_geo_data
        // Defensive fallback for a value this app never wrote itself (§10.4's own "fail loudly and
        // specifically" is about code paths, not about a persisted string a future app version, or
        // a hand-edited row, might carry) — never crashes the section over an unrecognised marker.
        else -> R.string.settings_geo_status_error
    }

/** The status line a [GeoRow] shows: its string and whether it is a failure. */
internal data class GeoStatus(
    @param:StringRes val messageRes: Int,
    val isError: Boolean,
)

/**
 * A fresh in-session [GeoInstallResult] wins over the persisted marker *unless* it is a stale
 * "Installed" masking a failure some other actor (a scheduled refresh, another process) has since
 * recorded — a live failure must never be hidden behind an earlier success this session happened
 * to produce. The error flag follows the same branch as the text, so what is shown and how it is
 * coloured cannot disagree.
 */
internal fun geoStatusFor(
    row: GeoRow,
    updating: Boolean,
    result: GeoInstallResult?,
): GeoStatus =
    when {
        updating -> GeoStatus(R.string.settings_geo_status_updating, isError = false)
        row.hasError && result == GeoInstallResult.Installed ->
            GeoStatus(lastFailureMessageRes(row.lastFailure), isError = true)
        result != null -> GeoStatus(result.messageRes(), isError = result != GeoInstallResult.Installed)
        row.hasError -> GeoStatus(lastFailureMessageRes(row.lastFailure), isError = true)
        row.installedAt != null -> GeoStatus(R.string.settings_geo_status_installed, isError = false)
        else -> GeoStatus(R.string.settings_geo_status_not_installed, isError = false)
    }
