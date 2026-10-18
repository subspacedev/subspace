// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.profiles.editor

import space.getsub.core.model.KcpHeader
import space.getsub.core.model.KcpObfuscation
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

private const val MIN_KCP_MTU = 21
private const val MIN_KCP_TTI = 10
private const val MAX_KCP_TTI = 1000

/** Networks (as stored, any case) that are mKCP. */
internal fun isKcpNetwork(network: String): Boolean = network.lowercase() in setOf("kcp", "mkcp")

internal fun isHttpUpgradeNetwork(network: String): Boolean = network.equals("httpupgrade", ignoreCase = true)

/**
 * The mKCP transport fields, as text so a half-typed value survives.
 *
 * ARCHITECTURE.md §5.6: [seed] is a shared secret. [toString] redacts it and [KcpField] errors carry no
 * content. Ruling D2-R6: the seed is verbatim, never trimmed; only the empty string means "no seed".
 *
 * [legacyOriginal] is the stored flag, written back as it was. A header with no seed always gets the
 * "original" mask first (ruling D2-R3), so in that state the switch shows as on and cannot be turned off
 * ([legacyForced]); the flag itself is left alone so an untouched load then save is exact.
 * [unsupported] is a fact about the source: it travels through a save, except [KcpUnsupported.InvalidSetting],
 * which [validate] has just proven fixed.
 */
internal data class KcpDraft(
    val mtu: String = "",
    val tti: String = "",
    val seed: String = "",
    val header: KcpHeader? = null,
    val legacyOriginal: Boolean = false,
    val unsupported: KcpUnsupported? = null,
) {
    /** A header is chosen and no seed gives the first mask: the core is asked for "original". */
    val legacyForced: Boolean get() = header != null && seed.isEmpty()

    /** What the "Legacy obfuscation" switch shows. */
    val legacySwitchOn: Boolean get() = legacyOriginal || legacyForced

    fun validate(): Set<KcpField> =
        buildSet {
            val m = mtu.trim()
            if (m.isNotEmpty() && (m.toIntOrNull() ?: 0) < MIN_KCP_MTU) add(KcpField.Mtu)
            val t = tti.trim()
            if (t.isNotEmpty() && t.toIntOrNull() !in MIN_KCP_TTI..MAX_KCP_TTI) add(KcpField.Tti)
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
                unsupported = unsupported.takeUnless { it == KcpUnsupported.InvalidSetting },
            ),
        )

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
            )
    }
}
