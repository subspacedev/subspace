// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.xray

import space.getsub.core.model.Profile
import space.getsub.core.model.Security
import space.getsub.core.model.StreamSettings
import space.getsub.core.model.TransportOptions

internal fun pinProfile(
    network: String,
    security: Security,
    transport: TransportOptions = TransportOptions.None,
): Profile {
    val stream = StreamSettings(network = network, security = security, transport = transport)
    return Profile(id = "id", name = "n", outbound = GOLDEN_OUTBOUND.copy(address = "srv.example", stream = stream))
}

internal fun tlsSecurity(serverName: String = ""): Security.Tls =
    Security.Tls(serverName = serverName, fingerprint = "chrome", allowInsecure = false)

internal fun realitySecurity(serverName: String = "www.microsoft.com"): Security.Reality =
    GOLDEN_REALITY.copy(serverName = serverName)

internal fun tlsProfile(serverName: String = "") = pinProfile("tcp", tlsSecurity(serverName))

internal fun realityProfile(serverName: String = "www.microsoft.com") = pinProfile("tcp", realitySecurity(serverName))

internal fun wsProfile(
    headers: Map<String, String>,
    security: Security = Security.None,
) = pinProfile("ws", security, TransportOptions.WebSocket("/chat", headers))

internal fun xhttpProfile(
    host: String?,
    security: Security = Security.None,
) = pinProfile("xhttp", security, TransportOptions.Xhttp("/x", host, "auto"))

internal fun grpcProfile(security: Security = Security.None) =
    pinProfile("grpc", security, TransportOptions.Grpc("svc"))
