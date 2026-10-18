// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.parser

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Test

/**
 * M9-E spec §2: [PassthroughAdvisory.SniffingCannotServeOwnRules] is judged against the block the
 * pure passthrough branch actually emits for the row's resolved sniffing.
 */
class PassthroughSniffingTest {
    private val withOwnBlock =
        """
        {
          "routing": { "rules": [ { "domain": ["geosite:ru"], "outboundTag": "direct" } ] },
          "inbounds": [
            { "tag": "socks", "protocol": "socks",
              "sniffing": { "enabled": true, "destOverride": ["http", "tls"] } }
          ],
          "outbounds": [ { "tag": "proxy", "protocol": "vless" }, { "tag": "direct", "protocol": "freedom" } ]
        }
        """.trimIndent()

    private val withoutOwnBlock =
        """
        {
          "routing": { "rules": [ { "domain": ["geosite:ru"], "outboundTag": "direct" } ] },
          "inbounds": [ { "tag": "socks", "protocol": "socks" } ],
          "outbounds": [ { "tag": "proxy", "protocol": "vless" }, { "tag": "direct", "protocol": "freedom" } ]
        }
        """.trimIndent()

    private fun advisories(
        json: String,
        enabled: Boolean,
        replacesOwn: Boolean,
    ) = analysePassthrough(json, SniffingDecision(enabled, replacesOwn)).advisories

    @Test
    fun `own block kept under a provider's off still serves its rules`() {
        advisories(withOwnBlock, enabled = false, replacesOwn = false) shouldNotContain
            PassthroughAdvisory.SniffingCannotServeOwnRules
    }

    @Test
    fun `your override off drops the own block, so the domain rules are flagged`() {
        advisories(withOwnBlock, enabled = false, replacesOwn = true) shouldContain
            PassthroughAdvisory.SniffingCannotServeOwnRules
    }

    @Test
    fun `no own block takes the default block, so the domain rules are served`() {
        advisories(withoutOwnBlock, enabled = true, replacesOwn = false) shouldNotContain
            PassthroughAdvisory.SniffingCannotServeOwnRules
        advisories(withoutOwnBlock, enabled = true, replacesOwn = true) shouldNotContain
            PassthroughAdvisory.SniffingCannotServeOwnRules
    }

    @Test
    fun `your override on replaces an own disabled block`() {
        val disabled = withOwnBlock.replace(""""enabled": true""", """"enabled": false""")

        advisories(disabled, enabled = false, replacesOwn = false) shouldContain
            PassthroughAdvisory.SniffingCannotServeOwnRules
        advisories(disabled, enabled = true, replacesOwn = true) shouldNotContain
            PassthroughAdvisory.SniffingCannotServeOwnRules
    }

    @Test
    fun `no own block and sniffing off is flagged`() {
        advisories(withoutOwnBlock, enabled = false, replacesOwn = false) shouldContain
            PassthroughAdvisory.SniffingCannotServeOwnRules
    }

    @Test
    fun `without a decision the config's own inbounds are judged as before`() {
        analysePassthrough(withoutOwnBlock).advisories shouldContain PassthroughAdvisory.SniffingCannotServeOwnRules
        analysePassthrough(withOwnBlock).advisories shouldNotContain PassthroughAdvisory.SniffingCannotServeOwnRules
    }

    @Test
    fun `the emitted block follows the shared rule`() {
        emittedSniffing(null, SniffingDecision(enabled = false, replacesOwn = false), fakeDns = true) shouldBe
            EmittedSniffing.Generated(listOf("fakedns"))
        emittedSniffing(null, SniffingDecision(enabled = false, replacesOwn = true), fakeDns = false) shouldBe
            EmittedSniffing.None
        emittedSniffing(null, SniffingDecision(enabled = true, replacesOwn = false), fakeDns = true) shouldBe
            EmittedSniffing.Generated(listOf("http", "tls", "quic", "fakedns"))
    }

    @Test
    fun `your override keeps fakedns when the config's own block sniffs it`() {
        val own =
            Json.parseToJsonElement(
                """{ "enabled": true, "destOverride": ["http", "fakedns"] }""",
            ) as JsonObject
        emittedSniffing(own, SniffingDecision(enabled = false, replacesOwn = true), fakeDns = false) shouldBe
            EmittedSniffing.Generated(listOf("fakedns"))
        emittedSniffing(own, SniffingDecision(enabled = true, replacesOwn = true), fakeDns = false) shouldBe
            EmittedSniffing.Generated(listOf("http", "tls", "quic", "fakedns"))
    }

    @Test
    fun `an own block that is disabled does not keep fakedns`() {
        val own =
            Json.parseToJsonElement(
                """{ "enabled": false, "destOverride": ["fakedns"] }""",
            ) as JsonObject
        emittedSniffing(own, SniffingDecision(enabled = false, replacesOwn = true), fakeDns = false) shouldBe
            EmittedSniffing.None
    }
}
