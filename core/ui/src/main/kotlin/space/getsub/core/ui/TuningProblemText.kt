// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import space.getsub.core.model.TuningProblem

// Why a tuning value is not applied, worded once for subscription details and Settings → Tunnel
// (M9-E spec §3, §4, §7.1), as ObfuscationProblemText.kt is for masks.

/** A string resource and its format arguments, so the choice is testable without resources. */
data class TuningText(
    @param:StringRes val res: Int,
    val args: List<Any> = emptyList(),
)

/** How a sentence about an unapplied value reads. */
enum class TuningIgnoreKind {
    /** The value is invalid and the default applies. */
    Ignored,

    /** Part of the value applies: some ranges of a list were dropped. */
    PartlyIgnored,

    /** The value is valid but waits for, or was refused, the user's consent. */
    NotApplied,
}

fun tuningIgnoreKind(
    problem: TuningProblem,
    partial: Boolean,
): TuningIgnoreKind =
    when {
        problem == TuningProblem.NeedsConsent || problem == TuningProblem.Declined -> TuningIgnoreKind.NotApplied
        partial -> TuningIgnoreKind.PartlyIgnored
        else -> TuningIgnoreKind.Ignored
    }

/** The reason clause, e.g. "it must be between 1280 and 65535". */
fun TuningProblem.reason(): TuningText =
    when (this) {
        is TuningProblem.OutOfRange -> TuningText(R.string.tuning_reason_out_of_range, listOf(min, max))
        is TuningProblem.BadValue -> TuningText(R.string.tuning_reason_bad_value)
        is TuningProblem.RangeTooWide -> TuningText(R.string.tuning_reason_range_too_wide)
        is TuningProblem.RangeCoversDns -> TuningText(R.string.tuning_reason_range_covers_dns)
        TuningProblem.NeedsConsent -> TuningText(R.string.tuning_reason_needs_consent)
        TuningProblem.Declined -> TuningText(R.string.tuning_reason_declined)
    }

/** "%1$s is ignored: %2$s." and its siblings: the directive key, then [reason]. */
@StringRes
fun TuningIgnoreKind.directiveLineRes(): Int =
    when (this) {
        TuningIgnoreKind.Ignored -> R.string.tuning_directive_ignored
        TuningIgnoreKind.PartlyIgnored -> R.string.tuning_directive_partly_ignored
        TuningIgnoreKind.NotApplied -> R.string.tuning_directive_not_applied
    }

/** "your subscription's value is ignored: %1$s" and its siblings, for a line that names no key. */
@StringRes
fun TuningIgnoreKind.subscriptionValueRes(): Int =
    when (this) {
        TuningIgnoreKind.Ignored -> R.string.tuning_subscription_value_ignored
        TuningIgnoreKind.PartlyIgnored -> R.string.tuning_subscription_value_partly_ignored
        TuningIgnoreKind.NotApplied -> R.string.tuning_subscription_value_not_applied
    }

// At most two numbers per call; the copy the spread makes is not worth a per-arity overload.
@Suppress("SpreadOperator")
@Composable
fun TuningProblem.reasonText(): String {
    val text = reason()
    return stringResource(text.res, *text.args.toTypedArray())
}

/** Subscription details: "xray-tun-mtu is ignored: it must be between 1280 and 65535." */
@Composable
fun tuningDirectiveLine(
    key: String,
    problem: TuningProblem,
    partial: Boolean = false,
): String = stringResource(tuningIgnoreKind(problem, partial).directiveLineRes(), key, problem.reasonText())

/** Settings → Tunnel: "your subscription's value is ignored: it must be between 1280 and 65535". */
@Composable
fun subscriptionValueIgnoredText(
    problem: TuningProblem,
    partial: Boolean,
): String = stringResource(tuningIgnoreKind(problem, partial).subscriptionValueRes(), problem.reasonText())
