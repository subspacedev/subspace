// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.ui

import androidx.annotation.StringRes
import space.getsub.core.model.ObfuscationProblem

/** One string per [ObfuscationProblem], shared by the settings page and subscription detail. */
@StringRes
fun ObfuscationProblem.textRes(): Int =
    when (this) {
        ObfuscationProblem.Blank -> R.string.obfuscation_problem_blank
        ObfuscationProblem.NotARange -> R.string.obfuscation_problem_not_a_range
        ObfuscationProblem.Reversed -> R.string.obfuscation_problem_reversed
        ObfuscationProblem.TooLarge -> R.string.obfuscation_problem_too_large
        ObfuscationProblem.OutOfBounds -> R.string.obfuscation_problem_out_of_bounds
        ObfuscationProblem.PacketsFromZero -> R.string.obfuscation_problem_packets_from_zero
        ObfuscationProblem.LengthFromZero -> R.string.obfuscation_problem_length_from_zero
        ObfuscationProblem.RandFromZero -> R.string.obfuscation_problem_rand_from_zero
        ObfuscationProblem.RandRangeOver255 -> R.string.obfuscation_problem_rand_range_over_255
        ObfuscationProblem.PacketUndecodable -> R.string.obfuscation_problem_packet_undecodable
        ObfuscationProblem.PacketAndRand -> R.string.obfuscation_problem_packet_and_rand
        ObfuscationProblem.MultiplePackets -> R.string.obfuscation_problem_multiple_packets
    }
