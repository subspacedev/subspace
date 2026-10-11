// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.routing

import io.kotest.matchers.shouldBe
import org.junit.Test
import space.getsub.core.model.PerAppMode
import space.getsub.core.ui.component.Outcome

/** M8.6 spec §4.9, fix A8: every mode against selected and unselected. */
class PerAppOutcomeTest {
    @Test
    fun offTunnelsEveryAppWhetherSelectedOrNot() {
        outcomeFor(PerAppMode.Off, isSelected = true) shouldBe Outcome.Tunnelled
        outcomeFor(PerAppMode.Off, isSelected = false) shouldBe Outcome.Tunnelled
    }

    @Test
    fun allowListTunnelsOnlySelectedApps() {
        outcomeFor(PerAppMode.AllowList, isSelected = true) shouldBe Outcome.Tunnelled
        outcomeFor(PerAppMode.AllowList, isSelected = false) shouldBe Outcome.Direct
    }

    @Test
    fun denyListSendsSelectedAppsDirect() {
        outcomeFor(PerAppMode.DenyList, isSelected = true) shouldBe Outcome.Direct
        outcomeFor(PerAppMode.DenyList, isSelected = false) shouldBe Outcome.Tunnelled
    }
}
