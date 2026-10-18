// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

private const val SCHEMA_VERSION_7 = 7
private const val SCHEMA_VERSION_8 = 8

/** M9-C's schema to M9-E's: the per-subscription answer to the provider's exclude-routes (M9-E spec §4). */
internal val MIGRATION_7_8 = object : Migration(SCHEMA_VERSION_7, SCHEMA_VERSION_8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE subscriptions ADD COLUMN routeExclusionTrust TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE subscriptions ADD COLUMN routeExclusionFingerprint TEXT DEFAULT NULL")
    }
}
