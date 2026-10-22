// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.data.serialization

import io.kotest.matchers.shouldBe
import org.junit.Test
import space.getsub.core.data.ProfileKind
import space.getsub.core.parser.SubscriptionParser

/** M10-B spec §2: a provider's caption and name are display text; they never move a server's identity. */
class CaptionIdentityTest {
    private val base = "trojan://pw@host.example:443"

    private fun hashOf(link: String): String {
        val profile = SubscriptionParser.parse(link).profiles.single()
        return identityHashOf(profile.outbound, ProfileKind.TYPED)
    }

    @Test
    fun `name and caption do not change the identity hash`() {
        val plain = hashOf("$base#Berlin")

        hashOf("$base#Other") shouldBe plain
        hashOf("$base#Berlin?serverDescription=SGFwcCB0aGUgYmVzdA==") shouldBe plain
        hashOf(base) shouldBe plain
    }
}
