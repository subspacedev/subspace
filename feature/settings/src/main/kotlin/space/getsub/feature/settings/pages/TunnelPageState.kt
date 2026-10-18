// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.settings.pages

import space.getsub.core.model.ExcludeRoutesUserSetting
import space.getsub.core.model.ExcludedRoutes
import space.getsub.core.model.MuxRules
import space.getsub.core.model.MuxUserSettings
import space.getsub.core.model.ProxyAuthMode
import space.getsub.core.model.ProxyAuthSettings
import space.getsub.core.model.SniffingUserSetting
import space.getsub.core.model.TunMtu
import space.getsub.core.model.TunMtuUserSetting
import space.getsub.core.model.TuningChecked
import space.getsub.core.model.TuningProblem
import space.getsub.core.model.Udp443Mode

/**
 * The Tunnel page's five settings as one draft (M9-E spec §1–§5). The page edits a draft and writes
 * it with one Save, so a session restarts once per save, not once per field.
 */
internal data class TunnelDraft(
    val sniffing: SniffingUserSetting = SniffingUserSetting.DEFAULT,
    val mux: MuxUserSettings = MuxUserSettings.DEFAULT,
    val mtu: TunMtuUserSetting = TunMtuUserSetting.DEFAULT,
    val routes: ExcludeRoutesUserSetting = ExcludeRoutesUserSetting.DEFAULT,
    val proxyAuth: ProxyAuthSettings = ProxyAuthSettings.DEFAULT,
) {
    // ARCHITECTURE.md §5.6: excluded ranges, the MTU and the manual credentials never reach a log.
    override fun toString(): String = "TunnelDraft(<redacted>)"
}

/** The draft's text fields that can be wrong. Excluded routes report per line instead. */
internal enum class TunnelField { MuxTcp, MuxXudp, Mtu, ProxyUser, ProxyPass }

internal sealed interface TunnelFieldError {
    data object NotANumber : TunnelFieldError

    data class OutOfRange(
        val min: Int,
        val max: Int,
    ) : TunnelFieldError

    data object Required : TunnelFieldError

    /** RFC 1929 caps a SOCKS5 user name and password at 255 bytes each. */
    data object TooLong : TunnelFieldError

    data object ControlCharacter : TunnelFieldError
}

internal enum class RouteLineError { NotARange, WholeInternet }

/**
 * Only fields that are in use count: a section whose switch is off keeps its values but cannot
 * block a save ([TunnelDraft.forSave] keeps the stored value instead of an invalid one).
 *
 * [routeLines] is keyed by the 0-based line of the text the user typed.
 */
internal data class TunnelErrors(
    val fields: Map<TunnelField, TunnelFieldError> = emptyMap(),
    val routeLines: Map<Int, RouteLineError> = emptyMap(),
) {
    val isEmpty: Boolean get() = fields.isEmpty() && routeLines.isEmpty()
}

private const val RFC1929_FIELD_MAX_BYTES = 255

/** A known-good value for the mux field not under test, so [MuxRules.check] names one field. */
private const val MUX_PROBE = "8"

internal val TunnelDraft.muxInUse: Boolean get() = mux.override && mux.enabled

internal fun TunnelDraft.errors(): TunnelErrors {
    val fields = mutableMapOf<TunnelField, TunnelFieldError>()
    if (muxInUse) {
        muxTcpError(mux.tcpConnections)?.let { fields[TunnelField.MuxTcp] = it }
        muxXudpError(mux.xudpConnections)?.let { fields[TunnelField.MuxXudp] = it }
    }
    if (mtu.override) mtuError(mtu.value)?.let { fields[TunnelField.Mtu] = it }
    if (proxyAuth.mode == ProxyAuthMode.Manual) {
        credentialError(proxyAuth.manualUser)?.let { fields[TunnelField.ProxyUser] = it }
        credentialError(proxyAuth.manualPass)?.let { fields[TunnelField.ProxyPass] = it }
    }
    val routeLines = if (routes.override) routeLineErrors(routes.ranges) else emptyMap()
    return TunnelErrors(fields, routeLines)
}

