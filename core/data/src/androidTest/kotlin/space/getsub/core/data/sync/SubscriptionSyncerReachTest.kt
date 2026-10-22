// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.data.sync

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import space.getsub.core.data.FetchVia
import space.getsub.core.data.ProfileRepository
import space.getsub.core.data.RedirectTrust
import space.getsub.core.data.RoutingProfileDeletion
import space.getsub.core.data.RoutingRepository
import space.getsub.core.data.RuleSetAssets
import space.getsub.core.data.SettingsRepository
import space.getsub.core.data.SubscriptionRepository
import space.getsub.core.data.db.SubspaceDatabase
import space.getsub.core.model.FragmentSettings
import space.getsub.core.model.LocalProxy
import space.getsub.core.model.TunnelProxyLocator
import space.getsub.core.model.XrayRange
import space.getsub.core.network.FetchFailure
import space.getsub.core.network.FetchOutcome
import space.getsub.core.network.HwidProvider
import space.getsub.core.network.SubscriptionRequest
import space.getsub.core.network.SubscriptionSource
import java.io.File

// Backtick names with spaces are avoided: see SubscriptionSyncerTest (minSdk 26 and D8).

private fun uuidFor(name: String): String {
    val last12 = name.hashCode().toUInt().toString(16).padStart(8, '0').let { (it + it).take(12) }
    return "11111111-2222-3333-4444-$last12"
}

// [uuid] defaults to uuidFor(name) but can be overridden so a fixture can put one server's
// content under a different server's label — see the name-swap and identical-rename tests below.
private fun link(name: String, host: String = "example.com", uuid: String = uuidFor(name)) =
    "vless://$uuid@$host:443?" +
        "security=reality&pbk=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8&sni=example.org&fp=chrome#$name"

private const val FRONTED_LINK =
    "https://example.com/sub#T?resolve-address=front.example&host=real.example&fragment=80-250,10-100,tlshello"

class SubscriptionSyncerReachTest {
    private lateinit var db: SubspaceDatabase
    private lateinit var subscriptions: SubscriptionRepository
    private lateinit var settings: SettingsRepository
    private lateinit var profiles: ProfileRepository
    private lateinit var root: File

    private var response: FetchOutcome = FetchOutcome.Success("", emptyMap())
    private val requests = mutableListOf<SubscriptionRequest>()
    private var lastRequest: SubscriptionRequest? = null
    private var responseForRequest: suspend (SubscriptionRequest) -> FetchOutcome = { response }
    private val source = SubscriptionSource { request ->
        lastRequest = request
        responseForRequest(request)
    }

    /** No tunnel bound by default; individual tests override to assert the proxy is threaded through. */
    private var proxy: LocalProxy? = null

