// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.data.sync

import space.getsub.core.model.Checked
import space.getsub.core.model.FragmentSettings
import space.getsub.core.model.FragmentSpec
import space.getsub.core.model.ObfuscationValidation
import space.getsub.core.network.FetchFailure
import space.getsub.core.parser.LinkFragment
import space.getsub.core.parser.RedirectCheck
import space.getsub.core.parser.RedirectRejection
import space.getsub.core.parser.RedirectTargets
import java.net.URI

internal sealed interface MoveDecision {
    data object None : MoveDecision

    data class Move(val url: String, val fromHost: String) : MoveDecision {
        override fun toString(): String = "Move(url=<redacted>, fromHost=<redacted>)"
    }

    data class Rejected(val reason: RedirectRejection) : MoveDecision
}

/** Plan ruling P4. HWID answers are the server talking; a backup address would say the same. */
private val FALLBACK_FAILURES =
    setOf(
        FetchFailure.NotFound,
        FetchFailure.ClientError,
        FetchFailure.ServerError,
        FetchFailure.TimedOut,
        FetchFailure.Unreachable,
        FetchFailure.TlsFailure,
    )

/** The syncer's decisions with no I/O, so they are tested on the JVM (M9-B spec §1.3, §3.3, §3.4). */
internal object FetchDecisions {
    /** M9-B spec §1.3: the user's switch, else the link's `fragment=`, else none. */
    fun fetchFragment(
        user: FragmentSettings,
        linkFragment: String?,
    ): FragmentSpec? =
        if (user.enabled) {
            ObfuscationValidation.fragmentOrDefault(user)
        } else {
            linkFragment?.let { (LinkFragment.parse(it) as? Checked.Ok)?.value }
        }

    fun fallbackEligible(failure: FetchFailure): Boolean = failure in FALLBACK_FAILURES

    /** M9-B spec §3.4 steps 2–3, minus the collision check, which needs the database. */
    fun moveTarget(
        currentPlainUrl: String,
        newUrl: String?,
        newDomain: String?,
    ): MoveDecision {
        val check =
            when {
                newUrl != null -> RedirectTargets.checkUrl(newUrl)
                newDomain != null -> RedirectTargets.applyDomain(currentPlainUrl, newDomain)
                else -> return MoveDecision.None
            }
        return when (check) {
            is RedirectCheck.Rejected -> MoveDecision.Rejected(check.reason)
            is RedirectCheck.Ok ->
                if (check.url == currentPlainUrl) {
                    MoveDecision.None
                } else {
                    MoveDecision.Move(check.url, runCatching { URI(currentPlainUrl).host }.getOrNull().orEmpty())
                }
        }
    }
}
