// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.data

import io.kotest.matchers.shouldBe
import org.junit.Test
import space.getsub.core.parser.RedirectRejection

class RedirectStatusTest {
    private val current = "https://old.example/s"

    @Test
    fun nothingSentIsNone() {
        redirectStatusOf(current, emptyMap(), emptySet()) shouldBe RedirectStatus.NONE
    }

    @Test
    fun everyValidTargetHostIsListedOnceInKeyOrder() {
        redirectStatusOf(
            current,
            mapOf(
                "new-domain" to "new.example",
                "fallback-url" to "https://backup.example/s",
                "new-url" to "https://new.example/x",
            ),
            emptySet(),
        ).targetHosts shouldBe listOf("new.example", "backup.example")
    }

    @Test
    fun invalidTargetsBecomeIssuesNotHosts() {
        val status = redirectStatusOf(current, mapOf("fallback-url" to "http://b.example/s"), emptySet())
        status.targetHosts shouldBe emptyList()
        status.issues shouldBe listOf(RedirectIssue("fallback-url", RedirectRejection.NotHttps, collides = false))
    }

    @Test
    fun aMoveOntoAnotherSubscriptionIsACollision() {
        val status =
            redirectStatusOf(
                current,
                mapOf("new-url" to "https://taken.example/s"),
                setOf("https://taken.example/s"),
            )
        status.issues shouldBe listOf(RedirectIssue("new-url", reason = null, collides = true))
    }

    @Test
    fun aNewDomainOntoAnotherSubscriptionIsACollision() {
        val status = redirectStatusOf(current, mapOf("new-domain" to "taken.example"), setOf("https://taken.example/s"))
        status.issues shouldBe listOf(RedirectIssue("new-domain", reason = null, collides = true))
        status.targetHosts shouldBe emptyList()
    }

    @Test
    fun aTargetEqualToTheCurrentUrlIsNotListed() {
        redirectStatusOf(current, mapOf("new-url" to current), emptySet()) shouldBe RedirectStatus.NONE
    }

    @Test
    fun aFallbackOntoAnotherSubscriptionIsNotACollision() {
        val status =
            redirectStatusOf(
                current,
                mapOf("fallback-url" to "https://taken.example/s"),
                setOf("https://taken.example/s"),
            )
        status.issues shouldBe emptyList()
        status.targetHosts shouldBe listOf("taken.example")
    }
}
