// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.model

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.Test
import java.util.Base64

class WireGuardTest {
    private val bytes32 = ByteArray(32) { (it * 8 + 251).toByte() }
    private val std = Base64.getEncoder().encodeToString(bytes32)
    private val url = Base64.getUrlEncoder().encodeToString(bytes32)

    @Test
    fun keyIsValidUnderStandardOrUrlSafeBase64PaddedOrNot() {
        for (k in listOf(std, url, std.trimEnd('='), url.trimEnd('='))) {
            WireGuardRules.keyValid(k) shouldBe true
        }
        // The fixture really exercises both alphabets.
        (std.contains('+') || std.contains('/')) shouldBe true
        (url.contains('-') || url.contains('_')) shouldBe true
    }

    @Test
    fun hexKeyIsValidInEitherCaseButOnlyAtSixtyFourCharacters() {
        WireGuardRules.keyValid("ab".repeat(32)) shouldBe true
        WireGuardRules.keyValid("AB".repeat(32)) shouldBe true
        WireGuardRules.keyValid("aB09".repeat(16)) shouldBe true
        WireGuardRules.keyValid("ab".repeat(31)) shouldBe false
        WireGuardRules.keyValid("ab".repeat(33)) shouldBe false
        WireGuardRules.keyValid("gg".repeat(32)) shouldBe false
    }

    @Test
    fun keyOfAnyOtherLengthOrNotBase64IsInvalid() {
        val b31 = Base64.getEncoder().encodeToString(ByteArray(31))
        val b33 = Base64.getEncoder().encodeToString(ByteArray(33))
        for (bad in listOf("", "   ", b31, b33, "not base64!", "$std$std", "AAAA", std.take(40))) {
            WireGuardRules.keyValid(bad) shouldBe false
        }
    }

    @Test
    fun keyMixingBothAlphabetsIsRejectedAndNothingIsTrimmed() {
        val raw = ByteArray(32)
        byteArrayOf(0xFB.toByte(), 0xEF.toByte(), 0xBE.toByte(), -1, -1, -1).copyInto(raw)
        val valid = Base64.getEncoder().encodeToString(raw)
        valid.contains('+') shouldBe true
        valid.contains('/') shouldBe true
        WireGuardRules.keyValid(valid) shouldBe true
        WireGuardRules.keyValid(valid.replaceFirst('/', '_')) shouldBe false
        WireGuardRules.keyValid(" $valid") shouldBe false
    }

    @Test
    fun reservedParsesListBase64AndBlank() {
        WireGuardRules.parseReserved("1,2,3") shouldBe listOf(1, 2, 3)
        WireGuardRules.parseReserved(" 0, 255 ,7 ") shouldBe listOf(0, 255, 7)
        WireGuardRules.parseReserved("") shouldBe emptyList<Int>()
        WireGuardRules.parseReserved("  ") shouldBe emptyList<Int>()
        WireGuardRules.parseReserved("AQID") shouldBe listOf(1, 2, 3)
        WireGuardRules.parseReserved(Base64.getEncoder().encodeToString(byteArrayOf(-1, 0, -128))) shouldBe
            listOf(255, 0, 128)
    }

    @Test
    fun reservedRejectsEverythingElse() {
        val bad = listOf("1,2", "1,2,3,4", "1,2,256", "-1,2,3", "a,b,c", "1,,3", "AQ==", "AQIDBA==", "!!!!", "1;2;3")
        for (b in bad) WireGuardRules.parseReserved(b) shouldBe null
    }

    // Ruling D2-R15. Expected values checked against Go's netip.ParsePrefix (go1.26), which wireguard-go
    // applies to each allowed_ip (device/uapi.go:382) when the tunnel starts, after `xray -test` has passed.
    @Test
    fun allowedIpKeepsAValidCidrAsGiven() {
        val valid =
            listOf("10.0.0.0/8", "1.2.3.4/24", "1.2.3.4/0", "0.0.0.0/0", "::/0", "FD00::/8", "1:2:3:4:5:6:7:8/64")
        for (cidr in valid) {
            WireGuardRules.normaliseAllowedIp(cidr) shouldBe cidr
        }
        WireGuardRules.normaliseAllowedIp("::ffff:1.2.3.4/128") shouldBe "::ffff:1.2.3.4/128"
    }

    @Test
    fun allowedIpGivesABareAddressAHostPrefix() {
        WireGuardRules.normaliseAllowedIp("1.2.3.4") shouldBe "1.2.3.4/32"
        WireGuardRules.normaliseAllowedIp("fd00::1") shouldBe "fd00::1/128"
        WireGuardRules.normaliseAllowedIp("::") shouldBe "::/128"
    }

    @Test
    fun allowedIpRefusesWhatParsePrefixRefuses() {
        val bad =
            listOf(
                "",
                " ",
                "example.com",
                "01.2.3.4/32",
                "1.2.3.4/08",
                "1.2.3.4/33",
                "::/129",
                "fe80::1%eth0/64",
                "1.2.3.4/",
                "/24",
                " 1.2.3.4/32",
                "1.2.3.4/32 ",
                "-1.2.3.4/32",
                "1.2.3.4/+8",
                "1::2::3/64",
                "1.2.3",
                "256.1.1.1",
                "1.2.3.4/24/8",
                "1:2:3:4:5:6:7:8:9",
                "12345::/16",
            )
        for (text in bad) WireGuardRules.normaliseAllowedIp(text) shouldBe null
    }

    @Test
    fun toStringHidesEveryKeyAndTheAddress() {
        val rendered =
            WireGuardOutbound(
                address = "wg-host.example.net",
                port = 51820,
                secretKey = "SECRETKEY-aaaa",
                localAddresses = listOf("10.9.8.7/32"),
                peerPublicKey = "PEERPUB-bbbb",
                preSharedKey = "PSK-cccc",
            ).toString()
        for (s in listOf("wg-host.example.net", "SECRETKEY-aaaa", "PSK-cccc")) rendered shouldNotContain s
        rendered shouldContain "51820"
    }

    @Test
    fun defaultsMatchTheBrief() {
        val o = WireGuardOutbound("h", 1, "s", listOf("10.0.0.2/32"), "p", null)
        o.mtu shouldBe 1420
        o.reserved shouldBe emptyList<Int>()
        o.keepAlive shouldBe null
        o.allowedIps shouldBe listOf("0.0.0.0/0", "::/0")
        o.notConnectableReason shouldBe null
    }

    @Test
    fun httpOutboundHidesPasswordAndAddress() {
        val rendered = HttpOutbound("proxy-host.example.net", 8080, "uuser", "PASS-dddd", Security.None).toString()
        for (s in listOf("proxy-host.example.net", "PASS-dddd", "uuser")) rendered shouldNotContain s
        rendered shouldContain "8080"
    }
}
