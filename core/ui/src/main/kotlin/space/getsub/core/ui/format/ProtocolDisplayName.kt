// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.ui.format

/**
 * The canonical protocol names [space.getsub.core.data.ProfileRepository] stores, capitalised for
 * display. Shared by the Servers list and Home so both spell a protocol the same way. Every
 * mapping is a case variant of the stored value, so lowercasing the result recovers it.
 */
public fun protocolDisplayName(protocol: String): String =
    when (protocol) {
        "vless" -> "VLESS"
        "vmess" -> "VMess"
        "trojan" -> "Trojan"
        "shadowsocks" -> "Shadowsocks"
        "socks" -> "SOCKS"
        "hysteria2" -> "Hysteria2"
        "wireguard" -> "WireGuard"
        "http" -> "HTTP"
        else -> protocol
    }
