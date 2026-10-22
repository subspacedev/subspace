// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

private const val SCHEMA_VERSION_9 = 9
private const val SCHEMA_VERSION_10 = 10

/**
 * M10-B's schema to M10-C's: a group can be pinned to the top of the Servers list (M10-C spec §3.4).
 *
 * `DEFAULT 0` matches the entity's `@ColumnInfo(defaultValue = "0")`, so every existing group
 * upgrades unpinned and Room's schema validation sees the same default on both sides.
 */
internal val MIGRATION_9_10: Migration =
    object : Migration(SCHEMA_VERSION_9, SCHEMA_VERSION_10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE profile_groups ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
        }
    }
