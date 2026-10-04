// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import io.kotest.matchers.shouldBe
import org.junit.Test

/** M8.6 Part 3 D1 and review focus 1: which `active_profile_id` changes move a live session. */
class ProfileSwitchWatchTest {
    @Test
    fun `no live session never requests a switch`() {
        switchRequested(liveRowId = null, activeRowId = 3L) shouldBe false
    }

    @Test
    fun `the live server picked again is not a switch`() {
        switchRequested(liveRowId = 3L, activeRowId = 3L) shouldBe false
    }

    @Test
    fun `a different active server is a switch`() {
        switchRequested(liveRowId = 3L, activeRowId = 4L) shouldBe true
    }

    @Test
    fun `no active server is not a switch`() {
        switchRequested(liveRowId = 3L, activeRowId = null) shouldBe false
    }
}