    private fun syncer() =
        SubscriptionSyncer(db.subscriptionDao(), subscriptions, settings, source, TunnelProxyLocator { proxy })

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            SubspaceDatabase::class.java,
        ).build()
        profiles = ProfileRepository(db.profileDao())
        settings = SettingsRepository(db.settingDao(), HwidProvider { "test-hwid" })
        root =
            File(
                InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
                "subscription-syncer-${System.nanoTime()}",
            ).apply { mkdirs() }
        val deletion =
            RoutingProfileDeletion(
                db,
                RoutingRepository(db.routingRuleSetDao()),
                RuleSetAssets(root),
                settings,
                profiles,
            )
        subscriptions = SubscriptionRepository(db.subscriptionDao(), profiles, db, deletion)
        responseForRequest = { request ->
            requests += request
            response
        }
    }

    @After
    fun tearDown() {
        db.close()
        root.deleteRecursively()
    }

    private suspend fun addSubscription() =
        subscriptions.add("https://example.com/sub", name = "Provider").id

    private fun ok(headers: Map<String, String> = emptyMap()) = FetchOutcome.Success(link("Tokyo"), headers)

    @Test
    fun theRouteCarriesTheRowsFrontingAndTheLinkFragment() = runTest {
        val id = subscriptions.add(FRONTED_LINK, "p").id
        response = ok()

        syncer().sync(id)

        val route = lastRequest!!.route
        route.resolveAddress shouldBe "front.example"
        route.hostHeader shouldBe "real.example"
        route.fragment!!.length shouldBe XrayRange(80, 250)
        lastRequest!!.url shouldBe "https://example.com/sub"
    }

    @Test
    fun theUsersFragmentationSwitchWinsInTheRoute() = runTest {
        settings.setFragmentSettings(FragmentSettings.DEFAULT.copy(enabled = true, length = "60-90"))
        val id = subscriptions.add("https://example.com/sub#T?fragment=80-250,10-100,tlshello", "p").id
        response = ok()

        syncer().sync(id)

        lastRequest!!.route.fragment!!.length shouldBe XrayRange(60, 90)
    }

    private suspend fun allowedWithFallback(link: String = "https://example.com/sub"): Long {
        val id = subscriptions.add(link, "Provider").id
        subscriptions.setRedirectTrust(id, RedirectTrust.Allowed)
        response = ok(mapOf("fallback-url" to "https://backup.example/s"))
        syncer().sync(id)
        requests.clear()
        return id
    }

    @Test
    fun aDownMainUrlFallsBackWhenAllowed() = runTest {
        val id = allowedWithFallback(FRONTED_LINK)
        responseForRequest = { r ->
            requests += r
            if (r.url == "https://example.com/sub") FetchOutcome.Failed(FetchFailure.TimedOut) else ok()
        }

        syncer().sync(id).shouldBeInstanceOf<SyncResult.Synced>()

        requests.map { it.url } shouldBe listOf("https://example.com/sub", "https://backup.example/s")
        requests[1].route.hostHeader shouldBe null
        requests[1].route.resolveAddress shouldBe null
        requests[1].route.fragment shouldBe requests[0].route.fragment
        subscriptions.observeSubscriptions().first().single().lastFetchVia shouldBe FetchVia.Fallback
    }

    @Test
    fun aFallbackThatDoesNotRepeatItsUrlIsStillTriedNextTime() = runTest {
        // Found on device 2026-10-15: the fallback's own response carried no fallback-url, replaced the
        // stored directives, and the second refresh with the main URL still down had nowhere to go.
        val id = allowedWithFallback()
        responseForRequest = { r ->
            requests += r
            if (r.url == "https://example.com/sub") FetchOutcome.Failed(FetchFailure.ServerError) else ok()
        }

        syncer().sync(id).shouldBeInstanceOf<SyncResult.Synced>()
        requests.clear()
        syncer().sync(id).shouldBeInstanceOf<SyncResult.Synced>()

        requests.map { it.url } shouldBe listOf("https://example.com/sub", "https://backup.example/s")
        subscriptions.observeSubscriptions().first().single().lastFetchVia shouldBe FetchVia.Fallback
    }

    @Test
    fun aFallbackThatNamesItsOwnFallbackReplacesTheStoredOne() = runTest {
        val id = allowedWithFallback()
        responseForRequest = { r ->
            requests += r
            when (r.url) {
                "https://example.com/sub" -> FetchOutcome.Failed(FetchFailure.ServerError)
                else -> ok(mapOf("fallback-url" to "https://backup2.example/s"))
            }
        }

        syncer().sync(id)
        requests.clear()
        syncer().sync(id)

        requests.map { it.url } shouldBe listOf("https://example.com/sub", "https://backup2.example/s")
    }

    @Test
    fun bothFailingReportsTheMainFailureAndFallbackFailed() = runTest {
        val id = allowedWithFallback()
        responseForRequest = { r ->
            requests += r
            FetchOutcome.Failed(FetchFailure.ServerError)
        }

        syncer().sync(id).shouldBeInstanceOf<SyncResult.Failed>()

        requests.size shouldBe 2
        subscriptions.observeSubscriptions().first().single().let {
            it.lastFetchStatus shouldBe FetchFailure.ServerError.name
            it.lastFetchVia shouldBe FetchVia.FallbackFailed
        }
    }

    @Test
    fun noFallbackWhenUnaskedOrDeniedOrHwid() = runTest {
        val id = allowedWithFallback()
        subscriptions.setRedirectTrust(id, RedirectTrust.Denied)
        responseForRequest = { r ->
            requests += r
            FetchOutcome.Failed(FetchFailure.TimedOut)
        }
        syncer().sync(id)
        requests.size shouldBe 1

        subscriptions.setRedirectTrust(id, RedirectTrust.Allowed)
        requests.clear()
        responseForRequest = { r ->
            requests += r
            FetchOutcome.Failed(FetchFailure.HwidRequired)
        }
        syncer().sync(id)
        requests.size shouldBe 1
    }

    @Test
    fun anInvalidFallbackIsNotTried() = runTest {
        val id = addSubscription()
        subscriptions.setRedirectTrust(id, RedirectTrust.Allowed)
        response = ok(mapOf("fallback-url" to "https://127.0.0.1/s"))
        syncer().sync(id)
        requests.clear()
        responseForRequest = { r ->
            requests += r
            FetchOutcome.Failed(FetchFailure.TimedOut)
        }

        syncer().sync(id)

        requests.size shouldBe 1
        subscriptions.observeSubscriptions().first().single().lastFetchVia shouldBe FetchVia.Main
    }

    @Test
    fun aMoveAppliesOnlyWhenAllowedAndOnlyOncePerRefresh() = runTest {
        val id = addSubscription()
        response = ok(mapOf("new-domain" to "moved.example"))

        syncer().sync(id) // Unasked: stored, not acted on
        subscriptions.observeSubscriptions().first().single().url shouldBe "https://example.com/sub"

        subscriptions.setRedirectTrust(id, RedirectTrust.Allowed)
        requests.clear()
        syncer().sync(id)

        requests.size shouldBe 1 // no re-fetch inside the same refresh
        subscriptions.observeSubscriptions().first().single().let {
            it.url shouldBe "https://moved.example/sub"
            it.movedFromHost shouldBe "example.com"
        }
    }

    @Test
    fun aDomainPingPongMovesOneHopPerRefresh() = runTest {
        val id = addSubscription()
        subscriptions.setRedirectTrust(id, RedirectTrust.Allowed)
        responseForRequest = { r ->
            requests += r
            val next = if ("example.com" in r.url) "other.example" else "example.com"
            ok(mapOf("new-domain" to next))
        }

        syncer().sync(id)
        subscriptions.observeSubscriptions().first().single().url shouldBe "https://other.example/sub"
        syncer().sync(id)
        subscriptions.observeSubscriptions().first().single().url shouldBe "https://example.com/sub"
        requests.size shouldBe 2
    }

    @Test
    fun aMoveOntoAnotherSubscriptionsUrlIsSkipped() = runTest {
        val id = addSubscription()
        subscriptions.add("https://taken.example/sub", "other")
        subscriptions.setRedirectTrust(id, RedirectTrust.Allowed)
        response = ok(mapOf("new-url" to "https://taken.example/sub"))

        syncer().sync(id)

        subscriptions.observeSubscriptions().first().single { it.id == id }.url shouldBe "https://example.com/sub"
    }

    @Test
    fun anInvalidMoveTargetIsIgnored() = runTest {
        val id = addSubscription()
        subscriptions.setRedirectTrust(id, RedirectTrust.Allowed)
        response = ok(mapOf("new-url" to "http://plain.example/s"))

        syncer().sync(id)

        subscriptions.observeSubscriptions().first().single().url shouldBe "https://example.com/sub"
    }

    @Test
    fun aRevocationDuringTheFetchStopsTheMove() = runTest {
        val id = addSubscription()
        subscriptions.setRedirectTrust(id, RedirectTrust.Allowed)
        responseForRequest = { r ->
            requests += r
            subscriptions.setRedirectTrust(id, RedirectTrust.Denied)
            ok(mapOf("new-domain" to "moved.example"))
        }

        syncer().sync(id)

        subscriptions.observeSubscriptions().first().single().url shouldBe "https://example.com/sub"
    }

    @Test
    fun aRevocationDuringTheFetchStopsTheFallback() = runTest {
        val id = allowedWithFallback()
        responseForRequest = { r ->
            requests += r
            subscriptions.setRedirectTrust(id, RedirectTrust.Denied)
            FetchOutcome.Failed(FetchFailure.TimedOut)
        }

        syncer().sync(id)

        requests.size shouldBe 1
    }

    @Test
    fun aRevocationDuringTheFallbackFetchDiscardsItsResponse() = runTest {
        val id = allowedWithFallback()
        responseForRequest = { r ->
            requests += r
            if (r.url == "https://example.com/sub") {
                FetchOutcome.Failed(FetchFailure.TimedOut)
            } else {
                subscriptions.setRedirectTrust(id, RedirectTrust.Denied)
                FetchOutcome.Success(link("Osaka"), emptyMap())
            }
        }

        syncer().sync(id).shouldBeInstanceOf<SyncResult.Failed>()

        requests.size shouldBe 2
        subscriptions.observeSubscriptions().first().single().let {
            it.lastFetchStatus shouldBe FetchFailure.TimedOut.name
            it.lastFetchVia shouldBe FetchVia.Main
        }
    }

    @Test
    fun aMoveClearsTheFrontingButKeepsTheLinkFragment() = runTest {
        val id = subscriptions.add(FRONTED_LINK, "p").id
        subscriptions.setRedirectTrust(id, RedirectTrust.Allowed)
        response = ok(mapOf("new-domain" to "moved.example"))

        syncer().sync(id)

        subscriptions.observeSubscriptions().first().single().let {
            it.url shouldBe "https://moved.example/sub"
            it.resolveAddress shouldBe null
            it.hostHeader shouldBe null
            it.linkFragment shouldBe "80-250,10-100,tlshello"
        }
    }
}
