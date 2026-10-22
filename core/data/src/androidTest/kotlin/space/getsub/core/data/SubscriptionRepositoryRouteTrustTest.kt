// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import space.getsub.core.data.db.SubscriptionDirectiveEntity
import space.getsub.core.data.db.SubspaceDatabase
import space.getsub.core.model.CidrRange
import space.getsub.core.model.ExcludedRoutes
import space.getsub.core.network.HwidProvider
import java.io.File

@RunWith(AndroidJUnit4::class)
class SubscriptionRepositoryRouteTrustTest {
    private lateinit var db: SubspaceDatabase
    private lateinit var repo: SubscriptionRepository
    private lateinit var root: File

    private val lan = CidrRange("172.16.0.0", 12, isV6 = false)
    private val host = CidrRange("192.168.1.5", 32, isV6 = false)
    private val v6 = CidrRange("2001:db8::", 32, isV6 = true)

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, SubspaceDatabase::class.java).build()
        val profiles = ProfileRepository(db.profileDao())
        val settings = SettingsRepository(db.settingDao(), HwidProvider { "h" })
        root = File(context.cacheDir, "sub-route-${System.nanoTime()}").apply { mkdirs() }
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

    private suspend fun send(
        id: Long,
        vararg directives: Pair<String, String>,
    ) {
        db.subscriptionDao().pruneDirectives(id, directives.map { it.first })
        db.subscriptionDao().putDirectives(
            directives.map { (key, value) -> SubscriptionDirectiveEntity(id, key, value, receivedAt = 0) },
        )
    }

    @Test
    fun noDirectiveOffersNothing() = runTest {
        val id = repo.add("https://a.example/s", name = "a").id

        val status = repo.routeExclusionStatus(id).first()

        status.trust shouldBe RouteExclusionTrust.Unasked
        status.fingerprint shouldBe null
        status.offered.shouldBeEmpty()
    }

    @Test
    fun aNewListIsUnaskedAndOffersEveryParsedRange() = runTest {
        val id = repo.add("https://a.example/s", name = "a").id
        send(id, "exclude-routes" to "172.20.1.2/12,192.168.1.5")

        val status = repo.routeExclusionStatus(id).first()

        status.trust shouldBe RouteExclusionTrust.Unasked
        status.offered shouldBe listOf(lan, host)
        status.fingerprint shouldBe ExcludedRoutes.fingerprint(listOf(lan, host))
    }

    @Test
    fun allowingBindsToTheFingerprintAndAChangedListAsksAgain() = runTest {
        val id = repo.add("https://a.example/s", name = "a").id
        send(id, "exclude-routes" to "172.16.0.0/12,192.168.1.5")
        val asked = repo.routeExclusionStatus(id).first()
        repo.setRouteExclusionTrust(id, RouteExclusionTrust.Allowed, checkNotNull(asked.fingerprint))
        repo.routeExclusionStatus(id).first().trust shouldBe RouteExclusionTrust.Allowed

        send(id, "exclude-routes" to "172.16.0.0/12,192.168.1.6")

        val changed = repo.routeExclusionStatus(id).first()
        changed.trust shouldBe RouteExclusionTrust.Unasked
        changed.fingerprint shouldBe ExcludedRoutes.fingerprint(listOf(lan, CidrRange("192.168.1.6", 32, false)))
    }

    @Test
    fun theSameListReorderedStaysAllowed() = runTest {
        val id = repo.add("https://a.example/s", name = "a").id
        send(id, "exclude-routes" to "172.16.0.0/12,192.168.1.5")
        repo.setRouteExclusionTrust(
            id,
            RouteExclusionTrust.Allowed,
            checkNotNull(repo.routeExclusionStatus(id).first().fingerprint),
        )

        send(id, "exclude-routes" to "192.168.1.5/32,172.16.0.0/12")

        val status = repo.routeExclusionStatus(id).first()
        status.trust shouldBe RouteExclusionTrust.Allowed
        status.offered shouldBe listOf(host, lan)
    }

    @Test
    fun aDenialAlsoHoldsUntilTheListChanges() = runTest {
        val id = repo.add("https://a.example/s", name = "a").id
        send(id, "exclude-routes" to "172.16.0.0/12")
        repo.setRouteExclusionTrust(
            id,
            RouteExclusionTrust.Denied,
            checkNotNull(repo.routeExclusionStatus(id).first().fingerprint),
        )
        repo.routeExclusionStatus(id).first().trust shouldBe RouteExclusionTrust.Denied

        send(id, "exclude-routes" to "172.16.0.0/12,2001:db8::/32")

        repo.routeExclusionStatus(id).first().trust shouldBe RouteExclusionTrust.Unasked
    }

    @Test
    fun theSetVariantReplacesTheList() = runTest {
        val id = repo.add("https://a.example/s", name = "a").id
        send(id, "exclude-routes" to "172.16.0.0/12", "exclude-routes-set" to "2001:db8::/32")

        val status = repo.routeExclusionStatus(id).first()

        status.offered shouldBe listOf(v6)
        status.fingerprint shouldBe ExcludedRoutes.fingerprint(listOf(v6))
    }

    @Test
    fun aTooWideRangeIsOfferedButLeftOutOfWhatIsApproved() = runTest {
        val id = repo.add("https://a.example/s", name = "a").id
        send(id, "exclude-routes" to "0.0.0.0/0,172.16.0.0/12")

        val status = repo.routeExclusionStatus(id).first()

        status.offered shouldBe listOf(CidrRange("0.0.0.0", 0, false), lan)
        status.applicable shouldBe listOf(lan)
        status.refused shouldBe listOf(CidrRange("0.0.0.0", 0, false))
        status.fingerprint shouldBe ExcludedRoutes.fingerprint(listOf(lan))
        repo.setRouteExclusionTrust(id, RouteExclusionTrust.Allowed, checkNotNull(status.fingerprint))

        // Dropping the refused range changes nothing that was approved, so nothing is asked again.
        send(id, "exclude-routes" to "172.16.0.0/12")
        repo.routeExclusionStatus(id).first().trust shouldBe RouteExclusionTrust.Allowed
    }

    @Test
    fun onlyTooWideRangesLeaveNothingToApprove() = runTest {
        val id = repo.add("https://a.example/s", name = "a").id
        send(id, "exclude-routes" to "::/0")

        val status = repo.routeExclusionStatus(id).first()

        status.applicable.shouldBeEmpty()
        status.fingerprint shouldBe null
    }

    @Test
    fun anUnparseableListOffersNothing() = runTest {
        val id = repo.add("https://a.example/s", name = "a").id
        send(id, "exclude-routes" to "172.16.0.0/12,not-a-range")

        val status = repo.routeExclusionStatus(id).first()

        status.offered.shouldBeEmpty()
        status.fingerprint shouldBe null
    }

    @Test
    fun aMissingSubscriptionOffersNothing() = runTest {
        repo.routeExclusionStatus(404L).first() shouldBe RouteExclusionStatus.NONE
    }
}