/**
 * What Save writes: the draft, except that a field whose section is off and whose text is invalid
 * keeps its stored value. Values survive the switch going off, but an invalid one is never stored.
 */
internal fun TunnelDraft.forSave(stored: TunnelDraft): TunnelDraft {
    val muxValid = muxTcpError(mux.tcpConnections) == null && muxXudpError(mux.xudpConnections) == null
    val mux =
        if (muxInUse || muxValid) {
            mux
        } else {
            mux.copy(tcpConnections = stored.mux.tcpConnections, xudpConnections = stored.mux.xudpConnections)
        }
    val mtu = if (mtu.override || mtuError(mtu.value) == null) mtu else mtu.copy(value = stored.mtu.value)
    val routes =
        if (routes.override || routeLineErrors(routes.ranges).isEmpty()) {
            routes
        } else {
            routes.copy(ranges = stored.routes.ranges)
        }
    val credsValid = credentialError(proxyAuth.manualUser) == null && credentialError(proxyAuth.manualPass) == null
    val proxyAuth =
        if (proxyAuth.mode == ProxyAuthMode.Manual || credsValid) {
            proxyAuth
        } else {
            proxyAuth.copy(manualUser = stored.proxyAuth.manualUser, manualPass = stored.proxyAuth.manualPass)
        }
    return copy(mux = mux, mtu = mtu, routes = routes, proxyAuth = proxyAuth)
}

/** Save is offered when nothing in use is wrong and saving would change something. */
internal fun TunnelDraft.canSave(stored: TunnelDraft): Boolean = errors().isEmpty && forSave(stored) != stored

internal fun mtuError(text: String): TunnelFieldError? = TunMtu.check(text).toFieldError()

/** −1 is valid: don't mux TCP, only apply the UDP 443 rule (research R2.4). */
internal fun muxTcpError(text: String): TunnelFieldError? =
    MuxRules.check(text, MUX_PROBE, Udp443Mode.Reject.wire).toFieldError()

internal fun muxXudpError(text: String): TunnelFieldError? =
    MuxRules.check(MUX_PROBE, text, Udp443Mode.Reject.wire).toFieldError()

/** The checks `:bg` applies before using Manual credentials; it falls back to Auto when they fail. */
internal fun credentialError(value: String): TunnelFieldError? =
    when {
        value.isEmpty() -> TunnelFieldError.Required
        value.toByteArray(Charsets.UTF_8).size > RFC1929_FIELD_MAX_BYTES -> TunnelFieldError.TooLong
        value.any { it.isISOControl() } -> TunnelFieldError.ControlCharacter
        else -> null
    }

/**
 * Errors by the line the user typed. Each line is parsed on its own, because
 * [ExcludedRoutes.validate] indexes the de-duplicated list of the whole text: a repeated range on an
 * earlier line would shift every later index. Within a line, ranges may be separated by commas or
 * spaces, as [ExcludedRoutes.parse] accepts.
 */
internal fun routeLineErrors(text: String): Map<Int, RouteLineError> =
    text
        .lines()
        .withIndex()
        .mapNotNull { (index, line) -> routeLineError(line)?.let { index to it } }
        .toMap()

private fun routeLineError(line: String): RouteLineError? =
    when (val parsed = ExcludedRoutes.parse(line)) {
        is TuningChecked.Invalid -> RouteLineError.NotARange
        is TuningChecked.Ok ->
            RouteLineError.WholeInternet.takeIf {
                ExcludedRoutes.validate(parsed.value, emptyList()).any { it is TuningProblem.RangeTooWide }
            }
    }

private fun TuningChecked<*>.toFieldError(): TunnelFieldError? =
    when (this) {
        is TuningChecked.Ok -> null
        is TuningChecked.Invalid ->
            when (val p = problem) {
                is TuningProblem.OutOfRange -> TunnelFieldError.OutOfRange(p.min, p.max)
                else -> TunnelFieldError.NotANumber
            }
    }
