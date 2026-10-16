// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.data.preresolve

import io.kotest.matchers.shouldBe
import org.junit.Test
import space.getsub.core.data.ResolverTrust
import space.getsub.core.model.FragmentPackets
import space.getsub.core.model.FragmentSpec
import space.getsub.core.model.PreResolveSettings
import space.getsub.core.model.XrayRange
import space.getsub.core.network.preresolve.PreResolveReason

class PreResolvePolicyTest {
    private val userOn = PreResolveSettings(true, "https://cloudflare-dns.com/dns-query", "1.1.1.1")
    private val userOff = userOn.copy(enabled = false)
    private val provider = ProviderResolver.Valid("https://dns.example/q", "dns.example", "77.88.8.8")

    @Test
    fun userWinsOverProvider() {
        val d = PreResolvePolicy.decide(userOn, null, provider, ResolverTrust.Allowed, "dns.example")
        (d as PreResolveDecision.Run).source shouldBe PreResolveSource.User
        d.config.dohUrl shouldBe "https://cloudflare-dns.com/dns-query"
    }

    @Test
    fun providerNeedsAllowed() {
        PreResolvePolicy.decide(userOff, null, provider, ResolverTrust.Unasked, null) shouldBe
            PreResolveDecision.Off(PreResolveReason.Off)
        PreResolvePolicy.decide(userOff, null, provider, ResolverTrust.Denied, "dns.example") shouldBe
            PreResolveDecision.Off(PreResolveReason.Off)
        val run = PreResolvePolicy.decide(userOff, null, provider, ResolverTrust.Allowed, "dns.example")
        (run as PreResolveDecision.Run).source shouldBe PreResolveSource.Provider
    }

    @Test
    fun aChangedProviderHostResetsTheGrant() {
        PreResolvePolicy.effectiveTrust(ResolverTrust.Allowed, "old.example", provider) shouldBe ResolverTrust.Unasked
        PreResolvePolicy.decide(userOff, null, provider, ResolverTrust.Allowed, "old.example") shouldBe
            PreResolveDecision.Off(PreResolveReason.Off)
    }

    @Test
    fun trustHostComparisonIgnoresCaseAndOneTrailingDot() {
        PreResolvePolicy.effectiveTrust(ResolverTrust.Allowed, "DNS.Example.", provider) shouldBe ResolverTrust.Allowed
    }

    @Test
    fun invalidUserResolverIsSkippedNotThrown() {
        val absent = ProviderResolver.Absent
        val badIp = userOn.copy(bootstrapIp = "garbage")
        PreResolvePolicy.decide(badIp, null, absent, ResolverTrust.Unasked, null) shouldBe
            PreResolveDecision.Off(PreResolveReason.InvalidUserResolver, PreResolveSource.User)
        PreResolvePolicy.decide(
            userOn.copy(dohUrl = "http://x.example/q"),
            null,
            absent,
            ResolverTrust.Unasked,
            null,
        ) shouldBe PreResolveDecision.Off(PreResolveReason.InvalidUserResolver, PreResolveSource.User)
    }

    @Test
    fun theUsersFragmentRidesAlongForBothSources() {
        val spec = FragmentSpec(FragmentPackets.TlsHello, XrayRange(60, 90), XrayRange(10, 20), null)
        listOf(userOff, userOn).forEach { user ->
            val run = PreResolvePolicy.decide(user, spec, provider, ResolverTrust.Allowed, "dns.example")
            (run as PreResolveDecision.Run).config.fragment shouldBe spec
        }
    }

    @Test
    fun noProviderAndUserOffIsOff() {
        PreResolvePolicy.decide(userOff, null, ProviderResolver.Absent, ResolverTrust.Unasked, null) shouldBe
            PreResolveDecision.Off(PreResolveReason.Off)
    }
}
