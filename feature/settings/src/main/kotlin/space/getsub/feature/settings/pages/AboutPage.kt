// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.settings.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import space.getsub.core.ui.theme.SubspaceShapeTokens
import space.getsub.core.ui.theme.mono
import space.getsub.feature.settings.R
import space.getsub.feature.settings.SettingsState
import space.getsub.feature.settings.displayText

/** App and Xray-core versions (monospace) and the licence, on one tonal card (M8.6 spec §4.4). */
@Composable
internal fun AboutPage(
    state: SettingsState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PageScaffold(title = stringResource(R.string.settings_section_about), onBack = onBack, modifier = modifier) {
        Surface(
            shape = SubspaceShapeTokens.card,
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AboutLine(stringResource(R.string.settings_about_app_version_label), state.appVersion, mono = true)
                AboutLine(
                    stringResource(R.string.settings_about_xray_version_label),
                    state.xrayVersion.displayText(),
                    mono = true,
                )
                AboutLine(
                    stringResource(R.string.settings_about_license_label),
                    stringResource(R.string.settings_about_license_value),
                    mono = false,
                )
            }
        }
    }
}

@Composable
private fun AboutLine(
    label: String,
    value: String,
    mono: Boolean,
) {
    val style: TextStyle = MaterialTheme.typography.bodyMedium
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Text(label, style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = if (mono) style.mono() else style,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}
