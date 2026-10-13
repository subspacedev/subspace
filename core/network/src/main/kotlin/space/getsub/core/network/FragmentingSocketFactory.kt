// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.network

import space.getsub.core.model.FragmentPackets
import space.getsub.core.model.FragmentSpec
import space.getsub.core.model.XrayRange
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory
import kotlin.random.Random

private const val TLS_HANDSHAKE: Int = 0x16
private const val TLS_HEADER_BYTES = 5
private const val BYTE_MASK = 0xff
private const val BITS_PER_BYTE = 8
private const val LENGTH_HIGH_OFFSET = 3
private const val LENGTH_LOW_OFFSET = 4

/** Sleeps and draws randoms for [FragmentingOutputStream]; injected so tests are deterministic. */
public interface FragmentClock {
    public fun sleepMillis(millis: Long)

    public fun nextInt(
        from: Int,
        toInclusive: Int,
    ): Int
}

public object SystemFragmentClock : FragmentClock {
    override fun sleepMillis(millis: Long): Unit = Thread.sleep(millis)

    override fun nextInt(
        from: Int,
        toInclusive: Int,
    ): Int = Random.nextLong(from.toLong(), toInclusive.toLong() + 1).toInt()
}

/**
 * Splits a subscription fetch's outgoing bytes the way Xray's Finalmask `fragment` does (M9-B spec
 * §3.2). `tlshello` cuts the first write's TLS handshake record into pieces of `length` bytes, each
 * re-framed as its own record, which is what M9-A's device run saw Xray emit. `a-b` cuts writes `a..b`
 * into raw pieces. Between pieces it sleeps `delay` ms on the writing thread, which is OkHttp's call
 * thread and never the main thread.
 */
internal class FragmentingOutputStream(
    private val sink: OutputStream,
    private val spec: FragmentSpec,
    private val clock: FragmentClock,
) : OutputStream() {
    private var writeIndex = 0

    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    override fun write(
        b: ByteArray,
        off: Int,
        len: Int,
    ) {
        writeIndex++
        when (val packets = spec.packets) {
            FragmentPackets.TlsHello ->
                if (writeIndex == 1) writeHello(b, off, len) else sink.write(b, off, len)
            is FragmentPackets.Records ->
                if (writeIndex in packets.range.from..packets.range.to) {
                    split(b, off, off + len, header = null)
                } else {
                    sink.write(b, off, len)
                }
        }
    }

    override fun flush() = sink.flush()

    override fun close() = sink.close()

    /** Splits only a complete handshake record at the start of the write; anything else passes through. */
    private fun writeHello(
        b: ByteArray,
        off: Int,
        len: Int,
    ) {
        val recordLength =
            if (len >= TLS_HEADER_BYTES && (b[off].toInt() and BYTE_MASK) == TLS_HANDSHAKE) {
                ((b[off + LENGTH_HIGH_OFFSET].toInt() and BYTE_MASK) shl BITS_PER_BYTE) or
                    (b[off + LENGTH_LOW_OFFSET].toInt() and BYTE_MASK)
            } else {
                -1
            }
        if (recordLength < 0 || TLS_HEADER_BYTES + recordLength > len) {
            sink.write(b, off, len)
            return
        }
        val payloadStart = off + TLS_HEADER_BYTES
        val payloadEnd = payloadStart + recordLength
        split(b, payloadStart, payloadEnd, header = byteArrayOf(b[off], b[off + 1], b[off + 2]))
        if (payloadEnd < off + len) sink.write(b, payloadEnd, off + len - payloadEnd)
    }

    /** Writes `b[start, end)` in pieces; with [header], each piece becomes its own TLS record. */
    private fun split(
        b: ByteArray,
        start: Int,
        end: Int,
        header: ByteArray?,
    ) {
        val maxPieces = spec.maxSplit?.let(::draw)?.takeIf { it > 0 } ?: Int.MAX_VALUE
        var pos = start
        var pieces = 0
        while (pos < end) {
            pieces++
            val size = if (pieces == maxPieces) end - pos else minOf(maxOf(1, draw(spec.length)), end - pos)
            if (header == null) {
                sink.write(b, pos, size)
            } else {
                val piece = ByteArray(TLS_HEADER_BYTES + size)
                header.copyInto(piece)
                piece[LENGTH_HIGH_OFFSET] = (size shr BITS_PER_BYTE).toByte()
                piece[LENGTH_LOW_OFFSET] = (size and BYTE_MASK).toByte()
                b.copyInto(piece, TLS_HEADER_BYTES, pos, pos + size)
                sink.write(piece, 0, piece.size)
            }
            sink.flush()
            pos += size
            if (pos < end) clock.sleepMillis(draw(spec.delay).toLong())
        }
    }

    private fun draw(range: XrayRange): Int = clock.nextInt(range.from, range.to)
}

/** A plain socket whose output is fragmented and whose writes are not coalesced by Nagle. */
private class FragmentingSocket(
    private val spec: FragmentSpec,
    private val clock: FragmentClock,
) : Socket() {
    private var wrapped: OutputStream? = null

    override fun connect(
        endpoint: SocketAddress?,
        timeout: Int,
    ) {
        super.connect(endpoint, timeout)
        tcpNoDelay = true
    }

    @Synchronized
    override fun getOutputStream(): OutputStream =
        wrapped ?: FragmentingOutputStream(super.getOutputStream(), spec, clock).also { wrapped = it }
}

/**
 * The fetch's [SocketFactory] when a [FragmentSpec] applies (M9-B spec §1.3). OkHttp only calls the
 * no-argument [createSocket] and connects the socket itself; the others are here for the contract.
 */
public class FragmentingSocketFactory(
    private val spec: FragmentSpec,
    private val clock: FragmentClock = SystemFragmentClock,
) : SocketFactory() {
    override fun createSocket(): Socket = FragmentingSocket(spec, clock)

    override fun createSocket(
        host: String?,
        port: Int,
    ): Socket = createSocket().apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(
        host: String?,
        port: Int,
        localHost: InetAddress?,
        localPort: Int,
    ): Socket =
        createSocket().apply {
            bind(InetSocketAddress(localHost, localPort))
            connect(InetSocketAddress(host, port))
        }

    override fun createSocket(
        host: InetAddress?,
        port: Int,
    ): Socket = createSocket().apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(
        address: InetAddress?,
        port: Int,
        localAddress: InetAddress?,
        localPort: Int,
    ): Socket =
        createSocket().apply {
            bind(InetSocketAddress(localAddress, localPort))
            connect(InetSocketAddress(address, port))
        }
}
