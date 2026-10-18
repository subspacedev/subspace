// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.model

import java.math.BigInteger
import java.security.MessageDigest

/** [address] is normalised: lowercase, compressed IPv6, network bits only. */
public data class CidrRange(
    val address: String,
    val prefixLength: Int,
    val isV6: Boolean,
) {
    override fun toString(): String = "$address/$prefixLength"
}

/** Routes the user keeps out of the tunnel (research R3). */
@Suppress("TooManyFunctions")
public object ExcludedRoutes {
    private const val V4_BITS = 32
    private const val V6_BITS = 128
    private const val V4_OCTETS = 4
    private const val V6_GROUPS = 8
    private const val GROUP_BITS = 16
    private const val OCTET_BITS = 8
    private val SEPARATORS = Regex("[,\\s]+")
    private const val HEX_RADIX = 16
    private const val MAX_PREFIX_DIGITS = 3
    private const val GROUP_MASK = 0xFFFF

    public fun parse(text: String): TuningChecked<List<CidrRange>> {
        val ranges = mutableListOf<CidrRange>()
        for (token in text.split(SEPARATORS).filter { it.isNotEmpty() }) {
            val range = parseEntry(token) ?: return TuningChecked.Invalid(TuningProblem.BadValue("exclude-routes"))
            ranges += range
        }
        return TuningChecked.Ok(ranges.distinct())
    }

    /**
     * The problems with [ranges], each naming the range's index.
     *
     * [TuningProblem.RangeTooWide]: a `/0` range, the whole IPv4 or IPv6 space. Containing the TUN's
     * own addresses is not a problem (controller ruling): `10.0.0.0/8` is the common LAN exclusion,
     * and nothing in the app dials the TUN's addresses.
     *
     * [TuningProblem.RangeCoversDns]: the range contains one of [dnsAddresses]; entries that are not
     * IP literals are skipped.
     */
    public fun validate(
        ranges: List<CidrRange>,
        dnsAddresses: List<String>,
    ): List<TuningProblem> {
        val dns = dnsAddresses.mapNotNull { parseAddress(it.trim()) }
        val problems = mutableListOf<TuningProblem>()
        ranges.forEachIndexed { index, range ->
            if (range.prefixLength == 0) problems += TuningProblem.RangeTooWide(index)
            if (dns.any { contains(range, it) }) problems += TuningProblem.RangeCoversDns(index)
        }
        return problems
    }

