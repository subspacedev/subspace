// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M10-B spec §3.5 and §6: the upgrade keeps every subscription's HWID exactly and carries the
 * old global switch's "off" onto every subscription, so no provider sees a new device and none
 * starts receiving an HWID it was not sent before.
 */
@RunWith(AndroidJUnit4::class)
class Migration8To9Test {
    private companion object {
        const val DB_NAME = "migration-8-9-test"

        // Stands in for today's derived hash; any value in the panel format will do.
        const val LEGACY_HWID = "LegacyDerivedHwid0123456789-="
    }

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), SubspaceDatabase::class.java)

    private fun seedVersion8(hwidSetting: String?) {
        helper.createDatabase(DB_NAME, 8).use { db ->
            db.execSQL(
                "INSERT INTO profile_groups (id, name, source, position, createdAt) " +
                    "VALUES (1, 'g1', 'SUBSCRIPTION', 1, 0), (2, 'g2', 'SUBSCRIPTION', 2, 0)",
            )
            insertSubscription(db, id = 1, groupId = 1, url = "https://a.example.com/s", hwidEnabled = 1)
            insertSubscription(db, id = 2, groupId = 2, url = "https://b.example.com/s", hwidEnabled = 0)
            db.execSQL(
                "INSERT INTO profiles (id, groupId, kind, identityHash, name, protocol, address, port, " +
                    "transport, outbound, rawJson, position, lastConnectedAt, lastError, createdAt, " +
                    "subscriptionKey, droppedFromSubscriptionAt, passthroughRejection) " +
                    "VALUES (7, 1, 'TYPED', 'hash7', 'server one', 'vless', '192.0.2.10', 443, 'tcp', " +
                    "'{\"o\":1}', NULL, 3, 1000, 'TIMEOUT', 500, 'key7', NULL, NULL)",
            )
            if (hwidSetting != null) {
                db.execSQL("INSERT INTO settings (key, value) VALUES ('hwid_enabled', ?)", arrayOf(hwidSetting))
            }
            // An unrelated setting, to prove the migration deletes only its own row.
            db.execSQL("INSERT INTO settings (key, value) VALUES ('other', 'kept')")
        }
    }

    private fun insertSubscription(
        db: SupportSQLiteDatabase,
        id: Long,
        groupId: Long,
        url: String,
        hwidEnabled: Int,
    ) {
        db.execSQL(
            "INSERT INTO subscriptions (id, groupId, url, userAgentOverride, hwidEnabled, lastFetchedAt, " +
                "lastAttemptedAt, lastFetchStatus, lastFetchDetail, createdAt) " +
                "VALUES (?, ?, ?, NULL, ?, NULL, NULL, NULL, NULL, 0)",
            arrayOf<Any>(id, groupId, url, hwidEnabled),
        )
    }

    private fun migrate(): SupportSQLiteDatabase =
        helper.runMigrationsAndValidate(DB_NAME, 9, true, migration8To9(LEGACY_HWID))

    /** id to hwidEnabled, for every subscription. */
    private fun hwidSwitches(db: SupportSQLiteDatabase): Map<Long, Int> =
        db.query("SELECT id, hwidEnabled FROM subscriptions ORDER BY id").use { c ->
            buildMap { while (c.moveToNext()) put(c.getLong(0), c.getInt(1)) }
        }

    private fun settingRows(db: SupportSQLiteDatabase): Map<String, String> =
        db.query("SELECT key, value FROM settings ORDER BY key").use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) }
        }

    @Test
    fun everySubscriptionKeepsTodaysHwidAndFollowsTheDevice() {
        seedVersion8(hwidSetting = "true")

        val db = migrate()

        db.query(
            "SELECT hwid, deviceOsEnabled, osVersionEnabled, deviceModelEnabled, appVersionEnabled, " +
                "deviceOsLiteral, osVersionLiteral, deviceModelLiteral, appVersionLiteral, url " +
                "FROM subscriptions ORDER BY id",
        ).use { c ->
            c.count shouldBe 2
            while (c.moveToNext()) {
                c.getString(0) shouldBe LEGACY_HWID
                (1..4).forEach { c.getInt(it) shouldBe 1 }
                (5..8).forEach { c.isNull(it) shouldBe true }
            }
        }
        hwidSwitches(db) shouldBe mapOf(1L to 1, 2L to 0)
    }

    @Test
    fun aGlobalSwitchThatWasOffTurnsEverySubscriptionsHwidOffAndIsRemoved() {
        seedVersion8(hwidSetting = "false")

        val db = migrate()

        hwidSwitches(db) shouldBe mapOf(1L to 0, 2L to 0)
        settingRows(db) shouldBe mapOf("other" to "kept")
    }

    @Test
    fun aGlobalSwitchThatWasOnIsRemovedAndChangesNothing() {
        seedVersion8(hwidSetting = "true")

        val db = migrate()

        settingRows(db) shouldBe mapOf("other" to "kept")
    }

    @Test
    fun noStoredGlobalSwitchLeavesEverySubscriptionsHwidSwitchAlone() {
        seedVersion8(hwidSetting = null)

        val db = migrate()

        hwidSwitches(db) shouldBe mapOf(1L to 1, 2L to 0)
        settingRows(db) shouldBe mapOf("other" to "kept")
    }

    @Test
    fun aServerKeepsAllItsDataAndHasNoCaption() {
        seedVersion8(hwidSetting = "true")

        val db = migrate()

        db.query(
            "SELECT groupId, kind, identityHash, name, protocol, address, port, transport, outbound, " +
                "rawJson, position, lastConnectedAt, lastError, createdAt, subscriptionKey, " +
                "droppedFromSubscriptionAt, passthroughRejection, caption FROM profiles WHERE id = 7",
        ).use { c ->
            c.moveToFirst() shouldBe true
            c.getLong(0) shouldBe 1L
            c.getString(1) shouldBe "TYPED"
            c.getString(2) shouldBe "hash7"
            c.getString(3) shouldBe "server one"
            c.getString(4) shouldBe "vless"
            c.getString(5) shouldBe "192.0.2.10"
            c.getInt(6) shouldBe 443
            c.getString(7) shouldBe "tcp"
            c.getString(8) shouldBe "{\"o\":1}"
            c.isNull(9) shouldBe true
            c.getInt(10) shouldBe 3
            c.getLong(11) shouldBe 1000L
            c.getString(12) shouldBe "TIMEOUT"
            c.getLong(13) shouldBe 500L
            c.getString(14) shouldBe "key7"
            c.isNull(15) shouldBe true
            c.isNull(16) shouldBe true
            c.isNull(17) shouldBe true
        }
    }
}
