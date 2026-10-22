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
import space.getsub.core.model.ExcludeRoutesUserSetting
import space.getsub.core.model.MuxUserSettings
import space.getsub.core.model.ProxyAuthMode
import space.getsub.core.model.ProxyAuthSettings
import space.getsub.core.model.SniffingUserSetting
import space.getsub.core.model.TunMtuUserSetting

class SettingsRepositoryTuningTest {
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
    fun aFreshDatabaseReadsEveryDefault() =
        runTest {
            repository.sniffing.first() shouldBe SniffingUserSetting.DEFAULT
            repository.mux.first() shouldBe MuxUserSettings.DEFAULT
            repository.tunMtu.first() shouldBe TunMtuUserSetting.DEFAULT
            repository.excludeRoutes.first() shouldBe ExcludeRoutesUserSetting.DEFAULT
            repository.proxyAuth.first() shouldBe ProxyAuthSettings.DEFAULT
        }

    // Every field gets a distinct non-default value, so a swap in the positional read shows up.
    @Test
    fun everyTypeRoundTrips() =
        runTest {
            val sniffing = SniffingUserSetting(override = true, enabled = false)
            val mux = MuxUserSettings(true, true, "3", "5", "allow")
            val mtu = TunMtuUserSetting(true, "1280")
            val routes = ExcludeRoutesUserSetting(true, "10.0.0.0/8,192.168.0.0/16")
            val auth = ProxyAuthSettings(ProxyAuthMode.Manual, "alice", "s3cret")
            repository.setSniffing(sniffing)
            repository.setMux(mux)
            repository.setTunMtu(mtu)
            repository.setExcludeRoutes(routes)
            repository.setProxyAuth(auth)
            repository.sniffing.first() shouldBe sniffing
            repository.mux.first() shouldBe mux
            repository.tunMtu.first() shouldBe mtu
            repository.excludeRoutes.first() shouldBe routes
            repository.proxyAuth.first() shouldBe auth
        }

    @Test
    fun anUnknownProxyAuthModeReadsAuto() =
        runTest {
            db.settingDao().put(SettingEntity(key = "proxy_auth_mode", value = "Telepathy"))
            db.settingDao().put(SettingEntity(key = "proxy_auth_user", value = "alice"))
            repository.proxyAuth.first().mode shouldBe ProxyAuthMode.Auto
        }
}
