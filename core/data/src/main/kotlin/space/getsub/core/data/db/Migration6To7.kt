// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

private const val SCHEMA_VERSION_6 = 6
private const val SCHEMA_VERSION_7 = 7

/** M9-B's schema to M9-C's: the per-subscription resolver grant (M9-C spec §1.5). */
internal val MIGRATION_6_7 = object : Migration(SCHEMA_VERSION_6, SCHEMA_VERSION_7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE subscriptions ADD COLUMN resolverTrust TEXT NOT NULL DEFAULT 'UNASKED'")
        db.execSQL("ALTER TABLE subscriptions ADD COLUMN resolverTrustHost TEXT DEFAULT NULL")
    }
}
