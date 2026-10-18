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
    fun `every startup stage reads as its step of seven`() {
        StartupStage.entries.forEachIndexed { i, stage ->
            ConnectionState.Connecting(stage).detail() shouldBe HomeDetail.Step(i + 1, StartupStage.entries.size)
        }
        StartupStage.entries.size shouldBe 7
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
            HomeDetail.NotProtected
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
        ConnectionState.Connecting(StartupStage.AllocatingPort).detail() shouldBe HomeDetail.Step(1, 7)
        ConnectionState.Connecting(StartupStage.StartingTunnel).detail() shouldBe HomeDetail.Step(7, 7)
    }

    @Test
    fun `disconnected with a connectable server needs no detail, the blob says Connect`() {
        HomeState(connection = ConnectionState.Disconnected, activeProfile = profile("tcp")).detail() shouldBe null
    }

    @Test
    fun `disconnected with nothing selected asks for a server`() {
        HomeState(connection = ConnectionState.Disconnected, activeProfile = null).detail() shouldBe
            HomeDetail.ChooseServer
    }

    @Test
    fun `disconnected with an unsupported server shows no line, the note explains`() {
        HomeState(connection = ConnectionState.Disconnected, activeProfile = profile("hysteria")).detail() shouldBe null
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

    /** The one status line, label then detail, for every state Home draws (M8.6 spec §3.3, amended 2026-10-04). */
    @Test
    fun `status line for every state`() {
        fun line(connection: ConnectionState, server: StoredProfile? = profile("tcp")) =
            HomeState(connection = connection, activeProfile = server).statusLine()

        line(ConnectionState.Disconnected) shouldBe StatusLine(R.string.state_disconnected, null)
        line(ConnectionState.Disconnected, server = null) shouldBe
            StatusLine(R.string.state_disconnected, HomeDetail.ChooseServer)
        line(ConnectionState.Disconnected, server = profile("hysteria")) shouldBe
            StatusLine(R.string.state_disconnected, null)
        line(ConnectionState.Connecting(StartupStage.StartingCore)) shouldBe
            StatusLine(R.string.stage_starting_core, HomeDetail.Step(5, 7))
        line(connected(Health.Open)) shouldBe
            StatusLine(R.string.state_connected, HomeDetail.Uptime(1_000L, idle = false))
        line(connected(Health.Idle)) shouldBe
            StatusLine(R.string.state_connected, HomeDetail.Uptime(1_000L, idle = true))
        line(connected(Health.Stalled)) shouldBe StatusLine(R.string.home_state_stalled, HomeDetail.NothingComingBack)
        line(ConnectionState.Reconnecting(FailureReason.CoreStartFailed, 1, blocked = false)) shouldBe
            StatusLine(R.string.home_state_reconnecting_open, HomeDetail.NotProtected)
        line(ConnectionState.Reconnecting(FailureReason.CoreStartFailed, 1, blocked = true)) shouldBe
            StatusLine(R.string.home_state_reconnecting_blocked, HomeDetail.KillSwitchHolding)
        line(ConnectionState.Disconnecting) shouldBe StatusLine(R.string.state_disconnecting, HomeDetail.TearingDown)
        line(failure(FailureReason.CoreStartFailed, "boom")) shouldBe
            StatusLine(R.string.failure_core_start, HomeDetail.FailureText("boom"))
    }
}
