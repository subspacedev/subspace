// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.profiles.editor

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import space.getsub.core.data.AddedSubscription
import space.getsub.core.data.EffectiveValue
import space.getsub.core.data.MaskResolution
import space.getsub.core.data.ObfuscationReport
import space.getsub.core.data.PendingRoutingConversion
import space.getsub.core.data.ProfileGroup
import space.getsub.core.data.ProfileKind
import space.getsub.core.data.ResolvedObfuscation
import space.getsub.core.data.StoredProfile
import space.getsub.core.data.StoredSubscription
import space.getsub.core.data.sync.SyncResult
import space.getsub.core.model.Hysteria2Congestion
import space.getsub.core.model.Hysteria2Obfuscation
import space.getsub.core.model.Hysteria2Outbound
import space.getsub.core.model.Hysteria2Tls
import space.getsub.core.model.Hysteria2Unsupported
import space.getsub.core.model.Outbound
import space.getsub.core.model.PortHopping
import space.getsub.core.model.Profile
import space.getsub.core.parser.SniffingDecision
import space.getsub.feature.profiles.ProfileSource

/** Hysteria2 in the server editor: load, edit, validate, save (M9-D1 Task 7). */
@OptIn(ExperimentalCoroutinesApi::class)
class Hysteria2EditorTest {
    private val pinHex = "ab".repeat(32)
    private val otherPinHex = "01".repeat(32)
    private val obfsSecret = "obfs-secret-value"
    private val passwordSecret = "hunter2-password"

    private val full =
        Hysteria2Outbound(
            address = "198.51.100.7",
            port = 443,
            password = passwordSecret,
            tls =
            Hysteria2Tls(
                serverName = "hy.example",
                alpn = listOf("h3", "h2"),
                pins = listOf(pinHex, otherPinHex),
            ),
            hopping = PortHopping(listOf(20000..30000, 40000..40000), 45),
            obfuscation = Hysteria2Obfuscation.Gecko(obfsSecret, 100, 1200),
            congestion = Hysteria2Congestion.Brutal("100 mbps", "1 gbps"),
        )

