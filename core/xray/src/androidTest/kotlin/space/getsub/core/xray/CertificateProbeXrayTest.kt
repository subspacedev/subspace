// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.xray

import androidx.test.platform.app.InstrumentationRegistry
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import space.getsub.core.model.Hysteria2Obfuscation
import space.getsub.core.model.Hysteria2Outbound
import space.getsub.core.model.Hysteria2Tls
import space.getsub.core.model.LatencyOutcome
import space.getsub.core.model.LatencyResult
import space.getsub.core.model.PortHopping
import space.getsub.core.model.Profile
import space.getsub.core.model.ProxyCredentialsFactory
import java.io.File

/**
 * The certificate probe against the real libXray v26.10.10 core (M9-D1 spec §2).
 *
 * ARCHITECTURE.md §10.5: [CertificateProbeTest] proves what the probe config contains; only the core
 * proves it is accepted. The second test runs one whole probe in this process and then a `pingBatch`,
 * which refuses outright if the probe left a managed instance behind — the invariant `:ping` rests on.
 *
 * Failure messages carry labels of our own, never the core's text (ARCHITECTURE.md §5.6).
 * Camel-case names, no backticks: DEX 040 restricts synthetic class names in instrumented tests.
 */
class CertificateProbeXrayTest {
    private val dir: File =
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "cert-probe-test").apply {
            deleteRecursively()
            mkdirs()
        }

    private val plain =
        Hysteria2Outbound(
            address = "127.0.0.1",
            port = 1,
            password = "hy2-password",
            tls = Hysteria2Tls(serverName = "hy.test"),
        )

    private fun rowConfig(outbound: Hysteria2Outbound): String =
        checkNotNull(pingConfig(Profile(id = "1", name = "n", outbound = outbound), obfuscation = null)) {
            "no ping config"
        }

    @After
    fun cleanUp() {
        // pingBatch's precondition for every other test in this module; stopXray with nothing running succeeds.
        LibXrayInvoke.call("stopXray")
        dir.deleteRecursively()
    }

    @Test
    fun theProbeConfigPassesTestXrayForEveryHysteria2Shape() =
        runTest {
            val shapes =
                mapOf(
                    "plain" to plain,
                    "pinned" to plain.copy(tls = plain.tls.copy(pins = listOf("ab".repeat(32)))),
                    "salamander+hopping" to
                        plain.copy(
                            obfuscation = Hysteria2Obfuscation.Salamander("obfs-password"),
                            hopping = PortHopping(ports = listOf(20000..20010)),
                        ),
                )
            val rejected =
                shapes.filter { (_, outbound) ->
                    val config =
                        CertificateProbe.config(
                            rowConfig(outbound),
                            loopbackFreePort(),
                            File(dir, "v.log").absolutePath,
                            ProxyCredentialsFactory.random(),
                        )
                    @Suppress("TooGenericExceptionCaught", "SwallowedException")
                    try {
                        XrayController().validate(checkNotNull(config))
                        false
                    } catch (e: Exception) {
                        true
                    }
                }.keys

            rejected.toList().shouldBeEmpty()
        }

    @Test
    fun aProbeLeavesNoInstanceAndNoFileBehind() {
        // Nothing listens on 127.0.0.1:1: the handshake never happens, so no certificate verdict.
        val verdict = CertificateProbeRunner(dir).probe(rowConfig(plain), CHECK_URL)

        verdict shouldNotBe CertificateProbeResult.STOP_FAILED
        verdict shouldNotBe CertificateProbeResult.UNTRUSTED
        // Xray's logger goroutine closes its file asynchronously (common/log/logger.go:104-113), so
        // give the directory up to about a second to settle. No sweep here: a file the goroutine
        // re-created after the runner's delayed re-sweep should fail this, not be hidden by it.
        dirSettlesEmpty() shouldBe true
        // pingBatch refuses the whole call (XrayException) while a managed instance exists; a
        // per-item failure in the returned data proves there is none.
        val request = PingBatch.request(listOf(PingTarget(1L, rowConfig(plain))), timeoutSeconds = 2, url = CHECK_URL)
        val data = LibXrayPingBatchCall().invoke(request)
        PingBatch.results(listOf(PingTarget(1L, "")), data) shouldBe
            mapOf(1L to LatencyResult.failed(LatencyOutcome.UNREACHABLE))
        (data != null) shouldBe true
        LibXrayInvoke.call("getXrayState")?.optBoolean("running", false) shouldBe false
    }

    private fun dirSettlesEmpty(): Boolean {
        repeat(SETTLE_TRIES) {
            if (dir.listFiles().orEmpty().isEmpty()) return true
            Thread.sleep(SETTLE_STEP_MILLIS)
        }
        return dir.listFiles().orEmpty().isEmpty()
    }

    private companion object {
        const val SETTLE_TRIES = 10
        const val SETTLE_STEP_MILLIS = 100L

        const val CHECK_URL = "https://www.gstatic.com/generate_204"
    }
}
