// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.data.preresolve

import io.kotest.matchers.shouldBe
import org.junit.Test

class ProviderResolverTest {
    private val enable = "server-address-resolve-enable"
    private val domain = "server-address-resolve-dns-domain"
    private val ip = "server-address-resolve-dns-ip"
    private val on = enable to "true"

    @Test
    fun absentWithoutEnable() {
        providerResolverOf(mapOf(domain to "https://dns.example/q")) shouldBe ProviderResolver.Absent
    }

    @Test
    fun enableFalseIsAbsent() {
        providerResolverOf(mapOf(enable to "false", domain to "https://dns.example/q")) shouldBe
            ProviderResolver.Absent
    }

    @Test
    fun enableOneCounts() {
        providerResolverOf(mapOf(enable to "1", domain to "https://dns.example/q", ip to "77.88.8.8")) shouldBe
            ProviderResolver.Valid("https://dns.example/q", "dns.example", "77.88.8.8")
    }

    @Test
    fun enableWithoutDomainIsInvalid() {
        providerResolverOf(mapOf(on)) shouldBe ProviderResolver.Invalid(domain)
    }

    @Test
    fun httpIsInvalid() {
        providerResolverOf(mapOf(on, domain to "http://dns.example/q")) shouldBe
            ProviderResolver.Invalid(domain)
    }

    @Test
    fun ipLiteralHostIsInvalid() {
        providerResolverOf(mapOf(on, domain to "https://1.1.1.1/dns-query")) shouldBe
            ProviderResolver.Invalid(domain)
    }

    @Test
    fun localhostIsInvalid() {
        providerResolverOf(mapOf(on, domain to "https://localhost./q")) shouldBe
            ProviderResolver.Invalid(domain)
    }

    @Test
    fun badBootstrapIsInvalid() {
        providerResolverOf(mapOf(on, domain to "https://dns.example/q", ip to "dns.example")) shouldBe
            ProviderResolver.Invalid(ip)
    }

    @Test
    fun hostIsLowercasedWithoutTrailingDot() {
        val valid = providerResolverOf(mapOf(on, domain to "https://DNS.Example./q")) as ProviderResolver.Valid
        valid.host shouldBe "dns.example"
    }

    @Test
    fun validToStringIsRedacted() {
        val valid = ProviderResolver.Valid("https://dns.example/q", "dns.example", null)
        valid.toString().contains("dns.example") shouldBe false
    }
}
