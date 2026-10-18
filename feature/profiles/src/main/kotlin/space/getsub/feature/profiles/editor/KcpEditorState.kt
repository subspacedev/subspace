// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.profiles.editor

import space.getsub.core.model.KcpHeader
import space.getsub.core.model.KcpObfuscation
import space.getsub.core.model.KcpRules
import space.getsub.core.model.KcpUnsupported
import space.getsub.core.model.TransportOptions

/** The mKCP form fields that can fail validation. Errors name the field, never its content. */
internal enum class KcpField { Mtu, Tti }

/** Why the editor refuses to save REALITY on the chosen transport. */
internal enum class RealityConflict {
    /** mKCP and HTTPUpgrade are not carried over REALITY by the core. */
    Transport,

    /** An HTTP proxy is TCP with optional TLS; there is no REALITY for it. */
    HttpProxy,
}

/**
 * The mKCP transport fields, as text so a half-typed value survives.
 *
 * ARCHITECTURE.md §5.6: [seed] is a shared secret. [toString] redacts it and [KcpField] errors carry no
 * content. Ruling D2-R6: the seed is verbatim, never trimmed; only the empty string means "no seed".
 *
 * [legacyOriginal] is the stored flag, written back as it was. A header with no seed always gets the
 * "original" mask first (ruling D2-R3), so in that state the switch shows as on and cannot be turned off
 * ([legacyForced]); the flag itself is left alone so an untouched load then save is exact.
 * [unsupported] is a fact about the source: it travels through a save while the seed, header and legacy flag are
 * as loaded ([loadedSeed], [loadedHeader], [loadedLegacyOriginal]). Once the user changes any of them the draft is
 * exactly what [KcpRules.masks] writes, so the reason is dropped (final review M4, the rule WireGuard's bad-value
 * reasons follow). [KcpUnsupported.InvalidSetting] is always dropped: [validate] has just proven it fixed.
 */
internal data class KcpDraft(
    val mtu: String = "",
    val tti: String = "",
    val seed: String = "",
    val header: KcpHeader? = null,
    val legacyOriginal: Boolean = false,
    val unsupported: KcpUnsupported? = null,
    val loadedSeed: String = "",
    val loadedHeader: KcpHeader? = null,
    val loadedLegacyOriginal: Boolean = false,
) {
    /** A header is chosen and no seed gives the first mask: the core is asked for "original". */
    val legacyForced: Boolean get() = header != null && seed.isEmpty()

    /** What the "Legacy obfuscation" switch shows. */
    val legacySwitchOn: Boolean get() = legacyOriginal || legacyForced

    /**
     * A seed is set, so the first mask is the seed and the flag cannot change the wire ([KcpRules.masks]):
     * the switch is disabled with a hint (final review M5). The flag itself is kept for an exact round trip.
     */
    val legacySeedReplaces: Boolean get() = seed.isNotEmpty()

    /** Whether the user may flip the switch: neither a header alone ([legacyForced]) nor a seed decides it. */
    val legacySwitchEnabled: Boolean get() = !legacyForced && !legacySeedReplaces

    fun validate(): Set<KcpField> =
        buildSet {
            val m = mtu.trim()
            if (m.isNotEmpty() && m.toIntOrNull() !in KcpRules.MIN_MTU..KcpRules.MAX_MTU) add(KcpField.Mtu)
            val t = tti.trim()
            if (t.isNotEmpty() && t.toIntOrNull() !in KcpRules.MIN_TTI..KcpRules.MAX_TTI) add(KcpField.Tti)
        }

    /** Total: text that [validate] would refuse falls back to unset, since save validates first. */
    fun toTransport(): TransportOptions.Kcp =
        TransportOptions.Kcp(
            mtu = mtu.trim().toIntOrNull(),
            tti = tti.trim().toIntOrNull(),
            obfuscation =
            KcpObfuscation(
                seed = seed.ifEmpty { null },
                header = header,
                legacyOriginal = legacyOriginal,
                unsupported = unsupported.takeUnless { it == KcpUnsupported.InvalidSetting || obfuscationEdited },
            ),
        )

    private val obfuscationEdited: Boolean
        get() = seed != loadedSeed || header != loadedHeader || legacyOriginal != loadedLegacyOriginal

    override fun toString(): String =
        "KcpDraft(mtu=$mtu, tti=$tti, seed=${if (seed.isEmpty()) "none" else "<redacted>"}, header=$header, " +
            "legacyOriginal=$legacyOriginal, unsupported=$unsupported)"

    companion object {
        fun from(kcp: TransportOptions.Kcp): KcpDraft =
            KcpDraft(
                mtu = kcp.mtu?.toString().orEmpty(),
                tti = kcp.tti?.toString().orEmpty(),
                seed = kcp.obfuscation.seed.orEmpty(),
                header = kcp.obfuscation.header,
                legacyOriginal = kcp.obfuscation.legacyOriginal,
                unsupported = kcp.obfuscation.unsupported,
                loadedSeed = kcp.obfuscation.seed.orEmpty(),
                loadedHeader = kcp.obfuscation.header,
                loadedLegacyOriginal = kcp.obfuscation.legacyOriginal,
            )
    }
}
