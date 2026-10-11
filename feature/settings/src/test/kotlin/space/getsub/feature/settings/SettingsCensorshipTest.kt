// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.settings

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
import space.getsub.core.model.FragmentSettings
import space.getsub.core.model.NoiseMode
import space.getsub.core.model.NoisePacketType
import space.getsub.core.model.NoiseSettings
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

            vm.onFragmentSettingsChanged(FragmentSettings.DEFAULT.copy(enabled = true))
            advanceUntilIdle()
            source.fragment.value shouldBe FragmentSettings.DEFAULT.copy(enabled = true)
            vm.state.value.fragment.enabled shouldBe true

            vm.onNoiseSettingsChanged(NoiseSettings.DEFAULT.copy(enabled = true, delay = "5"))
            advanceUntilIdle()
            source.noise.value.delay shouldBe "5"
            vm.state.value.noise.delay shouldBe "5"
        }

    @Test
    fun `hub text names what is on`() {
        censorshipTextRes(SettingsState()) shouldBe R.string.settings_censorship_hub_off
        censorshipTextRes(SettingsState(fragment = FragmentSettings.DEFAULT.copy(enabled = true))) shouldBe
            R.string.settings_censorship_hub_fragment
        censorshipTextRes(SettingsState(noise = NoiseSettings.DEFAULT.copy(enabled = true))) shouldBe
            R.string.settings_censorship_hub_noise
        censorshipTextRes(
            SettingsState(
                fragment = FragmentSettings.DEFAULT.copy(enabled = true),
                noise = NoiseSettings.DEFAULT.copy(enabled = true),
            ),
        ) shouldBe R.string.settings_censorship_hub_both
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
