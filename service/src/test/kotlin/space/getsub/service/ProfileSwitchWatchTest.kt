// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import io.kotest.matchers.shouldBe
import org.junit.Test

/** M8.6 Part 3 D1 and review focus 1: which `active_profile_id` changes move a live session. */
class ProfileSwitchWatchTest {
    @Test
    fun `no live session never requests a switch`() {
        switchRequested(liveRowId = null, activeRowId = 3L, activeRunnable = true) shouldBe false
    }

    @Test
    fun `the live server picked again is not a switch`() {
        switchRequested(liveRowId = 3L, activeRowId = 3L, activeRunnable = true) shouldBe false
    }

    @Test
    fun `a different active server is a switch`() {
        switchRequested(liveRowId = 3L, activeRowId = 4L, activeRunnable = true) shouldBe true
    }

    @Test
    fun `no active server is not a switch`() {
        switchRequested(liveRowId = 3L, activeRowId = null, activeRunnable = true) shouldBe false
    }

    /**
     * Controller ruling on Task 1 review: a row this build can't run (undecodable, or
     * `connectable == false`) never moves a live session. The pick persists; the tunnel
     * stays on the server it is running.
     */
    @Test
    fun `an unrunnable active server is not a switch`() {
        switchRequested(liveRowId = 3L, activeRowId = 4L, activeRunnable = false) shouldBe false
    }

    @Test
    fun `an unrunnable pick with no live session is still not a switch`() {
        switchRequested(liveRowId = null, activeRowId = 4L, activeRunnable = false) shouldBe false
    }
}
