// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.tunnel

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.Test
import space.getsub.core.model.ConnectionState
import space.getsub.core.model.FailureReason
import space.getsub.core.model.LocalProxy
import space.getsub.core.model.ProxyCredentials
import space.getsub.core.model.StartupStage
import space.getsub.core.model.failure

/**
 * §5.4's fallback rule, which is the one thing here that can fail quietly.
 *
 * A wrong answer does not throw: it makes a fetch dial a port nothing is
 * listening on (connection refused, reported as an ordinary network failure) or
 * silently go direct when the user expected the tunnel. Neither produces a stack
 * trace pointing back here.
 */
class TunnelProxyBindingTest {
    private val credentials = ProxyCredentials("user", "pass")

    @Test
    fun `a connected tunnel with an http inbound offers its port and credentials`() {
        val state = connected(httpProxyPort = 1081)

        state.localHttpProxyOrNull() shouldBe LocalProxy(1081, credentials)
    }

    /**
     * M9-E spec §5.3: the HTTP inbound refuses every request without the session's account. A
     * port with no credentials would make every proxied fetch fail with a 407, so it is not offered.
     */
    @Test
    fun `a connected tunnel without credentials offers nothing`() {
        val state = connected(httpProxyPort = 1081, proxyCredentials = null)

        state.localHttpProxyOrNull() shouldBe null
        resolveHttpProxy(bound = true, state = state) shouldBe null
    }

    /**
     * Zero is the `Connected` default and means "this session carries no HTTP
     * inbound" — not "connected on port 0". Returning it would have every fetch
     * dial a port that cannot exist.
     */
    @Test
    fun `a connected tunnel without an http inbound offers nothing`() {
        val state = connected(httpProxyPort = 0)

        state.localHttpProxyOrNull() shouldBe null
    }

    /**
     * Every other state means there is no proxy to dial. A background refresh
     * with no bound service sees `Disconnected` and must fetch directly — that
     * fallback is what stops a broken tunnel from also breaking subscription
     * updates.
     */
    @Test
    fun `no other state offers a port`() {
        val states =
            listOf(
                ConnectionState.Disconnected,
                ConnectionState.Connecting(StartupStage.AllocatingPort),
                ConnectionState.Disconnecting,
                failure(FailureReason.CoreStartFailed, "boom"),
            )

        states.forEach { state ->
            withClue(state::class.simpleName.orEmpty()) { state.localHttpProxyOrNull() shouldBe null }
        }
    }

    /**
     * Review round 2, Critical 1: an unbound client's cached [ConnectionState.Connected] is not
     * evidence of anything current — `TunnelClient.unbind()` (called every time the UI
     * backgrounds) stops refreshing it, so it can go on reporting a port from a session that has
     * since ended with nobody listening (`onRevoke()`, `:bg` killed). [resolveHttpProxy] must
     * refuse to hand that out rather than trust a state it has no live link to.
     */
    @Test
    fun `a client without a completed handshake offers no stale port`() {
        val stale = connected(httpProxyPort = 1081)

        resolveHttpProxy(bound = false, state = stale) shouldBe null
    }

    @Test
    fun `a completed handshake with a live Connected state offers its port`() {
        resolveHttpProxy(bound = true, state = connected(httpProxyPort = 1081)) shouldBe LocalProxy(1081, credentials)
    }

    @Test
    fun `a bound client still applies every other state rule`() {
        resolveHttpProxy(bound = true, state = ConnectionState.Disconnected) shouldBe null
    }

    private fun connected(
        httpProxyPort: Int,
        proxyCredentials: ProxyCredentials? = credentials,
    ) = ConnectionState.Connected(
        sinceEpochMillis = 1L,
        socksPort = 1080,
        httpProxyPort = httpProxyPort,
        proxyCredentials = proxyCredentials,
    )
}
