// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.xray

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import space.getsub.core.model.Applied
import space.getsub.core.model.FragmentPackets
import space.getsub.core.model.FragmentSpec
import space.getsub.core.model.NoiseItem
import space.getsub.core.model.NoisePacketType
import space.getsub.core.model.NoisePayload
import space.getsub.core.model.NoiseSpec
import space.getsub.core.model.Obfuscation
import space.getsub.core.model.Profile
import space.getsub.core.model.Security
import space.getsub.core.model.StreamSettings
import space.getsub.core.model.VlessOutbound
import space.getsub.core.model.XrayRange
import java.io.File

/**
 * ARCHITECTURE.md §10.1/§10.5: the JVM tests prove we emit what we believe Finalmask takes. This
 * proves the pinned core's config build accepts every shape M9-A can produce.
 */
@RunWith(AndroidJUnit4::class)
class ObfuscationXrayTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val settings =
        TunnelSettings(
            socksPort = 41080,
            dnsServer = "1.1.1.1",
            enableSniffing = true,
            proxyCredentials = TEST_CREDENTIALS,
        )
    private val fragment =
        FragmentSpec(FragmentPackets.TlsHello, XrayRange(50, 100), XrayRange(10, 20), XrayRange(10, 10))
    private val records = fragment.copy(packets = FragmentPackets.Records(XrayRange(1, 3)), maxSplit = null)
    private val noise =
        NoiseSpec(
            listOf(
                NoiseItem(NoisePayload.Random(XrayRange(10, 20), XrayRange(0, 255)), XrayRange(10, 16)),
                NoiseItem(NoisePayload.Fixed(NoisePacketType.Str, "abc"), XrayRange(0, 0)),
                NoiseItem(NoisePayload.Fixed(NoisePacketType.Hex, "0aff"), XrayRange(1, 1)),
                NoiseItem(NoisePayload.Fixed(NoisePacketType.Base64, "aGVsbG8="), XrayRange(1, 1)),
                NoiseItem(NoisePayload.Fixed(NoisePacketType.Array, "1,2,255"), XrayRange(1, 1)),
            ),
        )

    private suspend fun validate(json: String) {
        val outcome = runCatching { XrayController().validate(json) }
        // ARCHITECTURE.md §5.6: only the exception's class name, never its message.
        check(outcome.isSuccess) { "core rejected: ${outcome.exceptionOrNull()?.javaClass?.simpleName}" }
    }

    private suspend fun typed(obfuscation: Obfuscation) {
        val profile = Profile("id", "n", GOLDEN_OUTBOUND_ANDROID)
        val result = XrayConfigGenerator.generate(profile, settings.copy(obfuscation = obfuscation))
        validate((result as ConfigResult.Ok).json)
    }

    private suspend fun raw(
        rawJson: String,
        obfuscation: Obfuscation,
    ) {
        val assetDir = File(context.filesDir, "geo").apply { mkdirs() }
        val withObfuscation = settings.copy(obfuscation = obfuscation)
        val composed = RawConfigComposer.compose(rawJson, withObfuscation, assetDir.absolutePath, null)
        validate((composed as ComposeResult.Ok).json)
    }

    @Test fun typedFragmentTlsHello() = runTest { typed(Obfuscation(Applied(fragment, false), null)) }

    @Test fun typedFragmentRecordsNoMaxSplit() = runTest { typed(Obfuscation(Applied(records, false), null)) }

    // Ruling R6: a udp mask on a TCP outbound. If this fails, stop emitting noise on the typed path.
    @Test fun typedFragmentAndEveryNoiseType() =
        runTest {
            typed(Obfuscation(Applied(fragment, false), Applied(noise, false)))
        }

    // Review Focus 3: the freedom fragmenter is still named by a routing rule after its link is cut.
    @Test fun rawChainCutWhileARuleStillNamesTheFreedomOutbound() =
        runTest {
            raw(
                """
                {"routing":{"rules":[{"type":"field","domain":["example.com"],"outboundTag":"frag"}]},
                 "outbounds":[
                  {"tag":"proxy","protocol":"vless","settings":{"vnext":[{"address":"192.0.2.1","port":443,
                    "users":[{"id":"00000000-0000-0000-0000-000000000001","encryption":"none"}]}]},
                   "streamSettings":{"network":"tcp","security":"none","sockopt":{"dialerProxy":"frag"}}},
                  {"tag":"frag","protocol":"freedom",
                   "settings":{"fragment":{"packets":"tlshello","length":"100-200","interval":"10-20"}}},
                  {"tag":"direct","protocol":"freedom"}]}
                """.trimIndent(),
                Obfuscation(Applied(fragment, replaceOwn = true), Applied(noise, replaceOwn = true)),
            )
        }

    @Test fun rawWithServerPairedMaskKeptAlongsideOurs() =
        runTest {
            raw(
                """
                {"outbounds":[
                  {"tag":"proxy","protocol":"vless","settings":{"vnext":[{"address":"192.0.2.1","port":443,
                    "users":[{"id":"00000000-0000-0000-0000-000000000001","encryption":"none"}]}]},
                   "streamSettings":{"network":"tcp","security":"none",
                     "finalmask":{"tcp":[{"type":"sudoku","settings":{"password":"x"}}]}}}]}
                """.trimIndent(),
                Obfuscation(Applied(fragment, replaceOwn = false), null),
            )
        }
}

// Same shape as GoldenProfile.kt's GOLDEN_OUTBOUND (a JVM test source, invisible here), placeholder values.
private val GOLDEN_REALITY_ANDROID =
    Security.Reality(
        serverName = "www.microsoft.com",
        publicKey = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8",
        shortId = "0123abcd",
        fingerprint = "chrome",
        spiderX = "/",
    )

private val GOLDEN_OUTBOUND_ANDROID =
    VlessOutbound(
        address = "example.com",
        port = 443,
        uuid = "70cc48c5-b2f4-4a1e-9f3d-0123456789ab",
        flow = "xtls-rprx-vision",
        stream = StreamSettings(network = "tcp", security = GOLDEN_REALITY_ANDROID),
    )
