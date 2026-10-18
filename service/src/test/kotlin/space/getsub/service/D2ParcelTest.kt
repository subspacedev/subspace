// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.Test
import space.getsub.core.model.HttpOutbound
import space.getsub.core.model.KcpHeader
import space.getsub.core.model.KcpObfuscation
import space.getsub.core.model.Outbound
import space.getsub.core.model.Profile
import space.getsub.core.model.Security
import space.getsub.core.model.StreamSettings
import space.getsub.core.model.TransportOptions
import space.getsub.core.model.TrojanOutbound
import space.getsub.core.model.VlessOutbound
import space.getsub.core.model.VmessOutbound
import space.getsub.core.model.WireGuardOutbound

class D2ParcelTest {
    private val wg =
        WireGuardOutbound(
            address = "wg.example.com",
            port = 51820,
            secretKey = "A".repeat(43) + "=",
            localAddresses = listOf("10.0.0.2/32"),
            peerPublicKey = "B".repeat(43) + "=",
            preSharedKey = "C".repeat(43) + "=",
            mtu = 1280,
            reserved = listOf(1, 2, 3),
            keepAlive = 25,
            allowedIps = listOf("10.0.0.0/8"),
        )
    private val http = HttpOutbound("proxy.example.com", 8080, "user", "p4ss", Security.Tls("sni", "chrome", false))
    private val kcp = TransportOptions.Kcp(1350, 20, KcpObfuscation("s33d", KcpHeader.SRTP, false))
    private val hu = TransportOptions.HttpUpgrade("h.example", "/up")

    private fun trip(o: Outbound) = Profile("p", "N", o).let { ProfileParcel.from(it, 5L).toProfile() shouldBe it }

    @Test
    fun `wireguard and http survive the parcel`() {
        trip(wg)
        trip(wg.copy(preSharedKey = null, reserved = emptyList(), keepAlive = null))
        trip(http)
        trip(http.copy(username = null, password = null, security = Security.None))
        ProfileParcel.from(Profile("p", "N", wg)).protocol shouldBe ProfileParcel.PROTOCOL_WIREGUARD
        ProfileParcel.from(Profile("p", "N", http)).protocol shouldBe ProfileParcel.PROTOCOL_HTTP
    }

    @Test
    fun `kcp and httpupgrade transports survive the parcel on vless vmess and trojan`() {
        for ((n, t) in listOf("kcp" to kcp, "mkcp" to kcp, "httpupgrade" to hu)) {
            val s = StreamSettings(n, Security.None, t)
            trip(VlessOutbound("a", 1, "u", null, s))
            trip(VmessOutbound("a", 1, "u", 2, "auto", s))
            trip(TrojanOutbound("a", 1, "pw", s))
        }
        val tls = Security.Tls("s", "chrome", false)
        trip(VlessOutbound("a", 1, "u", "xtls-rprx-vision", StreamSettings("httpupgrade", tls, hu)))
    }

    @Test
    fun `a new-protocol parcel without its payload does not decode`() {
        for (p in listOf(ProfileParcel.PROTOCOL_WIREGUARD, ProfileParcel.PROTOCOL_HTTP)) {
            emptyParcel(p).toProfile().shouldBeNull()
        }
    }

    private fun emptyParcel(protocol: Int) =
        ProfileParcel(
            protocol = protocol,
            rowId = 1,
            id = "p",
            name = "n",
            address = "a",
            port = 1,
            uuid = "",
            credential2 = "",
            alterId = 0,
            flow = null,
            network = "",
            securityKind = ProfileParcel.SECURITY_NONE,
            serverName = "",
            publicKey = "",
            shortId = "",
            fingerprint = "",
            spiderX = "",
            allowInsecure = false,
        )

    @Test
    fun `parcel toString does not leak new secrets`() {
        for (o in listOf(wg, http, VlessOutbound("a", 1, "u", null, StreamSettings("kcp", Security.None, kcp)))) {
            val r = ProfileParcel.from(Profile("p", "N", o)).toString()
            for (s in listOf("AAAA", "BBBB", "p4ss", "s33d", "wg.example.com")) r shouldNotContain s
        }
    }
}
