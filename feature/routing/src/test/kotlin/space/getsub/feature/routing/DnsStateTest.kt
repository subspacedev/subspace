// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.routing

import io.kotest.matchers.shouldBe
import org.junit.Test
import space.getsub.core.model.DnsResolver
import space.getsub.core.model.DnsState
import space.getsub.core.model.DnsTransport
import space.getsub.core.model.ProfileDns

class DnsStateTest {
    @Test
    fun noDnsBlockHasNoState() {
        dnsStateOf(dns = null) shouldBe DnsState.None
    }

    @Test
    fun validDnsBlockIsAppliedWhenSniffingIsEnabled() {
        val dns = ProfileDns(remote = DnsResolver(DnsTransport.DOU, ip = "1.1.1.1"))

        dnsStateOf(dns) shouldBe DnsState.Applied
    }

    @Test
    fun anOrdinaryEmptyDnsBlockIsAppliedNotInvalid() {
        dnsStateOf(ProfileDns()) shouldBe DnsState.Applied
    }

    @Test
    fun rejectedDnsBlockIsInvalid() {
        dnsStateOf(ProfileDns.INVALID) shouldBe DnsState.Invalid
    }

    // M9-E spec §2: FakeDNS keeps working with sniffing off, so it is never refused here.
    @Test
    fun fakeDnsIsApplied() {
        dnsStateOf(ProfileDns(fakeDns = true)) shouldBe DnsState.Applied
    }
}
