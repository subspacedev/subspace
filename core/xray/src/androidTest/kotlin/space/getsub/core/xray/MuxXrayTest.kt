// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.xray

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import space.getsub.core.model.Applied
import space.getsub.core.model.MuxSpec
import space.getsub.core.model.Outbound
import space.getsub.core.model.Profile
import space.getsub.core.model.Security
import space.getsub.core.model.ShadowsocksOutbound
import space.getsub.core.model.StreamSettings
import space.getsub.core.model.TrojanOutbound
import space.getsub.core.model.Udp443Mode
import space.getsub.core.model.VlessOutbound
import space.getsub.core.model.VmessOutbound

/**
 * M9-E spec §3: every protocol Subspace writes `mux` for, with each `xudpProxyUDP443` value, and a raw
 * config [MuxInjector] rewrites, against the real libXray v26.10.10 core (`testXray` through
 * [XrayController.validate], which runs `core.New`). `XrayConfigGeneratorMuxTest` pins the bytes; only
 * the core proves they build.
 *
 * Failure messages carry a case label of our own making, never the core's message, which can quote the
 * config back (ARCHITECTURE.md §5.6). Hosts without TLS are private literals, which the core's plaintext
 * rule allows. Camel-case names, no backticks: DEX 040 restricts synthetic class names in instrumented tests.
 */
@RunWith(AndroidJUnit4::class)
class MuxXrayTest {
    private val uuid = "70cc48c5-b2f4-4a1e-9f3d-0123456789ab"
    private val privateHost = "192.168.1.10"
    private val tls = Security.Tls("k.example.com", "chrome", allowInsecure = false)

    private fun settings(spec: MuxSpec) =
        TunnelSettings(
            socksPort = 41080,
            dnsServer = "1.1.1.1",
            enableSniffing = true,
            proxyCredentials = TEST_CREDENTIALS,
            mux = Applied(spec, replaceOwn = true),
        )

    private suspend fun accepted(json: String): Boolean = runCatching { XrayController().validate(json) }.isSuccess

    private val eligible: List<Pair<String, Outbound>> =
        listOf(
            "vless-ws-tls" to VlessOutbound("k.example.com", 443, uuid, null, StreamSettings("ws", tls)),
            "vless-tcp-plain" to VlessOutbound(privateHost, 443, uuid, null, StreamSettings("tcp", Security.None)),
            "vmess-tcp-tls" to VmessOutbound("k.example.com", 443, uuid, 0, "auto", StreamSettings("tcp", tls)),
            "trojan-tcp-tls" to TrojanOutbound("k.example.com", 443, "pw", StreamSettings("tcp", tls)),
            "shadowsocks" to ShadowsocksOutbound(privateHost, 8388, "aes-256-gcm", "pw"),
        )

    @Test
    fun everyEligibleProtocolWithMuxIsAcceptedByTheRealCore() =
        runTest {
            val rejected =
                eligible.flatMap { (label, outbound) ->
                    Udp443Mode.entries.mapNotNull { mode ->
                        val caseLabel = "$label ${mode.wire}"
                        val result =
                            XrayConfigGenerator.generate(
                                Profile(id = "id", name = "n", outbound = outbound),
                                settings(MuxSpec(8, 16, mode)),
                            )
                        when {
                            result !is ConfigResult.Ok -> "$caseLabel (generator refused)"
                            !result.json.contains("\"mux\"") -> "$caseLabel (no mux written)"
                            !accepted(result.json) -> "$caseLabel (core refused)"
                            else -> null
                        }
                    }
                }
            assertEquals("rejected mux cases", emptyList<String>(), rejected)
        }

    @Test
    fun theLimitsOfTheConnectionCountsAreAcceptedByTheRealCore() =
        runTest {
            val vmess = eligible.first { it.first == "vmess-tcp-tls" }.second
            val rejected =
                listOf(
                    MuxSpec(-1, -1, Udp443Mode.Skip),
                    MuxSpec(0, 0, Udp443Mode.Reject),
                    MuxSpec(1024, 1024, Udp443Mode.Allow)
                )
                    .mapNotNull { spec ->
                        val label = "${spec.concurrency}/${spec.xudpConcurrency}"
                        val result =
                            XrayConfigGenerator.generate(
                                Profile(id = "id", name = "n", outbound = vmess),
                                settings(spec)
                            )
                        if (result is ConfigResult.Ok && accepted(result.json)) null else label
                    }
            assertEquals("rejected connection counts", emptyList<String>(), rejected)
        }

    @Test
    fun aRawConfigWithInjectedMuxIsAcceptedByTheRealCore() =
        runTest {
            val raw =
                """{"outbounds":[
                    {"tag":"proxy","protocol":"vmess","settings":{"vnext":[{"address":"$privateHost","port":443,
                      "users":[{"id":"$uuid","security":"auto"}]}]},"mux":{"enabled":true,"concurrency":2}},
                    {"tag":"direct","protocol":"freedom"}]}"""
            val result =
                RawConfigComposer.compose(
                    raw,
                    settings(MuxSpec(8, 16, Udp443Mode.Skip)),
                    "/nonexistent",
                    override = null
                )
            val rejected =
                when {
                    result !is ComposeResult.Ok -> listOf("raw (composer refused)")
                    !accepted(result.json) -> listOf("raw (core refused)")
                    else -> emptyList()
                }
            assertEquals("rejected raw mux", emptyList<String>(), rejected)
        }
}
