// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.settings.pages

import space.getsub.core.model.ProxyAuthMode

// What the Tunnel page's Save writes, whether it may, and whether it restarts the session.

/**
 * What Save writes: the draft, except that a field whose section is off and whose text is invalid
 * keeps its stored value. Values survive the switch going off, but an invalid one is never stored.
 */
internal fun TunnelDraft.forSave(stored: TunnelDraft): TunnelDraft {
    val muxValid = muxTcpError(mux.tcpConnections) == null && muxXudpError(mux.xudpConnections) == null
    val mux =
        if (muxInUse || muxValid) {
            mux
        } else {
            mux.copy(tcpConnections = stored.mux.tcpConnections, xudpConnections = stored.mux.xudpConnections)
        }
    val mtu = if (mtu.override || mtuError(mtu.value) == null) mtu else mtu.copy(value = stored.mtu.value)
    val routes =
        if (routes.override || routeLineErrors(routes.ranges).isEmpty()) {
            routes
        } else {
            routes.copy(ranges = stored.routes.ranges)
        }
    val credsValid = credentialError(proxyAuth.manualUser) == null && credentialError(proxyAuth.manualPass) == null
    val proxyAuth =
        if (proxyAuth.mode == ProxyAuthMode.Manual || credsValid) {
            proxyAuth
        } else {
            proxyAuth.copy(manualUser = stored.proxyAuth.manualUser, manualPass = stored.proxyAuth.manualPass)
        }
    return copy(mux = mux, mtu = mtu, routes = routes, proxyAuth = proxyAuth)
}

/**
 * The draft as the tunnel sees it: values under a switch that is off (and Manual fields while Auto)
 * set to their defaults. Two drafts with the same [effective] run the same session, so saving only
 * a hidden value needs no restart.
 */
internal fun TunnelDraft.effective(): TunnelDraft {
    val d = TunnelDraft()
    return TunnelDraft(
        sniffing = if (sniffing.override) sniffing else d.sniffing,
        mux =
        when {
            !mux.override -> d.mux
            !mux.enabled -> d.mux.copy(override = true)
            else -> mux
        },
        mtu = if (mtu.override) mtu else d.mtu,
        routes = if (routes.override) routes else d.routes,
        proxyAuth = if (proxyAuth.mode == ProxyAuthMode.Manual) proxyAuth else d.proxyAuth,
    )
}

/** Save is offered when nothing in use is wrong and saving would change something. */
internal fun TunnelDraft.canSave(stored: TunnelDraft): Boolean = errors().isEmpty && forSave(stored) != stored
