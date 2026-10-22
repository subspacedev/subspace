// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.settings

import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.Assert.assertFalse
import org.junit.Test

class SettingsPageIdTest {
    /**
     * Each subscription carries its own device ID now (M10-B spec §3.5), so the hub has no
     * device-wide page. A `SettingsPage(DeviceId)` route restored from saved state would fail to
     * resolve, which is why the id is gone rather than hidden.
     */
    @Test
    fun theHubHasNoDeviceIdPage() {
        SettingsPageId.entries.map { it.name } shouldNotContain "DeviceId"
    }

    @Test
    fun theStateCarriesNoDeviceWideHwid() {
        val hwidFields =
            SettingsState::class.java.declaredFields
                .map { it.name }
                .filter { it.contains("hwid", ignoreCase = true) }
        hwidFields shouldBe emptyList()
    }

    @Test
    fun dnsEndpointsAreRedactedFromDiagnosticStringification() {
        val state =
            SettingsState(
                dnsAddress = "https://resolver.example/dns-query",
                dnsBootstrapIp = "192.0.2.12",
            )
        val diagnostic = state.toString()

        assertFalse("DNS address leaked into diagnostic text", diagnostic.contains("resolver.example"))
        assertFalse("DNS bootstrap IP leaked into diagnostic text", diagnostic.contains("192.0.2.12"))
    }
}
