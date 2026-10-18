// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.Test
import space.getsub.core.model.ProxyCredentials

/**
 * Pure string assertions, so they belong on the JVM.
 *
 * They previously lived in `androidTest`, where any CI without attached hardware
 * skips them silently — a test that cannot run is a test that does not exist.
 */
class Tun2SocksConfigTest {
    @Test
    fun `carries the port it is given`() {
        // §10.6: the SOCKS port is allocated by libXray getFreePorts at connect
        // time. This checks the plumbing, not the caller — nothing here stops
        // someone passing a constant, so it is not by itself §10.6 enforcement.
        tun2socksConfig(socksPort = 34567, mtu = 8500, credentials = TEST_CREDENTIALS) shouldContain "port: 34567"
    }

    @Test
    fun `points the tunnel at loopback`() {
        // ARCHITECTURE.md §6's companion: the Xray inbound binds 127.0.0.1 only, so the tunnel
        // must dial the same place. Anything else would not reach the core.
        val config = tun2socksConfig(socksPort = 10808, mtu = 8500, credentials = TEST_CREDENTIALS)
        config shouldContain "address: 127.0.0.1"
    }

    @Test
    fun `carries the mtu it is given`() {
        tun2socksConfig(socksPort = 10808, mtu = 1500, credentials = TEST_CREDENTIALS) shouldContain "mtu: 1500"
    }

    @Test
    fun `omits interface addressing`() {
        // The interface already exists — VpnService.Builder created and addressed
        // it, and upstream ignores these keys entirely when handed an external fd.
        // Restating them here would be a second source of truth for something
        // ARCHITECTURE.md §5.2 depends on.
        val config = tun2socksConfig(socksPort = 10808, mtu = 8500, credentials = TEST_CREDENTIALS)
        config shouldNotContain "ipv4"
        config shouldNotContain "ipv6"
        // A line-anchored key, so `socks5.username` does not match.
        config shouldNotContain Regex("(?m)^\\s*name:")
    }

    @Test
    fun `enables udp relay`() {
        // Without this, UDP is dropped and DNS over the tunnel fails — a ARCHITECTURE.md §5.2
        // leak that looks like "some sites do not load".
        tun2socksConfig(socksPort = 10808, mtu = 8500, credentials = TEST_CREDENTIALS) shouldContain "udp: 'udp'"
    }

    @Test
    fun `carries the session credentials hev authenticates with`() {
        // M9-E spec §5.3: the Xray SOCKS inbound refuses NO_AUTH, so hev must offer user/pass.
        // Both keys are required together (hev-config.c:240-243).
        val config = tun2socksConfig(socksPort = 10808, mtu = 8500, credentials = ProxyCredentials("u", "p"))
        config shouldContain "  username: 'u'"
        config shouldContain "  password: 'p'"
    }

    @Test
    fun `escapes a single quote in a manual password`() {
        // YAML single-quoted scalars double an embedded quote; anything else ends the scalar early.
        val config =
            tun2socksConfig(socksPort = 10808, mtu = 8500, credentials = ProxyCredentials("o'brien", "it's"))
        config shouldContain "username: 'o''brien'"
        config shouldContain "password: 'it''s'"
    }

    @Test
    fun `keeps hev's log level at warn`() {
        // hev logs user:pass at debug level (hev-socks5-session.c:40, research R4.3).
        tun2socksConfig(socksPort = 10808, mtu = 8500, credentials = TEST_CREDENTIALS) shouldContain "log-level: warn"
    }
}
