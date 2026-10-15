// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service.preresolve

import space.getsub.core.data.preresolve.PreResolveDecision
import space.getsub.core.data.preresolve.PreResolveSource
import space.getsub.core.network.preresolve.PreResolution
import space.getsub.core.network.preresolve.PreResolveReason
import space.getsub.core.network.preresolve.ResolverConfig
import space.getsub.core.xray.AddressPinInjector
import space.getsub.core.xray.ServerAddressPin

/**
 * M9-C spec §3.1: decide, resolve, pin. Never throws and never fails the start. Every log line carries
 * outcome, source, reason, counts and millis only (ARCHITECTURE.md §5.6, plan ruling R1).
 */
internal class PreResolveStep(
    private val decide: suspend (Long) -> PreResolveDecision,
    private val resolve: suspend (String, Int, ResolverConfig) -> PreResolution,
    private val log: (String) -> Unit,
) {
    // One early return per way the step ends without resolving: off, or a raw config that never names the host.
    @Suppress("ReturnCount")
    suspend fun run(
        rowId: Long,
        host: String,
        port: Int,
        rawJsonRunsAsWritten: String?,
    ): ServerAddressPin? {
        val decision = decide(rowId)
        if (decision is PreResolveDecision.Off) {
            if (decision.reason != PreResolveReason.Off) log(logLine(null, PreResolution.Skipped(decision.reason)))
            return null
        }
        decision as PreResolveDecision.Run
        if (rawJsonRunsAsWritten != null && !AddressPinInjector.matches(rawJsonRunsAsWritten, host)) {
            log(logLine(decision.source, PreResolution.Skipped(PreResolveReason.ConfigNotRecognised)))
            return null
        }
        val result = resolve(host, port, decision.config)
        if (result !is PreResolution.Skipped || result.reason != PreResolveReason.AlreadyIp) {
            log(logLine(decision.source, result))
        }
        return (result as? PreResolution.Resolved)?.let { ServerAddressPin(domain = host, ip = it.ip) }
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
}
