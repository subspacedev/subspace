// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.model.identity

import io.kotest.matchers.shouldBe
import org.junit.Test

class SubscriptionIdentityTest {
    @Test
    fun toStringRedactsHwidAndLiterals() {
        val id =
            SubscriptionIdentity(
                hwid = "SecretHwid1234567",
                hwidEnabled = true,
                headers = mapOf(DeviceHeader.Model to HeaderSetting(true, "Secret Model")),
            )
        val s = id.toString()
        (s.contains("SecretHwid1234567")) shouldBe false
        (s.contains("Secret Model")) shouldBe false
        val setting =
            id.headers.values
                .single()
                .toString()
        (setting.contains("Secret Model")) shouldBe false
    }
}
