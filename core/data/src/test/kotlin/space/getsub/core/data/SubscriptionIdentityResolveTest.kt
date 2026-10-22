// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.data

import io.kotest.matchers.shouldBe
import org.junit.Test
import space.getsub.core.model.identity.DeviceHeader
import space.getsub.core.model.identity.HeaderSetting
import space.getsub.core.model.identity.SubscriptionIdentity
import space.getsub.core.network.DeviceInfo

class SubscriptionIdentityResolveTest {
    private fun device(model: String = "Pixel 8", osVersion: String = "15") =
        object : DeviceInfo {
            override val osVersion: String = osVersion
            override val model: String = model
        }

    private val followAll = DeviceHeader.entries.associateWith { HeaderSetting(enabled = true, literal = null) }

    private fun identity(
        hwidEnabled: Boolean = true,
        headers: Map<DeviceHeader, HeaderSetting> = followAll,
    ) = SubscriptionIdentity(hwid = "abcDEF1234", hwidEnabled = hwidEnabled, headers = headers)

    @Test
    fun followingTheDeviceSendsWhatTheAppSentBefore() {
        val (hwid, headers) = identity().resolve(device(), appVersion = "0.1.0")

        hwid shouldBe "abcDEF1234"
        headers shouldBe
            mapOf(
                DeviceHeader.Os to "Android",
                DeviceHeader.OsVersion to "15",
                DeviceHeader.Model to "Pixel 8",
                DeviceHeader.AppVersion to "0.1.0",
            )
    }

    @Test
    fun aSwitchedOffHwidIsNotSent() {
        identity(hwidEnabled = false).resolve(device(), appVersion = "0.1.0").first shouldBe null
    }

    @Test
    fun aSwitchedOffHeaderIsNotSentAndALiteralWinsOverTheDevice() {
        val headers =
            followAll +
                (DeviceHeader.Model to HeaderSetting(enabled = false, literal = null)) +
                (DeviceHeader.AppVersion to HeaderSetting(enabled = true, literal = "9.9.9"))

        val resolved = identity(headers = headers).resolve(device(), appVersion = "0.1.0").second

        resolved.containsKey(DeviceHeader.Model) shouldBe false
        resolved[DeviceHeader.AppVersion] shouldBe "9.9.9"
    }

    @Test
    fun aMissingHeaderSettingIsNotSent() {
        val resolved = identity(headers = emptyMap()).resolve(device(), appVersion = "0.1.0").second

        resolved shouldBe emptyMap()
    }

    // M10-B spec §3.5: Build.MODEL can be non-ASCII, and OkHttp throws on such a header value.
    @Test
    fun aNonAsciiDeviceModelIsSanitised() {
        val resolved = identity().resolve(device(model = "华为 P60"), appVersion = "0.1.0").second

        resolved[DeviceHeader.Model] shouldBe "P60"
    }

    @Test
    fun aDeviceModelWithNothingPrintableIsNotSent() {
        val resolved = identity().resolve(device(model = "​"), appVersion = "0.1.0").second

        resolved.containsKey(DeviceHeader.Model) shouldBe false
    }

    @Test
    fun `a device value that lost characters but still has some is reported as adjusted`() {
        val values = DeviceHeaderValues(device(model = "Pixel \u00e9 8", osVersion = "15"), "1.0")

        values.adjusted() shouldBe setOf(DeviceHeader.Model)
        values.sanitised()[DeviceHeader.Model] shouldBe "Pixel  8"
    }

    @Test
    fun `a value with nothing left is not adjusted, it is not sent`() {
        val values = DeviceHeaderValues(device(model = "\u00e9\u00e9", osVersion = "15"), "1.0")

        values.adjusted() shouldBe emptySet()
        values.sanitised()[DeviceHeader.Model] shouldBe null
    }
}
