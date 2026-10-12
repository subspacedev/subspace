// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.model

/**
 * The user's Fragmentation setting as stored: text, exactly as typed and validated (M9-A spec §2.1).
 * Values persist while [enabled] is false, so switching back On restores them.
 */
public data class FragmentSettings(
    val enabled: Boolean,
    val packets: String,
    val length: String,
    val delay: String,
    val maxSplit: String,
) {
    public companion object {
        /** M9-A spec §2.2: `tlshello` is Xray's; the rest are v2rayNG's (research note). */
        public val DEFAULT: FragmentSettings =
            FragmentSettings(enabled = false, packets = "tlshello", length = "50-100", delay = "10-20", maxSplit = "10")
    }
}

public enum class NoiseMode { Random, Fixed }

/** The user's Noise setting as stored. [packet] is only read in [NoiseMode.Fixed]; rand fields only in Random. */
public data class NoiseSettings(
    val enabled: Boolean,
    val mode: NoiseMode,
    val packetType: NoisePacketType,
    val packet: String,
    val rand: String,
    val randRange: String,
    val delay: String,
) {
    override fun toString(): String =
        "NoiseSettings(enabled=$enabled, mode=$mode, packetType=$packetType, packet=<redacted>, " +
            "rand=$rand, randRange=$randRange, delay=$delay)"

    public companion object {
        /** M9-A spec §2.2. rand and delay have no upstream source; randRange is Xray's default. */
        public val DEFAULT: NoiseSettings =
            NoiseSettings(
                enabled = false,
                mode = NoiseMode.Random,
                packetType = NoisePacketType.Str,
                packet = "",
                rand = "10-20",
                randRange = "0-255",
                delay = "10-16",
            )
    }
}
