// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.parser

import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlScalar
import space.getsub.core.model.TransportOptions

/**
 * Reads `ws-opts` / `grpc-opts` off a Clash proxy entry into [TransportOptions].
 *
 * Split out of `ClashYaml.kt` rather than kept alongside `tls(common)`: it is
 * used by `vless` there, but living in its own file keeps that file under
 * detekt's TooManyFunctions threshold and leaves room for a future Clash
 * protocol to reuse it without growing that file further.
 */
internal fun transportOptions(
    proxy: YamlMap,
    network: String,
): TransportOptions = transportOptions(proxy, network, preserveExplicitEmpty = false)

/**
 * The Xray network a Clash entry means. Mihomo has no `httpupgrade` network: HTTPUpgrade is `network: ws`
 * with `ws-opts.v2ray-http-upgrade: true`, which on the wire is not a WebSocket at all, so it is
 * mapped to `httpupgrade` here (`v2ray-http-upgrade-fast-open` only skips waiting for the upgrade reply
 * and is ignored).
 */
internal fun clashNetwork(proxy: YamlMap): String {
    val network = proxy.text("network") ?: "tcp"
    val flag = ((proxy.node("ws-opts") as? YamlMap)?.node("v2ray-http-upgrade") as? YamlScalar)?.content
    return if (network == "ws" && flag == "true") "httpupgrade" else network
}

internal fun transportOptionsPreservingEmpty(
    proxy: YamlMap,
    network: String,
): TransportOptions = transportOptions(proxy, network, preserveExplicitEmpty = true)

private fun transportOptions(
    proxy: YamlMap,
    network: String,
    preserveExplicitEmpty: Boolean,
): TransportOptions =
    when (network) {
        "ws" -> {
            val opts = proxy.node("ws-opts") as? YamlMap
            val headers =
                (opts?.node("headers") as? YamlMap)
                    ?.entries
                    ?.mapNotNull { (key, value) ->
                        (value as? YamlScalar)?.content?.let { key.content to it }
                    }?.toMap()
                    .orEmpty()
            val path = opts?.transportText("path", preserveExplicitEmpty) ?: "/"
            TransportOptions.WebSocket(path = path, headers = headers)
        }

        "httpupgrade" -> {
            val opts = proxy.node("ws-opts") as? YamlMap
            val host =
                (opts?.node("headers") as? YamlMap)
                    ?.entries
                    ?.entries
                    ?.firstOrNull { it.key.content.equals("Host", ignoreCase = true) }
                    ?.value
                    ?.let { (it as? YamlScalar)?.content }
                    ?.takeIf { it.isNotBlank() }
                    ?: proxy.text("servername")
                    ?: proxy.text("sni")
            httpUpgrade(host, opts?.transportText("path", preserveExplicitEmpty))
        }

        "grpc" -> {
            val name =
                (proxy.node("grpc-opts") as? YamlMap)
                    ?.transportText("grpc-service-name", preserveExplicitEmpty)
            if (name == null) TransportOptions.None else TransportOptions.Grpc(name)
        }

        else -> TransportOptions.None
    }

private fun YamlMap.transportText(
    key: String,
    preserveExplicitEmpty: Boolean,
): String? = if (preserveExplicitEmpty) (node(key) as? YamlScalar)?.content else text(key)
