// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.profiles.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import space.getsub.core.ui.component.BannerTone
import space.getsub.core.ui.component.TonalBanner
import space.getsub.core.ui.icon.SubspaceIcons
import space.getsub.feature.profiles.R

/** Identifies the "carries TCP only" note. */
internal const val EDITOR_HTTP_TCP_ONLY_TEST_TAG = "editor-http-tcp-only"

/** Identifies the TLS on/off switch. */
internal const val EDITOR_HTTP_TLS_SWITCH_TEST_TAG = "editor-http-tls-switch"

/**
 * The HTTP proxy half of the typed editor: the TCP-only note and the TLS switch. Address, port, username
 * and password sit in the shared cards (see [toHttpOutbound]). With TLS on, [tlsFields] draws the same
 * SNI, fingerprint and "insecure ignored" fields every other protocol uses.
 */
@Composable
internal fun HttpSections(
    state: EditorState,
    onSecurityKindChanged: (EditorSecurityKind) -> Unit,
    tlsFields: @Composable () -> Unit,
) {
    TonalBanner(
        icon = SubspaceIcons.Info,
        text = stringResource(R.string.editor_http_tcp_only),
        tone = BannerTone.Neutral,
        modifier = Modifier.testTag(EDITOR_HTTP_TCP_ONLY_TEST_TAG),
    )
    SectionCard(title = stringResource(R.string.editor_section_security)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.editor_http_tls_label),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = state.securityKind != EditorSecurityKind.None,
                onCheckedChange = { on ->
                    onSecurityKindChanged(if (on) EditorSecurityKind.Tls else EditorSecurityKind.None)
                },
                modifier = Modifier.testTag(EDITOR_HTTP_TLS_SWITCH_TEST_TAG),
            )
        }
        when (state.realityConflict) {
            RealityConflict.HttpProxy ->
                Text(
                    text = stringResource(R.string.editor_http_reality_error),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            else -> Unit
        }
        if (state.securityKind == EditorSecurityKind.Tls) tlsFields()
    }
}
