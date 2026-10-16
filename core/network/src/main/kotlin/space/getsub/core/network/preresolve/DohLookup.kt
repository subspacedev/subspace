// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.network.preresolve

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import space.getsub.core.model.FragmentSpec
import space.getsub.core.network.FragmentingSocketFactory
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

/** Which DoH server to ask (M9-C spec §2.1). [fragment] is the user's global Fragmentation, or null. */
public data class ResolverConfig(
    val dohUrl: String,
    val bootstrapIp: String?,
    val fragment: FragmentSpec?,
) {
    // ARCHITECTURE.md §5.6: a provider's resolver is subscription content.
    override fun toString(): String =
        "ResolverConfig(dohUrl=<redacted>, bootstrapIp=${if (bootstrapIp == null) "none" else "<redacted>"}, " +
            "fragment=${fragment != null})"
}

public fun interface DohLookup {
    /** A and AAAA answers for [host]; throws [java.io.IOException] on any failure. */
    public suspend fun lookup(host: String): List<InetAddress>
}

public fun interface DohLookupFactory {
    public fun create(config: ResolverConfig): DohLookup
}

/**
 * RFC 8484 over a dedicated OkHttp client (M9-C spec §2.2). The bootstrap IP is the DoH host's address,
 * so its name never goes to plain DNS; without one (a provider resolver with no `-dns-ip`) OkHttp's
 * system resolver looks it up. Sockets are protected, and fragmented when the user's switch is On.
 */
public class OkHttpDohLookupFactory(
    private val protect: SocketProtect,
) : DohLookupFactory {
    override fun create(config: ResolverConfig): DohLookup {
        val base: SocketFactory = config.fragment?.let(::FragmentingSocketFactory) ?: SocketFactory.getDefault()
        val client =
            OkHttpClient.Builder()
                .socketFactory(ProtectedSocketFactory(base, protect))
                .callTimeout(DOH_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                .build()
        val doh =
            DnsOverHttps.Builder()
                .client(client)
                .url(config.dohUrl.toHttpUrl())
                .includeIPv6(true)
                .apply { config.bootstrapIp?.let(IpLiteral::parse)?.let { bootstrapDnsHosts(it) } }
                .build()
        return DohLookup { host -> withContext(Dispatchers.IO) { doh.lookup(host) } }
    }
}

internal const val DOH_TIMEOUT_MILLIS: Long = 3_000
