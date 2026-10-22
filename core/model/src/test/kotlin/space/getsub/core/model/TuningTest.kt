// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.model

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.Test

class TuningTest {
    @Test
    fun credentialsToStringRedacts() {
        ProxyCredentials("hunter", "swordfish").toString().let {
            it shouldNotContain "hunter"
            it shouldNotContain "swordfish"
        }
        ProxyAuthSettings(ProxyAuthMode.Manual, "hunter", "swordfish").toString().let {
            it shouldNotContain "hunter"
            it shouldNotContain "swordfish"
        }
    }

    @Test
    fun randomCredentialsDifferAndAreAlphanumeric() {
        val a = ProxyCredentialsFactory.random()
        val b = ProxyCredentialsFactory.random()
        (a == b) shouldBe false
        a.user.length shouldBe 16
        a.pass.length shouldBe 32
        (a.user + a.pass).all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' } shouldBe true
    }

    @Test
    fun defaults() {
        ProxyAuthSettings.DEFAULT shouldBe ProxyAuthSettings(ProxyAuthMode.Auto, "", "")
        SniffingUserSetting.DEFAULT shouldBe SniffingUserSetting(false, true)
        MuxUserSettings.DEFAULT shouldBe MuxUserSettings(false, false, "8", "16", "reject")
        TunMtuUserSetting.DEFAULT shouldBe TunMtuUserSetting(false, "8500")
        ExcludeRoutesUserSetting.DEFAULT shouldBe ExcludeRoutesUserSetting(false, "")
    }

    @Test
    fun tunMtuBounds() {
        TunMtu.check("1279") shouldBe TuningChecked.Invalid(TuningProblem.OutOfRange(1280, 65_535))
        TunMtu.check("65536") shouldBe TuningChecked.Invalid(TuningProblem.OutOfRange(1280, 65_535))
        TunMtu.check("1280") shouldBe TuningChecked.Ok(1280)
        TunMtu.check(" 65535 ") shouldBe TuningChecked.Ok(65_535)
        TunMtu.check("abc") shouldBe TuningChecked.Invalid(TuningProblem.BadValue("mtu"))
        TunMtu.check("") shouldBe TuningChecked.Invalid(TuningProblem.BadValue("mtu"))
    }

    @Test
    fun muxRules() {
        MuxRules.check("8", "16", "reject") shouldBe TuningChecked.Ok(MuxSpec(8, 16, Udp443Mode.Reject))
        MuxRules.check("-1", "-1", "skip") shouldBe TuningChecked.Ok(MuxSpec(-1, -1, Udp443Mode.Skip))
        MuxRules.check("0", "1024", "allow") shouldBe TuningChecked.Ok(MuxSpec(0, 1024, Udp443Mode.Allow))
        MuxRules.check("1025", "16", "reject") shouldBe TuningChecked.Invalid(TuningProblem.OutOfRange(-1, 1024))
        MuxRules.check("8", "-2", "reject") shouldBe TuningChecked.Invalid(TuningProblem.OutOfRange(-1, 1024))
        MuxRules.check("x", "16", "reject") shouldBe TuningChecked.Invalid(TuningProblem.BadValue("connections"))
        MuxRules.check("8", "16", "maybe") shouldBe TuningChecked.Invalid(TuningProblem.BadValue("quic"))
        MuxRules.check("8", "16", "") shouldBe TuningChecked.Invalid(TuningProblem.BadValue("quic"))
        MuxRules.check("8", "16", "Skip") shouldBe TuningChecked.Invalid(TuningProblem.BadValue("quic"))
    }
}
