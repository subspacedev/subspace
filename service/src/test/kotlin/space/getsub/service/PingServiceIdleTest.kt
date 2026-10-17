// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** The `:ping` process's idle exit (spec §9.7), on the test scheduler's clock. */
@OptIn(ExperimentalCoroutinesApi::class)
class PingServiceIdleTest {
    @Test
    fun theGracePeriodIsThirtySeconds() {
        IdleExit.IDLE_GRACE_MILLIS shouldBe 30_000L
    }

    @Test
    fun idleExitFiresOnlyAfterUnbindPlusGrace() =
        runTest {
            var exits = 0
            val idle = IdleExit(TestScope(testScheduler)) { exits++ }

            // Bound and busy: no timer runs, however long.
            advanceTimeBy(10 * IdleExit.IDLE_GRACE_MILLIS)
            runCurrent()
            exits shouldBe 0

            idle.arm() // onUnbind
            advanceTimeBy(IdleExit.IDLE_GRACE_MILLIS - 1)
            runCurrent()
            exits shouldBe 0
            advanceTimeBy(1)
            runCurrent()
            exits shouldBe 1
        }

    @Test
    fun rebindCancelsIdleExit() =
        runTest {
            var exits = 0
            val idle = IdleExit(TestScope(testScheduler)) { exits++ }

            idle.arm()
            advanceTimeBy(IdleExit.IDLE_GRACE_MILLIS - 1)
            idle.disarm() // onRebind / onBind
            advanceTimeBy(10 * IdleExit.IDLE_GRACE_MILLIS)
            runCurrent()
            exits shouldBe 0

            // The next unbind starts a full grace period, not the remainder.
            idle.arm()
            advanceTimeBy(IdleExit.IDLE_GRACE_MILLIS - 1)
            runCurrent()
            exits shouldBe 0
            advanceTimeBy(1)
            runCurrent()
            exits shouldBe 1
        }

    @Test
    fun aSecondUnbindRestartsTheTimerRatherThanStackingTwo() =
        runTest {
            var exits = 0
            val idle = IdleExit(TestScope(testScheduler)) { exits++ }

            idle.arm()
            advanceTimeBy(IdleExit.IDLE_GRACE_MILLIS / 2)
            idle.arm()
            advanceTimeBy(IdleExit.IDLE_GRACE_MILLIS - 1)
            runCurrent()
            exits shouldBe 0
            advanceTimeBy(10 * IdleExit.IDLE_GRACE_MILLIS)
            runCurrent()
            exits shouldBe 1
        }
}
