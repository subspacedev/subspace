// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.model

public enum class Udp443Mode(
    public val wire: String,
) {
    Reject("reject"),
    Allow("allow"),
    Skip("skip"),
}

public data class MuxSpec(
    val concurrency: Int,
    val xudpConcurrency: Int,
    val udp443: Udp443Mode,
)

/** Which outbounds mux.cool can serve (research R2.6). */
public object MuxApplicability {
    public fun applies(outbound: Outbound): Boolean =
        when (outbound) {
            is VlessOutbound -> outbound.flow.isNullOrBlank() && !isXhttp(outbound.stream)
            is VmessOutbound -> !isXhttp(outbound.stream)
            is TrojanOutbound -> !isXhttp(outbound.stream)
            // No stream in the model: always eligible.
            is ShadowsocksOutbound -> true
            is SocksOutbound, is HttpOutbound, is Hysteria2Outbound, is WireGuardOutbound -> false
        }

    private fun isXhttp(stream: StreamSettings): Boolean {
        val network = stream.network.lowercase()
        return network == "xhttp" || network == "splithttp"
    }
}

public object MuxRules {
    private const val MIN_CONNECTIONS = -1
    private const val MAX_CONNECTIONS = 1024

    public fun check(
        tcp: String,
        xudp: String,
        quic: String,
    ): TuningChecked<MuxSpec> {
        val mode = Udp443Mode.entries.firstOrNull { it.wire == quic }
        val tcpValue = connections(tcp)
        val xudpValue = connections(xudp)
        return when {
            tcpValue !is TuningChecked.Ok -> tcpValue as TuningChecked.Invalid
            xudpValue !is TuningChecked.Ok -> xudpValue as TuningChecked.Invalid
            mode == null -> TuningChecked.Invalid(TuningProblem.BadValue("quic"))
            else -> TuningChecked.Ok(MuxSpec(tcpValue.value, xudpValue.value, mode))
        }
    }

    private fun connections(text: String): TuningChecked<Int> {
        val parsed = text.trim().toIntOrNull()
        return when {
            parsed == null -> TuningChecked.Invalid(TuningProblem.BadValue("connections"))
            parsed !in MIN_CONNECTIONS..MAX_CONNECTIONS ->
                TuningChecked.Invalid(TuningProblem.OutOfRange(MIN_CONNECTIONS, MAX_CONNECTIONS))
            else -> TuningChecked.Ok(parsed)
        }
    }
}
