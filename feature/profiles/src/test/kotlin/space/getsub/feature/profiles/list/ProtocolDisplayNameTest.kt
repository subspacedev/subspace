// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.profiles.list

import io.kotest.matchers.shouldBe
import org.junit.Test

class ProtocolDisplayNameTest {
    @Test
    fun `wireguard and http have their own display names`() {
        "wireguard".toProtocolDisplayName() shouldBe "WireGuard"
        "http".toProtocolDisplayName() shouldBe "HTTP"
    }

    @Test
    fun `a display name lowercases back to the stored protocol`() {
        for (p in listOf("vless", "vmess", "trojan", "shadowsocks", "socks", "hysteria2", "wireguard", "http")) {
            p.toProtocolDisplayName().lowercase() shouldBe p
        }
    }
}
