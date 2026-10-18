// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.xray

import space.getsub.core.model.DnsValidation
import space.getsub.core.model.DomainStrategy
import space.getsub.core.model.HttpOutbound
import space.getsub.core.model.Hysteria2Outbound
import space.getsub.core.model.KcpRules
import space.getsub.core.model.Obfuscation
import space.getsub.core.model.ObfuscationApplicability.isHttpUpgradeNetwork
import space.getsub.core.model.ObfuscationApplicability.isKcpNetwork
import space.getsub.core.model.Outbound
import space.getsub.core.model.Profile
import space.getsub.core.model.RoutingRuleSet
import space.getsub.core.model.Security
import space.getsub.core.model.ShadowsocksOutbound
import space.getsub.core.model.SocksOutbound
import space.getsub.core.model.StreamSettings
import space.getsub.core.model.TransportOptions
import space.getsub.core.model.TrojanOutbound
import space.getsub.core.model.VlessOutbound
import space.getsub.core.model.VmessOutbound
import space.getsub.core.model.WireGuardOutbound
import space.getsub.core.model.applicableTo
import space.getsub.core.parser.OverrideTarget

/** Runtime settings that shape the config but do not belong to a stored profile. */
public data class TunnelSettings(
    /** Loopback SOCKS port, allocated at connect time. §10.6 forbids a literal. */
    val socksPort: Int,
    /** Advertised to both the `dns` block and `VpnService.Builder.addDnsServer` (ARCHITECTURE.md §5.2). */
    val dnsServer: String,
    val enableSniffing: Boolean,
    /**
     * The active routing rule set, or null when routing is off.
     *
     * Null is not a degraded mode: it produces the `"rules": []` block M1's
     * proven tunnel has always carried, byte for byte. Defaulted so that call
     * sites which do not route yet keep compiling.
     */
    val routing: RoutingRuleSet? = null,
    /**
     * Loopback HTTP proxy port, or null when app fetches are not proxied.
     *
     * §6's config shape has always read `socks [+ optional http]`; this is the
     * optional half. HTTP rather than a second SOCKS inbound because an HTTP
     * `CONNECT` carries the hostname to the proxy, so the *proxy* resolves it —
     * a Java SOCKS client may resolve locally first, which would leak the
     * subscription hostname while appearing to fetch through the tunnel (§5.2,
     * research §8).
     *
     * Allocated at connect time like [socksPort]; §10.6 forbids a literal.
     */
    val httpPort: Int? = null,
    /**
     * The resolved DNS plan, or null when nothing asks for DNS.
     *
     * Null is not a degraded mode: it produces the `"servers": ["1.1.1.1"]` block
     * M1's proven tunnel has always carried, byte for byte, with no `dns-out`
     * outbound and no prepended rules. Defaulted so call sites that do not resolve
     * DNS yet keep compiling.
     */
    val dns: DnsPlan? = null,
    /**
     * Loopback port for xray's metrics listener, or null when the per-tag
     * breakdown is off or no port could be allocated (M8.5 spec §2).
     *
     * Non-null is the only trigger for [metricsBlocks]: nothing here reads
     * `SettingsRepository.perTagBreakdown` directly, so a caller that never
     * allocates a port gets the exact M1 shape it always has, byte for byte.
     */
    val metricsPort: Int? = null,
    /**
     * Fragment and noise masks, already resolved by the caller (M9-A spec §3.1), or null.
     *
     * Null and an empty [Obfuscation] both emit nothing, so the output is byte-identical to a build
     * from before M9-A. Defaulted so call sites that do not resolve obfuscation keep compiling.
     */
    val obfuscation: Obfuscation? = null,
    /**
     * The pre-resolved server address, or null (M9-C spec §3.2). Null emits exactly today's config.
     * Non-null replaces the proxy outbound's address and fills, when empty, the fields Xray would
     * otherwise derive from the dial address (research note 2026-10-15-m9c-xray-address-fallbacks).
     */
    val addressPin: ServerAddressPin? = null,
    /**
     * Where `geoip.dat`/`geosite.dat` are installed (`GeoAssetRepository.geoDirectory()`), or null.
     *
     * Non-null emits a root `"env": {"xray.location.asset": …}` block, which is how libXray
     * v26.10.10 learns the asset location (the invoke request carries no `env`). Null emits no
     * `env` key, so the output is byte-identical to a build from before this existed.
     */
    val geoAssetDir: String? = null,
)

/**
 * The outcome of generating a config.
 *
 * A sealed result rather than a nullable String: "unsupported protocol" and
 * "generation failed" are different things to a user, and §10.4 says the
 * message is the only diagnostic they can hand back — ARCHITECTURE.md §5.6 forbids logging
 * the config that would otherwise explain it.
 */
public sealed interface ConfigResult {
    public data class Ok(val json: String) : ConfigResult

    public data class Unsupported(val protocol: String) : ConfigResult
}

/**
 * Generates the Xray config JSON at connect time.
 *
 * ARCHITECTURE.md §6: the stored profile is not an Xray config. Generation is
 * deterministic — the same profile plus the same settings produces byte-identical
 * JSON — which is what makes golden-file tests and reviewable config diffs
 * possible.
 *
 * Emitted by hand rather than through a serialisation library. §10.7 says every
 * dependency in a VPN client is attack surface and to prefer stdlib, and writing
 * the string in order makes byte-identical output a property of this code rather
 * than of some library's map iteration order.
 *
 * Indentation is written literally rather than computed, so the source reads in
 * the shape of the JSON it emits. For a generator whose contract is byte-exact
 * output, that is worth more than avoiding the repetition.
 *
 * **Every key here was checked against the Xray-core schema**, and the whole
 * config is additionally run through libXray's own `testXray` in an instrumented
 * test. §10.5: agents confidently invent plausible Xray keys, and an unknown key
 * can be silently ignored or reject the entire config.
 */
