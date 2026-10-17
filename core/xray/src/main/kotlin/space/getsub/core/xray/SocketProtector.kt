// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.xray

/**
 * Marks a socket to bypass the VPN route.
 *
 * ARCHITECTURE.md §5.1: every socket libXray opens to the remote server must be
 * passed to `VpnService.protect(fd)`. Without it the outbound packet is routed
 * back into the TUN interface — an infinite loop with no traffic, no error, and
 * rising CPU.
 *
 * This interface exists so `:core:xray` never imports `VpnService`. `:service`
 * supplies the implementation; JVM tests supply a fake.
 *
 * **Returning false aborts the socket.** Since libXray v26.10.10 the Go side
 * honours this value: `protectSocket` (`controller/controller.go`) turns a `false`
 * into `protect Android VPN socket failed`, and that dial or listen never happens
 * (`docs/agent/research/libxray-api.md` §3). A failed protect therefore surfaces as
 * a failed connection, not as ARCHITECTURE.md §5.1's loop — but the core's error
 * names no cause the user can see, so the implementation must still log the failure
 * itself — redacted, ARCHITECTURE.md §5.6 — or nothing will say *why*. Return `true`
 * only when the fd really was protected: answering `true` to keep a dial alive would
 * bring back the loop the abort now prevents.
 *
 * §10.2: this looks like boilerplate and is load-bearing. Do not simplify it.
 */
public fun interface SocketProtector {
    /** @return true if the fd was successfully protected. */
    public fun protect(fd: Int): Boolean
}
