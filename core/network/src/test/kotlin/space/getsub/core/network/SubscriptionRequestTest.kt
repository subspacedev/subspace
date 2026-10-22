// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.network

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.Test
import space.getsub.core.model.identity.DeviceHeader

class SubscriptionRequestTest {
    @Test
    fun `toString does not contain the subscription url`() {
        val request = SubscriptionRequest(
            url = "https://panel.example/api/sub/super-secret-token",
            hwid = null,
            headers = emptyMap(),
            userAgentOverride = null,
            timeoutSeconds = 9,
        )

        request.toString() shouldNotContain "super-secret-token"
        request.toString() shouldNotContain "panel.example"
    }

    // ARCHITECTURE.md §5.6 and the M10-B constraints: an HWID and a header value are never printed.
    @Test
    fun `toString redacts the hwid and every header value`() {
        val request = SubscriptionRequest(
            url = "https://panel.example/api/sub/super-secret-token",
            hwid = "SECRETHWID0123456789",
            headers = mapOf(DeviceHeader.Model to "SecretModel X", DeviceHeader.OsVersion to "SecretOs99"),
            userAgentOverride = null,
            timeoutSeconds = 9,
        )

        request.toString() shouldNotContain "SECRETHWID0123456789"
        request.toString() shouldNotContain "SecretModel"
        request.toString() shouldNotContain "SecretOs99"
    }

    @Test
    fun `toString still surfaces the non-secret fields, for debugging`() {
        val request = SubscriptionRequest(
            url = "https://panel.example/api/sub/super-secret-token",
            hwid = "SECRETHWID0123456789",
            headers = mapOf(DeviceHeader.Os to "Android", DeviceHeader.Model to "Pixel 8"),
            userAgentOverride = "v2rayNG/1.8.5",
            timeoutSeconds = 12,
        )

        request.toString() shouldBe
            "SubscriptionRequest(url=<redacted>, hwid=<redacted>, headers=[x-device-os, x-device-model], " +
            "userAgentOverride=v2rayNG/1.8.5, timeoutSeconds=12)"
    }

    @Test
    fun `toString says when no hwid is sent`() {
        val request = SubscriptionRequest(
            url = "https://panel.example/api/sub/super-secret-token",
            hwid = null,
            headers = emptyMap(),
            userAgentOverride = null,
            timeoutSeconds = 9,
        )

        request.toString() shouldBe
            "SubscriptionRequest(url=<redacted>, hwid=none, headers=[], " +
            "userAgentOverride=null, timeoutSeconds=9)"
    }
}
