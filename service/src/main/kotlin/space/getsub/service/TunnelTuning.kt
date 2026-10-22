// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import space.getsub.core.data.ResolvedTuning
import space.getsub.core.model.Applied
import space.getsub.core.model.TuningOrigin
import space.getsub.core.xray.TunnelSettings

/**
 * [tuning]'s config-side values on these settings (M9-E spec §1–§3). Only your own setting replaces
 * a raw config's own block ([TunnelSettings.sniffingReplacesOwn], [Applied.replaceOwn]); a provider's
 * value or the default never does. Mux off from a provider or the default writes nothing; your switch
 * On with mux off is passed on, so a raw config's own `mux` is removed.
 *
 * One bundle so `TunnelService.startCore` takes the per-start [ResolvedTuning] in one line.
 */
internal fun TunnelSettings.withTuning(tuning: ResolvedTuning): TunnelSettings {
    val yourMux = tuning.mux.origin == TuningOrigin.Yours
    val spec = tuning.mux.value
    return copy(
        enableSniffing = tuning.sniffing.value,
        sniffingReplacesOwn = tuning.sniffing.origin == TuningOrigin.Yours,
        mux = if (spec != null || yourMux) Applied(spec, replaceOwn = yourMux) else null,
    )
}
