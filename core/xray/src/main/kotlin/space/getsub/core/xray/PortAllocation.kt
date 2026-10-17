// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.xray

/**
 * Bounded so a genuinely exhausted ephemeral range fails fast rather than
 * spinning, rather than looping until one shows up (see [allocateDistinctPorts]).
 */
private const val MAX_ALLOCATE_DISTINCT_ATTEMPTS = 3

/**
 * Requests [count] free ports from [fetch], retrying when the result is not
 * distinct.
 *
 * libXray v26.10.10's `GetFreePorts` keeps every listener open until it returns, so one call
 * yields distinct ports (`docs/agent/research/libxray-api.md` §2); v26.7.11 closed each before
 * opening the next and could return `[38411, 38411]` from a single `count = 2` call. Two Xray
 * inbounds sharing a port is a config the core rejects outright, so this still checks
 * distinctness itself, as a guard that costs nothing, rather than trusting the call.
 *
 * A free function rather than a method on [XrayController], for the same
 * reason [PingBatchCall] is a seam under [PingBatch]: the
 * underlying libXray call ([LibXrayInvoke], backed by native `LibXray.invoke`)
 * cannot run on the JVM, but the retry loop that consumes it can, with [fetch]
 * faked — see `PortAllocationTest`.
 *
 * @param fetch requests exactly [count] ports from the underlying source. May
 *   itself return duplicates or a short list; both are treated as a failed
 *   attempt and retried.
 * @throws XrayException when [count] distinct ports could not be obtained
 *   within [MAX_ALLOCATE_DISTINCT_ATTEMPTS] attempts.
 */
internal suspend fun allocateDistinctPorts(
    count: Int,
    fetch: suspend (Int) -> List<Int>,
): List<Int> {
    repeat(MAX_ALLOCATE_DISTINCT_ATTEMPTS) {
        val ports = fetch(count)
        if (ports.size == count && ports.toSet().size == count) return ports
    }
    throw XrayException("libXray could not return $count distinct free ports")
}