/**
 * The three fixed outbound objects every generated config carries alongside
 * `proxy`, shared between [XrayConfigGenerator.appendOutbounds] (which always
 * emits `direct`/`block`, and `dns-out` when [TunnelSettings.dns] is set) and
 * [XrayConfigGenerator.overrideBlocks] (which hands the same three to the
 * passthrough path's override branch for outbounds the config lacks). One
 * definition rather than two hand-typed copies, so the pinned wire shape has
 * a single author (§10.5).
 */
private const val DIRECT_OUTBOUND_JSON = """{ "tag": "direct", "protocol": "freedom" }"""
private const val BLOCK_OUTBOUND_JSON = """{ "tag": "block", "protocol": "blackhole" }"""
private const val DNS_OUT_OUTBOUND_JSON = """{ "tag": "dns-out", "protocol": "dns" }"""

/**
 * The typed path's own server outbound tag.
 *
 * `appendOutbounds` emits `"tag": "proxy"` for every generated config, so the
 * typed path resolves to itself and its output is byte-identical to what this
 * generator produced before M7.5 — which the golden file test holds us to.
 */
private val TYPED_PATH_TARGET = OverrideTarget.ViaOutbound("proxy")

/**
 * The stream Shadowsocks and SOCKS dial with. The model gives them none; this is what Xray would
 * default to, emitted explicitly so the global fragment/noise `finalmask` (M9-A spec §3.3) has a
 * `streamSettings` to live in (M9-D2a spec §2).
 */
private val PLAIN_TCP_STREAM = StreamSettings(network = "tcp", security = Security.None)

/** Ruling D2-R10: an `httpupgrade` row stored without options (pre-D2) writes the core's own defaults. */
private val DEFAULT_HTTP_UPGRADE = TransportOptions.HttpUpgrade(host = "", path = "/")

// One object per wire shape (ARCHITECTURE.md §6); splitting it would scatter the shape's single author.
@Suppress("TooManyFunctions")
public object XrayConfigGenerator {
    /**
     * Dispatches on the profile's protocol.
     *
     * Emits VLESS, VMess, Trojan, Shadowsocks, SOCKS (M9-D2a spec §1), Hysteria2 (M9-D1 spec §4),
     * WireGuard and the HTTP proxy (M9-D2 spec §3). The `when`s over the sealed
     * [space.getsub.core.model.Outbound] have no `else` — adding a protocol to the model is a compile
     * error here until this generator is taught to emit it, rather than a silent fallthrough to a
     * config that connects to nothing.
     *
     * A row the core would refuse, or that would connect to nothing, is answered with
     * [ConfigResult.Unsupported] before anything is generated ([typedRefusal]): v26.10.10 has dropped
     * Shadowsocks ciphers the importers still accept, its Shadowsocks 2022 error quotes the key
     * (M9-D2a spec §0), and it refuses REALITY over mKCP and HTTPUpgrade. The protocol string is a
     * fixed literal because the UI shows it (ARCHITECTURE.md §5.6).
     *
     * For VLESS, VMess and Trojan it emits `tcp`, `ws`, `grpc`, `xhttp`, `kcp` and `httpupgrade`.
     * That set is mirrored by `StoredProfile.connectable` in `:core:data`, which decides what the UI offers —
     * `:core:data` cannot depend on this module (§4), so the two are kept in step by
     * hand. Teaching this generator a new transport means widening that set too;
     * leaving it narrow is how a working xhttp server came to be labelled
     * "not supported by this build yet".
     */
    public fun generate(
        profile: Profile,
        settings: TunnelSettings,
    ): ConfigResult {
        val outbound = profile.outbound
        return typedRefusal(outbound)?.let { ConfigResult.Unsupported(it) }
            ?: ConfigResult.Ok(generateTyped(outbound, settings))
    }

