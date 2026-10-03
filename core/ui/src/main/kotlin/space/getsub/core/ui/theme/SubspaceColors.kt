// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The one colour Material has no role for: the "pop", used for exactly one
 * standout button per screen (M8.6 spec §2.3). With dynamic colour it is the
 * scheme's primary; with the friendly fallback it is coral. The connected
 * colour is no longer here: it is `colorScheme.tertiary`, and connection
 * state is told apart by label, icon and shape (ARCHITECTURE.md §5.5).
 */
@Immutable
data class SubspaceColors(
    val pop: Color,
    val onPop: Color,
)

val LocalSubspaceColors = staticCompositionLocalOf<SubspaceColors> {
    error("SubspaceColors requested outside SubspaceTheme")
}
