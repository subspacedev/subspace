// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.profiles.list

import io.kotest.matchers.shouldBe
import org.junit.Test
import space.getsub.core.data.ProfileKind
import space.getsub.core.model.LatencyOutcome
import space.getsub.core.model.LatencyResult
import space.getsub.core.ui.component.NoteTone

/**
 * Defect 1 (device-fixes finding): the code tile in the Servers row took two
 * `Char`s off [ServerRow.name] for its label. A `String` is UTF-16, so an
 * emoji like the rocket in the real fixture names below is a surrogate
 * pair — one `Char` alone from it is an unpaired surrogate, which renders as
 * `<27>`. A flag emoji is two regional-indicator code points, each already a
 * surrogate pair, so even code-point-aware truncation still cuts one in half.
 *
 * Fixture names below are invented, not copied from a real subscription
 * (ARCHITECTURE.md §5.6) — they reproduce the shapes the device run actually hit: a leading
 * pictograph, a mid-string flag, plain Latin, plain Cyrillic, all-emoji, and
 * blank.
 */
class ServerRowPresentationTest {
    @Test
    fun `a name starting with an emoji uses the first two lettered words, not the emoji`() {
        "🚀 Авто | Лучший сервер 🇪🇺".codeTileInitials() shouldBe "АЛ"
    }

    @Test
    fun `a flag emoji mid-name is skipped, not split`() {
        "Хельсинки 🇫🇮 XHTTP".codeTileInitials() shouldBe "ХX"
    }

    @Test
    fun `a plain Latin name keeps its previous behaviour`() {
        "Frankfurt".codeTileInitials() shouldBe "F"
        "US East".codeTileInitials() shouldBe "UE"
    }

    @Test
    fun `a plain Cyrillic name keeps its previous behaviour`() {
        "Тестовый Сервер".codeTileInitials() shouldBe "ТС"
    }

    @Test
    fun `a name that is entirely emoji falls back to the placeholder`() {
        "🚀 🇪🇺".codeTileInitials() shouldBe "?"
    }

    @Test
    fun `an empty or whitespace-only name falls back to the placeholder`() {
        "".codeTileInitials() shouldBe "?"
        "   ".codeTileInitials() shouldBe "?"
    }

    @Test
    fun `the same name always yields the same tile`() {
        val name = "🚀 Авто | Лучший сервер 🇪🇺"
        name.codeTileInitials() shouldBe name.codeTileInitials()
    }

    @Test
    fun `a caption is shown trimmed and a blank one is not shown`() {
        row().copy(caption = "  Example caption ").displayCaption() shouldBe "Example caption"
        row().copy(caption = "   ").displayCaption() shouldBe null
        row().displayCaption() shouldBe null
    }

    private fun row(
        id: Long = 1,
        name: String = "Server",
        latency: LatencyResult? = null,
        testing: Boolean = false,
    ) = ServerRow(
        id = id,
        name = name,
        protocol = "vless",
        protocolDisplay = "VLESS",
        transport = "tcp",
        kind = ProfileKind.entries.first(),
        compatibilityMode = false,
        connectable = true,
        isActive = false,
        latency = latency,
        isTesting = testing,
    )

    @Test
    fun `note is null for an ordinary row`() {
        row().note() shouldBe null
    }

    @Test
    fun `note prefers not connectable over everything`() {
        val all = row().copy(connectable = false, droppedFromSubscriptionAt = 1L, compatibilityMode = true)
        all.note() shouldBe RowNote.NotConnectable
    }

    @Test
    fun `note prefers dropped over compatibility`() {
        row().copy(droppedFromSubscriptionAt = 1L, compatibilityMode = true).note() shouldBe RowNote.Dropped
        row().copy(compatibilityMode = true).note() shouldBe RowNote.Compatibility
    }

    @Test
    fun `ranks go to the three fastest OK rows`() {
        val rows =
            listOf(
                row(1, latency = LatencyResult.ok(90)),
                row(2, latency = LatencyResult.ok(30)),
                row(3, latency = LatencyResult.ok(60)),
                row(4, latency = LatencyResult.ok(10)),
            )
        rows.ranks() shouldBe mapOf(4L to 1, 2L to 2, 3L to 3)
    }

    @Test
    fun `ranks ignore rows that are not OK`() {
        val rows =
            listOf(
                row(1, latency = LatencyResult.failed(LatencyOutcome.TIMEOUT)),
                row(2, latency = LatencyResult.ok(500)),
                row(3),
            )
        rows.ranks() shouldBe mapOf(2L to 1)
    }

    @Test
    fun `ranks break ties by list order`() {
        val rows = listOf(row(1, latency = LatencyResult.ok(40)), row(2, latency = LatencyResult.ok(40)))
        rows.ranks() shouldBe mapOf(1L to 1, 2L to 2)
    }

    @Test
    fun `ranks of an empty or unmeasured list are empty`() {
        emptyList<ServerRow>().ranks() shouldBe emptyMap()
        listOf(row(1), row(2)).ranks() shouldBe emptyMap()
    }

    @Test
    fun `pill maps every state`() {
        row(testing = true, latency = LatencyResult.ok(5)).pill() shouldBe PillText.Testing
        row().pill() shouldBe PillText.None
        row(latency = LatencyResult.ok(42)).pill() shouldBe PillText.Ms(42)
        row(latency = LatencyResult.failed(LatencyOutcome.UNSUPPORTED)).pill() shouldBe PillText.Unsupported
        row(latency = LatencyResult.failed(LatencyOutcome.FOREIGN_VPN)).pill() shouldBe PillText.ForeignVpn
        row(latency = LatencyResult.failed(LatencyOutcome.CERT_UNTRUSTED)).pill() shouldBe PillText.CertUntrusted
        row(latency = LatencyResult.failed(LatencyOutcome.TIMEOUT)).pill() shouldBe PillText.Failed
        row(latency = LatencyResult.failed(LatencyOutcome.UNREACHABLE)).pill() shouldBe PillText.Failed
        row(latency = LatencyResult.failed(LatencyOutcome.CANCELLED)).pill() shouldBe PillText.Failed
    }

    @Test
    fun `leading mark is the flag wherever it sits in the name`() {
        row(name = "Хельсинки \uD83C\uDDEB\uD83C\uDDEE XHTTP").leadingMark() shouldBe "\uD83C\uDDEB\uD83C\uDDEE"
    }

    @Test
    fun `leading mark falls back to initials without a flag`() {
        row(name = "US East").leadingMark() shouldBe "UE"
    }

    @Test
    fun `a group shows the provider order only when its sort came from the provider`() {
        val g = ServersGroup(id = 1, name = "g", totalProfileCount = 0, profiles = emptyList())
        g.copy(sortFromProvider = true).showsProviderOrder() shouldBe true
        g.showsProviderOrder() shouldBe false
    }

    @Test
    fun `the display name drops the flag the tile already shows`() {
        row(name = "🇩🇪 Frankfurt").displayName() shouldBe "Frankfurt"
        row(name = "Хельсинки 🇫🇮 XHTTP").displayName() shouldBe "Хельсинки XHTTP"
    }

    @Test
    fun `the display name keeps a name with no flag, or only a flag`() {
        row(name = "US East").displayName() shouldBe "US East"
        row(name = "🇩🇪").displayName() shouldBe "🇩🇪"
    }

    @Test
    fun `only compatibility mode reads as information`() {
        RowNote.NotConnectable.tone() shouldBe NoteTone.Error
        RowNote.Dropped.tone() shouldBe NoteTone.Error
        RowNote.Compatibility.tone() shouldBe NoteTone.Info
    }
}
