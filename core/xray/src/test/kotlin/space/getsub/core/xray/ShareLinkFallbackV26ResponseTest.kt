// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.xray

import io.kotest.matchers.shouldBe
import org.junit.Test
import space.getsub.core.model.Security
import space.getsub.core.model.VlessOutbound
import space.getsub.core.parser.DetailField
import space.getsub.core.parser.FailureDetail
import space.getsub.core.parser.ParseFailureReason
import space.getsub.core.parser.ParseOutcome
import space.getsub.core.parser.parseFailure

/**
 * Feeds [ShareLinkFallback.retry] the `data` object libXray v26.10.10 returns from
 * `convertShareLinksToXrayJson` and asserts the profile Subspace reads out of it.
 *
 * The response text is the literal output of `share.ConvertShareLinksToXrayJson` at the
 * `v26.10.10` tag (run 2026-10-17; see `docs/agent/research/libxray-api.md` §2): outbound-only,
 * flat VLESS settings, the REALITY key under `password`, TCP spelled `raw`. The real call needs the
 * AAR, so it stays in `androidTest/ShareLinkFallbackTest`; this pins the reading side on the JVM.
 */
class ShareLinkFallbackV26ResponseTest {
    private val uuid = "70cc48c5-b2f4-4a1e-9f3d-0123456789ab"
    private val pbk = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"

    private val realityLink =
        "vless://$uuid@example.com:443?encryption=none&flow=xtls-rprx-vision&security=reality" +
            "&sni=example.com&fp=chrome&pbk=$pbk&sid=ab&spx=%2F#name"

    private val realityResponse =
        """{"outbounds":[{"protocol":"vless","settings":{"address":"example.com","encryption":"none",""" +
            """"flow":"xtls-rprx-vision","id":"$uuid","port":443},"streamSettings":{"network":"raw",""" +
            """"realitySettings":{"fingerprint":"chrome","password":"$pbk","serverName":"example.com",""" +
            """"shortId":"ab","spiderX":"/"},"security":"reality"},"tag":"name"}]}"""

    private fun failedOutcome() =
        ParseOutcome(
            emptyList(),
            listOf(
                parseFailure(0, ParseFailureReason.UnknownScheme, FailureDetail.Unsupported(DetailField.Scheme)),
            ),
        )

    @Test
    fun `a line the core converts becomes a profile read from the flat reality response`() {
        val after = ShareLinkFallback.retry(realityLink, failedOutcome()) { realityResponse }

        after.failures shouldBe emptyList()
        val profile = after.profiles.single()
        profile.name shouldBe "name"
        val vless = profile.outbound as VlessOutbound
        vless.address shouldBe "example.com"
        vless.uuid shouldBe uuid
        vless.flow shouldBe "xtls-rprx-vision"
        (vless.stream.security as Security.Reality).publicKey shouldBe pbk
    }

    @Test
    fun `a line the core drops keeps its original failure`() {
        // Plaintext VLESS to a public host: Xray-core a1bf968 rejects it, so libXray answers
        // {"success":false,"error":"no valid outbound found"} and LibXrayInvoke throws, which
        // convertOrNull turns into null. The user sees the failure our own parser reported.
        val link = "vless://$uuid@example.com:443?encryption=none&type=ws&path=%2Fws#plain"
        val before = failedOutcome()

        val after = ShareLinkFallback.retry(link, before) { null }

        after.profiles shouldBe emptyList()
        after.failures shouldBe before.failures
    }
}
