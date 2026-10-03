// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.home

import space.getsub.core.model.ConnectionState
import space.getsub.core.model.FailureReason
import space.getsub.core.model.Health
import space.getsub.core.model.PerAppMode
import space.getsub.core.model.PerAppSelection
import space.getsub.core.model.StartupStage

/** The line under Home's label (M8.6 spec §3.3). Resolved to text by the screen. */
internal sealed interface HomeDetail {
    data object TapToConnect : HomeDetail

    data class Step(val number: Int, val of: Int) : HomeDetail

    data class Uptime(val sinceEpochMillis: Long, val idle: Boolean) : HomeDetail

    data object NothingComingBack : HomeDetail

    data object NotProtectedTapToStop : HomeDetail

    data object KillSwitchHolding : HomeDetail

    data object TearingDown : HomeDetail

    /** The redacted detail (ARCHITECTURE.md §5.6); M8.6 Part 2 ruling R3. */
    data class FailureText(val text: String) : HomeDetail
}

internal fun ConnectionState.detail(): HomeDetail =
    when (this) {
        ConnectionState.Disconnected -> HomeDetail.TapToConnect
        is ConnectionState.Connecting -> HomeDetail.Step(stage.ordinal + 1, StartupStage.entries.size)
        is ConnectionState.Connected ->
            when (health) {
                Health.Stalled -> HomeDetail.NothingComingBack
                Health.Idle -> HomeDetail.Uptime(sinceEpochMillis, idle = true)
                Health.Open -> HomeDetail.Uptime(sinceEpochMillis, idle = false)
            }
        is ConnectionState.Reconnecting ->
            if (blocked) HomeDetail.KillSwitchHolding else HomeDetail.NotProtectedTapToStop
        ConnectionState.Disconnecting -> HomeDetail.TearingDown
        is ConnectionState.Failed -> HomeDetail.FailureText(detail)
    }

/** M8.6 spec §5.5: the only state with the blocked banner. */
internal fun ConnectionState.isBlocked(): Boolean = this is ConnectionState.Reconnecting && blocked

internal enum class HomePrimaryAction { Connect, Disconnect, None }

/**
 * The button under the blob (M8.6 Part 2 ruling R1). Connecting and
 * Disconnecting offer nothing, as the replaced `ConnectControl` refused those taps.
 * Reconnecting offers Disconnect: M8 spec §7.3, it has no bound.
 */
internal fun HomeState.primaryAction(): HomePrimaryAction =
    when (connection) {
        ConnectionState.Disconnected, is ConnectionState.Failed -> HomePrimaryAction.Connect
        is ConnectionState.Connected, is ConnectionState.Reconnecting -> HomePrimaryAction.Disconnect
        is ConnectionState.Connecting, ConnectionState.Disconnecting -> HomePrimaryAction.None
    }

internal sealed interface AppsChip {
    data object AllApps : AppsChip

    data class AllowList(val count: Int) : AppsChip

    data class DenyList(val count: Int) : AppsChip
}

/** From the **effective** selection (`PerAppRepository.selection`), so an empty deny-list reads as all apps. */
internal fun PerAppSelection.toAppsChip(): AppsChip =
    when (mode) {
        PerAppMode.Off -> AppsChip.AllApps
        PerAppMode.AllowList -> AppsChip.AllowList(packages.size)
        PerAppMode.DenyList -> AppsChip.DenyList(packages.size)
    }

internal fun ConnectionState.labelRes(): Int =
    when (this) {
        is ConnectionState.Disconnected -> R.string.state_disconnected
        is ConnectionState.Disconnecting -> R.string.state_disconnecting
        is ConnectionState.Connected ->
            if (health == Health.Stalled) R.string.home_state_stalled else R.string.state_connected
        is ConnectionState.Connecting -> stage.labelRes()
        is ConnectionState.Failed -> reason.labelRes()
        // M8.5 spec §4.2 (amended): blocked is published state, read here and by the
        // notification alike. Still one steady label per value rather than cycling
        // through each retry's reason.
        is ConnectionState.Reconnecting ->
            if (blocked) R.string.home_state_reconnecting_blocked else R.string.home_state_reconnecting_open
    }

private fun StartupStage.labelRes(): Int =
    when (this) {
        StartupStage.AllocatingPort -> R.string.stage_allocating_port
        StartupStage.GeneratingConfig -> R.string.stage_generating_config
        StartupStage.ValidatingConfig -> R.string.stage_validating_config
        StartupStage.StartingCore -> R.string.stage_starting_core
        StartupStage.EstablishingTun -> R.string.stage_establishing_tun
        StartupStage.StartingTunnel -> R.string.stage_starting_tunnel
    }

// One exhaustive enum-to-resource mapping; splitting it would only hide the same branches.
@Suppress("CyclomaticComplexMethod")
private fun FailureReason.labelRes(): Int =
    when (this) {
        FailureReason.ConfigGenerationFailed -> R.string.failure_config_generation
        FailureReason.ConfigRejected -> R.string.failure_config_rejected
        FailureReason.PortAllocationFailed -> R.string.failure_port_allocation
        FailureReason.CoreStartFailed -> R.string.failure_core_start
        FailureReason.VpnPermissionMissing -> R.string.failure_vpn_permission
        FailureReason.TunEstablishFailed -> R.string.failure_tun_establish
        FailureReason.TunnelStartFailed -> R.string.failure_tunnel_start
        FailureReason.Revoked -> R.string.failure_revoked
        FailureReason.ProtocolNotSupported -> R.string.failure_protocol_not_supported
        FailureReason.ProfileDecodeFailed -> R.string.failure_profile_decode
        FailureReason.GeoDataMissing -> R.string.failure_geo_data_missing
        FailureReason.PerAppAllowListEmpty -> R.string.failure_per_app_allow_list_empty
        FailureReason.PassthroughOverrideUnavailable -> R.string.failure_passthrough_override_unavailable
        FailureReason.PassthroughRejectedAtConnect -> R.string.failure_passthrough_rejected_at_connect
    }
