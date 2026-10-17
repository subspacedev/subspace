// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.model

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.Test
import java.util.Base64

class Hysteria2Test {
    private val hex = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    @Test
    fun bbrProfilesAreTheCoresFourLowercased() {
        BBR_PROFILES shouldBe setOf("", "conservative", "standard", "aggressive")
        normaliseBbrProfile("Aggressive") shouldBe "aggressive"
        normaliseBbrProfile("") shouldBe ""
        normaliseBbrProfile("turbo") shouldBe null
        normaliseBbrProfile(" standard") shouldBe null
    }

    @Test
    fun hopIntervalIsDigitsOnlyAtLeastFiveAndBlankMeansTheDefault() {
        Hysteria2Rules.hopInterval(null) shouldBe 30
        Hysteria2Rules.hopInterval("") shouldBe 30
        Hysteria2Rules.hopInterval("  ") shouldBe 30
        Hysteria2Rules.hopInterval("5") shouldBe 5
        Hysteria2Rules.hopInterval(" 10 ") shouldBe 10
        for (bad in listOf("4", "0", "+10", "-10", "1e2", "10s", "1 0", "9999999999")) {
            Hysteria2Rules.hopInterval(bad) shouldBe null
        }
    }

    @Test
    fun geckoRangeIsPositiveAscendingAndAtMost2048() {
        Hysteria2Rules.geckoRangeValid(1, 2048) shouldBe true
        Hysteria2Rules.geckoRangeValid(100, 100) shouldBe true
        Hysteria2Rules.geckoRangeValid(0, 100) shouldBe false
        Hysteria2Rules.geckoRangeValid(200, 100) shouldBe false
        Hysteria2Rules.geckoRangeValid(100, 2049) shouldBe false
    }

    @Test
    fun defaultsLiveInOnePlace() {
        Hysteria2Rules.DEFAULT_ALPN shouldBe listOf("h3")
        Hysteria2Tls("s").alpn shouldBe Hysteria2Rules.DEFAULT_ALPN
        PortHopping(listOf(1..2)).intervalSeconds shouldBe Hysteria2Rules.DEFAULT_HOP_INTERVAL
    }

    @Test
    fun hexWithColonsNormalises() {
        val colons = hex.uppercase().chunked(2).joinToString(":")
        normalisePin(colons) shouldBe PinResult.Ok(hex)
        normalisePin(hex) shouldBe PinResult.Ok(hex)
    }

    @Test
    fun base64PinNormalises() {
        val bytes = ByteArray(32) { it.toByte() }
        val expected = bytes.joinToString("") { "%02x".format(it) }
        normalisePin(Base64.getEncoder().encodeToString(bytes)) shouldBe PinResult.Ok(expected)
    }

    @Test
    fun unpaddedBase64PinNormalises() {
        val bytes = ByteArray(32) { it.toByte() }
        val encoded = Base64.getEncoder().withoutPadding().encodeToString(bytes)
        (encoded.endsWith("=")) shouldBe false
        normalisePin(encoded) shouldBe PinResult.Ok(bytes.joinToString("") { "%02x".format(it) })
    }

    @Test
    fun urlSafeBase64PinNormalises() {
        val bytes = ByteArray(32) { 0xfb.toByte() }
        val encoded = Base64.getUrlEncoder().encodeToString(bytes)
        (encoded.contains('-') || encoded.contains('_')) shouldBe true
        normalisePin(encoded) shouldBe PinResult.Ok("fb".repeat(32))
    }

    @Test
    fun surroundingWhitespaceIsIgnored() {
        normalisePin("  \t$hex\n") shouldBe PinResult.Ok(hex)
        val bytes = ByteArray(32) { it.toByte() }
        val encoded = Base64.getEncoder().encodeToString(bytes)
        normalisePin(" $encoded ") shouldBe PinResult.Ok(bytes.joinToString("") { "%02x".format(it) })
    }

    @Test
    fun wrongLengthIsInvalid() {
        normalisePin(hex.dropLast(2)) shouldBe PinResult.Invalid
        normalisePin(Base64.getEncoder().encodeToString(ByteArray(31))) shouldBe PinResult.Invalid
        normalisePin("") shouldBe PinResult.Invalid
    }

    @Test
    fun nonHexNonBase64IsInvalid() {
        normalisePin("zz".repeat(32)) shouldBe PinResult.Invalid
        normalisePin("not a pin!") shouldBe PinResult.Invalid
    }

    @Test
    fun toStringHidesPasswordObfsPasswordAndPins() {
        val ob =
            Hysteria2Outbound(
                address = "secret-host.example",
                port = 443,
                password = "pw-SECRET",
                tls = Hysteria2Tls(serverName = "sni.example", pins = listOf(hex)),
                obfuscation = Hysteria2Obfuscation.Salamander("obfs-SECRET"),
                congestion = Hysteria2Congestion.Brutal("100 mbps", "200 mbps"),
            )
        val all =
            listOf(
                ob.toString(),
                ob.tls.toString(),
                ob.obfuscation.toString(),
                Hysteria2Obfuscation.Gecko("gecko-SECRET", 1, 2).toString(),
            ).joinToString("\n")
        for (secret in listOf("pw-SECRET", "obfs-SECRET", "gecko-SECRET", hex, "secret-host.example")) {
            all shouldNotContain secret
        }
        all shouldContain "redacted"
    }

    @Test
    fun defaultsAreH3AndNoHopping() {
        val ob = Hysteria2Outbound("h", 443, "p", Hysteria2Tls(serverName = "s"))
        ob.tls.alpn shouldBe listOf("h3")
        ob.tls.pins shouldBe emptyList()
        ob.tls.skipChecksRequested shouldBe false
        ob.hopping shouldBe null
        ob.obfuscation shouldBe Hysteria2Obfuscation.None
        ob.congestion shouldBe Hysteria2Congestion.Default
        ob.notConnectableReason shouldBe null
        PortHopping(listOf(1000..2000)).intervalSeconds shouldBe 30
    }

    @Test
    fun aSinglePortHopIsNoHoppingOnThatPort() {
        // udphop dials a port from remotePorts and ignores the outbound port (xc udphop/conn.go:68-69).
        Hysteria2Rules.collapseSinglePortHop(443, PortHopping(listOf(20000..20000), 10)) shouldBe (20000 to null)
        val real = PortHopping(listOf(20000..30000))
        Hysteria2Rules.collapseSinglePortHop(443, real) shouldBe (443 to real)
        val two = PortHopping(listOf(20000..20000, 30000..30000))
        Hysteria2Rules.collapseSinglePortHop(443, two) shouldBe (443 to two)
        Hysteria2Rules.collapseSinglePortHop(443, null) shouldBe (443 to null)
    }
}
