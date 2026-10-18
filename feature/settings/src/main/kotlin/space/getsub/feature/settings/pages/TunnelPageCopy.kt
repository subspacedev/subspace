// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.settings.pages

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import space.getsub.core.data.TuningValue
import space.getsub.core.model.CidrRange
import space.getsub.core.model.MuxSpec
import space.getsub.core.model.TuningOrigin
import space.getsub.core.model.Udp443Mode
import space.getsub.feature.settings.R

// The Tunnel page's text, rendered from TunnelPageState.kt's values and the resolved provider values.

/** What applies with your switch off: the active server's subscription, else the default. */
@Composable
internal fun <T> providerLine(
    value: TuningValue<T>?,
    format: @Composable (T) -> String,
): String =
    when {
        value == null -> stringResource(R.string.settings_tunnel_no_server)
        value.origin == TuningOrigin.Subscription ->
            stringResource(R.string.settings_tunnel_from_subscription, format(value.value))
        else -> stringResource(R.string.settings_tunnel_default_value, format(value.value))
    }

@Composable
internal fun onOff(on: Boolean): String =
    stringResource(if (on) R.string.settings_tunnel_value_on else R.string.settings_tunnel_value_off)

@Composable
internal fun muxText(spec: MuxSpec?): String =
    if (spec == null) {
        onOff(false)
    } else {
        stringResource(
            R.string.settings_tunnel_mux_value,
            spec.concurrency,
            spec.xudpConcurrency,
            stringResource(spec.udp443.labelRes()),
        )
    }

@Composable
internal fun routesText(ranges: List<CidrRange>): String =
    if (ranges.isEmpty()) stringResource(R.string.settings_tunnel_value_none) else ranges.joinToString(", ")

@StringRes
internal fun Udp443Mode.labelRes(): Int =
    when (this) {
        Udp443Mode.Reject -> R.string.settings_tunnel_mux_udp443_reject
        Udp443Mode.Allow -> R.string.settings_tunnel_mux_udp443_allow
        Udp443Mode.Skip -> R.string.settings_tunnel_mux_udp443_skip
    }

/** The error under a field, naming the field so it reads on its own (a screen reader, a long page). */
@Composable
internal fun errorText(
    label: String,
    error: TunnelFieldError,
): String =
    when (error) {
        TunnelFieldError.NotANumber -> stringResource(R.string.settings_tunnel_error_number, label)
        is TunnelFieldError.OutOfRange ->
            stringResource(R.string.settings_tunnel_error_range, label, error.min, error.max)
        TunnelFieldError.Required -> stringResource(R.string.settings_tunnel_error_required, label)
        TunnelFieldError.TooLong -> stringResource(R.string.settings_tunnel_error_too_long, label)
        TunnelFieldError.ControlCharacter -> stringResource(R.string.settings_tunnel_error_control, label)
        TunnelFieldError.Colon -> stringResource(R.string.settings_tunnel_error_colon, label)
    }
