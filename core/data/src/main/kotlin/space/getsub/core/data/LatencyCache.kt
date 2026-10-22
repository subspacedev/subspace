// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import space.getsub.core.model.LatencyResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * This session's latency measurements.
 *
 * **Deliberately not Room.** §3 routes state through Room when the *other*
 * process must read it, and nothing in `:bg` consumes a latency
 * (the provider's `subscription-autoconnect-type` is ignored, M10-C spec §3.5). Keeping these
 * in memory makes session scoping a property of where the data lives rather than
 * a cleanup step someone can forget, and avoids a migration for values that stop
 * being true the moment the network changes.
 *
 * A `@Singleton` is safe here for the same reason `TunnelClient`'s is: §3's
 * warning is about expecting `:bg` to see a singleton. This one is explicitly
 * `:main`-only, and `:bg` reaching for it would be the bug rather than the fix.
 *
 * Consequence to accept: a result survives `:bg` dying but not `:main` dying.
 * That is the right way round — the UI that renders it is what went away.
 */
@Singleton
public class LatencyCache
@Inject
constructor() {
    private val _results = MutableStateFlow<Map<Long, LatencyResult>>(emptyMap())

    /**
     * Measured results by profile id.
     *
     * Absence means "never measured" and must render as an em-dash. It is never a
     * zero — see [LatencyResult] for why a substituted number here is the §10.1
     * failure this whole milestone is careful about.
     */
    public val results: StateFlow<Map<Long, LatencyResult>> = _results.asStateFlow()

    private val _testing = MutableStateFlow<Set<Long>>(emptySet())

    /** Profile ids with a measurement in flight. */
    public val testing: StateFlow<Set<Long>> = _testing.asStateFlow()

    public fun markTesting(profileIds: Collection<Long>) {
        _testing.update { current -> current + profileIds }
    }

    /** Records one measurement and clears that row's in-flight flag. */
    public fun put(
        profileId: Long,
        result: LatencyResult,
    ) {
        _results.update { current -> current + (profileId to result) }
        _testing.update { current -> current - profileId }
    }

    /**
     * Ends a run: any row still marked testing goes back to idle.
     *
     * It deliberately does **not** write a result for those rows. A cancelled or
     * superseded measurement produced no number, and recording one — even a
     * failure — would claim a test happened that did not.
     */
    public fun finish(profileIds: Collection<Long>) {
        _testing.update { current -> current - profileIds.toSet() }
    }
}
