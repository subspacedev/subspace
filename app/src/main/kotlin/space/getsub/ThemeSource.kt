// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import space.getsub.core.data.SettingsRepository
import space.getsub.core.data.ThemePreference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything [MainActivity] needs before it can draw its first frame:
 * light/dark, dynamic colour, and whether decorative motion is off
 * (lightweight mode, M8.6 spec §2.6).
 */
internal data class Appearance(
    val theme: ThemePreference = ThemePreference.System,
    val dynamicColor: Boolean = true,
    val lightweight: Boolean = false,
)

/**
 * Combines the three settings. A failure in any of them emits the defaults
 * rather than nothing: [MainActivity] holds its first frame until this emits,
 * so silence here would leave the window blank (M8.6 spec §6).
 */
internal fun appearanceFlow(
    theme: Flow<ThemePreference>,
    dynamicColor: Flow<Boolean>,
    lightweight: Flow<Boolean>,
): Flow<Appearance> =
    combine(theme, dynamicColor, lightweight) { t, d, l -> Appearance(theme = t, dynamicColor = d, lightweight = l) }
        .catch { emit(Appearance()) }

/**
 * The [SettingsRepository] slice [MainActivity] needs to configure
 * [space.getsub.core.ui.theme.SubspaceTheme].
 *
 * [SettingsRepository] has an `internal` constructor scoped to `:core:data`
 * (ARCHITECTURE.md §3) — this module cannot build a real instance of it to
 * test against, the same reason
 * [space.getsub.feature.settings.SettingsSource] and
 * [space.getsub.feature.home.ActiveProfileSource] exist.
 * [BoundThemeSource] is the one place that touches the real repository.
 */
internal interface ThemeSource {
    val appearance: Flow<Appearance>
}

@Singleton
internal class BoundThemeSource
@Inject
constructor(
    private val settingsRepository: SettingsRepository,
) : ThemeSource {
    override val appearance: Flow<Appearance> =
        appearanceFlow(
            theme = settingsRepository.theme,
            dynamicColor = settingsRepository.dynamicColor,
            lightweight = settingsRepository.lightweightMode,
        )
}
