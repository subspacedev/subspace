// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import kotlinx.coroutines.test.runTest
import org.junit.Test
import space.getsub.core.model.ProxyAuthMode
import space.getsub.core.model.ProxyAuthSettings
import space.getsub.core.model.ProxyCredentials

/**
 * The per-session credential rule (M9-E spec §5.2, Task 9 Review Focus 1).
 *
 * hev and the core must hold the same account, and `:main` and `:ping` are told it once per
 * `Connected`. So a session mints once and every core start in it (retained-TUN restarts and
 * server switches included) reuses that value; only a new session may mint again.
 */
class SessionCredentialsTest {
    private val existing = ProxyCredentials("existing-user", "existing-pass")
    private val manual = ProxyAuthSettings(ProxyAuthMode.Manual, "manual-user", "manual-pass")

    @Test
    fun `auto keeps the existing credentials`() {
        sessionCredentials(ProxyAuthSettings.DEFAULT, existing) shouldBe existing
    }

    @Test
    fun `auto with nothing existing mints a random alphanumeric account`() {
        val minted = sessionCredentials(ProxyAuthSettings.DEFAULT, existing = null)
        minted.user shouldMatch Regex("[A-Za-z0-9]{16}")
        minted.pass shouldMatch Regex("[A-Za-z0-9]{32}")
        sessionCredentials(ProxyAuthSettings.DEFAULT, existing = null) shouldNotBe minted
    }

    @Test
    fun `manual uses what the user typed`() {
        sessionCredentials(manual, existing) shouldBe ProxyCredentials("manual-user", "manual-pass")
    }

    @Test
    fun `manual with an empty field falls back to auto`() {
        // The Settings page shows a field error; the tunnel must still start authenticated.
        sessionCredentials(manual.copy(manualPass = ""), existing) shouldBe existing
        sessionCredentials(manual.copy(manualUser = ""), existing) shouldBe existing
        val minted = sessionCredentials(manual.copy(manualUser = ""), existing = null)
        minted.user shouldMatch Regex("[A-Za-z0-9]{16}")
    }

    @Test
    fun `manual values SOCKS5 or hev's YAML cannot carry fall back to auto`() {
        sessionCredentials(manual.copy(manualPass = "x".repeat(256)), existing) shouldBe existing
        sessionCredentials(manual.copy(manualUser = "line\nbreak"), existing) shouldBe existing
        sessionCredentials(manual.copy(manualPass = "x".repeat(255)), existing).pass shouldBe "x".repeat(255)
    }

    @Test
    fun `a manual user name with a colon falls back to auto, a password with one does not`() {
        // HTTP Basic splits at the first colon: the app's own fetches would get 407 while connected.
        sessionCredentials(manual.copy(manualUser = "al:ice"), existing) shouldBe existing
        sessionCredentials(manual.copy(manualPass = "pa:ss"), existing) shouldBe ProxyCredentials("manual-user", "pa:ss")
    }

    @Test
    fun `a core start in a session that already holds credentials reuses them without reading settings`() =
        runTest {
            var reads = 0
            val reused =
                credentialsForCoreStart(sessionOwn = existing) {
                    reads++
                    manual
                }
            reused shouldBe existing
            reads shouldBe 0
        }

    @Test
    fun `the first core start of a session mints from the current settings`() =
        runTest {
            credentialsForCoreStart(sessionOwn = null) { manual } shouldBe
                ProxyCredentials("manual-user", "manual-pass")
            val auto = credentialsForCoreStart(sessionOwn = null) { ProxyAuthSettings.DEFAULT }
            auto.pass shouldMatch Regex("[A-Za-z0-9]{32}")
        }

    @Test
    fun `every start of one session sees the value its first start minted`() =
        runTest {
            // Models TunnelService: the session slot starts empty, each start stores what it used.
            var slot: ProxyCredentials? = null
            val seen =
                List(3) {
                    credentialsForCoreStart(slot) { ProxyAuthSettings.DEFAULT }.also { slot = it }
                }
            seen.distinct().size shouldBe 1
            // A new session starts with an empty slot and may mint a different value.
            credentialsForCoreStart(sessionOwn = null) { ProxyAuthSettings.DEFAULT } shouldNotBe seen.first()
        }
}
