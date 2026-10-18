// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.model

import io.kotest.matchers.shouldBe
import org.junit.Test

class UdpCarriageTest {
    @Test
    fun onlyTheHttpProxyCannotCarryUdp() {
        UdpCarriage.carriesUdp(HttpOutbound("p.example", 8080, null, null, Security.None)) shouldBe false
        UdpCarriage.carriesUdp(SocksOutbound("s.example", 1080, null, null)) shouldBe true
        val vless = VlessOutbound("v.example", 443, "u", null, StreamSettings("tcp", Security.None))
        UdpCarriage.carriesUdp(vless) shouldBe true
    }
}
