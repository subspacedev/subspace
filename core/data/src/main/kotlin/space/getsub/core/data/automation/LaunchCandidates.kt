// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.data.automation

import kotlinx.coroutines.flow.first
import space.getsub.core.data.ProfileRepository
import space.getsub.core.data.SubscriptionRepository
import space.getsub.core.model.PingMode
import space.getsub.core.model.pingModeFrom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A server auto-connect may measure and choose.
 *
 * [mode] is the subscription's effective `ping-type`, or null when the global setting applies
 * (a manual group, or a subscription that sent none). [listIndex] is the position on the
 * Servers list, the fastest-pick's last tiebreak.
 *
 * ARCHITECTURE.md §5.6: [name] is provider-chosen, so [toString] leaves it out.
 */
public data class LaunchCandidate(
    val id: Long,
    val groupId: Long,
    val name: String,
    val connectable: Boolean,
    val mode: PingMode?,
    val listIndex: Int,
) {
    override fun toString(): String =
        "LaunchCandidate(id=$id, groupId=$groupId, name=<redacted>, connectable=$connectable, " +
            "mode=$mode, listIndex=$listIndex)"
}

/** Reads the servers auto-connect chooses among (M10-C spec §3.1). */
@Singleton
public class LaunchCandidates
@Inject
constructor(
    private val profiles: ProfileRepository,
    private val subscriptions: SubscriptionRepository,
) {
    /** Every server in every group, in list order. */
    public suspend fun all(): List<LaunchCandidate> = read(groupId = null)

    /** The servers of [groupId], with indexes that match their place in [all]. */
    public suspend fun inGroup(groupId: Long): List<LaunchCandidate> = read(groupId)

    private suspend fun read(groupId: Long?): List<LaunchCandidate> {
        var index = 0
        val out = mutableListOf<LaunchCandidate>()
        for (group in profiles.observeGroups().first()) {
            // Counted for every group, so a group's indexes are the same whichever way it was read.
            val start = index
            index += group.profiles.size
            if (groupId != null && group.id != groupId) continue
            val mode = modeOf(group.id)
            group.profiles.forEachIndexed { i, p ->
                out += LaunchCandidate(p.id, group.id, p.name, p.connectable, mode, start + i)
            }
        }
        return out
    }

    /** `ping-type` is scoped to the subscription that delivered it (ARCHITECTURE.md §A.1). */
    private suspend fun modeOf(groupId: Long): PingMode? {
        val subscriptionId = subscriptions.subscriptionIdForGroup(groupId) ?: return null
        return subscriptions.effective(subscriptionId, KEY_PING_TYPE, default = null).value?.let(::pingModeFrom)
    }
}

private const val KEY_PING_TYPE = "ping-type"
