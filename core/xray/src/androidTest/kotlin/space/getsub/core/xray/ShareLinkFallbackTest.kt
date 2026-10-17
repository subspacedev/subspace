// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.xray

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import space.getsub.core.model.Security
import space.getsub.core.model.VlessOutbound
import space.getsub.core.parser.DetailField
import space.getsub.core.parser.FailureDetail
import space.getsub.core.parser.ParseFailureReason
import space.getsub.core.parser.ParseOutcome
import space.getsub.core.parser.SubscriptionParser
import space.getsub.core.parser.parseFailure

/**
 * Instrumented because it needs the libXray AAR. This is the only part of M2
 * that cannot run on the JVM, which is why it lives here and not in
 * `:core:parser` — ARCHITECTURE.md §7's near-100% coverage target stays achievable because the
 * parser itself never depends on this.
 */
@RunWith(AndroidJUnit4::class)
class ShareLinkFallbackTest {
    @Test
    fun leavesACleanOutcomeUntouchedAndMakesNoCalls() {
        val text = "vless://70cc48c5-b2f4-4a1e-9f3d-0123456789ab@host.example:443"
        val outcome = SubscriptionParser.parse(text)
        outcome.failures.size shouldBe 0

        val after = ShareLinkFallback.retry(text, outcome)
        after.profiles.size shouldBe outcome.profiles.size
        after.failures.size shouldBe 0
    }

    @Test
    fun doesNotThrowWhenTheCoreRejectsALine() {
        val text = "wireguard://nonsense@host.example:443"
        val outcome = SubscriptionParser.parse(text)
        outcome.failures.size shouldBe 1

        // The core will reject this too. The contract is that we degrade to
        // the original failure, never throw and never lose the outcome.
        val after = ShareLinkFallback.retry(text, outcome)
        (after.profiles.size + after.failures.size) shouldBe 1
    }

    // libXray v26.10.10 answers convertShareLinksToXrayJson with an outbound-only object: flat
    // VLESS settings, the REALITY key as `password`, network "raw". The fallback must turn that
    // back into a profile (docs/agent/research/libxray-api.md §2).
    @Test
    fun recoversARealityLinkFromTheOutboundOnlyResponse() {
        val link =
            "vless://70cc48c5-b2f4-4a1e-9f3d-0123456789ab@example.com:443?encryption=none" +
                "&flow=xtls-rprx-vision&security=reality&sni=example.com&fp=chrome" +
                "&pbk=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8&sid=ab&spx=%2F#name"

        val after = ShareLinkFallback.retry(link, failedOutcome())

        after.failures.size shouldBe 0
        val vless = after.profiles.single().outbound as VlessOutbound
        vless.address shouldBe "example.com"
        (vless.stream.security as Security.Reality).publicKey shouldBe "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
    }

    // Xray-core a1bf968: plaintext VLESS to a public host is dropped by the core, so libXray
    // fails the call with "no valid outbound found". The fallback degrades to the original
    // failure rather than throwing or inventing a profile.
    @Test
    fun keepsTheOriginalFailureForPlaintextVlessToAPublicHost() {
        val link = "vless://70cc48c5-b2f4-4a1e-9f3d-0123456789ab@example.com:443?encryption=none&type=ws&path=%2Fws#p"
        val before = failedOutcome()

        val after = ShareLinkFallback.retry(link, before)

        after.profiles.size shouldBe 0
        after.failures shouldBe before.failures
    }

    private fun failedOutcome() =
        ParseOutcome(
            emptyList(),
            listOf(parseFailure(0, ParseFailureReason.UnknownScheme, FailureDetail.Unsupported(DetailField.Scheme))),
        )
}
