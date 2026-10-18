// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.profiles.editor

import space.getsub.core.model.WireGuardOutbound
import space.getsub.core.model.WireGuardRules
import space.getsub.core.model.WireGuardUnsupported
import space.getsub.core.parser.WireGuardInput

/** The WireGuard form fields that can fail validation. Errors name the field, never its content. */
internal enum class WireGuardField {
    SecretKey,
    PeerPublicKey,
    PreSharedKey,
    LocalAddresses,
    Mtu,
    Reserved,
    Keepalive,
    AllowedIps,
}

private val LIST_SEPARATORS = Regex("[,\\s]+")

private val DEFAULT_ALLOWED_IPS = listOf("0.0.0.0/0", "::/0")

/**
 * Everything the WireGuard form edits beyond the shared address and port (the peer endpoint).
 *
 * All text, like the rest of [EditorState], so a half-typed value survives. Every input is trimmed before it
 * is checked (ruling D2-R7: the key and `reserved` rules do not trim). ARCHITECTURE.md §5.6: the three keys
 * never reach a log or an error string; [toString] redacts them and [WireGuardField] errors carry no content.
 *
 * [notConnectableReason] is a fact about the source. `MultiplePeers` and `UnsupportedMask` cannot be fixed
 * here, so they travel through a save; `InvalidKey` and `InvalidAllowedIp` are dropped by [toOutbound] (save has
 * validated them), `InvalidReserved` and `InvalidMtu` once the user has changed that value (see [remainingReason]).
 * Allowed IPs are saved through [WireGuardRules.normaliseAllowedIp] (ruling D2-R15: a bare address gets `/32` or
 * `/128`); interface addresses keep a bare address as typed.
 */
internal data class WireGuardDraft(
    val secretKey: String = "",
    val peerPublicKey: String = "",
    val preSharedKey: String = "",
    val localAddresses: String = "",
    val mtu: String = WireGuardRules.DEFAULT_MTU.toString(),
    val reserved: String = "",
    val keepAlive: String = "",
    val allowedIps: String = DEFAULT_ALLOWED_IPS.joinToString("\n"),
    val notConnectableReason: WireGuardUnsupported? = null,
    /** The mtu and reserved text as loaded: a reason about either is kept until the user changes that field. */
    val loadedMtu: String = WireGuardRules.DEFAULT_MTU.toString(),
    val loadedReserved: String = "",
    /** The row was stored with no allowed IPs (nothing routes). An untouched blank field keeps it that way. */
    val loadedAllowedEmpty: Boolean = false,
) {
    private fun tokens(text: String): List<String> = text.trim().split(LIST_SEPARATORS).filter { it.isNotEmpty() }

    val localAddressTokens: List<String> get() = tokens(localAddresses)

    val allowedIpTokens: List<String> get() = tokens(allowedIps)

    /** The fields that are wrong, by name. Empty means [toOutbound] is exact. */
    fun validate(): Set<WireGuardField> =
        buildSet {
            if (!WireGuardRules.keyValid(secretKey.trim())) add(WireGuardField.SecretKey)
            if (!WireGuardRules.keyValid(peerPublicKey.trim())) add(WireGuardField.PeerPublicKey)
            if (preSharedKey.isNotBlank() && !WireGuardRules.keyValid(preSharedKey.trim())) {
                add(WireGuardField.PreSharedKey)
            }
            // At least one interface address: the core has nothing to bind the tunnel to without one.
            val addresses = localAddressTokens
            if (addresses.isEmpty() || addresses.any { !WireGuardInput.isAddress(it) }) {
                add(WireGuardField.LocalAddresses)
            }
            if (mtu.isNotBlank() && mtu.trim().toIntOrNull() !in WireGuardInput.MIN_MTU..WireGuardInput.MAX_MTU) {
                add(WireGuardField.Mtu)
            }
            if (WireGuardRules.parseReserved(reserved) == null) add(WireGuardField.Reserved)
            if (keepAlive.isNotBlank() && keepAlive.trim().toIntOrNull() !in 0..WireGuardInput.MAX_KEEPALIVE_SECONDS) {
                add(WireGuardField.Keepalive)
            }
            // Ruling D2-R15: a bare address is fine (saved with its host prefix); only garbage is an error.
            if (allowedIpTokens.any { WireGuardRules.normaliseAllowedIp(it) == null }) add(WireGuardField.AllowedIps)
        }

    /** Total: text that [validate] would refuse falls back to empty/default, since save validates first. */
    fun toOutbound(
        address: String,
        port: Int,
    ): WireGuardOutbound =
        WireGuardOutbound(
            address = address.trim(),
            port = port,
            secretKey = secretKey.trim(),
            localAddresses = localAddressTokens,
            peerPublicKey = peerPublicKey.trim(),
            preSharedKey = preSharedKey.trim().ifEmpty { null },
            mtu = mtu.trim().toIntOrNull() ?: WireGuardRules.DEFAULT_MTU,
            reserved = WireGuardRules.parseReserved(reserved).orEmpty(),
            keepAlive = keepAlive.trim().toIntOrNull()?.takeIf { it > 0 },
            allowedIps =
            allowedIpTokens
                .mapNotNull(WireGuardRules::normaliseAllowedIp)
                .ifEmpty { if (loadedAllowedEmpty) emptyList() else DEFAULT_ALLOWED_IPS },
            notConnectableReason = remainingReason(),
        )

    /**
     * The importer stores the projected value (the default mtu, empty reserved), not the bad one, so an invalid
     * mtu or reserved is cleared only once the user changes that field from its loaded text. A bad key cannot be
     * saved at all, so by the time this runs it has been retyped.
     */
    private fun remainingReason(): WireGuardUnsupported? =
        when (notConnectableReason) {
            // Save validated every key and normalised every allowed IP, so neither reason can still hold.
            WireGuardUnsupported.InvalidKey, WireGuardUnsupported.InvalidAllowedIp -> null
            WireGuardUnsupported.InvalidMtu -> notConnectableReason.takeIf { mtu.trim() == loadedMtu.trim() }
            WireGuardUnsupported.InvalidReserved ->
                notConnectableReason.takeIf { reserved.trim() == loadedReserved.trim() }
            else -> notConnectableReason
        }

    override fun toString(): String =
        "WireGuardDraft(keys=<redacted>, localAddresses=${localAddressTokens.size}, " +
            "notConnectableReason=$notConnectableReason)"

    companion object {
        fun from(outbound: WireGuardOutbound): WireGuardDraft =
            WireGuardDraft(
                secretKey = outbound.secretKey,
                peerPublicKey = outbound.peerPublicKey,
                preSharedKey = outbound.preSharedKey.orEmpty(),
                localAddresses = outbound.localAddresses.joinToString("\n"),
                mtu = outbound.mtu.toString(),
                reserved = outbound.reserved.joinToString(","),
                keepAlive = outbound.keepAlive?.toString().orEmpty(),
                allowedIps = outbound.allowedIps.joinToString("\n"),
                notConnectableReason = outbound.notConnectableReason,
                loadedMtu = outbound.mtu.toString(),
                loadedReserved = outbound.reserved.joinToString(","),
                loadedAllowedEmpty = outbound.allowedIps.isEmpty(),
            )
    }
}
