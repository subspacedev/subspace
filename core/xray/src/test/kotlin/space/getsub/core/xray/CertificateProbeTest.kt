// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.xray

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import space.getsub.core.model.Hysteria2Outbound
import space.getsub.core.model.Hysteria2Tls
import space.getsub.core.model.Profile
import java.io.File
import java.io.IOException

/**
 * The certificate probe's config (M9-D1 spec §2, "How the error is obtained") and the
 * run around it: start, one HEAD, stop, read, delete, classify.
 */
class CertificateProbeTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val password = "hy2-secret-password"
    private val hysteria2 =
        Hysteria2Outbound(
            address = "hy.example.com",
            port = 443,
            password = password,
            tls = Hysteria2Tls(serverName = "hy.example.com"),
        )

    /** The row's generated config, exactly as `:bg` sends it to `:ping`. */
    private val rowConfig: String =
        checkNotNull(pingConfig(Profile(id = "1", name = "n", outbound = hysteria2), obfuscation = null))

    private fun parse(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    private fun probeConfig(): JsonObject =
        parse(checkNotNull(CertificateProbe.config(rowConfig, port = 23456, logFile = "/cache/cert-probe/a.log")))

    // ── Config shape ─────────────────────────────────────────────────────────

    @Test
    fun theCoreLogsAtInfoToTheFileOnlyWithNoAccessLog() {
        val log = probeConfig().getValue("log").jsonObject

        log.getValue("loglevel").jsonPrimitive.content shouldBe "info"
        log.getValue("error").jsonPrimitive.content shouldBe "/cache/cert-probe/a.log"
        log.getValue("access").jsonPrimitive.content shouldBe "none"
        log.keys shouldBe setOf("loglevel", "error", "access")
    }

    @Test
    fun theOnlyOutboundIsTheRowsOwnProxyOutbound() {
        val outbounds = probeConfig().getValue("outbounds").jsonArray
        val rowProxy =
            parse(rowConfig).getValue("outbounds").jsonArray.single {
                it.jsonObject["tag"]?.jsonPrimitive?.content == "proxy"
            }

        outbounds.size shouldBe 1
        outbounds.single() shouldBe rowProxy
    }

    @Test
    fun theOnlyInboundListensOnLoopback() {
        val inbound = probeConfig().getValue("inbounds").jsonArray.single().jsonObject

        inbound.getValue("listen").jsonPrimitive.content shouldBe "127.0.0.1"
        inbound.getValue("port").jsonPrimitive.int shouldBe 23456
        inbound.getValue("protocol").jsonPrimitive.content shouldBe "http"
    }

    @Test
    fun everythingIsRoutedToTheProxyAndNothingElseIsConfigured() {
        val config = probeConfig()
        val rules = config.getValue("routing").jsonObject.getValue("rules").jsonArray

        rules.map { it.jsonObject.getValue("outboundTag").jsonPrimitive.content } shouldContainExactly listOf("proxy")
        config.keys shouldBe setOf("log", "inbounds", "outbounds", "routing")
    }

    @Test
    fun aDialerProxyDependencyComesAlongButNothingElse() {
        val chained =
            """{"outbounds":[
              {"tag":"proxy","protocol":"vless","streamSettings":{"sockopt":{"dialerProxy":"frag"}}},
              {"tag":"direct","protocol":"freedom"},
              {"tag":"frag","protocol":"freedom","settings":{"fragment":{}}}]}"""
        val outbounds =
            parse(checkNotNull(CertificateProbe.config(chained, 1, "/f"))).getValue("outbounds").jsonArray

        outbounds.map { it.jsonObject.getValue("tag").jsonPrimitive.content } shouldContainExactly
            listOf("proxy", "frag")
    }

    @Test
    fun aConfigWithNoProxyOutboundOrNoJsonHasNoProbe() {
        CertificateProbe.config("""{"outbounds":[{"tag":"direct","protocol":"freedom"}]}""", 1, "/f") shouldBe null
        CertificateProbe.config("not json $password", 1, "/f") shouldBe null
        CertificateProbe.config(
            """{"outbounds":[{"tag":"proxy","streamSettings":{"sockopt":{"dialerProxy":"missing"}}}]}""",
            1,
            "/f",
        ) shouldBe null
    }

    // ── The run ──────────────────────────────────────────────────────────────

    private val untrustedLine =
        "[Info] [1] app/proxyman/outbound: failed to process outbound traffic > proxy/hysteria: failed to find " +
            "an available destination > INTERNAL_ERROR (local): tls: failed to verify certificate: " +
            "x509: certificate signed by unknown authority"

    private class FakeCore(
        val onStart: (String) -> Unit = {},
        val startThrows: Boolean = false,
        val stopThrows: Boolean = false,
    ) : ProbeCore {
        val events = mutableListOf<String>()
        var lastConfig: String? = null

        override fun start(xrayJson: String) {
            events += "start"
            lastConfig = xrayJson
            if (startThrows) throw XrayException("libXray runXray failed: hy2-secret-password")
            onStart(xrayJson)
        }

        override fun stop() {
            events += "stop"
            if (stopThrows) throw XrayException("libXray stopXray failed")
        }
    }

    /** The log file the fake core was told to write, from the config it was started with. */
    private fun FakeCore.logFile(): File =
        File(parse(checkNotNull(lastConfig)).getValue("log").jsonObject.getValue("error").jsonPrimitive.content)

    private fun runner(
        core: ProbeCore,
        connect: ProbeConnect = ProbeConnect { _, _ -> throw IOException("closed") },
        dir: File = temp.newFolder(),
        onSleep: (Long) -> Unit = {},
    ): CertificateProbeRunner {
        // A clock that moves only when the runner sleeps, so the settle wait is bounded and instant.
        var now = 0L
        return CertificateProbeRunner(
            logDir = dir,
            core = core,
            connect = connect,
            freePort = { 34567 },
            sleep = {
                onSleep(it)
                now += it
            },
            nowMillis = { now },
        )
    }

    @Test
    fun anUntrustedCertificateInTheLogIsNamedAndTheFileIsDeleted() {
        lateinit var core: FakeCore
        core = FakeCore(onStart = { core.logFile().writeText("[Info] started\n$untrustedLine\n") })
        val dir = temp.newFolder("probe-dir")

        runner(core, dir = dir).probe(rowConfig, "https://www.gstatic.com/generate_204") shouldBe
            CertificateProbeResult.UNTRUSTED

        core.events shouldContainExactly listOf("start", "stop")
        core.logFile().exists() shouldBe false
        dir.listFiles().orEmpty().toList().shouldBeEmpty()
    }

    @Test
    fun aLogWithoutACertificateLineIsNotACertificateFailure() {
        lateinit var core: FakeCore
        core = FakeCore(onStart = { core.logFile().writeText("[Info] timeout: no recent network activity\n") })

        runner(core).probe(rowConfig, "https://x.invalid") shouldBe CertificateProbeResult.NOT_A_CERTIFICATE_FAILURE
        core.logFile().exists() shouldBe false
    }

    @Test
    fun theInstanceIsStoppedEvenWhenTheExchangeThrowsSomethingUnexpected() {
        lateinit var core: FakeCore
        core = FakeCore(onStart = { core.logFile().writeText(untrustedLine) })

        val throwing = runner(core, connect = { _, _ -> throw IllegalStateException(password) })

        throwing.probe(rowConfig, "https://x.invalid") shouldBe CertificateProbeResult.UNTRUSTED
        core.events shouldContainExactly listOf("start", "stop")
        core.logFile().exists() shouldBe false
    }

    @Test
    fun anExchangeThatSucceedsStillStopsAndDeletes() {
        lateinit var core: FakeCore
        core = FakeCore(onStart = { core.logFile().writeText("[Info] tunnelling\n") })

        runner(core, connect = { _, _ -> }).probe(rowConfig, "https://x.invalid") shouldBe
            CertificateProbeResult.NOT_A_CERTIFICATE_FAILURE
        core.events shouldContainExactly listOf("start", "stop")
        core.logFile().exists() shouldBe false
    }

    @Test
    fun anInstanceThatFailsToStartDidNotRunAndLeavesNoFile() {
        val core = FakeCore(startThrows = true)
        val dir = temp.newFolder("probe-start")

        runner(core, dir = dir).probe(rowConfig, "https://x.invalid") shouldBe CertificateProbeResult.DID_NOT_RUN
        // A stop after a failed start costs nothing and proves no instance is left.
        core.events shouldContainExactly listOf("start", "stop")
        dir.listFiles().orEmpty().toList().shouldBeEmpty()
    }

    @Test
    fun aFailedStartWhoseStopAlsoFailsIsReportedAsAFailedStop() {
        val core = FakeCore(startThrows = true, stopThrows = true)

        // Over-reports on purpose (one retired :ping), never under-reports.
        runner(core).probe(rowConfig, "https://x.invalid") shouldBe CertificateProbeResult.STOP_FAILED
    }

    @Test
    fun aFileTheCoreReCreatesAfterTheFinalSweepIsSweptAgain() {
        val dir = temp.newFolder("probe-resweep")
        val late = File(dir, "late.log")
        // The resweep's sleep is when Xray's logger goroutine could re-create its file (O_CREATE).
        val probe =
            runner(FakeCore(), dir = dir, onSleep = { if (it == RESWEEP) late.writeText(untrustedLine) })

        probe.probe(rowConfig, "https://x.invalid")

        late.exists() shouldBe false
    }

    @Test
    fun aFailedStopIsReportedSoThePingProcessCanRetire() {
        lateinit var core: FakeCore
        core = FakeCore(stopThrows = true, onStart = { core.logFile().writeText(untrustedLine) })

        runner(core).probe(rowConfig, "https://x.invalid") shouldBe CertificateProbeResult.STOP_FAILED
        core.logFile().exists() shouldBe false
    }

    @Test
    fun aConfigThatCannotBeProbedNeverStartsTheCore() {
        val core = FakeCore()

        runner(core).probe("""{"outbounds":[]}""", "https://x.invalid") shouldBe CertificateProbeResult.DID_NOT_RUN
        core.events.shouldBeEmpty()
    }

    @Test
    fun aStaleFileFromAnEarlierProcessIsSweptAway() {
        val dir = temp.newFolder("probe-stale")
        val stale = File(dir, "old.log").apply { writeText(untrustedLine) }

        runner(FakeCore(), dir = dir).probe(rowConfig, "https://x.invalid")

        stale.exists() shouldBe false
    }

    @Test
    fun theExchangeGoesThroughTheProbesOwnLoopbackPortToTheCheckHost() {
        val seen = mutableListOf<Pair<Int, String>>()
        val probe = runner(FakeCore(), connect = { port, target -> seen += port to target })

        probe.probe(rowConfig, "https://u.invalid/204")
        probe.probe(rowConfig, "http://cleartext.invalid/generate_204")

        seen shouldContainExactly listOf(34567 to "u.invalid:443", 34567 to "cleartext.invalid:80")
    }

    @Test
    fun aCheckUrlWithNoUsableTargetNeverStartsTheCore() {
        val core = FakeCore()

        runner(core).probe(rowConfig, "ftp://x.invalid/") shouldBe CertificateProbeResult.DID_NOT_RUN
        runner(core).probe(rowConfig, "not a url") shouldBe CertificateProbeResult.DID_NOT_RUN
        core.events.shouldBeEmpty()
    }

    @Test
    fun theConnectTargetFollowsTheUrlsPortOrItsSchemesDefault() {
        CertificateProbe.connectTarget("https://www.gstatic.com/generate_204") shouldBe "www.gstatic.com:443"
        CertificateProbe.connectTarget("http://cp.cloudflare.com/") shouldBe "cp.cloudflare.com:80"
        CertificateProbe.connectTarget("HTTP://cp.cloudflare.com") shouldBe "cp.cloudflare.com:80"
        CertificateProbe.connectTarget("http://example.com:8080/x") shouldBe "example.com:8080"
        CertificateProbe.connectTarget("https://[2001:db8::1]/x") shouldBe "[2001:db8::1]:443"
        CertificateProbe.connectTarget("ftp://example.com/") shouldBe null
        CertificateProbe.connectTarget("ftp://example.com:21/") shouldBe null
        CertificateProbe.connectTarget("https:///nohost") shouldBe null
    }

    @Test
    fun theRunNeverThrowsEvenWhenEverySeamThrowsTheSecret() {
        val core =
            object : ProbeCore {
                override fun start(xrayJson: String) = throw IllegalStateException(password)

                override fun stop() = throw IllegalStateException(password)
            }

        // Start and stop both fail: reported as a failed stop, never thrown.
        runner(core).probe(rowConfig, "https://x.invalid") shouldBe CertificateProbeResult.STOP_FAILED
        CertificateProbeRunner(
            logDir = temp.newFolder(),
            core = FakeCore(),
            connect = { _, _ -> },
            freePort = { throw IOException(password) },
        ).probe(rowConfig, "https://x.invalid") shouldBe CertificateProbeResult.DID_NOT_RUN
    }

    private companion object {
        /** The runner's delayed re-sweep (CertificateProbe.kt RESWEEP_DELAY_MILLIS). */
        const val RESWEEP = 200L
    }
}
