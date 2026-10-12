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
class Migration5To6Test {
    private companion object {
        const val DB_NAME = "migration-5-6-test"
    }

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), SubspaceDatabase::class.java)

    private fun androidx.sqlite.db.SupportSQLiteDatabase.sub(
        id: Int,
        url: String,
    ) {
        execSQL(
            "INSERT INTO profile_groups (id, name, source, position, createdAt) " +
                "VALUES ($id, 'g$id', 'SUBSCRIPTION', $id, 0)",
        )
        execSQL(
            "INSERT INTO subscriptions (id, groupId, url, userAgentOverride, hwidEnabled, lastFetchedAt, " +
                "lastAttemptedAt, lastFetchStatus, lastFetchDetail, createdAt) " +
                "VALUES ($id, $id, ?, NULL, 1, NULL, NULL, NULL, NULL, 0)",
            arrayOf(url),
        )
    }

    @Test
    fun stripsLinkParametersIntoColumnsAndSurvivesCollisions() {
        helper.createDatabase(DB_NAME, 5).use { db ->
            db.sub(
                1,
                "https://a.example/s#T?resolve-address=front.example&host=real.example" +
                    "&fragment=80-250,10-100,tlshello&insecure",
            )
            db.sub(2, "https://b.example/s")
            db.sub(3, "https://c.example/s#x") // row 4 already holds the plain URL
            db.sub(4, "https://c.example/s")
            db.sub(5, "https://d.example/s#A") // rewritten first, so row 6 is left alone
            db.sub(6, "https://d.example/s#B")
        }

        val db = helper.runMigrationsAndValidate(DB_NAME, 6, true, MIGRATION_5_6)

        db.query(
            "SELECT url, resolveAddress, hostHeader, linkFragment, insecureIgnored, redirectTrust " +
                "FROM subscriptions WHERE id = 1",
        ).use { c ->
            c.moveToFirst() shouldBe true
            c.getString(0) shouldBe "https://a.example/s"
            c.getString(1) shouldBe "front.example"
            c.getString(2) shouldBe "real.example"
            c.getString(3) shouldBe "80-250,10-100,tlshello"
            c.getInt(4) shouldBe 1
            c.getString(5) shouldBe "UNASKED"
        }
        fun url(id: Int) =
            db.query("SELECT url FROM subscriptions WHERE id = $id").use {
                it.moveToFirst()
                it.getString(0)
            }
        url(2) shouldBe "https://b.example/s"
        url(3) shouldBe "https://c.example/s#x"
        url(4) shouldBe "https://c.example/s"
        url(5) shouldBe "https://d.example/s"
        url(6) shouldBe "https://d.example/s#B"
    }
}
