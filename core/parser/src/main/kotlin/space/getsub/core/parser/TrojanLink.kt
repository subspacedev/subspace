// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.parser

import space.getsub.core.model.Profile
import space.getsub.core.model.Security
import space.getsub.core.model.StreamSettings
import space.getsub.core.model.TransportOptions
import space.getsub.core.model.TrojanOutbound

/** Parse a `trojan://password@host:port?params#name` share link. */
@Suppress("ReturnCount")
internal fun parseTrojanLink(
    raw: String,
    index: Int,
): LinkResult {
    val uri =
        parseUri(raw)
            ?: return LinkResult.Bad(
                parseFailure(
                    index,
                    ParseFailureReason.MalformedUri,
                    FailureDetail.Malformed(DetailField.Uri),
                ),
            )

    validatePort(uri.port)?.let {
        return LinkResult.Bad(parseFailure(index, ParseFailureReason.InvalidPort, it))
    }
    if (uri.userInfo.isEmpty()) {
        return LinkResult.Bad(
            parseFailure(
                index,
                ParseFailureReason.MissingCredential,
                FailureDetail.Missing(DetailField.Password),
            ),
        )
    }

    // TLS unless the link says `security=none`: Trojan is TLS by convention, so an absent or
    // blank value keeps TLS. A plaintext row is still refused at connect when its address is
    // public (Xray-core v26.10.10 a1bf968, mirrored by `refusedWithoutTransportSecurity`).
    val security =
        if (uri.query["security"].equals("none", ignoreCase = true)) {
            Security.None
        } else {
            val serverName = uri.query["sni"]?.takeIf { it.isNotBlank() } ?: uri.host
            val fingerprint = uri.query["fp"]?.takeIf { it.isNotBlank() } ?: "chrome"
            Security.Tls(serverName, fingerprint, allowInsecure = uri.query["allowInsecure"] == "1")
        }
    val network = uri.query["type"]?.takeIf { it.isNotBlank() } ?: "tcp"
    val transport =
        when (val special = queryKcpOrHttpUpgrade(uri.query, network)) {
            null -> trojanTransportOptions(uri, network)
            is Step.Fail -> return LinkResult.Bad(parseFailure(index, special.reason, special.detail))
            is Step.Ok -> special.value
        }
    val stream = StreamSettings(network = network, security = security, transport = transport)
    val outbound =
        TrojanOutbound(
            address = uri.host,
            port = uri.port,
            password = uri.userInfo,
            stream = stream,
        )
    val named = splitCaption(uri.fragment)
    return LinkResult.Ok(
        Profile(
            id = profileId("trojan", uri.host, uri.port, uri.userInfo),
            name = named.name.ifBlank { uri.host },
            outbound = outbound,
            caption = named.caption,
        ),
    )
}

private fun trojanTransportOptions(
    uri: UriParts,
    network: String,
): TransportOptions =
    when (network) {
        "ws" -> {
            val path = if (uri.query.containsKey("path")) uri.query.getValue("path") else "/"
            val headers =
                if (uri.query.containsKey("host")) {
                    mapOf("Host" to uri.query.getValue("host"))
                } else {
                    emptyMap()
                }
            TransportOptions.WebSocket(path = path, headers = headers)
        }

        "grpc" ->
            if (uri.query.containsKey("serviceName")) {
                TransportOptions.Grpc(uri.query.getValue("serviceName"))
            } else {
                TransportOptions.None
            }

        else -> TransportOptions.None
    }
