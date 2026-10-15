// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.network.preresolve

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress

class ServerAddressResolverTest {
    private val config = ResolverConfig("https://dns.example/dns-query", "1.1.1.1", fragment = null)
    private var raced: List<InetAddress>? = null

    private fun ip(s: String) = InetAddress.getByName(s)

    private fun resolver(
        answer: suspend () -> List<InetAddress>,
        winner: (List<InetAddress>) -> InetAddress? = { it.first() },
    ) = ServerAddressResolver(
        lookups = { DohLookup { answer() } },
        racer = ConnectRacer { c, _, _ ->
            raced = c
            winner(c)
        },
    )

    @Test fun anIpLiteralIsSkipped() = runTest {
        resolver({ error("no lookup") }).resolve("203.0.113.5", 443, config) shouldBe
            PreResolution.Skipped(PreResolveReason.AlreadyIp)
    }

    @Test fun oneAddressIsUsedWithoutProbing() = runTest {
        val r = resolver({ listOf(ip("203.0.113.5")) }).resolve("srv.example", 443, config)
        r.shouldBeInstanceOf<PreResolution.Resolved>().ip shouldBe "203.0.113.5"
        raced shouldBe null
    }

    @Test fun severalAreRacedCappedAtEightInterleavedV4First() = runTest {
        val v4 = (1..6).map { ip("203.0.113.$it") }
        val v6 = (1..6).map { ip("2001:db8::$it") }
        resolver({ v6 + v4 }).resolve("srv.example", 443, config)
        raced!!.size shouldBe 8
        raced!!.map { it is Inet4Address } shouldBe listOf(true, false, true, false, true, false, true, false)
    }

    @Test fun theRaceWinnerIsChosen() = runTest {
        val r = resolver({ listOf(ip("203.0.113.1"), ip("203.0.113.2")) }, winner = { it[1] })
            .resolve("srv.example", 443, config)
        r.shouldBeInstanceOf<PreResolution.Resolved>().ip shouldBe "203.0.113.2"
    }

    @Test fun nobodyAnswersIsNoneReachable() = runTest {
        val r = resolver({ listOf(ip("203.0.113.1"), ip("2001:db8::1")) }, winner = { null })
            .resolve("srv.example", 443, config)
        r.shouldBeInstanceOf<PreResolution.Failed>().reason shouldBe PreResolveReason.NoneReachable
    }

    @Test fun emptyAnswerIsNoAddresses() = runTest {
        resolver({ emptyList() }).resolve("srv.example", 443, config)
            .shouldBeInstanceOf<PreResolution.Failed>().reason shouldBe PreResolveReason.NoAddresses
    }

    @Test fun ioErrorIsDohError() = runTest {
        resolver({ throw IOException("x") }).resolve("srv.example", 443, config)
            .shouldBeInstanceOf<PreResolution.Failed>().reason shouldBe PreResolveReason.DohError
    }

    @Test fun anUnexpectedExceptionIsDohError() = runTest {
        resolver({ throw IllegalStateException("x") }).resolve("srv.example", 443, config)
            .shouldBeInstanceOf<PreResolution.Failed>().reason shouldBe PreResolveReason.DohError
    }

    @Test fun slowDohIsDohTimeout() = runTest {
        resolver({
            delay(10_000)
            emptyList()
        }).resolve("srv.example", 443, config)
            .shouldBeInstanceOf<PreResolution.Failed>().reason shouldBe PreResolveReason.DohTimeout
    }

    @Test fun toStringsNeverCarryAddressesOrUrls() {
        PreResolution.Resolved("203.0.113.5", 3, 40).toString().contains("203.0.113.5") shouldBe false
        config.toString().contains("dns.example") shouldBe false
        config.toString().contains("1.1.1.1") shouldBe false
    }
}
