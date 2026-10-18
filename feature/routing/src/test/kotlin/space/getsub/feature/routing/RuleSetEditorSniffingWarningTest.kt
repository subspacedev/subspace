// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.routing

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import space.getsub.core.data.StoredRuleSet
import space.getsub.core.model.BucketField
import space.getsub.core.model.RouteOutcome
import space.getsub.core.model.RoutingRuleSet
import space.getsub.core.model.RuleBucket
import space.getsub.core.model.SniffingUserSetting

/** M9-E spec §2 "warn but obey": only your own sniffing setting is known on this global screen. */
@OptIn(ExperimentalCoroutinesApi::class)
class RuleSetEditorSniffingWarningTest {
    private class FakeSource(
        private val stored: RoutingRuleSet? = null,
    ) : RoutingSource {
        override val ruleSets = flowOf(emptyList<StoredRuleSet>())
        override val activeRuleSetId = MutableStateFlow<Long?>(null)
        override val installedGeoFiles = MutableStateFlow(emptySet<String>())
        override val sniffing = MutableStateFlow(SniffingUserSetting.DEFAULT)

        override suspend fun setActive(id: Long?) = Unit

        override suspend fun delete(id: Long) = Unit

        override suspend fun ruleSet(id: Long): RoutingRuleSet? = stored?.takeIf { it.id == id }

        override suspend fun ruleSetNamed(name: String): RoutingRuleSet? = null

        override suspend fun categoriesFor(field: BucketField): List<GeoCategory> = emptyList()

        override suspend fun upsert(set: RoutingRuleSet): Long = set.id
    }

    private val yoursOff = SniffingUserSetting(override = true, enabled = false)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a domain entry warns while your sniffing override is off`() = runTest {
        val source = FakeSource().apply { sniffing.value = yoursOff }
        val viewModel = RuleSetEditorViewModel(source)
        viewModel.load(0L)

        viewModel.state.value.showSniffingWarning shouldBe false
        viewModel.addEntry(RouteOutcome.PROXY, BucketField.SITES, "geosite:youtube")
        viewModel.state.value.showSniffingWarning shouldBe true

        source.sniffing.value = SniffingUserSetting(override = true, enabled = true)
        viewModel.state.value.showSniffingWarning shouldBe false
    }

    @Test
    fun `no warning without your override, or for ip-only entries`() = runTest {
        val source = FakeSource().apply { sniffing.value = SniffingUserSetting(override = false, enabled = false) }
        val viewModel = RuleSetEditorViewModel(source)
        viewModel.load(0L)
        viewModel.addEntry(RouteOutcome.PROXY, BucketField.SITES, "geosite:youtube")
        viewModel.state.value.showSniffingWarning shouldBe false

        source.sniffing.value = yoursOff
        viewModel.removeEntry(RouteOutcome.PROXY, BucketField.SITES, "geosite:youtube")
        viewModel.addEntry(RouteOutcome.DIRECT, BucketField.IPS, "geoip:private")
        viewModel.state.value.showSniffingWarning shouldBe false
    }

    @Test
    fun `the sniffing setting survives loading a rule set`() = runTest {
        val stored =
            RoutingRuleSet(
                id = 4L,
                name = "Mine",
                buckets = mapOf(RouteOutcome.DIRECT to RuleBucket(sites = listOf("domain:ru"))),
            )
        val source = FakeSource(stored).apply { sniffing.value = yoursOff }
        val viewModel = RuleSetEditorViewModel(source)

        viewModel.load(4L)

        viewModel.state.value.showSniffingWarning shouldBe true
    }
}
