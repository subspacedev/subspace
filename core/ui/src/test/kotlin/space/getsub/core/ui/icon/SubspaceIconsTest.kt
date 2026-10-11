// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.ui.icon

import io.kotest.matchers.shouldBe
import org.junit.Test
import java.io.File

/**
 * The script and the object are edited separately; this keeps them in step.
 * Unit tests run with the module directory as the working directory.
 */
class SubspaceIconsTest {
    @Test
    fun everyImportedDrawableHasAConstantAndNoConstantIsDangling() {
        val files =
            File("src/main/res/drawable").listFiles()!!
                .map { it.name }
                .filter { it.startsWith("sym_") }
                .map { it.removePrefix("sym_").removeSuffix(".xml") }
                .toSet()
        SubspaceIcons.all.keys shouldBe files
    }
}
