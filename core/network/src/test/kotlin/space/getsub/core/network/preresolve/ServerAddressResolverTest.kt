// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.network.preresolve

import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ServerAddressResolverTest {
    private val config = ResolverConfig("https://dns.example/dns-query", "1.1.1.1", fragment = null)
    private var raced: List<InetAddress>? = null

    // Stands in for OkHttp's system-resolver lookup of the DoH host: blocks a real thread, ignores cancellation.
    private val blocked = CountDownLatch(1)
    private val blockingLookup: suspend () -> List<InetAddress> = {
        @Suppress("BlockingMethodInNonBlockingContext")
        blocked.await()
        emptyList()
    }

    @After fun releaseBlockedThread() = blocked.countDown()

    private fun ip(s: String) = InetAddress.getByName(s)

    private fun millisSince(startNanos: Long) = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos)

    private fun resolver(
        answer: suspend () -> List<InetAddress>,
        winner: (List<InetAddress>) -> InetAddress? = { it.first() },
        lookupDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) = ServerAddressResolver(
        lookups = { DohLookup { answer() } },
        racer = ConnectRacer { c, _, _ ->
            raced = c
            winner(c)
        },
        lookupDispatcher = lookupDispatcher,
    )

    // Under runTest the lookup must share the virtual clock, or the budget elapses before real IO work runs.
    private fun TestScope.testResolver(
        answer: suspend () -> List<InetAddress>,
        winner: (List<InetAddress>) -> InetAddress? = { it.first() },
    ) = resolver(answer, winner, StandardTestDispatcher(testScheduler))

    @Test fun anIpLiteralIsSkipped() = runTest {
        testResolver({ error("no lookup") }).resolve("203.0.113.5", 443, config) shouldBe
            PreResolution.Skipped(PreResolveReason.AlreadyIp)
    }

    @Test fun oneAddressIsUsedWithoutProbing() = runTest {
        val r = testResolver({ listOf(ip("203.0.113.5")) }).resolve("srv.example", 443, config)
        r.shouldBeInstanceOf<PreResolution.Resolved>().ip shouldBe "203.0.113.5"
        raced shouldBe null
    }

    @Test fun severalAreRacedCappedAtEightInterleavedV4First() = runTest {
        val v4 = (1..6).map { ip("203.0.113.$it") }
        val v6 = (1..6).map { ip("2001:db8::$it") }
        testResolver({ v6 + v4 }).resolve("srv.example", 443, config)
        raced!!.size shouldBe 8
        raced!!.map { it is Inet4Address } shouldBe listOf(true, false, true, false, true, false, true, false)
    }

    @Test fun theRaceWinnerIsChosen() = runTest {
        val r = testResolver({ listOf(ip("203.0.113.1"), ip("203.0.113.2")) }, winner = { it[1] })
            .resolve("srv.example", 443, config)
        r.shouldBeInstanceOf<PreResolution.Resolved>().ip shouldBe "203.0.113.2"
    }

    @Test fun aUdpServerTakesTheFirstAnswerWithoutRacing() = runTest {
        val r = testResolver({ listOf(ip("2001:db8::1"), ip("203.0.113.1"), ip("203.0.113.2")) }, winner = { it[1] })
            .resolve("srv.example", 443, config, race = false)
        val resolved = r.shouldBeInstanceOf<PreResolution.Resolved>()
        resolved.ip shouldBe ip("2001:db8::1").hostAddress
        resolved.raced shouldBe false
        raced shouldBe null
    }

    @Test fun aUdpServerWithNoAnswerStillFails() = runTest {
        testResolver({ emptyList() }).resolve("srv.example", 443, config, race = false)
            .shouldBeInstanceOf<PreResolution.Failed>().reason shouldBe PreResolveReason.NoAddresses
    }

    @Test fun nobodyAnswersIsNoneReachable() = runTest {
        val r = testResolver({ listOf(ip("203.0.113.1"), ip("2001:db8::1")) }, winner = { null })
            .resolve("srv.example", 443, config)
        r.shouldBeInstanceOf<PreResolution.Failed>().reason shouldBe PreResolveReason.NoneReachable
    }

    @Test fun emptyAnswerIsNoAddresses() = runTest {
        testResolver({ emptyList() }).resolve("srv.example", 443, config)
            .shouldBeInstanceOf<PreResolution.Failed>().reason shouldBe PreResolveReason.NoAddresses
    }

    @Test fun ioErrorIsDohError() = runTest {
        testResolver({ throw IOException("x") }).resolve("srv.example", 443, config)
            .shouldBeInstanceOf<PreResolution.Failed>().reason shouldBe PreResolveReason.DohError
    }

    @Test fun anUnexpectedExceptionIsDohError() = runTest {
        testResolver({ throw IllegalStateException("x") }).resolve("srv.example", 443, config)
            .shouldBeInstanceOf<PreResolution.Failed>().reason shouldBe PreResolveReason.DohError
    }

    @Test fun slowDohIsDohTimeout() = runTest {
        testResolver({
            delay(10_000)
            emptyList()
        }).resolve("srv.example", 443, config)
            .shouldBeInstanceOf<PreResolution.Failed>().reason shouldBe PreResolveReason.DohTimeout
    }

    @Test fun aLookupBlockingAThreadIsCutOffAtTheBudget() = runBlocking<Unit> {
        val start = System.nanoTime()
        val r = resolver(blockingLookup).resolve("srv.example", 443, config)
        val took = millisSince(start)
        val failed = r.shouldBeInstanceOf<PreResolution.Failed>()
        failed.reason shouldBe PreResolveReason.DohTimeout
        failed.candidates shouldBe 0
        took shouldBeLessThan 4_000L
        (took >= DOH_TIMEOUT_MILLIS) shouldBe true
    }

    @Test fun cancellingTheCallerWhileTheLookupBlocksReturnsPromptly() = runBlocking<Unit> {
        val pending = async { resolver(blockingLookup).resolve("srv.example", 443, config) }
        delay(200)
        val start = System.nanoTime()
        withTimeout(2_000) { pending.cancelAndJoin() }
        millisSince(start) shouldBeLessThan 1_000L
        pending.isCancelled shouldBe true
        runCatching { pending.await() }.exceptionOrNull().shouldBeInstanceOf<CancellationException>()
    }

    @Test fun aTimeoutThrownByTheLookupBeforeTheBudgetIsDohError() = runTest {
        testResolver({
            withTimeout(1) { delay(10) }
            emptyList()
        }).resolve("srv.example", 443, config)
            .shouldBeInstanceOf<PreResolution.Failed>().reason shouldBe PreResolveReason.DohError
    }

    @Test fun toStringsNeverCarryAddressesOrUrls() {
        PreResolution.Resolved("203.0.113.5", 3, 40).toString().contains("203.0.113.5") shouldBe false
        config.toString().contains("dns.example") shouldBe false
        config.toString().contains("1.1.1.1") shouldBe false
    }
}
