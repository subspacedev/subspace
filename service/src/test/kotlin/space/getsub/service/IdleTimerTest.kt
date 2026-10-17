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

/** `:bg`'s grace before unbinding `:ping` (Ruling P8), on the test scheduler's clock. */
@OptIn(ExperimentalCoroutinesApi::class)
class IdleTimerTest {
    @Test
    fun theGracePeriodIsThirtySeconds() {
        IdleTimer.IDLE_GRACE_MILLIS shouldBe 30_000L
    }

    @Test
    fun expiryFiresOnlyAfterArmPlusGrace() =
        runTest {
            var exits = 0
            val idle = IdleTimer(TestScope(testScheduler)) { exits++ }

            // Bound and busy: no timer runs, however long.
            advanceTimeBy(10 * IdleTimer.IDLE_GRACE_MILLIS)
            runCurrent()
            exits shouldBe 0

            idle.arm() // a run ended
            advanceTimeBy(IdleTimer.IDLE_GRACE_MILLIS - 1)
            runCurrent()
            exits shouldBe 0
            advanceTimeBy(1)
            runCurrent()
            exits shouldBe 1
        }

    @Test
    fun rebindCancelsIdleTimer() =
        runTest {
            var exits = 0
            val idle = IdleTimer(TestScope(testScheduler)) { exits++ }

            idle.arm()
            advanceTimeBy(IdleTimer.IDLE_GRACE_MILLIS - 1)
            idle.disarm() // the next batch
            advanceTimeBy(10 * IdleTimer.IDLE_GRACE_MILLIS)
            runCurrent()
            exits shouldBe 0

            // The next arm starts a full grace period, not the remainder.
            idle.arm()
            advanceTimeBy(IdleTimer.IDLE_GRACE_MILLIS - 1)
            runCurrent()
            exits shouldBe 0
            advanceTimeBy(1)
            runCurrent()
            exits shouldBe 1
        }

    @Test
    fun aSecondArmRestartsTheTimerRatherThanStackingTwo() =
        runTest {
            var exits = 0
            val idle = IdleTimer(TestScope(testScheduler)) { exits++ }

            idle.arm()
            advanceTimeBy(IdleTimer.IDLE_GRACE_MILLIS / 2)
            idle.arm()
            advanceTimeBy(IdleTimer.IDLE_GRACE_MILLIS - 1)
            runCurrent()
            exits shouldBe 0
            advanceTimeBy(10 * IdleTimer.IDLE_GRACE_MILLIS)
            runCurrent()
            exits shouldBe 1
        }
}
