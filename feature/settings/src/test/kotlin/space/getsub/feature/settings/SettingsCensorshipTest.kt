// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.settings

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.CompletableDeferred
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
import space.getsub.core.model.FragmentSettings
import space.getsub.core.model.NoiseMode
import space.getsub.core.model.NoisePacketType
import space.getsub.core.model.NoiseSettings
import space.getsub.core.model.PreResolveSettings
import space.getsub.feature.settings.SettingsViewModelTest.FakeAppVersionSource
import space.getsub.feature.settings.SettingsViewModelTest.FakeGeoAssetSource
import space.getsub.feature.settings.SettingsViewModelTest.FakeSettingsSource
import space.getsub.feature.settings.SettingsViewModelTest.FakeXraySource
import space.getsub.feature.settings.hub.censorshipTextRes

/** M9-A spec §2.3: the Censorship resistance page's ViewModel side and its hub value. */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsCensorshipTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(source: FakeSettingsSource) =
        SettingsViewModel(source, FakeXraySource(), FakeAppVersionSource(), FakeGeoAssetSource())

    @Test
    fun `state mirrors the stored settings and changes write through`() =
        runTest {
            val source = FakeSettingsSource()
            val vm = viewModel(source)
            vm.state.value.fragment shouldBe FragmentSettings.DEFAULT
            vm.state.value.noise shouldBe NoiseSettings.DEFAULT

            vm.onFragmentSettingsChanged { it.copy(enabled = true) }
            advanceUntilIdle()
            source.fragment.value shouldBe FragmentSettings.DEFAULT.copy(enabled = true)
            vm.state.value.fragment.enabled shouldBe true

            vm.onNoiseSettingsChanged { it.copy(enabled = true, delay = "5") }
            advanceUntilIdle()
            source.noise.value.delay shouldBe "5"
            vm.state.value.noise.delay shouldBe "5"
        }

    @Test
    fun `two patches issued back to back both survive`() =
        runTest {
            val source = FakeSettingsSource()
            val vm = viewModel(source)
            val gate = CompletableDeferred<Unit>()
            source.fragmentWriteGate = gate

            // The first write is held mid-flight; the second must still see its result, not the old value.
            vm.onFragmentSettingsChanged { it.copy(length = "1-9") }
            vm.onFragmentSettingsChanged { it.copy(delay = "3-4") }
            gate.complete(Unit)
            advanceUntilIdle()

            source.fragment.value.length shouldBe "1-9"
            source.fragment.value.delay shouldBe "3-4"
        }

    @Test
    fun `a field patch after switching off keeps it off`() =
        runTest {
            val source = FakeSettingsSource()
            source.fragment.value = FragmentSettings.DEFAULT.copy(enabled = true)
            val vm = viewModel(source)

            vm.onFragmentSettingsChanged { it.copy(enabled = false) }
            vm.onFragmentSettingsChanged { it.copy(length = "1-9") }
            advanceUntilIdle()

            source.fragment.value.enabled shouldBe false
            source.fragment.value.length shouldBe "1-9"
        }

    @Test
    fun `a pre-resolve patch is stored`() =
        runTest {
            val source = FakeSettingsSource()
            val vm = viewModel(source)

            vm.onPreResolveSettingsChanged { it.copy(enabled = true) }
            advanceUntilIdle()

            source.preResolve.value.enabled shouldBe true
        }

    @Test
    fun `pre-resolve state follows the source`() =
        runTest {
            val source = FakeSettingsSource()
            val vm = viewModel(source)
            vm.state.value.preResolve shouldBe PreResolveSettings.DEFAULT

            source.preResolve.value = PreResolveSettings(true, "https://dns.google/dns-query", "8.8.8.8")
            advanceUntilIdle()

            vm.state.value.preResolve.dohUrl shouldBe "https://dns.google/dns-query"
        }

    @Test
    fun `hub text names what is on`() {
        fun text(
            fragment: Boolean,
            noise: Boolean,
            preResolve: Boolean,
        ) = censorshipTextRes(
            SettingsState(
                fragment = FragmentSettings.DEFAULT.copy(enabled = fragment),
                noise = NoiseSettings.DEFAULT.copy(enabled = noise),
                preResolve = PreResolveSettings.DEFAULT.copy(enabled = preResolve),
            ),
        )
        text(false, false, false) shouldBe R.string.settings_censorship_hub_off
        text(true, false, false) shouldBe R.string.settings_censorship_hub_fragment
        text(false, true, false) shouldBe R.string.settings_censorship_hub_noise
        text(false, false, true) shouldBe R.string.settings_censorship_hub_preresolve
        text(true, true, false) shouldBe R.string.settings_censorship_hub_both
        text(true, false, true) shouldBe R.string.settings_censorship_hub_fragment_preresolve
        text(false, true, true) shouldBe R.string.settings_censorship_hub_noise_preresolve
        text(true, true, true) shouldBe R.string.settings_censorship_hub_all
    }

    @Test
    fun `a noise packet never reaches the state's diagnostic text`() {
        val state =
            SettingsState(
                noise =
                NoiseSettings.DEFAULT.copy(
                    enabled = true,
                    mode = NoiseMode.Fixed,
                    packetType = NoisePacketType.Str,
                    packet = "secret-noise-payload",
                ),
            )
        state.toString() shouldNotContain "secret-noise-payload"
    }
}
