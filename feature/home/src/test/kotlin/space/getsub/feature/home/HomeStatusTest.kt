// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.home

import io.kotest.matchers.shouldBe
import org.junit.Test
import space.getsub.core.data.ProfileKind
import space.getsub.core.data.StoredProfile
import space.getsub.core.model.ConnectionState
import space.getsub.core.model.FailureReason
import space.getsub.core.model.Health
import space.getsub.core.model.PerAppMode
import space.getsub.core.model.PerAppSelection
import space.getsub.core.model.Security
import space.getsub.core.model.StartupStage
import space.getsub.core.model.StreamSettings
import space.getsub.core.model.VlessOutbound
import space.getsub.core.model.failure

class HomeStatusTest {
    private fun profile(network: String) =
        StoredProfile(
            id = 1,
            groupId = 1,
            kind = ProfileKind.TYPED,
            name = "Test",
            protocol = "vless",
            address = "198.51.100.1",
            port = 443,
            transport = network,
            outbound =
            VlessOutbound(
                address = "198.51.100.1",
                port = 443,
                uuid = "1e0f2a2e-6b2b-4b9a-9a3b-000000000000",
                flow = null,
                stream = StreamSettings(network = network, security = Security.None),
            ),
            rawJson = null,
            lastConnectedAt = null,
            lastError = null,
        )

    private fun connected(health: Health) = ConnectionState.Connected(1_000L, 1080, 0, health)

    @Test
    fun `every startup stage reads as its step of six`() {
        StartupStage.entries.forEachIndexed { i, stage ->
            ConnectionState.Connecting(stage).detail() shouldBe HomeDetail.Step(i + 1, StartupStage.entries.size)
        }
        StartupStage.entries.size shouldBe 6
    }

    @Test
    fun `open and idle both show uptime, idle says so`() {
        connected(Health.Open).detail() shouldBe HomeDetail.Uptime(1_000L, idle = false)
        connected(Health.Idle).detail() shouldBe HomeDetail.Uptime(1_000L, idle = true)
    }

    @Test
    fun `a stall says nothing is coming back`() {
        connected(Health.Stalled).detail() shouldBe HomeDetail.NothingComingBack
    }

    @Test
    fun `open reconnecting still discloses that traffic is unprotected`() {
        ConnectionState.Reconnecting(FailureReason.CoreStartFailed, 1, blocked = false).detail() shouldBe
            HomeDetail.NotProtectedTapToStop
    }

    @Test
    fun `blocked reconnecting names the kill switch`() {
        val blocked = ConnectionState.Reconnecting(FailureReason.CoreStartFailed, 1, blocked = true)
        blocked.detail() shouldBe HomeDetail.KillSwitchHolding
        blocked.isBlocked() shouldBe true
        ConnectionState.Reconnecting(FailureReason.CoreStartFailed, 1, blocked = false).isBlocked() shouldBe false
        connected(Health.Open).isBlocked() shouldBe false
    }

    @Test
    fun `a failure shows its redacted detail`() {
        failure(FailureReason.CoreStartFailed, "boom").detail() shouldBe HomeDetail.FailureText("boom")
    }

    @Test
    fun `disconnecting says it is tearing down`() {
        ConnectionState.Disconnecting.detail() shouldBe HomeDetail.TearingDown
    }

    @Test
    fun `startup stages keep their order at the ends`() {
        ConnectionState.Connecting(StartupStage.AllocatingPort).detail() shouldBe HomeDetail.Step(1, 6)
        ConnectionState.Connecting(StartupStage.StartingTunnel).detail() shouldBe HomeDetail.Step(6, 6)
    }

    @Test
    fun `disconnected with a connectable server hints at tapping the blob`() {
        HomeState(connection = ConnectionState.Disconnected, activeProfile = profile("tcp")).detail() shouldBe
            HomeDetail.TapToConnect
    }

    @Test
    fun `disconnected with nothing selected asks for a server`() {
        HomeState(connection = ConnectionState.Disconnected, activeProfile = null).detail() shouldBe
            HomeDetail.ChooseServer
    }

    @Test
    fun `disconnected with an unsupported server shows no line, the note explains`() {
        HomeState(connection = ConnectionState.Disconnected, activeProfile = profile("kcp")).detail() shouldBe null
    }

    @Test
    fun `failed keeps its failure text whatever is selected`() {
        HomeState(connection = failure(FailureReason.CoreStartFailed, "boom"), activeProfile = null).detail() shouldBe
            HomeDetail.FailureText("boom")
    }

    @Test
    fun `primary action follows what a tap may do`() {
        HomeState(connection = ConnectionState.Disconnected).primaryAction() shouldBe HomePrimaryAction.Connect
        HomeState(connection = connected(Health.Open)).primaryAction() shouldBe HomePrimaryAction.Disconnect
        HomeState(connection = ConnectionState.Reconnecting(FailureReason.CoreStartFailed, 3, blocked = true))
            .primaryAction() shouldBe HomePrimaryAction.Disconnect
        HomeState(connection = ConnectionState.Connecting(StartupStage.StartingCore)).primaryAction() shouldBe
            HomePrimaryAction.None
        HomeState(connection = ConnectionState.Disconnecting).primaryAction() shouldBe HomePrimaryAction.None
        HomeState(connection = failure(FailureReason.Revoked, "")).primaryAction() shouldBe HomePrimaryAction.Connect
    }

    @Test
    fun `apps chip counts the effective selection`() {
        PerAppSelection.OFF.toAppsChip() shouldBe AppsChip.AllApps
        PerAppSelection(PerAppMode.AllowList, setOf("a", "b", "c")).toAppsChip() shouldBe AppsChip.AllowList(3)
        PerAppSelection(PerAppMode.AllowList, emptySet()).toAppsChip() shouldBe AppsChip.AllowList(0)
        PerAppSelection(PerAppMode.DenyList, setOf("a")).toAppsChip() shouldBe AppsChip.DenyList(1)
    }
}
