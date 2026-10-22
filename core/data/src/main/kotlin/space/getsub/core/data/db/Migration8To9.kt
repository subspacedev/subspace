// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

private const val SCHEMA_VERSION_8 = 8
private const val SCHEMA_VERSION_9 = 9

/**
 * M9-E's schema to M10-B's: each subscription owns its device identity (M10-B spec §3.5).
 *
 * [legacyHwid] is the value every existing subscription sends today, the hash `HwidProvider`
 * derives from `ANDROID_ID`. Copying it into every row is the whole point of this migration:
 * a provider with a device limit counts HWIDs, so a changed value on upgrade would take a new
 * slot and could lock the user out of their own subscription (M10-B spec §3.5, "keeps exactly
 * the value it sends today"). It is passed in rather than derived here so this file stays free
 * of `Settings.Secure`, and so the test can pin it. It is a supplier called only inside
 * [Migration.migrate], so the derivation runs once, on upgrade, and never on an ordinary launch.
 *
 * `hwid` is added as `NOT NULL DEFAULT ''` only because SQLite cannot add a NOT NULL column
 * without a default; the `UPDATE` replaces the empty string in the same transaction. The entity
 * carries no default, and Room compares defaults only when the entity declares one (see
 * [MIGRATION_1_2]'s comment), so the recorded `''` validates. The four switches use `DEFAULT 1`
 * to match their `@ColumnInfo(defaultValue = "1")`: every existing subscription keeps sending
 * the device headers it sends today.
 *
 * The global `hwid_enabled` setting goes away (M10-B spec §3.5). When it was `"false"`, every
 * subscription's own switch is turned off, so none starts sending an HWID it did not send
 * before. An absent row or `"true"` leaves each subscription's switch as it was.
 */
internal fun migration8To9(legacyHwid: () -> String): Migration =
    object : Migration(SCHEMA_VERSION_8, SCHEMA_VERSION_9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE subscriptions ADD COLUMN hwid TEXT NOT NULL DEFAULT ''")
            db.execSQL("UPDATE subscriptions SET hwid = ?", arrayOf(legacyHwid()))
            // Same order as SubscriptionEntity declares them: the four switches, then the four literals.
            val headers = listOf("deviceOs", "osVersion", "deviceModel", "appVersion")
            headers.forEach {
                db.execSQL("ALTER TABLE subscriptions ADD COLUMN ${it}Enabled INTEGER NOT NULL DEFAULT 1")
            }
            headers.forEach { db.execSQL("ALTER TABLE subscriptions ADD COLUMN ${it}Literal TEXT DEFAULT NULL") }

            db.execSQL(
                "UPDATE subscriptions SET hwidEnabled = 0 " +
                    "WHERE (SELECT value FROM settings WHERE key = 'hwid_enabled') = 'false'",
            )
            db.execSQL("DELETE FROM settings WHERE key = 'hwid_enabled'")

            // M10-B spec §2 (`serverDescription`): the per-server caption a provider may send.
            db.execSQL("ALTER TABLE profiles ADD COLUMN caption TEXT DEFAULT NULL")
        }
    }
