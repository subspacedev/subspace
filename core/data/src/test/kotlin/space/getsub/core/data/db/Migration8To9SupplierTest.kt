// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.data.db

import io.kotest.matchers.shouldBe
import org.junit.Test

/**
 * The legacy HWID is derived only when the 8 to 9 migration actually runs. `DataModule` builds
 * the migration list on every launch, so an eager read would run the derivation (and any throw
 * in it) on every database open, long after the one upgrade that needs it.
 */
class Migration8To9SupplierTest {
    @Test
    fun buildingTheMigrationDoesNotReadTheLegacyHwid() {
        var reads = 0

        val migration =
            migration8To9 {
                reads++
                error("read outside migrate()")
            }

        migration.startVersion shouldBe 8
        reads shouldBe 0
    }
}
