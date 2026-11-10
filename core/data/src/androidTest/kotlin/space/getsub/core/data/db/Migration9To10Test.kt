// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** M10-C spec §3.4: every existing group upgrades unpinned and otherwise untouched. */
@RunWith(AndroidJUnit4::class)
class Migration9To10Test {
    private companion object {
        const val DB_NAME = "migration-9-10-test"
    }

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), SubspaceDatabase::class.java)

    @Test
    fun existingGroupsGainPinnedZeroAndKeepTheirData() {
        helper.createDatabase(DB_NAME, 9).use { db ->
            db.execSQL(
                "INSERT INTO profile_groups (id, name, source, position, createdAt) " +
                    "VALUES (1, 'g1', 'MANUAL', 0, 10), (2, 'g2', 'SUBSCRIPTION', 1, 20)",
            )
        }

        val db = helper.runMigrationsAndValidate(DB_NAME, 10, true, MIGRATION_9_10)

        db.query("SELECT id, name, source, position, createdAt, pinned FROM profile_groups ORDER BY id").use { c ->
            c.count shouldBe 2
            c.moveToFirst()
            listOf(c.getLong(0), c.getString(1), c.getString(2), c.getInt(3), c.getLong(4), c.getInt(5)) shouldBe
                listOf(1L, "g1", "MANUAL", 0, 10L, 0)
            c.moveToNext()
            listOf(c.getLong(0), c.getString(1), c.getString(2), c.getInt(3), c.getLong(4), c.getInt(5)) shouldBe
                listOf(2L, "g2", "SUBSCRIPTION", 1, 20L, 0)
        }
    }
}
