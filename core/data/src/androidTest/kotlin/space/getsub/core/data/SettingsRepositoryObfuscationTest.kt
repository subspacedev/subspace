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
import space.getsub.core.data.db.SettingEntity
import space.getsub.core.data.db.SubspaceDatabase
import space.getsub.core.model.FragmentSettings
import space.getsub.core.model.NoiseMode
import space.getsub.core.model.NoisePacketType
import space.getsub.core.model.NoiseSettings

class SettingsRepositoryObfuscationTest {
    private lateinit var db: SubspaceDatabase
    private lateinit var repository: SettingsRepository

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, SubspaceDatabase::class.java).build()
        repository = SettingsRepository(db.settingDao()) { "test-hwid" }
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun unsetReadsAsTheDefaults() =
        runTest {
            repository.fragmentSettings.first() shouldBe FragmentSettings.DEFAULT
            repository.noiseSettings.first() shouldBe NoiseSettings.DEFAULT
            repository.fragmentSettingsNow() shouldBe FragmentSettings.DEFAULT
        }

    @Test
    fun roundTripsEveryField() =
        runTest {
            val fragment = FragmentSettings(true, "1-3", "5-9", "0", "")
            val noise = NoiseSettings(true, NoiseMode.Fixed, NoisePacketType.Hex, "0aff", "3-4", "1-2", "7")
            repository.setFragmentSettings(fragment)
            repository.setNoiseSettings(noise)
            repository.fragmentSettings.first() shouldBe fragment
            repository.noiseSettingsNow() shouldBe noise
        }

    @Test
    fun turningOffKeepsTheValues() =
        runTest {
            repository.setFragmentSettings(FragmentSettings(true, "1-3", "5-9", "0", ""))
            repository.setFragmentSettings(repository.fragmentSettingsNow().copy(enabled = false))
            repository.fragmentSettingsNow() shouldBe FragmentSettings(false, "1-3", "5-9", "0", "")
        }

    @Test
    fun anUnknownEnumNameFallsBack() =
        runTest {
            db.settingDao().put(SettingEntity(key = "noise_mode", value = "Telepathy"))
            repository.noiseSettingsNow().mode shouldBe NoiseMode.Random
        }
}
