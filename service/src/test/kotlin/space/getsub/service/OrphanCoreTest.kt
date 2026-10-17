// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.Test
import space.getsub.core.xray.XrayException

/**
 * Ruling P10: an orphaned core in `:bg` makes every later connect fail, because libXray
 * v26.10.10's `testXray` refuses while the process has a managed instance — and that refusal is
 * named as a Terminal `ConfigRejected`/`PassthroughRejectedAtConnect`. These pin the two decisions
 * `TunnelService` runs to keep one from being left behind or from blocking the next start.
 * `TunnelService` itself needs a device; the native calls are faked as lambdas.
 */
class OrphanCoreTest {
    // ── (a) resolveAndStartCore's catch ───────────────────────────────────────

    @Test
    fun `a failure after the core started stops it while the start still owns it`() =
        runTest {
            var stops = 0
            stopCoreLeftByFailedStart(started = true, stillOwned = { true }) { stops++ } shouldBe true
            stops shouldBe 1
        }

    @Test
    fun `a failure before the core started stops nothing`() =
        runTest {
            var stops = 0
            stopCoreLeftByFailedStart(started = false, stillOwned = { true }) { stops++ } shouldBe false
            stops shouldBe 0
        }

    @Test
    fun `a failure after a newer session took over leaves the process-wide stop alone`() =
        runTest {
            // stopXray is process-global: calling it here could stop the newer session's core.
            // The newer start's own sweep handles anything this attempt left running.
            var stops = 0
            stopCoreLeftByFailedStart(started = true, stillOwned = { false }) { stops++ } shouldBe false
            stops shouldBe 0
        }

    // ── (b) the sweep before validate ─────────────────────────────────────────

    @Test
    fun `a running core no session owns is stopped before validating`() =
        runTest {
            var stops = 0
            sweepOrphanedCore(ownsStartSlot = { true }, isRunning = { true }) { stops++ } shouldBe true
            stops shouldBe 1
        }

    @Test
    fun `nothing running means nothing stopped`() =
        runTest {
            var stops = 0
            sweepOrphanedCore(ownsStartSlot = { true }, isRunning = { false }) { stops++ } shouldBe false
            stops shouldBe 0
        }

    @Test
    fun `a start that no longer owns the slot neither asks nor stops`() =
        runTest {
            var asked = false
            var stops = 0
            sweepOrphanedCore(
                ownsStartSlot = { false },
                isRunning = {
                    asked = true
                    true
                },
            ) { stops++ } shouldBe false
            asked shouldBe false
            stops shouldBe 0
        }

    @Test
    fun `losing the slot while the state is read stops nothing`() =
        runTest {
            var owns = true
            var stops = 0
            sweepOrphanedCore(
                ownsStartSlot = { owns },
                isRunning = {
                    // A newer session bumps the generation during getXrayState.
                    owns = false
                    true
                },
            ) { stops++ } shouldBe false
            stops shouldBe 0
        }

    @Test
    fun `an unreadable core state is left to validate rather than thrown`() =
        runTest {
            var stops = 0
            sweepOrphanedCore(
                ownsStartSlot = { true },
                isRunning = { throw XrayException("libXray getXrayState failed") },
            ) { stops++ } shouldBe false
            stops shouldBe 0
        }
}
