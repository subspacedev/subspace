// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.profiles.editor

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import space.getsub.core.data.PendingRoutingConversion
import space.getsub.core.data.ProfileKind
import space.getsub.core.data.StoredProfile
import space.getsub.core.model.HttpOutbound
import space.getsub.core.model.KcpHeader
import space.getsub.core.model.KcpObfuscation
import space.getsub.core.model.KcpRules
import space.getsub.core.model.KcpUnsupported
import space.getsub.core.model.Outbound
import space.getsub.core.model.Security
import space.getsub.core.model.StreamSettings
import space.getsub.core.model.TransportOptions
import space.getsub.core.model.TrojanOutbound
import space.getsub.core.model.VlessOutbound
import space.getsub.core.model.WireGuardOutbound
import space.getsub.core.model.WireGuardUnsupported
import java.util.Base64

/** WireGuard, HTTP, mKCP and HTTPUpgrade in the server editor: load, edit, validate, save (M9-D2 Task 7). */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LargeClass", "TooManyFunctions") // One short test per rule; splitting would only scatter the fixtures.
class D2EditorTest {
    private fun key(byte: Int) = Base64.getEncoder().encodeToString(ByteArray(32) { byte.toByte() })

    private val secretKey = key(1)
    private val peerKey = key(2)
    private val pskKey = key(3)
    private val uuid = "11111111-1111-1111-1111-111111111111"
    private val realityKey = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 2 })
    private val seedSecret = "seed secret with spaces "
    private val proxyPassword = "proxy-pass-123"

    private val wg =
        WireGuardOutbound(
            address = "198.51.100.7",
            port = 51820,
            secretKey = secretKey,
            localAddresses = listOf("10.0.0.2/32", "fd00::2/128"),
            peerPublicKey = peerKey,
            preSharedKey = pskKey,
            mtu = 1280,
            reserved = listOf(1, 2, 3),
            keepAlive = 25,
            allowedIps = listOf("10.0.0.0/8", "::/0"),
        )

    private val http =
        HttpOutbound(
            address = "proxy.example",
            port = 8443,
            username = "alice",
            password = proxyPassword,
            security = Security.Tls("proxy.example", "chrome", allowInsecure = true),
        )

    private fun vless(
        transport: TransportOptions,
        network: String,
        security: Security = Security.Tls("sni.example", "chrome", false),
    ) = VlessOutbound("198.51.100.8", 443, uuid, null, StreamSettings(network, security, transport))

    private val kcpFull =
        TransportOptions.Kcp(
            mtu = 1350,
            tti = 20,
            obfuscation = KcpObfuscation(seedSecret, KcpHeader.SRTP, legacyOriginal = true),
        )

    private fun profileOf(outbound: Outbound) =
        StoredProfile(
            id = 1L,
            groupId = 10L,
            kind = ProfileKind.TYPED,
            name = "Row",
            protocol =
            when (outbound) {
                is WireGuardOutbound -> "wireguard"
                is HttpOutbound -> "http"
                is TrojanOutbound -> "trojan"
                else -> "vless"
            },
            address = outbound.address,
            port = outbound.port,
            transport = "x",
            outbound = outbound,
            rawJson = null,
            lastConnectedAt = null,
            lastError = null,
        )

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun loaded(outbound: Outbound): Pair<EditorViewModel, Hysteria2EditorTest.Source> {
        val source = Hysteria2EditorTest.Source(profileOf(outbound))
        val viewModel =
            EditorViewModel(source, PendingRoutingConversion()).apply {
                setConversionDispatcherForTesting(Dispatchers.Unconfined)
            }
        viewModel.load(1L)
        return viewModel to source
    }

    private fun roundTrip(outbound: Outbound) =
        runTest {
            val (viewModel, source) = loaded(outbound)
            advanceUntilIdle()
            viewModel.save()
            advanceUntilIdle()
            source.lastUpdate shouldBe outbound
        }

    private fun EditorViewModel.editWg(change: (WireGuardDraft) -> WireGuardDraft) =
        onWireGuardChanged(change(state.value.wireGuard))

    private fun EditorViewModel.editKcp(change: (KcpDraft) -> KcpDraft) = onKcpChanged(change(state.value.kcp))

    // ---- WireGuard -------------------------------------------------------------------------------

    @Test
    fun `an untouched WireGuard save writes back every field`() = roundTrip(wg)

    @Test
    fun `a minimal WireGuard server round-trips`() =
        roundTrip(
            wg.copy(
                preSharedKey = null,
                reserved = emptyList(),
                keepAlive = null,
                mtu = 1420,
                allowedIps = listOf("0.0.0.0/0", "::/0"),
                localAddresses = listOf("10.0.0.2"),
            ),
        )

    @Test
    fun `a WireGuard row that is not connectable keeps its reason through a save`() {
        roundTrip(wg.copy(notConnectableReason = WireGuardUnsupported.MultiplePeers))
        roundTrip(wg.copy(notConnectableReason = WireGuardUnsupported.UnsupportedMask))
    }

    @Test
    fun `an untouched save of the importer's projection of a bad mtu or reserved keeps the reason`() {
        // The raw-JSON importer stores the projected value (mtu 1420, no reserved), never the bad one.
        listOf(WireGuardUnsupported.InvalidMtu, WireGuardUnsupported.InvalidReserved).forEach { reason ->
            val projected = wg.copy(mtu = 1420, reserved = emptyList(), notConnectableReason = reason)
            roundTrip(projected)
        }
    }

    @Test
    fun `editing the field a bad-value reason is about clears it`() =
        runTest {
            val mtuRow =
                wg.copy(mtu = 1420, reserved = emptyList(), notConnectableReason = WireGuardUnsupported.InvalidMtu)
            val (viewModel, source) = loaded(mtuRow)
            advanceUntilIdle()
            viewModel.editWg { it.copy(reserved = "1,2,3") }
            viewModel.save()
            advanceUntilIdle()
            (source.lastUpdate as WireGuardOutbound).notConnectableReason shouldBe WireGuardUnsupported.InvalidMtu

            viewModel.editWg { it.copy(mtu = "1400") }
            viewModel.save()
            advanceUntilIdle()
            source.lastUpdate shouldBe mtuRow.copy(mtu = 1400, reserved = listOf(1, 2, 3), notConnectableReason = null)

            val reservedRow = mtuRow.copy(notConnectableReason = WireGuardUnsupported.InvalidReserved)
            val (second, secondSource) = loaded(reservedRow)
            advanceUntilIdle()
            second.editWg { it.copy(reserved = "4,5,6") }
            second.save()
            advanceUntilIdle()
            secondSource.lastUpdate shouldBe reservedRow.copy(reserved = listOf(4, 5, 6), notConnectableReason = null)
        }

    @Test
    fun `an empty stored allowed IPs list stays empty untouched, and a field edited to blank means the default`() =
        runTest {
            val none = wg.copy(allowedIps = emptyList())
            roundTrip(none)

            val (viewModel, source) = loaded(wg)
            advanceUntilIdle()
            viewModel.editWg { it.copy(allowedIps = "") }
            viewModel.save()
            advanceUntilIdle()
            (source.lastUpdate as WireGuardOutbound).allowedIps shouldBe listOf("0.0.0.0/0", "::/0")
        }

    @Test
    fun `an HTTP username and password are saved verbatim, and only empty means none`() =
        runTest {
            val padded = http.copy(username = " alice ", password = "  pass word  ")
            roundTrip(padded)

            val (viewModel, source) = loaded(http)
            advanceUntilIdle()
            viewModel.onPrimaryCredentialChanged("")
            viewModel.onSecondaryCredentialChanged(" ")
            viewModel.save()
            advanceUntilIdle()
            source.lastUpdate shouldBe http.copy(username = null, password = " ")
        }

    @Test
    fun `every WireGuard field is editable, trimmed, and saves`() =
        runTest {
            val newSecret = key(9)
            val (viewModel, source) = loaded(wg)
            advanceUntilIdle()
            viewModel.onAddressChanged("  203.0.113.9 ")
            viewModel.onPortChanged("51821")
            viewModel.editWg {
                it.copy(
                    secretKey = " $newSecret ",
                    peerPublicKey = "  ${"ab".repeat(32)}\n",
                    preSharedKey = "",
                    localAddresses = " 10.1.0.2/24,\n fd00::9 ",
                    mtu = " 1400 ",
                    reserved = "AQID",
                    keepAlive = "0",
                    allowedIps = "",
                )
            }
            viewModel.save()
            advanceUntilIdle()

            source.lastUpdate shouldBe
                WireGuardOutbound(
                    address = "203.0.113.9",
                    port = 51821,
                    secretKey = newSecret,
                    localAddresses = listOf("10.1.0.2/24", "fd00::9"),
                    peerPublicKey = "ab".repeat(32),
                    preSharedKey = null,
                    mtu = 1400,
                    reserved = listOf(1, 2, 3),
                    keepAlive = null,
                    allowedIps = listOf("0.0.0.0/0", "::/0"),
                )
        }

    private fun assertWgRejected(
        field: WireGuardField,
        change: (WireGuardDraft) -> WireGuardDraft,
    ) = runTest {
        val (viewModel, source) = loaded(wg)
        advanceUntilIdle()
        viewModel.editWg(change)
        viewModel.save()
        advanceUntilIdle()

        source.lastUpdate shouldBe null
        viewModel.state.value.wireGuardErrors shouldBe setOf(field)
        viewModel.state.value.saved shouldBe false
    }

    @Test
    fun `a bad WireGuard secret key, peer key or pre-shared key is rejected`() {
        assertWgRejected(WireGuardField.SecretKey) { it.copy(secretKey = "not-a-key") }
        assertWgRejected(WireGuardField.SecretKey) { it.copy(secretKey = "") }
        assertWgRejected(WireGuardField.PeerPublicKey) { it.copy(peerPublicKey = secretKey.dropLast(4)) }
        assertWgRejected(WireGuardField.PreSharedKey) { it.copy(preSharedKey = "short") }
    }

    @Test
    fun `at least one valid local address is required`() {
        assertWgRejected(WireGuardField.LocalAddresses) { it.copy(localAddresses = "") }
        assertWgRejected(WireGuardField.LocalAddresses) { it.copy(localAddresses = " , \n ") }
        assertWgRejected(WireGuardField.LocalAddresses) { it.copy(localAddresses = "10.0.0.2/33") }
        assertWgRejected(WireGuardField.LocalAddresses) { it.copy(localAddresses = "10.0.0.2, nonsense") }
    }

    @Test
    fun `mtu, reserved, keepalive and allowed IPs are checked`() {
        assertWgRejected(WireGuardField.Mtu) { it.copy(mtu = "0") }
        assertWgRejected(WireGuardField.Mtu) { it.copy(mtu = "65536") }
        assertWgRejected(WireGuardField.Mtu) { it.copy(mtu = "big") }
        assertWgRejected(WireGuardField.Reserved) { it.copy(reserved = "1,2") }
        assertWgRejected(WireGuardField.Reserved) { it.copy(reserved = "1,2,256") }
        assertWgRejected(WireGuardField.Keepalive) { it.copy(keepAlive = "-1") }
        assertWgRejected(WireGuardField.Keepalive) { it.copy(keepAlive = "70000") }
        assertWgRejected(WireGuardField.AllowedIps) { it.copy(allowedIps = "0.0.0.0/0, example.com") }
    }

    @Test
    fun `a blank mtu means the default`() =
        runTest {
            val (viewModel, source) = loaded(wg)
            advanceUntilIdle()
            viewModel.editWg { it.copy(mtu = " ") }
            viewModel.save()
            advanceUntilIdle()
            (source.lastUpdate as WireGuardOutbound).mtu shouldBe 1420
        }

    @Test
    fun `WireGuard validation and toString never echo a key`() =
        runTest {
            val (viewModel, _) = loaded(wg)
            advanceUntilIdle()
            viewModel.editWg { it.copy(secretKey = "leaky-secret-key-value", mtu = "x") }
            viewModel.save()
            advanceUntilIdle()

            val rendered = viewModel.state.value.wireGuard.toString() + viewModel.state.value.wireGuardErrors
            rendered shouldNotContain "leaky-secret-key-value"
            rendered shouldNotContain peerKey
            rendered shouldNotContain pskKey
        }

    // ---- HTTP ------------------------------------------------------------------------------------

    @Test
    fun `an untouched HTTP save writes back every field`() = roundTrip(http)

    @Test
    fun `a plain HTTP proxy without credentials round-trips`() =
        roundTrip(http.copy(username = null, password = null, security = Security.None))

    @Test
    fun `HTTP fields are editable and trimmed, and a blank credential is none`() =
        runTest {
            val (viewModel, source) = loaded(http)
            advanceUntilIdle()
            viewModel.onAddressChanged(" 203.0.113.9 ")
            viewModel.onPortChanged("3128")
            viewModel.onPrimaryCredentialChanged("")
            viewModel.onSecondaryCredentialChanged("new-pass")
            viewModel.onTlsServerNameChanged(" other.example ")
            viewModel.save()
            advanceUntilIdle()

            source.lastUpdate shouldBe
                HttpOutbound(
                    "203.0.113.9",
                    3128,
                    null,
                    "new-pass",
                    Security.Tls("other.example", "chrome", allowInsecure = true),
                )
        }

    @Test
    fun `turning HTTP TLS off leaves no TLS fields in the saved outbound`() =
        runTest {
            val (viewModel, source) = loaded(http)
            advanceUntilIdle()
            viewModel.onSecurityKindChanged(EditorSecurityKind.None)
            viewModel.save()
            advanceUntilIdle()

            source.lastUpdate shouldBe http.copy(security = Security.None)
        }

    @Test
    fun `an HTTP row stored with REALITY is refused until TLS is chosen or turned off`() =
        runTest {
            val reality = Security.Reality("sni.example", realityKey, "ab", "chrome", "/")
            val (viewModel, source) = loaded(http.copy(security = reality))
            advanceUntilIdle()
            viewModel.state.value.realityConflict shouldBe RealityConflict.HttpProxy
            viewModel.save()
            advanceUntilIdle()
            source.lastUpdate shouldBe null

            viewModel.onSecurityKindChanged(EditorSecurityKind.None)
            viewModel.save()
            advanceUntilIdle()
            source.lastUpdate shouldBe http.copy(security = Security.None)
        }

    @Test
    fun `an HTTP proxy with a bad port is rejected like any other server`() =
        runTest {
            val (viewModel, source) = loaded(http)
            advanceUntilIdle()
            viewModel.onPortChanged("0")
            viewModel.save()
            advanceUntilIdle()
            source.lastUpdate shouldBe null
            (EditorFieldKey.Port in viewModel.state.value.errors) shouldBe true
        }

    // ---- mKCP ------------------------------------------------------------------------------------

    @Test
    fun `an untouched mKCP save writes back every field`() = roundTrip(vless(kcpFull, "kcp"))

    @Test
    fun `mkcp spelling, a seed with whitespace and no header all round-trip`() {
        val seedOnly = TransportOptions.Kcp(null, null, KcpObfuscation(" ", null, legacyOriginal = false))
        roundTrip(vless(seedOnly, "mkcp"))
    }

    @Test
    fun `bare mKCP, header-only and legacy-only round-trip`() {
        roundTrip(vless(TransportOptions.Kcp(null, null, KcpObfuscation(null, null, false)), "kcp"))
        roundTrip(vless(TransportOptions.Kcp(null, null, KcpObfuscation(null, KcpHeader.DNS, false)), "kcp"))
        roundTrip(vless(TransportOptions.Kcp(1200, null, KcpObfuscation(null, null, true)), "kcp"))
    }

    @Test
    fun `mKCP works under VMess and Trojan too`() {
        val tls = Security.Tls("s", "", false)
        roundTrip(TrojanOutbound("198.51.100.8", 443, "pw", StreamSettings("kcp", tls, kcpFull)))
    }

    @Test
    fun `a row the build cannot write keeps its mKCP reason through a save`() {
        listOf(
            KcpUnsupported.HttpHeader,
            KcpUnsupported.UnknownHeader,
            KcpUnsupported.DnsHeaderDomain,
            KcpUnsupported.UnknownMask,
        ).forEach { reason ->
            val kcp = TransportOptions.Kcp(null, null, KcpObfuscation("s", null, false, unsupported = reason))
            roundTrip(vless(kcp, "kcp"))
        }
    }

    @Test
    fun `an invalid stored mKCP setting must be fixed, and then its reason is gone`() =
        runTest {
            val bad =
                TransportOptions.Kcp(10, null, KcpObfuscation(null, null, false, KcpUnsupported.InvalidSetting))
            val (viewModel, source) = loaded(vless(bad, "kcp"))
            advanceUntilIdle()
            viewModel.save()
            advanceUntilIdle()
            source.lastUpdate shouldBe null
            viewModel.state.value.kcpErrors shouldBe setOf(KcpField.Mtu)

            viewModel.editKcp { it.copy(mtu = "1350") }
            viewModel.save()
            advanceUntilIdle()
            source.lastUpdate shouldBe vless(TransportOptions.Kcp(1350, null, KcpObfuscation(null, null, false)), "kcp")
        }

    @Test
    fun `mKCP mtu and tti are checked, and blank means unset`() {
        fun rejected(
            field: KcpField,
            change: (KcpDraft) -> KcpDraft,
        ) = runTest {
            val (viewModel, source) = loaded(vless(kcpFull, "kcp"))
            advanceUntilIdle()
            viewModel.editKcp(change)
            viewModel.save()
            advanceUntilIdle()
            source.lastUpdate shouldBe null
            viewModel.state.value.kcpErrors shouldBe setOf(field)
        }
        rejected(KcpField.Mtu) { it.copy(mtu = "20") }
        rejected(KcpField.Mtu) { it.copy(mtu = "big") }
        rejected(KcpField.Tti) { it.copy(tti = "9") }
        rejected(KcpField.Tti) { it.copy(tti = "1001") }

        runTest {
            val (viewModel, source) = loaded(vless(kcpFull, "kcp"))
            advanceUntilIdle()
            viewModel.editKcp { it.copy(mtu = "21", tti = "") }
            viewModel.save()
            advanceUntilIdle()
            val saved = (source.lastUpdate as VlessOutbound).stream.transport as TransportOptions.Kcp
            saved.mtu shouldBe 21
            saved.tti shouldBe null
        }
    }

    @Test
    fun `the seed is saved verbatim and an empty seed means none`() =
        runTest {
            val (viewModel, source) = loaded(vless(kcpFull, "kcp"))
            advanceUntilIdle()
            viewModel.editKcp { it.copy(seed = "  padded  ") }
            viewModel.save()
            advanceUntilIdle()
            ((source.lastUpdate as VlessOutbound).stream.transport as TransportOptions.Kcp).obfuscation.seed shouldBe
                "  padded  "

            viewModel.editKcp { it.copy(seed = "") }
            viewModel.save()
            advanceUntilIdle()
            ((source.lastUpdate as VlessOutbound).stream.transport as TransportOptions.Kcp).obfuscation.seed shouldBe
                null
        }

    @Test
    fun `a header without a seed forces the legacy switch on and writes original then header`() =
        runTest {
            val bare = TransportOptions.Kcp(null, null, KcpObfuscation("seed", null, false))
            val (viewModel, source) = loaded(vless(bare, "kcp"))
            advanceUntilIdle()
            viewModel.state.value.kcp.legacyForced shouldBe false

            viewModel.editKcp { it.copy(seed = "", header = KcpHeader.UTP) }
            val draft = viewModel.state.value.kcp
            draft.legacyForced shouldBe true
            draft.legacySwitchOn shouldBe true

            viewModel.save()
            advanceUntilIdle()
            val saved = ((source.lastUpdate as VlessOutbound).stream.transport as TransportOptions.Kcp)
            KcpRules.masks(saved.obfuscation) shouldBe
                listOf(space.getsub.core.model.KcpMask.Original, space.getsub.core.model.KcpMask.Header(KcpHeader.UTP))

            // A seed gives the first mask again, so the switch is free.
            viewModel.editKcp { it.copy(seed = "s") }
            viewModel.state.value.kcp.legacyForced shouldBe false
        }

    @Test
    fun `the legacy switch is the stored flag when nothing forces it`() {
        KcpDraft(legacyOriginal = false).legacySwitchOn shouldBe false
        KcpDraft(legacyOriginal = true).legacySwitchOn shouldBe true
        KcpDraft(seed = "s", header = KcpHeader.DNS).legacyForced shouldBe false
    }

    @Test
    fun `the mKCP seed is not in toString`() {
        KcpDraft(seed = "do-not-print-me").toString() shouldNotContain "do-not-print-me"
    }

    // ---- HTTPUpgrade -----------------------------------------------------------------------------

    @Test
    fun `an untouched HTTPUpgrade save writes back host and path, early data included`() {
        roundTrip(vless(TransportOptions.HttpUpgrade("cdn.example", "/up?ed=2048"), "httpupgrade"))
        roundTrip(vless(TransportOptions.HttpUpgrade("", "/"), "httpupgrade"))
    }

    @Test
    fun `HTTPUpgrade host and path are editable, trimmed, and a blank path is slash`() =
        runTest {
            val (viewModel, source) = loaded(vless(TransportOptions.HttpUpgrade("a.example", "/x"), "httpupgrade"))
            advanceUntilIdle()
            viewModel.onHttpUpgradeHostChanged(" b.example ")
            viewModel.onHttpUpgradePathChanged("/y?ed=2560")
            viewModel.save()
            advanceUntilIdle()
            ((source.lastUpdate as VlessOutbound).stream.transport) shouldBe
                TransportOptions.HttpUpgrade("b.example", "/y?ed=2560")

            viewModel.onHttpUpgradePathChanged("  ")
            viewModel.save()
            advanceUntilIdle()
            (source.lastUpdate as VlessOutbound).stream.transport shouldBe
                TransportOptions.HttpUpgrade("b.example", "/")
        }

    // ---- REALITY and stale fields ----------------------------------------------------------------

    private val reality = Security.Reality("sni.example", realityKey, "ab", "chrome", "/")

    @Test
    fun `REALITY with mKCP or HTTPUpgrade is an error and nothing is saved`() {
        listOf(
            vless(kcpFull, "kcp", reality),
            vless(TransportOptions.HttpUpgrade("h", "/"), "httpupgrade", reality),
        ).forEach { outbound ->
            runTest {
                val (viewModel, source) = loaded(outbound)
                advanceUntilIdle()
                viewModel.state.value.realityConflict shouldBe RealityConflict.Transport
                viewModel.save()
                advanceUntilIdle()
                source.lastUpdate shouldBe null
                viewModel.state.value.saved shouldBe false
                // The refusal is the conflict itself, not some other field.
                viewModel.state.value.errors shouldBe emptyMap()
            }
        }
    }

    @Test
    fun `picking mKCP on a REALITY row is refused, and picking RAW lifts it`() =
        runTest {
            val tcp = vless(TransportOptions.None, "tcp", reality)
            val (viewModel, source) = loaded(tcp)
            advanceUntilIdle()
            viewModel.state.value.realityConflict shouldBe null

            viewModel.onNetworkChanged("kcp")
            viewModel.state.value.realityConflict shouldBe RealityConflict.Transport
            viewModel.save()
            advanceUntilIdle()
            source.lastUpdate shouldBe null

            viewModel.onNetworkChanged("tcp")
            viewModel.save()
            advanceUntilIdle()
            source.lastUpdate shouldBe tcp
        }

    @Test
    fun `switching away from mKCP leaves no mKCP field in the saved outbound`() =
        runTest {
            val (viewModel, source) = loaded(vless(kcpFull, "kcp"))
            advanceUntilIdle()
            viewModel.onNetworkChanged("ws")
            viewModel.onWsPathChanged("/ws")
            viewModel.save()
            advanceUntilIdle()
            (source.lastUpdate as VlessOutbound).stream shouldBe
                StreamSettings(
                    "ws",
                    Security.Tls("sni.example", "chrome", false),
                    TransportOptions.WebSocket("/ws", emptyMap()),
                )

            viewModel.onNetworkChanged("tcp")
            viewModel.save()
            advanceUntilIdle()
            (source.lastUpdate as VlessOutbound).stream.transport shouldBe TransportOptions.None
        }

    @Test
    fun `switching between mKCP and HTTPUpgrade carries nothing across`() =
        runTest {
            val (viewModel, source) = loaded(vless(kcpFull, "kcp"))
            advanceUntilIdle()
            viewModel.onNetworkChanged("httpupgrade")
            viewModel.save()
            advanceUntilIdle()
            (source.lastUpdate as VlessOutbound).stream.transport shouldBe TransportOptions.HttpUpgrade("", "/")

            viewModel.onNetworkChanged("kcp")
            viewModel.editKcp { KcpDraft() }
            viewModel.save()
            advanceUntilIdle()
            (source.lastUpdate as VlessOutbound).stream.transport shouldBe
                TransportOptions.Kcp(null, null, KcpObfuscation(null, null, false))
        }

    @Test
    fun `an invalid mKCP field does not block a row that is no longer mKCP`() =
        runTest {
            val (viewModel, source) = loaded(vless(kcpFull, "kcp"))
            advanceUntilIdle()
            viewModel.editKcp { it.copy(mtu = "5") }
            viewModel.onNetworkChanged("grpc")
            viewModel.save()
            advanceUntilIdle()
            (source.lastUpdate as VlessOutbound).stream.transport shouldBe TransportOptions.Grpc("")
        }
}
