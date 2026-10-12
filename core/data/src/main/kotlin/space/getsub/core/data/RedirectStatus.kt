// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.data

import space.getsub.core.parser.RedirectCheck
import space.getsub.core.parser.RedirectRejection
import space.getsub.core.parser.RedirectTargets

private val REDIRECT_KEYS = listOf("new-url", "new-domain", "fallback-url")

/** Why a stored redirect directive is not acted on: an invalid target, or a move onto another subscription. */
public data class RedirectIssue(
    val key: String,
    val reason: RedirectRejection?,
    val collides: Boolean,
)

/**
 * What subscription details and the Servers card show about redirects (plan rulings P2, P3): the hosts
 * a trust request names, and the stored keys that would be ignored. Computed live, never stored.
 */
public data class RedirectStatus(
    val targetHosts: List<String>,
    val issues: List<RedirectIssue>,
) {
    public companion object {
        public val NONE: RedirectStatus = RedirectStatus(emptyList(), emptyList())
    }
}

/** Lists the valid targets and ignored keys of [directives], given the other subscriptions' [otherUrls]. */
public fun redirectStatusOf(
    currentUrl: String,
    directives: Map<String, String>,
    otherUrls: Set<String>,
): RedirectStatus {
    val hosts = linkedSetOf<String>()
    val issues = mutableListOf<RedirectIssue>()
    REDIRECT_KEYS.forEach { key ->
        val value = directives[key] ?: return@forEach
        val check =
            if (key == "new-domain") {
                RedirectTargets.applyDomain(currentUrl, value)
            } else {
                RedirectTargets.checkUrl(value)
            }
        when (check) {
            is RedirectCheck.Rejected -> issues += RedirectIssue(key, check.reason, collides = false)
            is RedirectCheck.Ok ->
                if (key != "fallback-url" && check.url in otherUrls) {
                    issues += RedirectIssue(key, reason = null, collides = true)
                } else if (check.url != currentUrl) {
                    hosts += check.host
                }
        }
    }
    return RedirectStatus(hosts.toList(), issues)
}
