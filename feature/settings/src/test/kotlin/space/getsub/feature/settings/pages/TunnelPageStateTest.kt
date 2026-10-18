// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.settings.pages

import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.Test
import space.getsub.core.model.ExcludeRoutesUserSetting
import space.getsub.core.model.MuxUserSettings
import space.getsub.core.model.ProxyAuthMode
import space.getsub.core.model.ProxyAuthSettings
import space.getsub.core.model.TunMtuUserSetting

class TunnelPageStateTest {
    private val stored = TunnelDraft()

    private fun mtu(value: String) = stored.copy(mtu = TunMtuUserSetting(override = true, value = value))

    private fun routes(text: String) = stored.copy(routes = ExcludeRoutesUserSetting(override = true, ranges = text))

    private fun manual(
        user: String,
        pass: String,
    ) = stored.copy(proxyAuth = ProxyAuthSettings(ProxyAuthMode.Manual, user, pass))

    private fun mux(
        tcp: String = "8",
        xudp: String = "16",
    ) = stored.copy(mux = MuxUserSettings(override = true, enabled = true, tcp, xudp, "reject"))

    @Test
    fun `an MTU under 1280 is a field error`() {
        mtu("1000").errors().fields[TunnelField.Mtu] shouldBe TunnelFieldError.OutOfRange(1280, 65_535)
        mtu("1000").canSave(stored) shouldBe false
    }

    @Test
    fun `an MTU that is not a number is a field error`() {
        mtu("abc").errors().fields[TunnelField.Mtu] shouldBe TunnelFieldError.NotANumber
    }

    @Test
    fun `a valid MTU can be saved`() {
        mtu("1400").errors().isEmpty shouldBe true
        mtu("1400").canSave(stored) shouldBe true
    }

    @Test
    fun `an invalid MTU with the switch off does not block saving and is not stored`() {
        val draft =
            stored.copy(
                mtu = TunMtuUserSetting(override = false, value = "1000"),
                sniffing = stored.sniffing.copy(override = true),
            )
        draft.errors().isEmpty shouldBe true
        draft.forSave(stored).mtu.value shouldBe stored.mtu.value
    }

    @Test
    fun `a prefix over 32 is an error on line 1`() {
        routes("10.0.0.0/33").errors().routeLines shouldBe mapOf(0 to RouteLineError.NotARange)
    }

    @Test
    fun `slash zero is refused as the whole internet`() {
        routes("192.168.0.0/16\n0.0.0.0/0").errors().routeLines shouldBe mapOf(1 to RouteLineError.WholeInternet)
    }

    @Test
    fun `a duplicate earlier line does not move the error onto the wrong line`() {
        val text = "10.0.0.0/8\n10.0.0.0/8\n192.168.1.0/24\n::/0"
        routes(text).errors().routeLines shouldBe mapOf(3 to RouteLineError.WholeInternet)
    }

    @Test
    fun `commas spaces bare addresses and blank lines are accepted`() {
        routes("10.0.0.0/8, 192.168.1.1 172.16.0.0/12\n\n2001:db8::/32").errors().routeLines.shouldBeEmpty()
    }

    @Test
    fun `manual with an empty password is an error and save is disabled`() {
        val draft = manual("alice", "")
        draft.errors().fields shouldBe mapOf(TunnelField.ProxyPass to TunnelFieldError.Required)
        draft.canSave(stored) shouldBe false
    }

    @Test
    fun `manual credentials over 255 bytes or with a control character are errors`() {
        // 128 two-byte characters: 128 chars, 256 UTF-8 bytes.
        manual("é".repeat(128), "pw").errors().fields shouldBe mapOf(TunnelField.ProxyUser to TunnelFieldError.TooLong)
        manual("é".repeat(127), "pw").errors().isEmpty shouldBe true
        manual("alice", "pa\tss").errors().fields shouldBe
            mapOf(TunnelField.ProxyPass to TunnelFieldError.ControlCharacter)
        manual("al\nice", "pass").errors().fields shouldBe
            mapOf(TunnelField.ProxyUser to TunnelFieldError.ControlCharacter)
    }

    @Test
    fun `auto ignores the manual fields and keeps the stored ones when they are invalid`() {
        val storedManual = stored.copy(proxyAuth = ProxyAuthSettings(ProxyAuthMode.Auto, "alice", "secret"))
        val draft = storedManual.copy(proxyAuth = ProxyAuthSettings(ProxyAuthMode.Auto, "alice", ""))
        draft.errors().isEmpty shouldBe true
        draft.forSave(storedManual) shouldBe storedManual
        draft.canSave(storedManual) shouldBe false
    }

    @Test
    fun `mux errors name the wrong field`() {
        mux(tcp = "2000").errors().fields shouldBe mapOf(TunnelField.MuxTcp to TunnelFieldError.OutOfRange(-1, 1024))
        mux(xudp = "2000").errors().fields shouldBe mapOf(TunnelField.MuxXudp to TunnelFieldError.OutOfRange(-1, 1024))
        mux(tcp = "x", xudp = "y").errors().fields shouldBe
            mapOf(TunnelField.MuxTcp to TunnelFieldError.NotANumber, TunnelField.MuxXudp to TunnelFieldError.NotANumber)
    }

    @Test
    fun `mux tcp of minus one is allowed`() {
        mux(tcp = "-1").errors().isEmpty shouldBe true
    }

    @Test
    fun `mux fields do not count while mux is off`() {
        val draft = stored.copy(mux = MuxUserSettings(override = true, enabled = false, "2000", "16", "reject"))
        draft.errors().isEmpty shouldBe true
        draft.forSave(stored).mux.tcpConnections shouldBe "8"
    }

    @Test
    fun `an unchanged draft cannot be saved`() {
        stored.canSave(stored) shouldBe false
    }

    @Test
    fun `toString never shows the credentials`() {
        manual("alice", "secret").toString().contains("secret") shouldBe false
    }

    @Test
    fun `a value under a switch that is off does not change the effective settings`() {
        val hidden = stored.copy(mtu = TunMtuUserSetting(override = false, value = "1400"))
        hidden.effective() shouldBe stored.effective()
        mtu("1400").effective() shouldBe mtu("1400")
        val autoWithNames = stored.copy(proxyAuth = ProxyAuthSettings(ProxyAuthMode.Auto, "alice", "secret"))
        autoWithNames.effective() shouldBe stored.effective()
        manual("alice", "secret").effective() shouldBe manual("alice", "secret")
    }
}
