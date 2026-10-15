// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.parser

import space.getsub.core.model.Checked
import space.getsub.core.model.FragmentSpec
import space.getsub.core.model.ObfuscationProblem
import space.getsub.core.model.ObfuscationValidation

private const val LINK_FRAGMENT_PARTS = 3

/**
 * The link's `fragment=` value, `length,interval,packets` in Happ's order (research note), validated
 * by the same rules as the settings page (M9-A's [ObfuscationValidation]). There is no `maxSplit`.
 */
public object LinkFragment {
    public fun parse(value: String): Checked<FragmentSpec> {
        val parts = value.split(',').map(String::trim)
        if (parts.size != LINK_FRAGMENT_PARTS) return Checked.Invalid(ObfuscationProblem.NotARange)
        val (length, interval, packets) = parts
        return when (val p = ObfuscationValidation.packets(packets)) {
            is Checked.Invalid -> p
            is Checked.Ok ->
                when (val l = ObfuscationValidation.length(length)) {
                    is Checked.Invalid -> l
                    is Checked.Ok ->
                        when (val d = ObfuscationValidation.delay(interval)) {
                            is Checked.Invalid -> d
                            is Checked.Ok -> Checked.Ok(FragmentSpec(p.value, l.value, d.value, maxSplit = null))
                        }
                }
        }
    }
}
