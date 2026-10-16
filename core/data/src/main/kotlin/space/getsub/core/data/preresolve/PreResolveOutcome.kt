// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.data.preresolve

import space.getsub.core.network.preresolve.PreResolution
import space.getsub.core.network.preresolve.PreResolveReason
import space.getsub.core.network.preresolve.ResolverConfig

/**
 * What the connect stage needs back from [ServerPreResolution.run]: the chosen IP, or null to keep the
 * domain, and the one log line, if any. Never a host. Only `:core:data`, `:core:model` and JDK types, so
 * `:service` needs no `:core:network` (ARCHITECTURE.md §4).
 */
public data class PreResolveOutcome(
    val ip: String?,
    val logLine: String?,
) {
    // ARCHITECTURE.md §5.6: the chosen IP identifies the server.
    override fun toString(): String =
        "PreResolveOutcome(ip=${if (ip == null) "none" else "<redacted>"}, logLine=$logLine)"
}

/**
 * M9-C spec §3.1: decide, match, resolve. Never throws (bar cancellation) and never fails the start.
 * Every log line carries outcome, source, reason, counts and millis only (ARCHITECTURE.md §5.6, plan
 * ruling R1). [decide] and [resolve] are the seams the unit tests replace.
 */
// One early return per way the step ends without resolving: off, or a raw config that never names the host.
@Suppress("ReturnCount", "LongParameterList")
internal suspend fun runPreResolution(
    rowId: Long,
    host: String,
    port: Int,
    rawJsonRunsAsWritten: String?,
    configNamesHost: (rawJson: String, host: String) -> Boolean,
    decide: suspend (Long) -> PreResolveDecision,
    resolve: suspend (String, Int, ResolverConfig) -> PreResolution,
): PreResolveOutcome {
    val decision = decide(rowId)
    if (decision is PreResolveDecision.Off) {
        val silent = decision.reason == PreResolveReason.Off
        return PreResolveOutcome(
            ip = null,
            logLine = if (silent) null else logLine(decision.source, PreResolution.Skipped(decision.reason)),
        )
    }
    decision as PreResolveDecision.Run
    if (rawJsonRunsAsWritten != null && !configNamesHost(rawJsonRunsAsWritten, host)) {
        return PreResolveOutcome(
            ip = null,
            logLine = logLine(decision.source, PreResolution.Skipped(PreResolveReason.ConfigNotRecognised)),
        )
    }
    val result = resolve(host, port, decision.config)
    val silent = result is PreResolution.Skipped && result.reason == PreResolveReason.AlreadyIp
    return PreResolveOutcome(
        ip = (result as? PreResolution.Resolved)?.ip,
        logLine = if (silent) null else logLine(decision.source, result),
    )
}

internal fun logLine(
    source: PreResolveSource?,
    result: PreResolution,
): String =
    when (result) {
        is PreResolution.Resolved ->
            "preresolve outcome=Resolved source=$source candidates=${result.candidates} ms=${result.millis}"
        is PreResolution.Failed ->
            "preresolve outcome=Failed source=$source reason=${result.reason} " +
                "candidates=${result.candidates} ms=${result.millis}"
        is PreResolution.Skipped -> "preresolve outcome=Skipped source=$source reason=${result.reason}"
    }
