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
