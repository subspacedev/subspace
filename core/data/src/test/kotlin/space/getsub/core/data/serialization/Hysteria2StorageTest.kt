// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.data.serialization

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.Test
import space.getsub.core.data.ProfileKind
import space.getsub.core.data.StoredProfile
import space.getsub.core.data.protocolName
import space.getsub.core.data.transportSummary
import space.getsub.core.model.Hysteria2Congestion
import space.getsub.core.model.Hysteria2Obfuscation
import space.getsub.core.model.Hysteria2Outbound
import space.getsub.core.model.Hysteria2Tls
import space.getsub.core.model.Hysteria2Unsupported
import space.getsub.core.model.PortHopping

class Hysteria2StorageTest {
    private val pin = "ab".repeat(32)

    private val fullTls =
        Hysteria2Tls(
            serverName = "sni.example",
            alpn = listOf("h3", "h3-29"),
            pins = listOf(pin),
            skipChecksRequested = true,
        )

    private val full =
        Hysteria2Outbound(
            address = "hy.example.com",
            port = 443,
            password = "s3cret",
            tls = fullTls,
            hopping = PortHopping(ports = listOf(20000..30000, 40000..40000), intervalSeconds = 45),
            obfuscation = Hysteria2Obfuscation.Gecko("obfspw", 100, 1200),
            congestion = Hysteria2Congestion.Brutal("100 mbps", "50 mbps"),
            notConnectableReason = Hysteria2Unsupported.UnknownMask,
        )

    private val plain =
        Hysteria2Outbound(
            address = "hy.example.com",
            port = 443,
            password = "s3cret",
            tls = Hysteria2Tls("sni.example"),
        )

    @Test
    fun `a fully populated hysteria2 outbound round-trips`() {
        full.toJson().toOutbound() shouldBe full
    }

    @Test
    fun `every obfuscation and congestion variant round-trips`() {
        val obfs =
            listOf(
                Hysteria2Obfuscation.None,
                Hysteria2Obfuscation.Salamander("pwpw"),
                Hysteria2Obfuscation.Gecko("pwpw", 1, 2048),
            )
        val cc =
            listOf(
                Hysteria2Congestion.Default,
                Hysteria2Congestion.Bbr(),
                Hysteria2Congestion.Bbr("aggressive"),
                Hysteria2Congestion.Brutal("1 mbps", "2 mbps"),
            )
        for (o in obfs) {
            for (c in cc) {
                val ob = plain.copy(obfuscation = o, congestion = c)
                ob.toJson().toOutbound() shouldBe ob
            }
        }
        for (r in Hysteria2Unsupported.entries) {
            val ob = plain.copy(notConnectableReason = r)
            ob.toJson().toOutbound() shouldBe ob
        }
    }

    @Test
    fun `stored discriminator is hysteria2 and matches protocolName`() {
        plain.protocolName() shouldBe "hysteria2"
        plain.toJson().contains("\"type\":\"hysteria2\"") shouldBe true
    }

    private fun id(o: Hysteria2Outbound) = identityHashOf(o, ProfileKind.TYPED)

    @Test
    fun `identity differs by address port and password`() {
        id(plain) shouldNotBe id(plain.copy(address = "other.example.com"))
        id(plain) shouldNotBe id(plain.copy(port = 8443))
        id(plain) shouldNotBe id(plain.copy(password = "other"))
    }

    @Test
    fun `identity ignores pins obfs hopping congestion and tls settings`() {
        val base = id(plain)
        id(plain.copy(tls = plain.tls.copy(pins = listOf(pin)))) shouldBe base
        id(plain.copy(tls = plain.tls.copy(serverName = "x", alpn = listOf("h3-29")))) shouldBe base
        id(plain.copy(obfuscation = Hysteria2Obfuscation.Salamander("pwpw"))) shouldBe base
        id(plain.copy(hopping = PortHopping(listOf(1000..2000)))) shouldBe base
        id(plain.copy(congestion = Hysteria2Congestion.Bbr())) shouldBe base
        id(full) shouldBe id(full.copy(notConnectableReason = null))
    }

    @Test
    fun `identity differs between typed and raw kinds`() {
        identityHashOf(plain, ProfileKind.TYPED) shouldNotBe identityHashOf(plain, ProfileKind.RAW_JSON)
    }

    @Test
    fun `transport summary names quic and the port`() {
        plain.transportSummary() shouldBe "QUIC · 443"
    }

    @Test
    fun `transport summary shows hop ranges with an en dash`() {
        plain.copy(hopping = PortHopping(listOf(20000..30000))).transportSummary() shouldBe "QUIC · 20000–30000"
        plain.copy(hopping = PortHopping(listOf(20000..30000, 40000..40000))).transportSummary() shouldBe
            "QUIC · 20000–30000, 40000"
    }

    @Test
    fun `a clean hysteria2 row is connectable`() {
        stored(plain).connectable shouldBe true
        stored(full.copy(notConnectableReason = null)).connectable shouldBe true
    }

    @Test
    fun `a hysteria2 row with a not-connectable reason is not connectable`() {
        stored(full).connectable shouldBe false
        stored(plain.copy(notConnectableReason = Hysteria2Unsupported.UnknownCongestion)).connectable shouldBe false
    }

    private fun stored(o: Hysteria2Outbound) =
        StoredProfile(
            id = 1,
            groupId = 1,
            kind = ProfileKind.TYPED,
            name = "T",
            protocol = "hysteria2",
            address = o.address,
            port = o.port,
            transport = o.transportSummary(),
            outbound = o,
            rawJson = null,
            lastConnectedAt = null,
            lastError = null,
        )
}
