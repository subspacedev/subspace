// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import space.getsub.core.data.ThemePreference

class ThemeSourceTest {
    @Test
    fun combinesTheThreeSettings() =
        runTest {
            appearanceFlow(
                theme = flowOf(ThemePreference.Dark),
                dynamicColor = flowOf(false),
                lightweight = flowOf(true),
            ).first() shouldBe Appearance(theme = ThemePreference.Dark, dynamicColor = false, lightweight = true)
        }

    /**
     * Review Focus 2: MainActivity holds the first frame until this emits. If a
     * Room read throws and nothing is emitted, the window stays blank for good.
     */
    @Test
    fun aFailingReadEmitsTheDefaultsInsteadOfNothing() =
        runTest {
            appearanceFlow(
                theme = flow { throw IllegalStateException("room is closed") },
                dynamicColor = flowOf(false),
                lightweight = flowOf(true),
            ).first() shouldBe Appearance()
        }
}