    public fun fingerprint(ranges: List<CidrRange>): String {
        val joined = ranges.map { "${it.address}/${it.prefixLength}" }.sorted().joinToString("\n")
        return MessageDigest
            .getInstance("SHA-256")
            .digest(joined.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    /** The minimal set of routes covering the whole family except [excluded]. */
    public fun complement(
        excluded: List<CidrRange>,
        v6: Boolean,
    ): List<CidrRange> {
        val bits = if (v6) V6_BITS else V4_BITS
        val blocks =
            excluded
                .filter { it.isV6 == v6 }
                .mapNotNull { r -> parseAddress(r.address)?.let { Block(it.value, r.prefixLength) } }
        val out = mutableListOf<CidrRange>()
        split(Block(BigInteger.ZERO, 0), blocks, bits, v6, out)
        return out
    }

    /** Whether [inner] lies wholly inside [outer]. Ranges of different families never cover each other. */
    public fun covers(
        outer: CidrRange,
        inner: CidrRange,
    ): Boolean {
        val outerStart = parseAddress(outer.address)?.value
        val innerStart = parseAddress(inner.address)?.value
        if (outerStart == null || innerStart == null || outer.isV6 != inner.isV6) return false
        val bits = if (outer.isV6) V6_BITS else V4_BITS
        return outerStart <= innerStart &&
            endOf(Block(innerStart, inner.prefixLength), bits) <= endOf(Block(outerStart, outer.prefixLength), bits)
    }

    private class Block(
        val start: BigInteger,
        val length: Int,
    )

    private class Address(
        val value: BigInteger,
        val isV6: Boolean,
    )

    private fun split(
        block: Block,
        excluded: List<Block>,
        bits: Int,
        v6: Boolean,
        out: MutableList<CidrRange>,
    ) {
        val end = block.start + BigInteger.ONE.shiftLeft(bits - block.length) - BigInteger.ONE
        val covered = excluded.any { it.start <= block.start && end <= endOf(it, bits) }
        val disjoint = excluded.none { it.start <= end && block.start <= endOf(it, bits) }
        when {
            covered -> Unit
            disjoint -> out += CidrRange(format(block.start, v6), block.length, v6)
            else -> {
                val half = BigInteger.ONE.shiftLeft(bits - block.length - 1)
                split(Block(block.start, block.length + 1), excluded, bits, v6, out)
                split(Block(block.start + half, block.length + 1), excluded, bits, v6, out)
            }
        }
    }

    private fun endOf(
        block: Block,
        bits: Int,
    ): BigInteger = block.start + BigInteger.ONE.shiftLeft(bits - block.length) - BigInteger.ONE

    private fun contains(
        range: CidrRange,
        address: Address,
    ): Boolean {
        val start = parseAddress(range.address)?.value
        if (start == null || range.isV6 != address.isV6) return false
        val bits = if (range.isV6) V6_BITS else V4_BITS
        val end = start + BigInteger.ONE.shiftLeft(bits - range.prefixLength) - BigInteger.ONE
        return address.value in start..end
    }

    @Suppress("ReturnCount")
    private fun parseEntry(token: String): CidrRange? {
        val slash = token.indexOf('/')
        val address = parseAddress(if (slash >= 0) token.substring(0, slash) else token) ?: return null
        val bits = if (address.isV6) V6_BITS else V4_BITS
        val prefix = if (slash >= 0) prefixOf(token.substring(slash + 1)) else bits
        if (prefix == null || prefix > bits) return null
        val hostBits = bits - prefix
        val network = address.value.shiftRight(hostBits).shiftLeft(hostBits)
        return CidrRange(format(network, address.isV6), prefix, address.isV6)
    }

    private fun prefixOf(digits: String): Int? =
        digits.takeIf { it.length in 1..MAX_PREFIX_DIGITS && it.all { c -> c in '0'..'9' } }?.toInt()

    private fun parseAddress(text: String): Address? =
        when {
            IpLiteral.isV4(text) -> Address(v4Value(text), false)
            IpLiteral.isV6(text) -> Address(v6Value(text), true)
            else -> null
        }

    private fun v4Value(text: String): BigInteger =
        text.split('.').fold(BigInteger.ZERO) { acc, octet ->
            acc.shiftLeft(OCTET_BITS) + BigInteger.valueOf(octet.toLong())
        }

    private fun v6Value(text: String): BigInteger {
        val doubleColon = text.indexOf("::")
        val head = if (doubleColon >= 0) text.substring(0, doubleColon) else text
        val tail = if (doubleColon >= 0) text.substring(doubleColon + 2) else ""
        val headGroups = groups(head)
        val tailGroups = groups(tail)
        val missing = if (doubleColon >= 0) V6_GROUPS - headGroups.size - tailGroups.size else 0
        val zeros = List(missing) { BigInteger.ZERO }
        return (headGroups + zeros + tailGroups).fold(BigInteger.ZERO) { acc, g -> acc.shiftLeft(GROUP_BITS) + g }
    }

    private fun groups(part: String): List<BigInteger> {
        if (part.isEmpty()) return emptyList()
        return part.split(':').flatMap { piece ->
            if ('.' in piece) {
                val v4 = parseAddress(piece)?.value ?: BigInteger.ZERO
                listOf(v4.shiftRight(GROUP_BITS), v4.and(BigInteger.valueOf(GROUP_MASK.toLong())))
            } else {
                listOf(BigInteger(piece, HEX_RADIX))
            }
        }
    }

    private fun format(
        value: BigInteger,
        v6: Boolean,
    ): String = if (v6) formatV6(value) else formatV4(value)

    private fun formatV4(value: BigInteger): String {
        val octets = (V4_OCTETS - 1 downTo 0).map { chunk(value, it, OCTET_BITS) }
        return octets.joinToString(".")
    }

    private fun chunk(
        value: BigInteger,
        index: Int,
        width: Int,
    ): Int = value.shiftRight(index * width).and(BigInteger.ONE.shiftLeft(width) - BigInteger.ONE).toInt()

    // RFC 5952: lowercase hex, the longest run of two or more zero groups (first on a tie) becomes "::".
    private fun formatV6(value: BigInteger): String {
        val groups = (V6_GROUPS - 1 downTo 0).map { chunk(value, it, GROUP_BITS) }
        var bestStart = -1
        var bestLength = 0
        var i = 0
        while (i < V6_GROUPS) {
            if (groups[i] == 0) {
                var j = i
                while (j < V6_GROUPS && groups[j] == 0) j++
                if (j - i > bestLength) {
                    bestStart = i
                    bestLength = j - i
                }
                i = j
            } else {
                i++
            }
        }
        if (bestLength < 2) return groups.joinToString(":") { it.toString(HEX_RADIX) }
        val left = groups.take(bestStart).joinToString(":") { it.toString(HEX_RADIX) }
        val right = groups.drop(bestStart + bestLength).joinToString(":") { it.toString(HEX_RADIX) }
        return "$left::$right"
    }
}
