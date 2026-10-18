// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

/**
 * Offers a planned resolver address to a TUN builder and falls back when Android
 * rejects the plan's address literal.
 *
 * The builder is supplied as a lambda because [android.net.VpnService.Builder]
 * cannot be constructed in a JVM unit test. Keeping the Android call at the
 * boundary lets the rejection/fallback contract run as a real unit test.
 *
 * @return true when [plannedAddress] was rejected and [fallbackAddress] was used.
 */
internal fun addDnsServerOrFallback(
    plannedAddress: String?,
    fallbackAddress: String,
    addDnsServer: (String) -> Unit,
): Boolean {
    if (plannedAddress == null) {
        addDnsServer(fallbackAddress)
        return false
    }

    return try {
        addDnsServer(plannedAddress)
        false
    } catch (_: IllegalArgumentException) {
        addDnsServer(fallbackAddress)
        true
    }
}

/**
 * The address a TUN built for [plannedAddress] will advertise: the planned one,
 * or [fallbackAddress] when the plan has none to offer.
 *
 * This is the *planned* answer, not necessarily the one Android accepted —
 * [addDnsServerOrFallback] falls back again if the builder rejects the literal.
 * That asymmetry is deliberate and it is the safe direction: rejection is a
 * function of the address, so two plans resolving to the same planned address
 * also resolve to the same advertised one. The comparison in
 * [retainedTunKeepsShape] can therefore rebuild a TUN it did not need
 * to, and can never keep one it should have rebuilt — and only the second of
 * those is a DNS leak.
 */
internal fun advertisedTunDnsAddress(
    plannedAddress: String?,
    fallbackAddress: String,
): String = plannedAddress ?: fallbackAddress
