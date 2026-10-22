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
import space.getsub.core.model.PreResolveSettings

class PreResolveSettingsTest {
    private lateinit var db: SubspaceDatabase
    private lateinit var settings: SettingsRepository

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, SubspaceDatabase::class.java).build()
        settings = SettingsRepository(db.settingDao())
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun defaultsAreOffWithCloudflare() =
        runTest {
            settings.preResolveSettingsNow() shouldBe
                PreResolveSettings(
                    enabled = false,
                    dohUrl = "https://cloudflare-dns.com/dns-query",
                    bootstrapIp = "1.1.1.1",
                )
        }

    @Test
    fun everyFieldRoundTrips() =
        runTest {
            val value = PreResolveSettings(true, "https://dns.google/dns-query", "8.8.8.8")
            settings.setPreResolveSettings(value)
            settings.preResolveSettingsNow() shouldBe value
            settings.preResolveSettings.first() shouldBe value
        }

    @Test
    fun switchingOffKeepsTheFields() =
        runTest {
            settings.setPreResolveSettings(PreResolveSettings(true, "https://dns.google/dns-query", "8.8.8.8"))
            settings.setPreResolveSettings(settings.preResolveSettingsNow().copy(enabled = false))
            settings.preResolveSettingsNow().dohUrl shouldBe "https://dns.google/dns-query"
        }

    @Test
    fun anUnparseableBooleanReadsAsOff() =
        runTest {
            db.settingDao().put(SettingEntity("preresolve_enabled", "maybe"))
            settings.preResolveSettingsNow().enabled shouldBe false
        }
}
