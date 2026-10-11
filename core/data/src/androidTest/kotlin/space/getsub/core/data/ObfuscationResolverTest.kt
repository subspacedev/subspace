// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import space.getsub.core.data.db.SubscriptionDirectiveEntity
import space.getsub.core.data.db.SubspaceDatabase
import space.getsub.core.model.FragmentSettings
import space.getsub.core.model.NoiseSettings
import space.getsub.core.model.ObfuscationProblem
import space.getsub.core.model.Profile
import space.getsub.core.model.Security
import space.getsub.core.model.StreamSettings
import space.getsub.core.model.VlessOutbound

class ObfuscationResolverTest {
    private lateinit var db: SubspaceDatabase
    private lateinit var root: java.io.File
    private lateinit var settings: SettingsRepository
    private lateinit var profiles: ProfileRepository
    private lateinit var subscriptions: SubscriptionRepository
    private lateinit var resolver: ObfuscationResolver

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, SubspaceDatabase::class.java).build()
        profiles = ProfileRepository(db.profileDao())
        settings = SettingsRepository(db.settingDao()) { "test-hwid" }
        root = java.io.File(context.cacheDir, "obfuscation-resolver-${System.nanoTime()}").apply { mkdirs() }
        val deletion =
            RoutingProfileDeletion(
                db,
                RoutingRepository(db.routingRuleSetDao()),
                RuleSetAssets(root),
                settings,
                profiles,
            )
        subscriptions = SubscriptionRepository(db.subscriptionDao(), profiles, db, deletion)
        resolver = ObfuscationResolver(settings, subscriptions, profiles)
    }

    @After
    fun tearDown() {
        db.close()
        root.deleteRecursively()
    }

    @Test
    fun aSubscriptionDirectiveAppliesUntilTheUserTurnsTheSettingOn() =
        runTest {
            val id = subscriptions.add("https://example.com/sub", name = "Provider").id
            db.subscriptionDao().putDirectives(
                listOf(SubscriptionDirectiveEntity(id, "fragmentation-enable", "1", 0)),
            )
            val groupId = subscriptions.observeSubscriptions().first().single().groupId
            profiles.import(listOf(sampleProfile()), groupId)
            val rowId = profiles.observeGroups().first().single { it.id == groupId }.profiles.single().id

            resolver.forConfig(rowId)!!.fragment!!.replaceOwn shouldBe false
            resolver.report(rowId)!!.fragment.origin shouldBe ObfuscationOrigin.Subscription

            settings.setFragmentSettings(FragmentSettings.DEFAULT.copy(enabled = true))

            resolver.forConfig(rowId)!!.fragment!!.replaceOwn shouldBe true
            resolver.report(rowId)!!.fragment.origin shouldBe ObfuscationOrigin.Yours
        }

    // A provider's chain: the proxy dials through a freedom outbound carrying both fragment and noises.
    private val bothChain =
        """{"outbounds":[{"tag":"p","protocol":"vless","streamSettings":{"sockopt":{"dialerProxy":"f"}}},
            {"tag":"f","protocol":"freedom",
             "settings":{"fragment":{"packets":"tlshello"},"noises":[{"packet":"1"}]}}]}"""

    private suspend fun subscriptionRow(
        rawJson: String?,
        directives: Map<String, String>,
        runsAsWritten: Boolean = true,
    ): Long {
        val id = subscriptions.add("https://example.com/sub", name = "Provider").id
        db.subscriptionDao().putDirectives(
            directives.entries.mapIndexed { i, (key, value) ->
                SubscriptionDirectiveEntity(id, key, value, receivedAt = i.toLong())
            },
        )
        val groupId = subscriptions.observeSubscriptions().first().single().groupId
        return importRow(groupId, rawJson, runsAsWritten)
    }

    private suspend fun importRow(
        groupId: Long,
        rawJson: String?,
        runsAsWritten: Boolean = true,
    ): Long {
        profiles.import(listOf(sampleProfile().copy(rawJson = rawJson)), groupId)
        val rowId = profiles.observeGroups().first().single { it.id == groupId }.profiles.single().id
        // Set the verdict directly: these tests are about the report, not passthrough analysis.
        if (rawJson != null) profiles.setPassthroughRejection(rowId, if (runsAsWritten) null else "Unvalidated")
        profiles.profile(rowId)!!.runsAsWritten shouldBe (rawJson != null && runsAsWritten)
        return rowId
    }

    @Test
    fun aConfigsOwnMaskBeatsTheSubscriptionDirective() =
        runTest {
            val rowId = subscriptionRow(bothChain, mapOf("fragmentation-enable" to "1", "noises-enable" to "1"))
            val report = resolver.report(rowId)!!
            report.fragment shouldBe MaskReport(ObfuscationOrigin.Config, null)
            report.noise shouldBe MaskReport(ObfuscationOrigin.Config, null)
        }

    @Test
    fun noiseOnCutsTheChainSoItsFragmentationIsGoneWithoutADirective() =
        runTest {
            val rowId = subscriptionRow(bothChain, emptyMap())
            settings.setNoiseSettings(NoiseSettings.DEFAULT.copy(enabled = true))
            val report = resolver.report(rowId)!!
            report.noise.origin shouldBe ObfuscationOrigin.Yours
            report.fragment shouldBe MaskReport(ObfuscationOrigin.None, null)
        }

    @Test
    fun noiseOnCutsTheChainAndAFragmentDirectiveFillsTheGap() =
        runTest {
            val rowId = subscriptionRow(bothChain, mapOf("fragmentation-enable" to "1"))
            settings.setNoiseSettings(NoiseSettings.DEFAULT.copy(enabled = true))
            val report = resolver.report(rowId)!!
            report.noise.origin shouldBe ObfuscationOrigin.Yours
            report.fragment.origin shouldBe ObfuscationOrigin.Subscription
        }

    @Test
    fun aRawRowRunningItsTypedProjectionNeverReportsTheConfigsOwn() =
        runTest {
            val rowId = subscriptionRow(bothChain, mapOf("fragmentation-enable" to "1"), runsAsWritten = false)
            val report = resolver.report(rowId)!!
            report.fragment.origin shouldBe ObfuscationOrigin.Subscription
            report.noise shouldBe MaskReport(ObfuscationOrigin.None, null)
        }

    @Test
    fun aRawConfigWithNoEligibleOutboundReportsNone() =
        runTest {
            val wireguardOnly = """{"outbounds":[{"tag":"w","protocol":"wireguard"}]}"""
            val rowId = subscriptionRow(wireguardOnly, mapOf("fragmentation-enable" to "1"))
            settings.setNoiseSettings(NoiseSettings.DEFAULT.copy(enabled = true))
            val report = resolver.report(rowId)!!
            report.fragment shouldBe MaskReport(ObfuscationOrigin.None, null)
            report.noise shouldBe MaskReport(ObfuscationOrigin.None, null)
        }

    @Test
    fun anIgnoredDirectiveReportsNoneWithItsReason() =
        runTest {
            val rowId =
                subscriptionRow(
                    null,
                    mapOf("fragmentation-enable" to "1", "fragmentation-packets" to "1-3,tlshello"),
                )
            resolver.report(rowId)!!.fragment shouldBe
                MaskReport(ObfuscationOrigin.None, ObfuscationProblem.MultiplePackets)
        }

    @Test
    fun aManualGroupHasNoDirectivesOnlyTheUsersSettings() =
        runTest {
            val rowId = importRow(profiles.defaultGroupId(), rawJson = null)
            resolver.report(rowId) shouldBe
                ObfuscationReport(MaskReport(ObfuscationOrigin.None, null), MaskReport(ObfuscationOrigin.None, null))
            settings.setFragmentSettings(FragmentSettings.DEFAULT.copy(enabled = true))
            resolver.report(rowId)!!.fragment.origin shouldBe ObfuscationOrigin.Yours
            resolver.forConfig(rowId)!!.noise shouldBe null
        }

    @Test
    fun aVanishedRowHasNoReport() =
        runTest {
            val rowId = importRow(profiles.defaultGroupId(), rawJson = null)
            profiles.delete(rowId)
            resolver.report(rowId) shouldBe null
            resolver.forConfig(rowId) shouldBe null
        }

    private fun sampleProfile() =
        Profile(
            id = "unused",
            name = "Test",
            outbound =
            VlessOutbound(
                address = "198.51.100.1",
                port = 443,
                uuid = "1e0f2a2e-6b2b-4b9a-9a3b-000000000000",
                flow = null,
                stream = StreamSettings(network = "tcp", security = Security.None),
            ),
        )
}
