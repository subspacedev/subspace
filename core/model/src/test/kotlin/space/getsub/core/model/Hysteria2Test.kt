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
}
