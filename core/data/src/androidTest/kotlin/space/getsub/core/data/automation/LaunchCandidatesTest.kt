// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.data.automation

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import space.getsub.core.data.ProfileRepository
import space.getsub.core.data.RoutingProfileDeletion
import space.getsub.core.data.RoutingRepository
import space.getsub.core.data.RuleSetAssets
import space.getsub.core.data.SettingsRepository
import space.getsub.core.data.SubscriptionRepository
import space.getsub.core.data.db.SubscriptionDirectiveEntity
import space.getsub.core.data.db.SubspaceDatabase
import space.getsub.core.model.PingMode
import space.getsub.core.model.Profile
import space.getsub.core.model.Security
import space.getsub.core.model.StreamSettings
import space.getsub.core.model.VlessOutbound
import space.getsub.core.model.identity.Hwid

class LaunchCandidatesTest {
    private lateinit var db: SubspaceDatabase
    private lateinit var profiles: ProfileRepository
    private lateinit var subscriptions: SubscriptionRepository
    private lateinit var candidates: LaunchCandidates
    private lateinit var root: java.io.File

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, SubspaceDatabase::class.java).build()
        profiles = ProfileRepository(db.profileDao())
        val settings = SettingsRepository(db.settingDao())
        root = java.io.File(context.cacheDir, "launch-candidates-${System.nanoTime()}").apply { mkdirs() }
        val deletion =
            RoutingProfileDeletion(
                db,
                RoutingRepository(db.routingRuleSetDao()),
                RuleSetAssets(root),
                settings,
                profiles,
            )
        subscriptions = SubscriptionRepository(db.subscriptionDao(), profiles, db, deletion)
        candidates = LaunchCandidates(profiles, subscriptions)
    }

    @After
    fun tearDown() {
        db.close()
        root.deleteRecursively()
    }

    private suspend fun subscriptionGroup(pingType: String?): Long {
        val id = subscriptions.add("https://example.com/sub", name = "Provider", hwid = Hwid.random()).id
        if (pingType != null) {
            db.subscriptionDao().putDirectives(listOf(SubscriptionDirectiveEntity(id, "ping-type", pingType, 0)))
        }
        return subscriptions.observeSubscriptions().first().single { it.id == id }.groupId
    }

    @Test
    fun inGroupReturnsThatGroupsProfilesWithTheProviderPingType() =
        runTest {
            val groupId = subscriptionGroup("proxy-head")
            profiles.import(
                listOf(sampleProfile(address = "198.51.100.1"), sampleProfile(address = "198.51.100.2")),
                groupId,
            )

            val result = candidates.inGroup(groupId)

            result.size shouldBe 2
            result.map { it.groupId }.distinct() shouldBe listOf(groupId)
            result.map { it.mode }.distinct() shouldBe listOf(PingMode.PROXY_HEAD)
            result.map { it.listIndex } shouldBe listOf(0, 1)
            result.all { it.connectable } shouldBe true
        }

    @Test
    fun aSubscriptionThatSentNoPingTypeHasNoMode() =
        runTest {
            val groupId = subscriptionGroup(null)
            profiles.import(listOf(sampleProfile()), groupId)

            candidates.inGroup(groupId).single().mode shouldBe null
        }

    @Test
    fun aManualGroupHasNoMode() =
        runTest {
            val groupId = profiles.createGroup("Mine")
            profiles.import(listOf(sampleProfile()), groupId)

            candidates.inGroup(groupId).single().mode shouldBe null
        }

    @Test
    fun allSpansEveryGroupAndIndexesMatchInGroup() =
        runTest {
            val manual = profiles.createGroup("Mine")
            profiles.import(listOf(sampleProfile(address = "198.51.100.1")), manual)
            val provider = subscriptionGroup("tcp")
            profiles.import(
                listOf(sampleProfile(address = "198.51.100.2"), sampleProfile(address = "198.51.100.3")),
                provider,
            )

            val all = candidates.all()

            all.size shouldBe 3
            all.map { it.listIndex } shouldBe listOf(0, 1, 2)
            all.map { it.groupId }.toSet() shouldBe setOf(manual, provider)
            candidates.inGroup(provider).map { it.id to it.listIndex } shouldBe
                all.filter { it.groupId == provider }.map { it.id to it.listIndex }
        }

    @Test
    fun toStringNeverPrintsTheName() {
        val text = LaunchCandidate(1, 2, "Secret Name", true, null, 0).toString()
        (text.contains("Secret Name")) shouldBe false
    }

    private fun sampleProfile(address: String = "198.51.100.1") =
        Profile(
            id = "unused",
            name = "Test",
            outbound =
            VlessOutbound(
                address = address,
                port = 443,
                uuid = "1e0f2a2e-6b2b-4b9a-9a3b-000000000000",
                flow = null,
                stream = StreamSettings(network = "tcp", security = Security.None),
            ),
        )
}