    private fun generateTyped(
        outbound: Outbound,
        settings: TunnelSettings,
    ): String {
        val sb = StringBuilder()

        sb.appendLine("{")
        sb.appendLine("""  "log": {""")
        // ARCHITECTURE.md §5.6, and this one was found on a device, not by reading: without
        // "access": "none", Xray writes a line to logcat for EVERY destination
        // the user reaches —
        //
        //   GoLog: from tcp:127.0.0.1:60680 accepted tcp:23.239.16.110:443 [socks-in >> proxy]
        //
        // gomobile pipes Go's stdout straight to logcat, so that is a live record
        // of the user's browsing readable by anything holding READ_LOGS and
        // captured by every bug report. loglevel alone does not suppress it —
        // the access log is a separate stream.
        sb.appendLine("""    "access": "none",""")
        // Do not raise this to "debug" for convenience: error-level output quotes
        // addresses too.
        sb.appendLine("""    "loglevel": "warning"""")
        sb.appendLine("""  },""")
        settings.geoAssetDir?.let { dir ->
            sb.appendLine("""  "env": {""")
            sb.appendLine("""    "xray.location.asset": ${jsonString(dir)}""")
            sb.appendLine("""  },""")
        }

        appendDns(sb, settings)
        appendInbounds(sb, settings)
        appendOutbounds(sb, outbound, settings)

        appendRouting(sb, settings.routing, settings.dns, trailingComma = settings.metricsPort != null)
        appendMetrics(sb, settings.metricsPort)
        sb.append("}")

        return sb.toString()
    }

    /**
     * ARCHITECTURE.md §5.2, half one. The other half is `VpnService.Builder.addDnsServer()`, and
     * M6.5 adds a third that matters more than either: the port-53 hijack in
     * [appendRouting], which is what makes any of this reach an app's resolver.
     *
     * libXray v26.10.10 has `setDNS`, but it replaces Go's process-wide resolver and Subspace
     * does not call it (see `XrayController.start`), so the config is the only lever here.
     */
    private fun appendDns(
        sb: StringBuilder,
        settings: TunnelSettings,
    ) {
        sb.appendLine("""  "dns": ${dnsObject(settings)},""")
    }

    /**
     * ARCHITECTURE.md §6's routing block. Empty in M1; M5 fills it from the active rule set.
     *
     * A null [routing] and a null [dns] reproduce the M1 block exactly —
     * `IPIfNonMatch` and an empty rule array — because the tunnel that block
     * belongs to is proven on hardware and this milestone does not get to drift
     * it. `XrayConfigGeneratorTest` pins that byte-for-byte.
     *
     * M6.5 prepends [dns]'s resolver and hijack rules ahead of the profile's own
     * — see [dnsRuleLines] for why the order matters.
     */
    private fun appendRouting(
        sb: StringBuilder,
        routing: RoutingRuleSet?,
        dns: DnsPlan?,
        trailingComma: Boolean,
    ) {
        // The typed path builds the `proxy` outbound itself (appendOutbounds), so
        // it is the one caller that knows its own target by construction. This is
        // now the single place the literal appears; before M7.5 it was written
        // out at three separate emitters.
        //
        // `namesFallthrough = false`: `appendOutbounds` writes `proxy` first, so
        // unmatched traffic already reaches it and a rule saying so would change
        // bytes the golden files pin. See `fallthroughRuleLines`.
        //
        // `trailingComma`: `routing` is the last field unless a metrics port
        // follows it (M8.5 spec §2) — see `appendMetrics`.
        sb.appendLine(
            """  "routing": ${routingObject(routing, dns, TYPED_PATH_TARGET, namesFallthrough = false)}""" +
                if (trailingComma) "," else "",
        )
    }

    /**
     * Appends `stats`/`policy`/`metrics`, only when [port] is non-null.
     *
     * A null port changes nothing: this is the one call site that can make the
     * typed path's output differ from the M1 shape the golden file pins, and
     * every other caller of [generate] gets that shape back byte-identical
     * (M8.5 spec §2.4 makes the same promise on the passthrough side, in
     * `RawConfigComposer`).
     */
    private fun appendMetrics(
        sb: StringBuilder,
        port: Int?,
    ) {
        val p = port ?: return
        val entries = metricsBlocks(p).entries.toList()
        entries.forEachIndexed { index, (key, value) ->
            val comma = if (index == entries.size - 1) "" else ","
            sb.appendLine("""  ${jsonString(key)}: $value$comma""")
        }
    }

    /**
     * The `routing` and `dns` objects the typed path writes, for the passthrough
     * path's override branch.
     *
     * Exposed rather than duplicated so a rule's shape has one author. The
     * passthrough path splices these in place of a config's own blocks; the two
     * paths therefore agree on what an app rule looks like by construction.
     *
     * [target] comes from the caller because only the caller knows whose config
     * this is: the rules that name the server have to name whatever *that*
     * document uses to reach it, and this generator never sees the document.
     *
     * `namesFallthrough = true`, and it is not optional: replacing a config's
     * `routing` deletes that config's own catch-all, and `RawConfigComposer`
     * leaves its `outbounds` in the document's order — so without a trailing
     * rule, unmatched traffic goes wherever that document happened to list
     * first. See `fallthroughRuleLines`.
     */
    public fun overrideBlocks(
        settings: TunnelSettings,
        target: OverrideTarget.Resolved,
    ): OverrideBlocks =
        OverrideBlocks(
            routingJson = routingObject(settings.routing, settings.dns, target, namesFallthrough = true),
            dnsJson = dnsObject(settings),
            extraOutboundsJson =
            buildList {
                add(DIRECT_OUTBOUND_JSON)
                add(BLOCK_OUTBOUND_JSON)
                if (settings.dns != null) add(DNS_OUT_OUTBOUND_JSON)
            },
        )

    private fun appendInbounds(
        sb: StringBuilder,
        settings: TunnelSettings,
    ) {
        val sniffing =
            if (!settings.enableSniffing) {
                null
            } else if (settings.dns?.fakeDns == true) {
                SniffingSettings(DEFAULT_SNIFFING.destOverride + "fakedns")
            } else {
                DEFAULT_SNIFFING
            }
        sb.appendLine("""  "inbounds": [""")
        sb.append(socksInboundJson(settings.socksPort, sniffing))
        sb.appendLine(if (settings.httpPort != null) "," else "")
        settings.httpPort?.let { port ->
            sb.appendLine(httpInboundJson(port))
        }
        sb.appendLine("""  ],""")
    }

    private fun appendOutbounds(
        sb: StringBuilder,
        out: Outbound,
        settings: TunnelSettings,
    ) {
        // M9-D2a spec §1: every protocol dials the pinned IP in place of its address, as VLESS does.
        val address = settings.addressPin?.ip ?: out.address
        val pinDomain = settings.addressPin?.domain
        sb.appendLine("""  "outbounds": [""")
        sb.appendLine("""    {""")
        sb.appendLine("""      "tag": "proxy",""")
        sb.appendLine("""      "protocol": ${jsonString(out.wireProtocol())},""")
        // M9-D2 research §5.3: a mask that cannot run on this outbound is not written, so the report
        // (which asks the same ObfuscationApplicability) never claims one the tunnel does not run.
        val obfuscation = settings.obfuscation?.applicableTo(out)
        if (out is WireGuardOutbound) {
            // Its own settings and stream writer: no server array, and a stream only for noise.
            appendWireGuard(sb, out, address, obfuscation)
        } else {
            appendProtocolSettings(sb, out, address)
            appendStream(sb, out, obfuscation, pinDomain)
        }
        sb.appendLine("""    },""")
        sb.appendLine("""    $DIRECT_OUTBOUND_JSON,""")
        val needsDnsOutbound = settings.dns != null
        sb.appendLine("""    $BLOCK_OUTBOUND_JSON${if (needsDnsOutbound) "," else ""}""")
        if (needsDnsOutbound) {
            // No settings object: the modern (rewriteNetwork/rewriteAddress/rules)
            // and legacy (network/address/nonIPQuery) field sets both exist at
            // v26.7.11, and emitting neither is stable across the deprecation.
            // Research §4: A/AAAA queries default to hijack into the built-in
            // resolver, which is exactly what this config wants.
            sb.appendLine("""    $DNS_OUT_OUTBOUND_JSON""")
        }
        sb.appendLine("""  ],""")
    }

    private fun appendStream(
        sb: StringBuilder,
        out: Outbound,
        obfuscation: Obfuscation?,
        pinDomain: String?,
    ) {
        when (out) {
            is VlessOutbound -> appendStreamSettings(sb, out.stream, obfuscation, pinDomain)
            is VmessOutbound -> appendStreamSettings(sb, out.stream, obfuscation, pinDomain)
            is TrojanOutbound -> appendStreamSettings(sb, out.stream, obfuscation, pinDomain)
            is ShadowsocksOutbound, is SocksOutbound ->
                appendStreamSettings(sb, PLAIN_TCP_STREAM, obfuscation, pinDomain)
            // TLS, when set, rides the TCP stream (research §2.3); the outbound carries TCP only.
            is HttpOutbound -> appendStreamSettings(sb, StreamSettings("tcp", out.security), obfuscation, pinDomain)
            // Its own stream block: the password lives in the transport, and TLS is mandatory.
            is Hysteria2Outbound -> appendHysteria2Stream(sb, out, obfuscation, pinDomain)
            is WireGuardOutbound -> error("appendOutbounds writes WireGuard itself")
        }
    }

    /**
     * WireGuard (M9-D2 spec §3), `WireGuardConfig` in Xray-core v26.10.10 `infra/conf/wireguard.go`:
     * `secretKey`, `address` (an array; omitted when empty, which takes the core's bogon defaults),
     * one peer `{publicKey, preSharedKey?, endpoint "host:port", keepAlive?, allowedIPs}`, `mtu`,
     * `reserved` (omitted when empty) and `noKernelTun: true` (research §1.2: never a kernel TUN, whatever
     * the process's capabilities). Keys are emitted exactly as stored. `allowedIPs` is omitted when empty:
     * an explicit `[]` routes nothing, an absent one is `0.0.0.0/0, ::/0` (research §1.1).
     *
     * [host] is the pinned IP when there is an address pin (research §5.2): the endpoint is the only dial
     * target, and a domain there is resolved by the core's DNS, not the protected OS path.
     *
     * `streamSettings` only when user noise applies: the outbound honours `finalmask` UDP masks and
     * `sockopt`, and ignores the rest without error (research §1.3, `w6.json`).
     */
    private fun appendWireGuard(
        sb: StringBuilder,
        out: WireGuardOutbound,
        host: String,
        obfuscation: Obfuscation?,
    ) {
        sb.appendLine("""      "settings": {""")
        sb.appendLine("""        "secretKey": ${jsonString(out.secretKey)},""")
        if (out.localAddresses.isNotEmpty()) sb.appendLine("""        "address": ${jsonList(out.localAddresses)},""")
        sb.appendLine("""        "peers": [""")
        sb.appendLine("""          {""")
        val peer =
            listOfNotNull(
                """"publicKey": ${jsonString(out.peerPublicKey)}""",
                out.preSharedKey?.takeIf { it.isNotEmpty() }?.let { """"preSharedKey": ${jsonString(it)}""" },
                """"endpoint": ${jsonString(endpoint(host, out.port))}""",
                out.keepAlive?.takeIf { it > 0 }?.let { """"keepAlive": $it""" },
                out.allowedIps.takeIf { it.isNotEmpty() }?.let { """"allowedIPs": ${jsonList(it)}""" },
            )
        peer.forEachIndexed { index, field ->
            val comma = if (index == peer.size - 1) "" else ","
            sb.appendLine("""            $field$comma""")
        }
        sb.appendLine("""          }""")
        sb.appendLine("""        ],""")
        sb.appendLine("""        "mtu": ${out.mtu},""")
        if (out.reserved.isNotEmpty()) sb.appendLine("""        "reserved": [${out.reserved.joinToString(", ")}],""")
        sb.appendLine("""        "noKernelTun": true""")
        val finalmask = obfuscation?.let { FinalmaskJson.streamBlock(it, emptyList()) }
        if (finalmask == null) {
            sb.appendLine("""      }""")
        } else {
            sb.appendLine("""      },""")
            sb.appendLine("""      "streamSettings": {""")
            sb.appendLine("""        "finalmask": $finalmask""")
            sb.appendLine("""      }""")
        }
    }

    /**
     * The `settings` object of the `proxy` outbound: the one-member `vnext`/`servers` array each
     * protocol's Build demands (M9-D2a spec §1). Keys verified against Xray-core v26.10.10
     * `infra/conf/`: `vless.go`; `vmess.go` (`VMessOutboundTarget`, `VMessAccount`: `id`,
     * `security` — there is no `alterId` key, and `experiments` is never emitted); `trojan.go`
     * (`TrojanServerTarget`; `flow` is a removed feature); `shadowsocks.go`
     * (`ShadowsocksServerTarget`: `method`, `password`); `socks.go` (`SocksRemoteConfig`,
     * `SocksAccount`: `user`, `pass`).
     */
    private fun appendProtocolSettings(
        sb: StringBuilder,
        out: Outbound,
        address: String,
    ) {
        val endpoint = listOf(""""address": ${jsonString(address)}""", """"port": ${out.port}""")
        when (out) {
            is WireGuardOutbound -> error("appendOutbounds writes WireGuard itself")
            is HttpOutbound -> appendHttpSettings(sb, endpoint, out)
            // `HysteriaClientConfig` (`infra/conf/hysteria.go`): a flat `version`/`address`/`port`, no
            // server array and no credentials. Build fails unless `version == 2`.
            is Hysteria2Outbound -> {
                sb.appendLine("""      "settings": {""")
                sb.appendLine("""        "version": 2,""")
                sb.appendLine("""        "address": ${jsonString(address)},""")
                sb.appendLine("""        "port": ${out.port}""")
                sb.appendLine("""      },""")
            }
            // VLESS has no transport encryption of its own; "none" is required, not
            // a weakening — TLS/REALITY in streamSettings is what secures it.
            is VlessOutbound ->
                appendServerSettings(
                    sb,
                    "vnext",
                    endpoint,
                    user =
                    listOfNotNull(
                        """"id": ${jsonString(out.uuid)}""",
                        """"encryption": "none"""",
                        out.flow?.let { """"flow": ${jsonString(it)}""" },
                    ),
                )

            // `security` is emitted as stored: VMessAccount.Build maps an unknown value to `auto`
            // rather than failing (M9-D2a spec §1). `alterId` is the model's record only.
            is VmessOutbound ->
                appendServerSettings(
                    sb,
                    "vnext",
                    endpoint,
                    user = listOf(""""id": ${jsonString(out.uuid)}""", """"security": ${jsonString(out.security)}"""),
                )

            is TrojanOutbound ->
                appendServerSettings(sb, "servers", endpoint + """"password": ${jsonString(out.password)}""")

            is ShadowsocksOutbound ->
                appendServerSettings(
                    sb,
                    "servers",
                    endpoint +
                        """"method": ${jsonString(out.method)}""" +
                        """"password": ${jsonString(out.password)}""",
                )

            // A blank username means no authentication: a password alone is dropped. Stricter than
            // SocksClientConfig.Build, which attaches a user when len(user) > 0, so it would send
            // a whitespace-only name that we drop.
            is SocksOutbound ->
                appendServerSettings(
                    sb,
                    "servers",
                    endpoint,
                    user =
                    out.username?.takeIf { it.isNotBlank() }?.let { name ->
                        listOf(""""user": ${jsonString(name)}""", """"pass": ${jsonString(out.password.orEmpty())}""")
                    },
                )
        }
    }

    /**
     * `HTTPClientConfig` (`infra/conf/http.go:61-82`): the flat form, which Build rewrites into the
     * one-member `servers` array itself. Credentials as SOCKS: a blank username means none, and a
     * password alone is dropped (a `user` key, even with an empty `pass`, creates the account).
     */
    private fun appendHttpSettings(
        sb: StringBuilder,
        endpoint: List<String>,
        out: HttpOutbound,
    ) {
        val credentials =
            out.username?.takeIf { it.isNotBlank() }?.let { name ->
                listOf(""""user": ${jsonString(name)}""", """"pass": ${jsonString(out.password.orEmpty())}""")
            }
        val fields = endpoint + credentials.orEmpty()
        sb.appendLine("""      "settings": {""")
        fields.forEachIndexed { index, field ->
            val comma = if (index == fields.size - 1) "" else ","
            sb.appendLine("""        $field$comma""")
        }
        sb.appendLine("""      },""")
    }

    /**
     * Writes `"settings": { "<listKey>": [ { <fields>, "users": [ { <user> } ] } ] },` at the
     * indentation the M1 VLESS block has always had, so that block stays byte-identical. [fields]
     * and [user] are `"key": value` pairs; a null [user] omits `users`.
     */
    private fun appendServerSettings(
        sb: StringBuilder,
        listKey: String,
        fields: List<String>,
        user: List<String>? = null,
    ) {
        val members =
            buildList {
                addAll(fields)
                user?.let { pairs ->
                    add(
                        buildString {
                            appendLine(""""users": [""")
                            appendLine("""              {""")
                            pairs.forEachIndexed { index, pair ->
                                val comma = if (index == pairs.size - 1) "" else ","
                                appendLine("""                $pair$comma""")
                            }
                            appendLine("""              }""")
                            append("""            ]""")
                        },
                    )
                }
            }
        sb.appendLine("""      "settings": {""")
        sb.appendLine("""        ${jsonString(listKey)}: [""")
        sb.appendLine("""          {""")
        members.forEachIndexed { index, member ->
            val comma = if (index == members.size - 1) "" else ","
            sb.appendLine("""            $member$comma""")
        }
        sb.appendLine("""          }""")
        sb.appendLine("""        ]""")
        sb.appendLine("""      },""")
    }

    /**
     * The `streamSettings` object, shared by every protocol but Hysteria2 (M9-D2a spec §2): VLESS,
     * VMess and Trojan pass their own [stream]; Shadowsocks and SOCKS pass [PLAIN_TCP_STREAM].
     * Hysteria2 writes its own, in [appendHysteria2Stream].
     */
    private fun appendStreamSettings(
        sb: StringBuilder,
        stream: StreamSettings,
        obfuscation: Obfuscation?,
        pinDomain: String?,
    ) {
        // The security block is last in the object unless a transport block
        // follows it, and JSON has no trailing commas — so whether it ends with
        // one is decided here rather than inside each security branch.
        val kcp = isKcpNetwork(stream.network)
        val transportBlock = stream.transport !is TransportOptions.None || kcp || isHttpUpgradeNetwork(stream.network)
        val tail = if (transportBlock) "," else ""
        sb.appendLine("""      "streamSettings": {""")
        // `kcp` and `mkcp` are one transport (`transport_internet.go:22-23`); written `kcp`, as spec §3 says.
        sb.appendLine("""        "network": ${jsonString(if (kcp) "kcp" else stream.network)},""")
        // M9-A spec §3.3. Placed straight after `network` because `security` always follows,
        // so its trailing comma never depends on which transport block, if any, closes the object.
        // mKCP's obfuscation is `finalmask.udp` masks, never `kcpSettings.header`/`seed` (research §3.1-3.3).
        val kcpMasks = (stream.transport as? TransportOptions.Kcp)?.let { KcpRules.masks(it.obfuscation) }.orEmpty()
        FinalmaskJson.streamBlock(obfuscation, kcpMasks)?.let { block ->
            sb.appendLine("""        "finalmask": $block,""")
        }
        when (val security = stream.security) {
            is Security.Reality -> {
                sb.appendLine("""        "security": "reality",""")
                sb.appendLine("""        "realitySettings": {""")
                sb.appendLine("""          "serverName": ${jsonString(security.serverName.fillBlank(pinDomain))},""")
                sb.appendLine("""          "publicKey": ${jsonString(security.publicKey)},""")
                sb.appendLine("""          "shortId": ${jsonString(security.shortId)},""")
                sb.appendLine("""          "fingerprint": ${jsonString(security.fingerprint)},""")
                sb.appendLine("""          "spiderX": ${jsonString(security.spiderX)}""")
                sb.appendLine("""        }$tail""")
            }

            is Security.Tls -> {
                sb.appendLine("""        "security": "tls",""")
                sb.appendLine("""        "tlsSettings": {""")
                sb.appendLine("""          "serverName": ${jsonString(security.serverName.fillBlank(pinDomain))},""")
                // No `allowInsecure`: Xray-core v26.3.27+ removed it and fails the whole build on
                // `true` (`infra/conf/transport_security.go`). Certificate checks always stay on;
                // Security.Tls.allowInsecure only records that the source asked otherwise.
                sb.appendLine("""          "fingerprint": ${jsonString(security.fingerprint)}""")
                sb.appendLine("""        }$tail""")
            }

            Security.None -> {
                sb.appendLine("""        "security": "none"$tail""")
            }
        }
        appendTransportSettings(sb, stream, pinDomain)
        sb.appendLine("""      }""")
    }

    /**
     * Hysteria2's `streamSettings` (M9-D1 spec §4), Xray-core v26.10.10:
     * `hysteriaSettings` is `HysteriaConfig` (`infra/conf/transport_method.go:762`: `version`, `auth`;
     * the legacy `congestion`/`up`/`down`/`udphop` are gone), and `security` is always `tls` because
     * `hysteria/dialer.go` refuses a nil TLS config.
     *
     * `tlsSettings`: never `allowInsecure` (removed, fails Build) and never `fingerprint` (ignored on
     * this transport, plan ruling R5). `alpn` is emitted as stored although quic-go overwrites it with
     * `h3`. Pins are `pinnedPeerCertSha256`, comma-joined hex (`transport_security.go:365-379`).
     *
     * A blank `serverName` is filled with [pinDomain] (plan ruling R7): `WithDestination` would
     * otherwise make the pinned IP both the SNI and the verification name (`tls/config.go:493-499`).
     */
    private fun appendHysteria2Stream(
        sb: StringBuilder,
        out: Hysteria2Outbound,
        obfuscation: Obfuscation?,
        pinDomain: String?,
    ) {
        sb.appendLine("""      "streamSettings": {""")
        sb.appendLine("""        "network": "hysteria",""")
        sb.appendLine("""        "hysteriaSettings": {""")
        sb.appendLine("""          "version": 2,""")
        sb.appendLine("""          "auth": ${jsonString(out.password)}""")
        sb.appendLine("""        },""")
        FinalmaskJson.hysteria2Block(out, obfuscation?.noise?.spec)?.let { block ->
            sb.appendLine("""        "finalmask": $block,""")
        }
        sb.appendLine("""        "security": "tls",""")
        val tls =
            listOfNotNull(
                """"serverName": ${jsonString(out.tls.serverName.fillBlank(pinDomain))}""",
                out.tls.alpn.takeIf { it.isNotEmpty() }?.let { alpn ->
                    """"alpn": [${alpn.joinToString(", ", transform = ::jsonString)}]"""
                },
                out.tls.pins.takeIf { it.isNotEmpty() }?.let { pins ->
                    """"pinnedPeerCertSha256": ${jsonString(pins.joinToString(","))}"""
                },
            )
        sb.appendLine("""        "tlsSettings": {""")
        tls.forEachIndexed { index, field ->
            val comma = if (index == tls.size - 1) "" else ","
            sb.appendLine("""          $field$comma""")
        }
        sb.appendLine("""        }""")
        sb.appendLine("""      }""")
    }

    /**
     * Emits the transport's own settings object, when the source specified one.
     *
     * Every key here is verified against Xray-core v26.7.11 —
     * `infra/conf/transport_method.go`, the version ARCHITECTURE.md §14.3 pins: `WebSocketConfig`
     * (`path`, `host`, `headers`), `GRPCConfig` (`serviceName`), `SplitHTTPConfig`
     * (`path`, `host`, `mode`). §10.5 applies with full force in this function —
     * an invented key is either silently ignored, which presents as a tunnel that
     * connects and carries nothing, or rejects the entire config.
     *
     * [TransportOptions.None] emits **nothing**, including for a non-`tcp`
     * network. That is not a gap: `StreamConfig.Build` skips a transport whose
     * settings object is nil rather than erroring, and the dial then takes that
     * transport's registered defaults — which is what a link naming `type=ws`
     * and no path actually means. Writing `"path": ""` instead would assert an
     * empty path the source never claimed.
     *
     * Absent optional fields are likewise omitted rather than emitted empty.
     * `""` is a value, and for `host` in particular it is a different request on
     * the wire: omitted means Xray falls back to the dial address, where empty
     * would be a literal empty `Host` header.
     */
    private fun appendTransportSettings(
        sb: StringBuilder,
        stream: StreamSettings,
        pinDomain: String?,
    ) {
        val transport = stream.transport
        val security = stream.security
        // mKCP and HTTPUpgrade are decided by the network: a row with no options (ruling D2-R10) still
        // writes its defaults. typedRefusal has refused options of one under another network.
        if (isKcpNetwork(stream.network)) return appendKcpSettings(sb, transport as? TransportOptions.Kcp)
        if (isHttpUpgradeNetwork(stream.network)) {
            val upgrade = transport as? TransportOptions.HttpUpgrade ?: DEFAULT_HTTP_UPGRADE
            return appendHttpUpgradeSettings(sb, upgrade, pinDomain.takeUnless { security is Security.Tls })
        }
        // A transport key is filled only where Xray's fallback chain would have reached the dial
        // address, so an address pin keeps every derived name on the domain (M9-C spec §3.2).
        when (transport) {
            is TransportOptions.None -> Unit
            is TransportOptions.Kcp, is TransportOptions.HttpUpgrade ->
                error("typedRefusal refuses this transport under another network")
            is TransportOptions.WebSocket ->
                appendWebSocketSettings(sb, transport, pinDomain.takeUnless { security is Security.Tls })
            is TransportOptions.Grpc -> {
                sb.appendLine("""        "grpcSettings": {""")
                val authority = pinDomain.takeIf { security == Security.None }
                val comma = if (authority != null) "," else ""
                sb.appendLine("""          "serviceName": ${jsonString(transport.serviceName)}$comma""")
                authority?.let { sb.appendLine("""          "authority": ${jsonString(it)}""") }
                sb.appendLine("""        }""")
            }

            is TransportOptions.Xhttp ->
                appendXhttpSettings(sb, transport, pinDomain.takeIf { security == Security.None })
        }
    }

    /**
     * `kcpSettings` (`KCPConfig`, `infra/conf/transport_method.go:532-581`): `mtu` and `tti` only, each
     * when set. Never `header` or `seed`: v26.10.10 parses and ignores them (research §3.1); the
     * obfuscation is the `finalmask.udp` masks [appendStreamSettings] wrote. `{}` takes the core's
     * defaults, which is what a row with neither means.
     */
    private fun appendKcpSettings(
        sb: StringBuilder,
        kcp: TransportOptions.Kcp?,
    ) {
        val fields = listOfNotNull(kcp?.mtu?.let { """"mtu": $it""" }, kcp?.tti?.let { """"tti": $it""" })
        if (fields.isEmpty()) {
            sb.appendLine("""        "kcpSettings": {}""")
            return
        }
        sb.appendLine("""        "kcpSettings": {""")
        fields.forEachIndexed { index, field ->
            val comma = if (index == fields.size - 1) "" else ","
            sb.appendLine("""          $field$comma""")
        }
        sb.appendLine("""        }""")
    }

    /**
     * `httpupgradeSettings` (`HttpUpgradeConfig`, `infra/conf/transport_method.go:660-694`): `host` and
     * `path`, always both. Never `headers`: a `Host` key there is a hard Build error (research §4.1), and
     * the model has nothing else to put in it. An empty `host` falls back to TLS `serverName`, then the
     * dial address (`httpupgrade/dialer.go:82-88`), so [hostFallback] (the pinned domain, only without
     * TLS) fills it exactly where the pinned IP would otherwise become the `Host`. `?ed=N` rides in `path`.
     */
    private fun appendHttpUpgradeSettings(
        sb: StringBuilder,
        transport: TransportOptions.HttpUpgrade,
        hostFallback: String?,
    ) {
        sb.appendLine("""        "httpupgradeSettings": {""")
        sb.appendLine("""          "host": ${jsonString(transport.host.fillBlank(hostFallback))},""")
        sb.appendLine("""          "path": ${jsonString(transport.path)}""")
        sb.appendLine("""        }""")
    }

    private fun appendWebSocketSettings(
        sb: StringBuilder,
        transport: TransportOptions.WebSocket,
        hostFallback: String?,
    ) {
        val headers =
            if (hostFallback != null && transport.headers.keys.none { it.equals("Host", ignoreCase = true) }) {
                transport.headers + ("Host" to hostFallback)
            } else {
                transport.headers
            }
        sb.appendLine("""        "wsSettings": {""")
        sb.appendLine(
            """          "path": ${jsonString(transport.path)}${if (headers.isEmpty()) "" else ","}""",
        )
        if (headers.isNotEmpty()) {
            sb.appendLine("""          "headers": {""")
            // Sorted, like `OutboundMapper` sorts the same map before hashing it:
            // §6 requires byte-identical output for the same profile, and a Map's
            // iteration order is a property of its implementation, not its contents.
            val sorted = headers.toSortedMap()
            sorted.entries.forEachIndexed { index, (name, value) ->
                val comma = if (index == sorted.size - 1) "" else ","
                sb.appendLine("""            ${jsonString(name)}: ${jsonString(value)}$comma""")
            }
            sb.appendLine("""          }""")
        }
        sb.appendLine("""        }""")
    }

    private fun appendXhttpSettings(
        sb: StringBuilder,
        transport: TransportOptions.Xhttp,
        hostFallback: String?,
    ) {
        // Built as a list first: `host` and `mode` are independently optional, so
        // deciding each line's trailing comma in place would need to look ahead
        // past the other one.
        val fields = mutableListOf(""""path": ${jsonString(transport.path)}""")
        (transport.host ?: hostFallback)?.let { fields += """"host": ${jsonString(it)}""" }
        transport.mode?.let { fields += """"mode": ${jsonString(it)}""" }

        sb.appendLine("""        "xhttpSettings": {""")
        fields.forEachIndexed { index, field ->
            val comma = if (index == fields.size - 1) "" else ","
            sb.appendLine("""          $field$comma""")
        }
        sb.appendLine("""        }""")
    }
}

/** The outbound `protocol` value: the name Xray-core v26.10.10 registers each config under. */
private fun Outbound.wireProtocol(): String =
    when (this) {
        is VlessOutbound -> "vless"
        is VmessOutbound -> "vmess"
        is TrojanOutbound -> "trojan"
        is ShadowsocksOutbound -> "shadowsocks"
        is SocksOutbound -> "socks"
        // `hysteria` is the name `infra/conf` registers the outbound under; `hysteria2` is not one.
        is Hysteria2Outbound -> "hysteria"
        is WireGuardOutbound -> "wireguard"
        is HttpOutbound -> "http"
    }

/** `host:port`, with an IPv6 literal bracketed as `ParseEndpoint` requires (research §1.1, `w12`). */
private fun endpoint(
    host: String,
    port: Int,
): String = if (IPV6_LITERAL.matches(host)) "[$host]:$port" else "$host:$port"

// Hex groups and colons, optionally an embedded IPv4 tail and a zone; anything else (a domain, a bracketed
// literal) is written as given.
private val IPV6_LITERAL = Regex("""[0-9A-Fa-f:]*:[0-9A-Fa-f:.]*(%[0-9A-Za-z._-]+)?""")

private fun jsonList(values: List<String>): String = values.joinToString(", ", "[", "]", transform = ::jsonString)

private fun String.fillBlank(fallback: String?): String = if (isBlank() && fallback != null) fallback else this

/**
 * One `dns.servers` entry — a bare string when unscoped, an object when it carries
 * `domains`.
 *
 * `skipFallback` on the scoped domestic server is load-bearing: without it a
 * domestic miss falls through to the remote resolver, leaking in exactly the
 * direction the profile author tried to prevent (M6.5 spec §7.2).
 */
private fun DnsServerSpec.render(): String {
    if (domains.isEmpty()) return jsonString(address)
    val domainList = domains.joinToString(", ", transform = ::jsonString)
    return """{ "address": ${jsonString(address)}, "domains": [$domainList], "skipFallback": $skipFallback }"""
}

/**
 * The rules that must precede the profile's own, in this order: the domestic
 * direct-match rule (if any), the remote proxy-match rule (if any), the
 * unconditional catch-all below, then the hijack rule.
 *
 * **The ordering is a loop hazard, not a preference, and claiming the traffic
 * is not optional.** The built-in resolver's own query to a configured server
 * is UDP to port 53. If nothing ahead of the hijack rule claimed it first, that
 * query would match the hijack and be fed straight back into the resolver that
 * issued it. [directMatch][DnsPlan.directMatch] and
 * [proxyMatch][DnsPlan.proxyMatch] are frequently both null — no profile
 * resolver, or a profile that names only hosts — so they cannot be what
 * guarantees this. The catch-all is: it is emitted unconditionally, on every
 * non-null plan, matching `inboundTag: ["dns-module"]` with no address filter,
 * so *all* of the resolver's own traffic is claimed before rule 3 can see it,
 * regardless of which matches happen to be set (M6.5 spec §7.3).
 *
 * The catch-all's target is `proxy`, never `direct`: a resolver query sent to
 * `direct` leaves the tunnel, which is the exact ARCHITECTURE.md §5.2 leak this plan exists to
 * prevent.
 */
private fun dnsRuleLines(
    dns: DnsPlan?,
    target: OverrideTarget.Resolved,
): List<String> {
    if (dns == null) return emptyList()
    val rules = mutableListOf<String>()
    dns.directMatch?.let { match -> rules += resolverRule(match, OverrideTarget.ViaOutbound("direct")) }
    dns.proxyMatch?.let { match -> rules += resolverRule(match, target) }
    rules += """{ "type": "field", "inboundTag": ["dns-module"], ${target.ruleTargetJson()} }"""
    rules += """{ "type": "field", "network": "tcp,udp", "port": 53, "outboundTag": "dns-out" }"""
    return rules
}

/** Matches one resolver's own traffic by address — an IP literal on `ip`, a hostname on `domain`. */
private fun resolverRule(
    match: String,
    target: OverrideTarget.Resolved,
): String {
    val field = if (DnsValidation.isAddressLiteral(match)) "ip" else "domain"
    return """{ "type": "field", "inboundTag": ["dns-module"], "$field": [${jsonString(match)}], """ +
        """${target.ruleTargetJson()} }"""
}

/**
 * The `dns` object's text, without the surrounding key or trailing comma —
 * [XrayConfigGenerator.appendDns] supplies both for the typed path;
 * [XrayConfigGenerator.overrideBlocks] hands the bare object to the
 * passthrough path's override branch. Top-level rather than a member so it
 * does not count against the object's function budget.
 */
private fun dnsObject(settings: TunnelSettings): String {
    val plan = settings.dns
    val sb = StringBuilder()
    sb.append("{\n")
    if (plan == null) {
        sb.appendLine("""    "servers": [${jsonString(settings.dnsServer)}]""")
        sb.append("""  }""")
        return sb.toString()
    }
    if (plan.hosts.isNotEmpty()) {
        sb.appendLine("""    "hosts": {""")
        // Sorted: a Map's iteration order is a property of its implementation
        // rather than of its contents, and §6 requires byte-determinism.
        val hosts = plan.hosts.toSortedMap().entries.toList()
        hosts.forEachIndexed { index, (host, address) ->
            val comma = if (index == hosts.size - 1) "" else ","
            sb.appendLine("""      ${jsonString(host)}: ${jsonString(address)}$comma""")
        }
        sb.appendLine("""    },""")
    }
    sb.appendLine("""    "servers": [""")
    val entries = buildList {
        if (plan.fakeDns) add(""""fakedns"""")
        plan.servers.forEach { add(it.render()) }
    }
    entries.forEachIndexed { index, entry ->
        val comma = if (index == entries.size - 1) "" else ","
        sb.appendLine("""      $entry$comma""")
    }
    sb.appendLine("""    ],""")
    sb.appendLine("""    "queryStrategy": "UseIP",""")
    sb.appendLine("""    "tag": "dns-module"""")
    sb.append("""  }""")
    return sb.toString()
}

/**
 * The `routing` object's text, without the surrounding key —
 * [XrayConfigGenerator.appendRouting] supplies it for the typed path;
 * [XrayConfigGenerator.overrideBlocks] hands the bare object to the
 * passthrough path's override branch. Top-level rather than a member so it
 * does not count against the object's function budget.
 */
private fun routingObject(
    routing: RoutingRuleSet?,
    dns: DnsPlan?,
    target: OverrideTarget.Resolved,
    namesFallthrough: Boolean,
): String {
    val strategy = routing?.domainStrategy ?: DomainStrategy.IP_IF_NON_MATCH
    // Last, and it has to be last: it matches everything, so anywhere earlier it
    // would shadow every rule behind it — including the port-53 hijack the DNS
    // plan depends on.
    val rules =
        dnsRuleLines(dns, target) +
            routing?.let { routingRuleLines(it, target) }.orEmpty() +
            fallthroughRuleLines(routing, target, namesFallthrough)

    val sb = StringBuilder()
    sb.append("{\n")
    sb.appendLine("""    "domainStrategy": ${jsonString(strategy.wireValue)},""")
    if (rules.isEmpty()) {
        sb.appendLine("""    "rules": []""")
    } else {
        sb.appendLine("""    "rules": [""")
        rules.forEachIndexed { index, rule ->
            val comma = if (index == rules.size - 1) "" else ","
            sb.appendLine("""      $rule$comma""")
        }
        sb.appendLine("""    ]""")
    }
    sb.append("""  }""")
    return sb.toString()
}
