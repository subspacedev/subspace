// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.home.launch

/**
 * What the fresh-open automation is doing, as Home shows it (M10-C spec §3.1).
 *
 * [NoServerAnswered] and [ForeignVpnFallback] are transient notes: Home shows each once and then
 * calls [LaunchAutomation.onNoteShown].
 */
sealed interface LaunchAutomationState {
    /** Nothing to show. */
    data object Idle : LaunchAutomationState

    /** A Fastest mode is measuring its candidates: "Finding the fastest server…", with Cancel. */
    data object Finding : LaunchAutomationState

    /** Every candidate failed or was excluded and there is no active server to fall back to. */
    data object NoServerAnswered : LaunchAutomationState

    /** Another app's VPN held the route, so nothing could be measured; the active server was used. */
    data object ForeignVpnFallback : LaunchAutomationState
}
