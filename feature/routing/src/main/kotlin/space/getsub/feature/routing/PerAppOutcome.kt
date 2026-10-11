// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.routing

import space.getsub.core.model.PerAppMode
import space.getsub.core.ui.component.Outcome

/**
 * Where one app's traffic ends up under [mode] (M8.6 spec §4.9, fix A8).
 *
 * Allow-list tunnels only the selected apps; Deny-list tunnels everything but them; Off tunnels
 * every app, whatever is selected (the selection is kept for when the mode is switched back).
 */
internal fun outcomeFor(
    mode: PerAppMode,
    isSelected: Boolean,
): Outcome =
    when (mode) {
        PerAppMode.Off -> Outcome.Tunnelled
        PerAppMode.AllowList -> if (isSelected) Outcome.Tunnelled else Outcome.Direct
        PerAppMode.DenyList -> if (isSelected) Outcome.Direct else Outcome.Tunnelled
    }