    private fun profileOf(outbound: Hysteria2Outbound) =
        StoredProfile(
            id = 1L,
            groupId = 10L,
            kind = ProfileKind.TYPED,
            name = "Hy",
            protocol = "hysteria2",
            address = outbound.address,
            port = outbound.port,
            transport = "QUIC · ${outbound.port}",
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

    internal class Source(
        profile: StoredProfile,
    ) : ProfileSource {
        private var stored = profile
        var lastUpdate: Outbound? = null
            private set

        override fun observeGroups(
            query: String,
            protocol: String?,
        ): Flow<List<ProfileGroup>> = MutableStateFlow(listOf(ProfileGroup(10L, "Local configs", emptyList())))

        override val activeProfileId: StateFlow<Long?> = MutableStateFlow<Long?>(null).asStateFlow()
        override val globalHwidEnabled: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow()
        override val routingOverridesPassthrough: Flow<Boolean> = MutableStateFlow(false).asStateFlow()

        override suspend fun setActiveProfile(id: Long?) = Unit

        override suspend fun renameGroup(
            id: Long,
            name: String,
        ) = Unit

        override suspend fun deleteGroup(id: Long) = Unit

        override suspend fun defaultGroupId(): Long = 10L

        override suspend fun import(
            profiles: List<Profile>,
            groupId: Long,
        ) = 0

        override suspend fun profile(id: Long): StoredProfile? = stored.takeIf { it.id == id }

        override suspend fun rename(
            id: Long,
            name: String,
        ) = Unit

        override suspend fun move(
            id: Long,
            toGroupId: Long,
        ): Boolean = true

        override suspend fun update(
            id: Long,
            name: String,
            outbound: Outbound,
        ): Boolean {
            lastUpdate = outbound
            stored = stored.copy(name = name, outbound = outbound)
            return true
        }

        override suspend fun addSubscription(
            url: String,
            name: String,
        ) = AddedSubscription(0L, created = true)

        override suspend fun syncSubscription(id: Long): SyncResult = SyncResult.Synced(0, 0, 0, 0, 0)

        override suspend fun deleteSubscription(id: Long) = Unit

        override fun observeSubscriptions(): Flow<List<StoredSubscription>> = MutableStateFlow(emptyList())

        override fun observeUserInfo(id: Long): Flow<String?> = MutableStateFlow(null)

        override fun observeEffective(
            id: Long,
            key: String,
            default: String?,
        ): Flow<EffectiveValue> = MutableStateFlow(EffectiveValue(key, default, null, isPinned = false))

        override suspend fun pin(
            id: Long,
            key: String,
            value: String,
        ) = Unit

        override suspend fun unpin(
            id: Long,
            key: String,
        ) = Unit

        override suspend fun setHwidEnabled(
            id: Long,
            enabled: Boolean,
        ) = Unit

        override suspend fun obfuscationReport(id: Long): ObfuscationReport? = null

        override suspend fun sniffingDecision(id: Long): SniffingDecision =
            SniffingDecision(enabled = true, replacesOwn = false)

        override suspend fun obfuscationDirectiveIssues(subscriptionId: Long): ResolvedObfuscation =
            ResolvedObfuscation(MaskResolution.Absent, MaskResolution.Absent)

        override suspend fun setUserAgentOverride(
            id: Long,
            userAgent: String?,
        ) = Unit
    }

    private fun loaded(outbound: Hysteria2Outbound = full): Pair<EditorViewModel, Source> {
        val source = Source(profileOf(outbound))
        val viewModel =
            EditorViewModel(source, PendingRoutingConversion()).apply {
                setConversionDispatcherForTesting(Dispatchers.Unconfined)
            }
        viewModel.load(1L)
        return viewModel to source
    }

    private fun EditorViewModel.edit(change: (Hysteria2Draft) -> Hysteria2Draft) =
        onHysteria2Changed(change(state.value.hysteria2))

    @Test
    fun `an untouched save writes back exactly what was loaded, every field`() =
        runTest {
            val (viewModel, source) = loaded()
            advanceUntilIdle()

            viewModel.state.value.primaryCredential shouldBe passwordSecret
            viewModel.save()
            advanceUntilIdle()

            source.lastUpdate shouldBe full
        }

    @Test
    fun `salamander with a bbr profile and no hopping round-trips`() =
        runTest {
            val outbound =
                full.copy(
                    hopping = null,
                    obfuscation = Hysteria2Obfuscation.Salamander(obfsSecret),
                    congestion = Hysteria2Congestion.Bbr("aggressive"),
                )
            val (viewModel, source) = loaded(outbound)
            advanceUntilIdle()
            viewModel.save()
            advanceUntilIdle()

            source.lastUpdate shouldBe outbound
        }

    @Test
    fun `a plain server round-trips with the stored alpn kept though the editor never shows it`() =
        runTest {
            val outbound =
                full.copy(
                    hopping = null,
                    obfuscation = Hysteria2Obfuscation.None,
                    congestion = Hysteria2Congestion.Default,
                )
            val (viewModel, source) = loaded(outbound)
            advanceUntilIdle()
            viewModel.save()
            advanceUntilIdle()

            source.lastUpdate shouldBe outbound
            (source.lastUpdate as Hysteria2Outbound).tls.alpn shouldBe listOf("h3", "h2")
        }

    @Test
    fun `every field is editable and saves`() =
        runTest {
            val (viewModel, source) = loaded()
            advanceUntilIdle()
            viewModel.onAddressChanged("203.0.113.9")
            viewModel.onPortChanged("8443")
            viewModel.onPrimaryCredentialChanged("new-password")
            viewModel.edit {
                it.copy(
                    serverName = "other.example",
                    // base64 of 32 bytes of 0xcd, with a newline between the two pins
                    pinsText = "zc3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc0=\n$pinHex",
                    hopPorts = "1000-2000",
                    hopInterval = "5",
                    obfsKind = Hysteria2ObfsKind.Salamander,
                    obfsPassword = "abcd",
                    congestionKind = Hysteria2CongestionKind.Bbr,
                    bbrProfile = "conservative",
                )
            }
            viewModel.save()
            advanceUntilIdle()

            source.lastUpdate shouldBe
                Hysteria2Outbound(
                    address = "203.0.113.9",
                    port = 8443,
                    password = "new-password",
                    tls =
                    Hysteria2Tls(
                        serverName = "other.example",
                        alpn = listOf("h3", "h2"),
                        pins = listOf("cd".repeat(32), pinHex),
                    ),
                    hopping = PortHopping(listOf(1000..2000), 5),
                    obfuscation = Hysteria2Obfuscation.Salamander("abcd"),
                    congestion = Hysteria2Congestion.Bbr("conservative"),
                )
        }

    @Test
    fun `a bare brutal number means Mbps, as in links`() =
        runTest {
            val (viewModel, source) = loaded()
            advanceUntilIdle()
            viewModel.edit { it.copy(brutalUp = "50", brutalDown = "200") }
            viewModel.save()
            advanceUntilIdle()

            val saved = source.lastUpdate as Hysteria2Outbound
            saved.congestion shouldBe Hysteria2Congestion.Brutal("50 mbps", "200 mbps")
        }

    @Test
    fun `a stored bare brutal bandwidth keeps its meaning of bits per second`() =
        runTest {
            val outbound = full.copy(congestion = Hysteria2Congestion.Brutal("800000", "800000"))
            val (viewModel, source) = loaded(outbound)
            advanceUntilIdle()
            viewModel.save()
            advanceUntilIdle()

            val saved = source.lastUpdate as Hysteria2Outbound
            saved.congestion shouldBe Hysteria2Congestion.Brutal("800000 bps", "800000 bps")
        }

    private fun assertRejected(
        field: Hysteria2Field,
        change: (Hysteria2Draft) -> Hysteria2Draft,
    ) = runTest {
        val (viewModel, source) = loaded()
        advanceUntilIdle()
        viewModel.edit(change)
        viewModel.save()
        advanceUntilIdle()

        source.lastUpdate shouldBe null
        viewModel.state.value.hysteria2Errors shouldBe setOf(field)
        viewModel.state.value.saved shouldBe false
    }

    @Test
    fun `an invalid pin is rejected`() =
        assertRejected(Hysteria2Field.Pins) { it.copy(pinsText = "$pinHex\nnot-a-pin") }

    @Test
    fun `a bad hop port list is rejected`() =
        assertRejected(Hysteria2Field.HopPorts) { it.copy(hopPorts = "30000-20000") }

    @Test
    fun `a hop interval under 5 is rejected`() =
        assertRejected(Hysteria2Field.HopInterval) { it.copy(hopInterval = "4") }

    @Test
    fun `a short obfuscation password is rejected`() =
        assertRejected(Hysteria2Field.ObfsPassword) { it.copy(obfsPassword = "abc") }

    @Test
    fun `gecko packet sizes must satisfy 0 less than min, min at most max, max at most 2048`() {
        assertRejected(Hysteria2Field.PacketRange) { it.copy(minPacket = "0") }
        assertRejected(Hysteria2Field.PacketRange) { it.copy(minPacket = "1300", maxPacket = "1200") }
        assertRejected(Hysteria2Field.PacketRange) { it.copy(maxPacket = "2049") }
        assertRejected(Hysteria2Field.PacketRange) { it.copy(minPacket = "", maxPacket = "") }
    }

    @Test
    fun `a bbr profile the core does not know is rejected`() =
        assertRejected(Hysteria2Field.BbrProfile) {
            it.copy(congestionKind = Hysteria2CongestionKind.Bbr, bbrProfile = "turbo")
        }

    @Test
    fun `brutal bandwidth must be valid and above the floor`() {
        assertRejected(Hysteria2Field.BrutalUp) { it.copy(brutalUp = "fast") }
        assertRejected(Hysteria2Field.BrutalDown) { it.copy(brutalDown = "1 kbps") }
    }

    @Test
    fun `brutal with neither direction set is rejected`() =
        assertRejected(Hysteria2Field.BrutalUnset) { it.copy(brutalUp = "", brutalDown = " ") }

    @Test
    fun `brutal with one direction set saves`() =
        runTest {
            val (viewModel, source) = loaded()
            advanceUntilIdle()
            viewModel.edit { it.copy(brutalUp = "", brutalDown = "200") }
            viewModel.save()
            advanceUntilIdle()

            (source.lastUpdate as Hysteria2Outbound).congestion shouldBe Hysteria2Congestion.Brutal("", "200 mbps")
        }

    @Test
    fun `a hop interval that is not plain digits is rejected`() {
        assertRejected(Hysteria2Field.HopInterval) { it.copy(hopInterval = "+10") }
        assertRejected(Hysteria2Field.HopInterval) { it.copy(hopInterval = "10s") }
    }

    @Test
    fun `a single hop port means no hopping, as in links`() =
        runTest {
            val (viewModel, source) = loaded()
            advanceUntilIdle()
            viewModel.edit { it.copy(hopPorts = "20000", hopInterval = "1") }
            viewModel.state.value.hysteria2.hops shouldBe false
            viewModel.save()
            advanceUntilIdle()

            (source.lastUpdate as Hysteria2Outbound).hopping shouldBe null
        }

    @Test
    fun `a stored single-port hop loads as that port and saves without losing it`() =
        runTest {
            // R11: udphop dials remotePorts, not the outbound port, so this row dials 20000 today.
            val outbound = full.copy(port = 443, hopping = PortHopping(listOf(20000..20000), 45))
            val (viewModel, source) = loaded(outbound)
            advanceUntilIdle()
            viewModel.state.value.port shouldBe "20000"
            viewModel.state.value.hysteria2.hopPorts shouldBe ""
            viewModel.save()
            advanceUntilIdle()

            source.lastUpdate shouldBe outbound.copy(port = 20000, hopping = null)
        }

    @Test
    fun `a missing password and a bad port use the shared errors`() =
        runTest {
            val (viewModel, source) = loaded()
            advanceUntilIdle()
            viewModel.onPrimaryCredentialChanged("")
            viewModel.onPortChanged("0")
            viewModel.save()
            advanceUntilIdle()

            source.lastUpdate shouldBe null
            viewModel.state.value.errors.keys shouldBe setOf(EditorFieldKey.Credential, EditorFieldKey.Port)
        }

    @Test
    fun `without hopping the interval is ignored and cannot be wrong`() =
        runTest {
            val (viewModel, source) = loaded()
            advanceUntilIdle()
            viewModel.edit { it.copy(hopPorts = "", hopInterval = "1") }
            viewModel.save()
            advanceUntilIdle()

            (source.lastUpdate as Hysteria2Outbound).hopping shouldBe null
        }

    @Test
    fun `nothing the user typed is echoed by the draft, its errors or the state text`() =
        runTest {
            val (viewModel, _) = loaded()
            advanceUntilIdle()
            viewModel.edit { it.copy(obfsPassword = "x") }
            viewModel.save()
            advanceUntilIdle()

            val shown = viewModel.state.value.hysteria2.toString() + viewModel.state.value.hysteria2Errors
            shown shouldNotContain pinHex
            shown shouldNotContain obfsSecret
            shown shouldNotContain passwordSecret
        }

    @Test
    fun `asking to skip checks without a pin shows the note, and a pin removes it`() =
        runTest {
            val outbound = full.copy(tls = full.tls.copy(pins = emptyList(), skipChecksRequested = true))
            val (viewModel, source) = loaded(outbound)
            advanceUntilIdle()

            viewModel.state.value.hysteria2.checksStayOnWithoutPin shouldBe true

            viewModel.edit { it.copy(pinsText = pinHex) }
            viewModel.state.value.hysteria2.checksStayOnWithoutPin shouldBe false

            viewModel.edit { it.copy(pinsText = "") }
            viewModel.save()
            advanceUntilIdle()
            (source.lastUpdate as Hysteria2Outbound).tls.skipChecksRequested shouldBe true
        }

    @Test
    fun `no note when checks were never skipped`() =
        runTest {
            val (viewModel, _) = loaded(full.copy(tls = full.tls.copy(pins = emptyList())))
            advanceUntilIdle()

            viewModel.state.value.hysteria2.checksStayOnWithoutPin shouldBe false
        }

    @Test
    fun `a row the build cannot run names why and keeps the reason through a save`() =
        runTest {
            val outbound = full.copy(notConnectableReason = Hysteria2Unsupported.UnknownMask)
            val (viewModel, source) = loaded(outbound)
            advanceUntilIdle()

            viewModel.state.value.hysteria2.notConnectableReason shouldBe Hysteria2Unsupported.UnknownMask
            viewModel.onAddressChanged("203.0.113.1")
            viewModel.edit { it.copy(obfsKind = Hysteria2ObfsKind.None) }
            viewModel.save()
            advanceUntilIdle()

            (source.lastUpdate as Hysteria2Outbound).notConnectableReason shouldBe Hysteria2Unsupported.UnknownMask
        }

    @Test
    fun `the same pin as hex and as base64 is saved once`() =
        runTest {
            val (viewModel, source) = loaded(full.copy(tls = full.tls.copy(pins = emptyList())))
            advanceUntilIdle()
            val cdHex = "cd".repeat(32)
            viewModel.edit { it.copy(pinsText = "$cdHex\nzc3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc0=") }
            viewModel.save()
            advanceUntilIdle()

            (source.lastUpdate as Hysteria2Outbound).tls.pins shouldBe listOf(cdHex)
        }

    @Test
    fun `switching obfuscation to none and congestion to default saves none and default`() =
        runTest {
            val (viewModel, source) = loaded()
            advanceUntilIdle()
            viewModel.edit {
                it.copy(obfsKind = Hysteria2ObfsKind.None, congestionKind = Hysteria2CongestionKind.Default)
            }
            viewModel.save()
            advanceUntilIdle()

            val saved = source.lastUpdate as Hysteria2Outbound
            saved.obfuscation shouldBe Hysteria2Obfuscation.None
            saved.congestion shouldBe Hysteria2Congestion.Default
        }

    @Test
    fun `salamander switched to none also saves none`() =
        runTest {
            val (viewModel, source) = loaded(full.copy(obfuscation = Hysteria2Obfuscation.Salamander(obfsSecret)))
            advanceUntilIdle()
            viewModel.edit { it.copy(obfsKind = Hysteria2ObfsKind.None) }
            viewModel.save()
            advanceUntilIdle()

            (source.lastUpdate as Hysteria2Outbound).obfuscation shouldBe Hysteria2Obfuscation.None
        }

    @Test
    fun `a stored bare bandwidth saves, reloads and saves again to the same outbound`() =
        runTest {
            val (first, firstSource) = loaded(full.copy(congestion = Hysteria2Congestion.Brutal("800000", "900000")))
            advanceUntilIdle()
            first.save()
            advanceUntilIdle()
            val once = firstSource.lastUpdate as Hysteria2Outbound

            val (second, secondSource) = loaded(once)
            advanceUntilIdle()
            second.save()
            advanceUntilIdle()

            secondSource.lastUpdate shouldBe once
        }
}
