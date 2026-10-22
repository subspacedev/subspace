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

@RunWith(AndroidJUnit4::class)
class Migration7To8Test {
    private companion object {
        const val DB_NAME = "migration-7-8-test"
    }

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), SubspaceDatabase::class.java)

    @Test
    fun keepsTheRowAndAddsAnUnansweredRouteExclusionGrant() {
        helper.createDatabase(DB_NAME, 7).use { db ->
            db.execSQL(
                "INSERT INTO profile_groups (id, name, source, position, createdAt) " +
                    "VALUES (1, 'g1', 'SUBSCRIPTION', 1, 0)",
            )
            db.execSQL(
                "INSERT INTO subscriptions (id, groupId, url, userAgentOverride, hwidEnabled, lastFetchedAt, " +
                    "lastAttemptedAt, lastFetchStatus, lastFetchDetail, createdAt, resolverTrust, resolverTrustHost) " +
                    "VALUES (1, 1, 'https://a.example/s', NULL, 1, NULL, NULL, NULL, NULL, 0, " +
                    "'ALLOWED', 'dns.example')",
            )
        }

        val db = helper.runMigrationsAndValidate(DB_NAME, 8, true, MIGRATION_7_8)

        db.query(
            "SELECT url, resolverTrust, resolverTrustHost, routeExclusionTrust, routeExclusionFingerprint " +
                "FROM subscriptions WHERE id = 1",
        ).use { c ->
            c.moveToFirst() shouldBe true
            c.getString(0) shouldBe "https://a.example/s"
            c.getString(1) shouldBe "ALLOWED"
            c.getString(2) shouldBe "dns.example"
            c.isNull(3) shouldBe true
            c.isNull(4) shouldBe true
        }
    }
}
