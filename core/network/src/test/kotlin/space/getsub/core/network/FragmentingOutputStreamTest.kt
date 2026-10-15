// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.network

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.shouldBe
import org.junit.Test
import space.getsub.core.model.FragmentPackets
import space.getsub.core.model.FragmentSpec
import space.getsub.core.model.XrayRange
import java.io.OutputStream

/** Records every write the fragmenter makes, one entry per call, plus every sleep. */
private class Recorder : OutputStream() {
    val writes = mutableListOf<ByteArray>()
    override fun write(b: Int) { writes += byteArrayOf(b.toByte()) }
    override fun write(b: ByteArray, off: Int, len: Int) { writes += b.copyOfRange(off, off + len) }
}

/** Always picks the low end of a range, so piece sizes and delays are deterministic. */
private class LowClock : FragmentClock {
    val sleeps = mutableListOf<Long>()
    override fun sleepMillis(millis: Long) { sleeps += millis }
    override fun nextInt(from: Int, toInclusive: Int): Int = from
}

private fun record(payload: Int, type: Int = 0x16): ByteArray =
    byteArrayOf(type.toByte(), 0x03, 0x01, (payload shr 8).toByte(), (payload and 0xff).toByte()) +
        ByteArray(payload) { (it % 251).toByte() }

private fun spec(
    packets: FragmentPackets = FragmentPackets.TlsHello,
    length: IntRange = 10..10,
    delay: IntRange = 7..7,
    maxSplit: IntRange? = null,
) = FragmentSpec(
    packets,
    XrayRange(length.first, length.last),
    XrayRange(delay.first, delay.last),
    maxSplit?.let { XrayRange(it.first, it.last) },
)

class FragmentingOutputStreamTest {
    @Test
    fun tlshelloReframesTheFirstRecordIntoPieces() {
        val sink = Recorder()
        val clock = LowClock()
        val hello = record(payload = 25)

        FragmentingOutputStream(sink, spec(), clock).write(hello, 0, hello.size)

        // 25 payload bytes in pieces of 10, 10, 5, each with its own 5-byte record header.
        sink.writes.map { it.size } shouldContainExactly listOf(15, 15, 10)
        sink.writes.forEach { piece ->
            piece[0] shouldBe 0x16.toByte()
            piece[1] shouldBe 0x03.toByte()
            piece[2] shouldBe 0x01.toByte()
            ((piece[3].toInt() and 0xff) shl 8 or (piece[4].toInt() and 0xff)) shouldBe piece.size - 5
        }
        // The payload survives byte for byte once the headers are stripped.
        sink.writes.flatMap { it.drop(5) }.toByteArray().toList() shouldBe hello.drop(5)
        clock.sleeps shouldContainExactly listOf(7L, 7L)
    }

    @Test
    fun maxSplitPutsTheRemainderInTheLastPiece() {
        val sink = Recorder()
        val hello = record(payload = 45)

        FragmentingOutputStream(sink, spec(maxSplit = 2..2), LowClock()).write(hello, 0, hello.size)

        sink.writes.map { it.size } shouldContainExactly listOf(15, 40)
    }

    @Test
    fun maxSplitZeroIsUnlimited() {
        val sink = Recorder()
        val hello = record(payload = 30)

        FragmentingOutputStream(sink, spec(maxSplit = 0..0), LowClock()).write(hello, 0, hello.size)

        sink.writes.size shouldBe 3
    }

    @Test
    fun onlyTheFirstWriteIsSplitInTlshelloMode() {
        val sink = Recorder()
        val stream = FragmentingOutputStream(sink, spec(), LowClock())
        val hello = record(payload = 25)
        val later = record(payload = 25, type = 0x17)

        stream.write(hello, 0, hello.size)
        stream.write(later, 0, later.size)

        sink.writes.last().toList() shouldBe later.toList()
    }

    @Test
    fun aFirstWriteThatIsNotAHandshakeRecordPassesThrough() {
        val sink = Recorder()
        val data = record(payload = 25, type = 0x17)

        FragmentingOutputStream(sink, spec(), LowClock()).write(data, 0, data.size)

        sink.writes.map { it.toList() } shouldContainExactly listOf(data.toList())
    }

    @Test
    fun aPartialRecordPassesThroughUntouched() {
        val sink = Recorder()
        val truncated = record(payload = 25).copyOf(20) // header claims 25, only 15 payload bytes present

        FragmentingOutputStream(sink, spec(), LowClock()).write(truncated, 0, truncated.size)

        sink.writes.map { it.toList() } shouldContainExactly listOf(truncated.toList())
    }

    @Test
    fun aWriteShorterThanAHeaderPassesThrough() {
        val sink = Recorder()
        val tiny = byteArrayOf(0x16, 0x03, 0x01)

        FragmentingOutputStream(sink, spec(), LowClock()).write(tiny, 0, tiny.size)

        sink.writes.map { it.toList() } shouldContainExactly listOf(tiny.toList())
    }

    @Test
    fun bytesAfterTheFirstRecordFollowUnchanged() {
        val sink = Recorder()
        val first = record(payload = 12)
        val second = record(payload = 3)
        val both = first + second

        FragmentingOutputStream(sink, spec(), LowClock()).write(both, 0, both.size)

        sink.writes.map { it.size } shouldContainExactly listOf(15, 7, second.size)
        sink.writes.last().toList() shouldBe second.toList()
    }

    @Test
    fun recordsModeSplitsTheNamedWritesRawWithoutHeaders() {
        val sink = Recorder()
        val stream =
            FragmentingOutputStream(sink, spec(packets = FragmentPackets.Records(XrayRange(2, 2))), LowClock())
        val one = ByteArray(12) { 1 }
        val two = ByteArray(12) { 2 }

        stream.write(one, 0, one.size)
        stream.write(two, 0, two.size)

        sink.writes.map { it.size } shouldContainExactly listOf(12, 10, 2)
        sink.writes[1].all { it == 2.toByte() } shouldBe true
    }
}

class SystemFragmentClockTest {
    @Test fun `a draw over the whole Int range does not overflow`() {
        repeat(200) {
            SystemFragmentClock.nextInt(1, Int.MAX_VALUE) shouldBeInRange (1..Int.MAX_VALUE)
        }
        SystemFragmentClock.nextInt(Int.MAX_VALUE, Int.MAX_VALUE) shouldBe Int.MAX_VALUE
    }
}
