// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.ui

import io.kotest.matchers.collections.shouldHaveSize
import org.junit.Test
import space.getsub.core.model.ObfuscationProblem

class ObfuscationProblemTextTest {
    @Test
    fun `every problem has its own string`() {
        ObfuscationProblem.entries.map { it.textRes() }.toSet() shouldHaveSize ObfuscationProblem.entries.size
    }
}
