// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import space.getsub.core.parser.SubscriptionLink

private const val SCHEMA_VERSION_5 = 5
private const val SCHEMA_VERSION_6 = 6

/**
 * M9-A's schema to M9-B's: link parameters and redirect state on `subscriptions` (M9-B spec §2.1).
 *
 * Existing URLs carrying `#…` were stored verbatim, and OkHttp never sent the fragment, so the
 * parameters in them were silently ignored. They are split out here. A row is rewritten only if no
 * other row already holds, or has just been given, that plain URL (plan ruling P5): the unique index
 * on `url` would abort the whole migration otherwise, and leaving the row as it was loses nothing.
 */
internal val MIGRATION_5_6 = object : Migration(SCHEMA_VERSION_5, SCHEMA_VERSION_6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        listOf(
            "resolveAddress TEXT DEFAULT NULL",
            "hostHeader TEXT DEFAULT NULL",
            "linkFragment TEXT DEFAULT NULL",
            "insecureIgnored INTEGER NOT NULL DEFAULT 0",
            "redirectTrust TEXT NOT NULL DEFAULT 'UNASKED'",
            "movedFromHost TEXT DEFAULT NULL",
            "movedAt INTEGER DEFAULT NULL",
            "lastFetchVia TEXT DEFAULT NULL",
        ).forEach { db.execSQL("ALTER TABLE subscriptions ADD COLUMN $it") }

        val taken = mutableSetOf<String>()
        val pending = mutableListOf<Pair<Long, String>>()
        db.query("SELECT id, url FROM subscriptions ORDER BY id").use { c ->
            while (c.moveToNext()) {
                val url = c.getString(1)
                if ('#' in url) pending += c.getLong(0) to url else taken += url
            }
        }
        pending.forEach { (id, url) ->
            val link = SubscriptionLink.parse(url)
            if (!taken.add(link.plainUrl)) return@forEach
            db.execSQL(
                "UPDATE subscriptions SET url = ?, resolveAddress = ?, hostHeader = ?, linkFragment = ?, " +
                    "insecureIgnored = ? WHERE id = ?",
                arrayOf<Any?>(
                    link.plainUrl,
                    link.resolveAddress,
                    link.hostHeader,
                    link.fragment,
                    if (link.insecure) 1 else 0,
                    id,
                ),
            )
        }
    }
}
