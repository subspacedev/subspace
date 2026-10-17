// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import io.kotest.matchers.shouldBe
import org.junit.Test

/** When `:ping` ends its own process (Ruling P8). */
class PingServiceIdleTest {
    @Test
    fun pingExitsOnUnbindWhenIdle() {
        var exits = 0
        val idle = ExitWhenIdle { exits++ }

        idle.onBind()
        idle.batchQueued()
        idle.batchDone()
        exits shouldBe 0 // still bound: :bg's grace is running

        idle.onUnbind()
        exits shouldBe 1
    }

    @Test
    fun pingExitsAfterTheInFlightBatchWhenBusy() {
        var exits = 0
        val idle = ExitWhenIdle { exits++ }

        idle.onBind()
        idle.batchQueued()
        idle.batchQueued()
        idle.onUnbind()
        exits shouldBe 0

        idle.batchDone()
        exits shouldBe 0
        idle.batchDone()
        exits shouldBe 1
    }

    @Test
    fun aRebindBeforeTheBatchFinishesKeepsTheProcess() {
        var exits = 0
        val idle = ExitWhenIdle { exits++ }

        idle.onBind()
        idle.batchQueued()
        idle.onUnbind()
        idle.onBind()
        idle.batchDone()

        exits shouldBe 0
    }
}
