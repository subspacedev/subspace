// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.ui.format

import io.kotest.matchers.shouldBe
import org.junit.Test

class FlagTest {
    @Test fun `a leading flag is found`() { flagIn("🇳🇱 Amsterdam") shouldBe "🇳🇱" }

    @Test fun `a flag mid-name is found`() { flagIn("Хельсинки 🇫🇮 XHTTP") shouldBe "🇫🇮" }

    @Test fun `no flag is null`() { flagIn("Home Bastion") shouldBe null }

    @Test fun `a lone regional indicator is not a flag`() { flagIn("x 🇫 y") shouldBe null }

    @Test fun `the first of two flags wins`() { flagIn("🇩🇪→🇺🇸") shouldBe "🇩🇪" }

    @Test fun `a leading flag is removed from the name`() { nameWithoutFlag("🇩🇪 Frankfurt") shouldBe "Frankfurt" }

    @Test fun `a mid-name flag leaves single spaces`() {
        nameWithoutFlag("Хельсинки 🇫🇮 XHTTP") shouldBe "Хельсинки XHTTP"
    }

    @Test fun `a name without a flag is unchanged`() { nameWithoutFlag("Home Bastion") shouldBe "Home Bastion" }

    @Test fun `a name that is only a flag is kept`() { nameWithoutFlag("🇩🇪") shouldBe "🇩🇪" }
}
