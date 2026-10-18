// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.ui.format

import io.kotest.matchers.shouldBe
import org.junit.Test

class ProtocolDisplayNameTest {
    @Test
    fun `every stored protocol maps to the spelling the Servers list shows`() {
        protocolDisplayName("vless") shouldBe "VLESS"
        protocolDisplayName("vmess") shouldBe "VMess"
        protocolDisplayName("trojan") shouldBe "Trojan"
        protocolDisplayName("shadowsocks") shouldBe "Shadowsocks"
        protocolDisplayName("socks") shouldBe "SOCKS"
        protocolDisplayName("hysteria2") shouldBe "Hysteria2"
        protocolDisplayName("wireguard") shouldBe "WireGuard"
        protocolDisplayName("http") shouldBe "HTTP"
    }

    @Test
    fun `an unknown protocol is shown as stored`() {
        protocolDisplayName("future") shouldBe "future"
    }

    @Test
    fun `a display name lowercases back to the stored protocol`() {
        for (p in listOf("vless", "vmess", "trojan", "shadowsocks", "socks", "hysteria2", "wireguard", "http")) {
            protocolDisplayName(p).lowercase() shouldBe p
        }
    }
}
