// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import space.getsub.core.data.db.SubspaceDatabase
import space.getsub.core.network.HwidProvider
import java.io.File

@RunWith(AndroidJUnit4::class)
class SubscriptionRepositoryLinkTest {
    private lateinit var db: SubspaceDatabase
    private lateinit var repo: SubscriptionRepository
    private lateinit var profiles: ProfileRepository
    private lateinit var root: File

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, SubspaceDatabase::class.java).build()
        profiles = ProfileRepository(db.profileDao())
        val settings = SettingsRepository(db.settingDao(), HwidProvider { "h" })
        root = File(context.cacheDir, "sub-link-${System.nanoTime()}").apply { mkdirs() }
        val deletion =
            RoutingProfileDeletion(
                db,
                RoutingRepository(db.routingRuleSetDao()),
                RuleSetAssets(root),
                settings,
                profiles,
            )
        repo = SubscriptionRepository(db.subscriptionDao(), profiles, db, deletion)
    }

    @After
    fun tearDown() {
        db.close()
        root.deleteRecursively()
    }

    @Test
    fun addStoresThePlainUrlTheParametersAndTheTitleAsGroupName() = runTest {
        val added =
            repo.add(
                "https://visa.com/123#MyVPN?resolve-address=visa.com&host=my.example&insecure",
                name = "visa.com",
            )

        val stored = repo.observeSubscriptions().first().single { it.id == added.id }
        stored.url shouldBe "https://visa.com/123"
        stored.resolveAddress shouldBe "visa.com"
        stored.hostHeader shouldBe "my.example"
        stored.insecureIgnored shouldBe true
        stored.redirectTrust shouldBe RedirectTrust.Unasked
        profiles.observeGroups().first().single { it.id == stored.groupId }.name shouldBe "MyVPN"
    }

    @Test
    fun reAddingTheSamePlainUrlReturnsTheExistingRowUnchanged() = runTest {
        val first = repo.add("https://a.example/s#One?host=one.example", name = "a")
        val second = repo.add("https://a.example/s#Two?host=two.example", name = "a")

        second.id shouldBe first.id
        second.created shouldBe false
        repo.observeSubscriptions().first().single().hostHeader shouldBe "one.example"
    }

    @Test
    fun settersWriteOnlyTheirColumnsAndBlankClears() = runTest {
        val id = repo.add("https://a.example/s", name = "a").id

        repo.setRedirectTrust(id, RedirectTrust.Allowed)
        repo.setFronting(id, resolveAddress = "front.example", hostHeader = " ")
        repo.setLinkFragment(id, "50-100,10-20,tlshello")

        val stored = repo.observeSubscriptions().first().single()
        stored.redirectTrust shouldBe RedirectTrust.Allowed
        stored.resolveAddress shouldBe "front.example"
        stored.hostHeader shouldBe null
        stored.linkFragment shouldBe "50-100,10-20,tlshello"
    }
}
