// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.ui.snapshot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.Paparazzi
import space.getsub.core.ui.preview.SubspacePreviewTheme
import space.getsub.core.ui.preview.ThemeVariant

/**
 * Snapshots [content] once per theme variant, on the theme's background,
 * with motion reduced so animated components render a stable frame.
 */
fun Paparazzi.snapshotThemes(
    name: String,
    content: @Composable () -> Unit,
) {
    ThemeVariant.entries.forEach { v ->
        snapshot(name = "$name-${v.fileSuffix}") {
            SubspacePreviewTheme(v) { Box(Modifier.padding(16.dp)) { content() } }
        }
    }
}

/**
 * Snapshots [content] once, in the friendly light theme, with motion left on.
 * Paparazzi captures the first frame, before any animation has advanced, so
 * this shows the shape a component starts from when motion is allowed, which
 * [snapshotThemes] cannot (it reduces motion).
 */
fun Paparazzi.snapshotWithMotion(
    name: String,
    content: @Composable () -> Unit,
) {
    val v = ThemeVariant.FriendlyLight
    snapshot(name = "$name-motion-${v.fileSuffix}") {
        SubspacePreviewTheme(v, reducedMotion = false) { Box(Modifier.padding(16.dp)) { content() } }
    }
}
