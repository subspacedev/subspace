// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.model.automation

/** Which server "Connect automatically" picks (M10-C spec §3.2). [wire] is the stored value. */
public enum class AutoConnectMode(
    public val wire: String,
) {
    LastPicked("last_picked"),
    FastestInSubscription("fastest_subscription"),
    FastestOverall("fastest_overall"),
}

/** Unknown or missing stored values fall back to [AutoConnectMode.LastPicked], the no-surprise choice. */
public fun autoConnectModeFrom(wire: String?): AutoConnectMode =
    AutoConnectMode.entries.firstOrNull { it.wire == wire } ?: AutoConnectMode.LastPicked

/** Which servers the test-on-open run measures (M10-C spec §3.3). [wire] is the stored value. */
public enum class LaunchTestScope(
    public val wire: String,
) {
    ActiveSubscription("active_subscription"),
    AllServers("all_servers"),
}

/** Unknown or missing stored values fall back to [LaunchTestScope.AllServers]. */
public fun launchTestScopeFrom(wire: String?): LaunchTestScope =
    LaunchTestScope.entries.firstOrNull { it.wire == wire } ?: LaunchTestScope.AllServers
