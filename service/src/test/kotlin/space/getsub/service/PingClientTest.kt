// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import android.os.DeadObjectException
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import space.getsub.core.model.LatencyOutcome
import space.getsub.core.model.LatencyResult
import space.getsub.core.xray.PingTarget

/**
 * Allocates an Android exception without running its constructor.
 *
 * `android.jar`'s stubs throw "Stub!" from every constructor on the JVM, and the
 * point of the binder-death test is that [PingClient] reacts to the real
 * [DeadObjectException] type a dead `:ping` raises — not to a stand-in.
 */
internal fun deadObject(): DeadObjectException {
    val field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }
    val unsafe = field.get(null)
    val allocate = unsafe.javaClass.getMethod("allocateInstance", Class::class.java)
    return allocate.invoke(unsafe, DeadObjectException::class.java) as DeadObjectException
}

/**
 * A [PingConnector] whose "service" is a lambda. Connects synchronously; a test
 * drives death through [listener].
 */
internal class FakePingConnector(
    var remote: (PingBatchParcel, (PingBatchResultParcel?) -> Unit) -> Unit = { request, reply ->
        reply(PingBatchResultParcel(request.keys, IntArray(request.keys.size) { 7 }, IntArray(request.keys.size) { 0 }))
    },
    private val refuse: Boolean = false,
) : PingConnector {
    var binds = 0
    var unbinds = 0
    var listener: PingConnector.Listener? = null

    override fun bind(listener: PingConnector.Listener): PingConnector.Binding? {
        if (refuse) return null
        binds++
        this.listener = listener
        listener.onConnected { request, reply -> remote(request, reply) }
        return PingConnector.Binding { unbinds++ }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PingClientTest {
    private val batch = listOf(PingTarget(1, "{a}"), PingTarget(2, "{b}"))
    private val failed = LatencyResult.failed(LatencyOutcome.UNREACHABLE)
    private val bothFailed = mapOf(1L to failed, 2L to failed)

    @Test
    fun resultsComeBackKeyedByTarget() =
        runTest {
            val connector = FakePingConnector()
            val client = PingClient(connector, TestScope(testScheduler))

            client.measure(batch, 5, "https://x.invalid") shouldContainExactly
                mapOf(1L to LatencyResult.ok(7), 2L to LatencyResult.ok(7))
            connector.binds shouldBe 1
        }

    @Test
    fun theRequestCarriesTheBatchVerbatim() =
        runTest {
            var seen: PingBatchParcel? = null
            val connector =
                FakePingConnector(remote = { request, reply ->
                    seen = request
                    reply(null)
                })

            PingClient(connector, TestScope(testScheduler)).measure(batch, 4, "https://x.invalid")

            val request = checkNotNull(seen)
            request.keys.toList() shouldContainExactly listOf(1L, 2L)
            request.configs.toList() shouldContainExactly listOf("{a}", "{b}")
            request.timeoutSeconds shouldBe 4
            request.url shouldBe "https://x.invalid"
        }

    @Test
    fun binderDeathFailsInFlightServersAndNextRunRebinds() =
        runTest {
            val connector = FakePingConnector(remote = { _, _ -> throw deadObject() })
            val client = PingClient(connector, TestScope(testScheduler))

            client.measure(batch, 5, "https://x.invalid") shouldContainExactly bothFailed
            // The binding is dropped, so BIND_AUTO_CREATE cannot resurrect :ping on
            // its own (spec §9.7)…
            connector.unbinds shouldBe 1

            // …and the next run starts it again.
            connector.remote = { request, reply ->
                reply(PingBatchResultParcel(request.keys, intArrayOf(30, 40), intArrayOf(0, 0)))
            }
            client.measure(batch, 5, "https://x.invalid") shouldContainExactly
                mapOf(1L to LatencyResult.ok(30), 2L to LatencyResult.ok(40))
            connector.binds shouldBe 2
        }

    @Test
    fun aDisconnectWhileWaitingFailsTheInFlightBatch() =
        runTest {
            // :ping accepted the oneway call, then was killed before answering.
            val connector = FakePingConnector(remote = { _, _ -> })
            val client = PingClient(connector, TestScope(testScheduler))

            val pending = async { client.measure(batch, 5, "https://x.invalid") }
            runCurrent()
            connector.listener!!.onDisconnected()

            pending.await() shouldContainExactly bothFailed
            connector.unbinds shouldBe 1
        }

    @Test
    fun aRefusedBindFailsTheBatch() =
        runTest {
            val client = PingClient(FakePingConnector(refuse = true), TestScope(testScheduler))

            client.measure(batch, 5, "https://x.invalid") shouldContainExactly bothFailed
        }

    @Test
    fun aServiceThatNeverAnswersFailsTheBatchAfterTheWatchdog() =
        runTest {
            val client = PingClient(FakePingConnector(remote = { _, _ -> }), TestScope(testScheduler))

            val pending = async { client.measure(batch, 5, "https://x.invalid") }
            advanceTimeBy(PingClient.replyWatchdogMillis(5) + 1)

            pending.await() shouldContainExactly bothFailed
        }

    @Test
    fun aWatchdogTimeoutDropsTheWedgedSessionSoTheNextBatchBindsAgain() =
        runTest {
            val connector = FakePingConnector(remote = { _, _ -> })
            val client = PingClient(connector, TestScope(testScheduler))

            val first = async { client.measure(batch, 5, "https://x.invalid") }
            advanceTimeBy(PingClient.replyWatchdogMillis(5) + 1)
            first.await() shouldContainExactly bothFailed
            connector.unbinds shouldBe 1

            val second = async { client.measure(batch, 5, "https://x.invalid") }
            runCurrent()
            connector.binds shouldBe 2
            second.cancel()
        }

    @Test
    fun closeUnbindsAtOnceAndFailsWhatIsStillWaiting() =
        runTest {
            val connector = FakePingConnector(remote = { _, _ -> })
            val client = PingClient(connector, TestScope(testScheduler))

            val pending = async { client.measure(batch, 5, "https://x.invalid") }
            runCurrent()
            client.close()

            pending.await() shouldContainExactly bothFailed
            connector.unbinds shouldBe 1
            client.close()
            connector.unbinds shouldBe 1
        }

    @Test
    fun onlyOneBatchIsInFlightAtATime() =
        runTest {
            val replies = mutableListOf<() -> Unit>()
            val connector =
                FakePingConnector(remote = { request, reply ->
                    val size = request.keys.size
                    replies += { reply(PingBatchResultParcel(request.keys, IntArray(size), IntArray(size))) }
                })
            val client = PingClient(connector, TestScope(testScheduler))

            val first = async { client.measure(batch, 5, "https://x.invalid") }
            val second = async { client.measure(batch, 5, "https://x.invalid") }
            runCurrent()
            // pingBatch holds coreServerMu in :ping; a second queued batch there
            // would outlive a supersession (spec §9.3).
            replies.size shouldBe 1
            replies.removeAt(0)()
            runCurrent()
            replies.size shouldBe 1
            replies.removeAt(0)()
            first.await()
            second.await()
        }

    @Test
    fun aResultForAnotherKeyOrAnUnknownOutcomeIsAFailureNotANumber() =
        runTest {
            val connector =
                FakePingConnector(remote = { _, reply ->
                    reply(PingBatchResultParcel(longArrayOf(1, 99), intArrayOf(10000, 5), intArrayOf(42, 0)))
                })

            val client = PingClient(connector, TestScope(testScheduler))

            client.measure(batch, 5, "https://x.invalid") shouldContainExactly bothFailed
        }

    // ── The grace before unbinding (Ruling P8) ─────────────────────────────────

    @Test
    fun theIdleUnbindFiresOnlyAfterTheGraceFollowingTheLastBatch() =
        runTest {
            val connector = FakePingConnector()
            val client = PingClient(connector, TestScope(testScheduler))

            client.measure(batch, 5, "https://x.invalid")
            advanceTimeBy(10 * IdleTimer.IDLE_GRACE_MILLIS)
            runCurrent()
            // Bound and idle with no run ended: nothing lets go.
            connector.unbinds shouldBe 0

            client.releaseAfterGrace() // the run ended
            advanceTimeBy(IdleTimer.IDLE_GRACE_MILLIS - 1)
            runCurrent()
            connector.unbinds shouldBe 0
            advanceTimeBy(1)
            runCurrent()
            connector.unbinds shouldBe 1
        }

    @Test
    fun aMeasureInsideTheGraceCancelsTheUnbindAndDoesNotRebind() =
        runTest {
            val connector = FakePingConnector()
            val client = PingClient(connector, TestScope(testScheduler))

            client.measure(batch, 5, "https://x.invalid")
            client.releaseAfterGrace()
            advanceTimeBy(IdleTimer.IDLE_GRACE_MILLIS - 1)
            client.measure(batch, 5, "https://x.invalid") shouldContainExactly
                mapOf(1L to LatencyResult.ok(7), 2L to LatencyResult.ok(7))
            advanceTimeBy(10 * IdleTimer.IDLE_GRACE_MILLIS)
            runCurrent()

            connector.binds shouldBe 1
            connector.unbinds shouldBe 0

            // The next run's end starts a full grace of its own.
            client.releaseAfterGrace()
            advanceTimeBy(IdleTimer.IDLE_GRACE_MILLIS)
            runCurrent()
            connector.unbinds shouldBe 1
        }

    @Test
    fun aMeasureAfterTheGraceExpiredBindsAgain() =
        runTest {
            val connector = FakePingConnector()
            val client = PingClient(connector, TestScope(testScheduler))

            client.measure(batch, 5, "https://x.invalid")
            client.releaseAfterGrace()
            advanceTimeBy(IdleTimer.IDLE_GRACE_MILLIS)
            runCurrent()
            client.measure(batch, 5, "https://x.invalid")

            connector.binds shouldBe 2
        }

    // ── Abandoned batches keep the slot (review fix round 1) ──────────────────

    /** Three superseded callers, each cancelled after its batch reached an unanswering :ping. */
    private fun TestScope.abandonThree(
        client: PingClient,
    ): List<Job> =
        List(3) {
            val caller = launch { client.measure(batch, 5, "https://x.invalid") }
            runCurrent()
            caller.cancel()
            runCurrent()
            caller
        }

    @Test
    fun cancelledCallersReturnAtOnceButTheNextBatchWaitsForTheAbandonedOneToSettle() =
        runTest {
            val replies = mutableListOf<(PingBatchResultParcel?) -> Unit>()
            val connector = FakePingConnector(remote = { _, reply -> replies += reply })
            val client = PingClient(connector, TestScope(testScheduler))

            val abandoned = abandonThree(client)
            // Each cancelled caller returned promptly…
            abandoned.all { it.isCompleted } shouldBe true
            // …but only the first batch was ever sent: the other two were still
            // queued in :bg when their runs were superseded.
            replies.size shouldBe 1

            val live = async { client.measure(batch, 5, "https://x.invalid") }
            runCurrent()
            replies.size shouldBe 1 // not sent behind a batch :ping is still running

            replies[0](PingBatchResultParcel(longArrayOf(1, 2), intArrayOf(9, 9), intArrayOf(0, 0)))
            runCurrent()
            replies.size shouldBe 2
            replies[1](PingBatchResultParcel(longArrayOf(1, 2), intArrayOf(30, 40), intArrayOf(0, 0)))

            live.await() shouldContainExactly mapOf(1L to LatencyResult.ok(30), 2L to LatencyResult.ok(40))
        }

    @Test
    fun anAbandonedBatchThatNeverAnswersHoldsTheSlotOnlyUntilItsWatchdog() =
        runTest {
            val replies = mutableListOf<(PingBatchResultParcel?) -> Unit>()
            val connector = FakePingConnector(remote = { _, reply -> replies += reply })
            val client = PingClient(connector, TestScope(testScheduler))

            abandonThree(client)
            val live = async { client.measure(batch, 5, "https://x.invalid") }
            advanceTimeBy(PingClient.replyWatchdogMillis(5) - 1)
            runCurrent()
            replies.size shouldBe 1

            advanceTimeBy(1)
            runCurrent()
            replies.size shouldBe 2
            // The live batch gets a full watchdog of its own, not the remainder.
            replies[1](PingBatchResultParcel(longArrayOf(1, 2), intArrayOf(5, 6), intArrayOf(0, 0)))
            live.await() shouldContainExactly mapOf(1L to LatencyResult.ok(5), 2L to LatencyResult.ok(6))
        }

    @Test
    fun deathOfAnAbandonedBatchFreesTheSlot() =
        runTest {
            val replies = mutableListOf<(PingBatchResultParcel?) -> Unit>()
            val connector = FakePingConnector(remote = { _, reply -> replies += reply })
            val client = PingClient(connector, TestScope(testScheduler))

            abandonThree(client)
            val live = async { client.measure(batch, 5, "https://x.invalid") }
            runCurrent()
            connector.listener!!.onDisconnected() // :ping killed mid-batch
            runCurrent()

            // The live batch rebinds and is sent.
            connector.binds shouldBe 2
            replies.size shouldBe 2
            replies[1](PingBatchResultParcel(longArrayOf(1, 2), intArrayOf(5, 6), intArrayOf(0, 0)))
            live.await() shouldContainExactly mapOf(1L to LatencyResult.ok(5), 2L to LatencyResult.ok(6))
        }
}
