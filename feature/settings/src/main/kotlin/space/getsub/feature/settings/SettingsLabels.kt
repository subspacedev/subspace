// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import space.getsub.core.data.ThemePreference
import space.getsub.core.model.PingMode

internal fun ThemePreference.labelRes(): Int =
    when (this) {
        ThemePreference.System -> R.string.settings_theme_system
        ThemePreference.Light -> R.string.settings_theme_light
        ThemePreference.Dark -> R.string.settings_theme_dark
    }

/**
 * The text shown for the Xray-core version on the About page.
 */
@Composable
internal fun XrayVersionState.displayText(): String =
    when (this) {
        XrayVersionState.Loading -> stringResource(R.string.settings_about_xray_version_loading)
        is XrayVersionState.Available -> version
        XrayVersionState.Unavailable -> stringResource(R.string.settings_about_xray_version_unavailable)
    }

internal fun PingMode.labelRes(): Int =
    when (this) {
        PingMode.TCP -> R.string.settings_ping_mode_tcp
        PingMode.PROXY_HEAD -> R.string.settings_ping_mode_proxy_head
    }

internal fun PingMode.summaryRes(): Int =
    when (this) {
        PingMode.TCP -> R.string.settings_ping_mode_tcp_summary
        PingMode.PROXY_HEAD -> R.string.settings_ping_mode_proxy_head_summary
    }
