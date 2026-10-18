// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.network

import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import space.getsub.core.model.LocalProxy
import java.net.InetSocketAddress
import java.net.Proxy
import java.nio.charset.StandardCharsets

private const val PROXY_AUTHORIZATION = "Proxy-Authorization"

/** The challenge scheme OkHttp puts on the synthetic 407 it sends to the authenticator before a `CONNECT`. */
private const val PREEMPTIVE_SCHEME = "OkHttp-Preemptive"
private const val BASIC_SCHEME = "Basic"
private const val CONNECT = "CONNECT"

/**
 * Routes this client through the tunnel's loopback HTTP inbound, authenticating with the session's
 * account (M9-E spec §5.3).
 *
 * The inbound answers a request without the account with a 407 and then closes the socket
 * (research R4.2), so a challenge-then-retry cannot be relied on: the credential is sent with the
 * first request instead.
 * - **https** goes through a `CONNECT` tunnel. Before the first `CONNECT`, OkHttp asks the proxy
 *   authenticator with a synthetic 407 whose scheme is [PREEMPTIVE_SCHEME], and uses what it
 *   returns as the `CONNECT` request (research R4.4). The credential stays on the `CONNECT` and
 *   never enters the tunnel, so the origin server does not see it.
 * - **plain http** has no `CONNECT`; the request itself goes to the proxy. OkHttp does not ask the
 *   authenticator before it, so [PlainHttpProxyAuthorization] adds the header.
 *
 * A 407 on a request that already carried the credential is a failed attempt: the authenticator
 * returns null and the call fails. Nothing here retries without the proxy or falls back to a direct
 * connection (M9-E Review Focus 2).
 *
 * `Proxy.Type.HTTP`, never SOCKS — see the callers for why.
 */
internal fun OkHttpClient.Builder.throughLocalProxy(local: LocalProxy): OkHttpClient.Builder {
    val header = Credentials.basic(local.credentials.user, local.credentials.pass, StandardCharsets.UTF_8)
    return proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", local.port)))
        .proxyAuthenticator(LocalProxyAuthenticator(header))
        .addInterceptor(PlainHttpProxyAuthorization(header))
}

/**
 * Answers the proxy's own questions with [header], once: the preemptive `CONNECT` question and a
 * real `Basic` challenge on a `CONNECT` or a plain-http request.
 *
 * OkHttp 5.2.1 hands the proxy authenticator *every* 407 on an HTTP-proxy route
 * (`RetryAndFollowUpInterceptor.followUpRequest`), including one an https origin sends back
 * through an established `CONNECT` tunnel. That 407 is the origin's, not the proxy's, and answering
 * it would send the session's account inside TLS to the origin. So an https request that is not
 * the `CONNECT` itself never gets an answer.
 *
 * [header] is the encoded credential. It is held, never printed: the class has no `toString`
 * override that would show it and the default prints only the class name and identity hash.
 */
internal class LocalProxyAuthenticator(
    private val header: String,
) : Authenticator {
    override fun authenticate(
        route: Route?,
        response: Response,
    ): Request? {
        val request = response.request
        // The proxy sees the CONNECT, and a plain-http request in absolute form; nothing else.
        val forTheProxy = request.method == CONNECT || !request.url.isHttps
        val answerable =
            response.challenges().any { challenge ->
                challenge.scheme.equals(BASIC_SCHEME, ignoreCase = true) ||
                    challenge.scheme.equals(PREEMPTIVE_SCHEME, ignoreCase = true)
            }
        // Already sent: the proxy refused this account, and asking again would only repeat it.
        val alreadySent = request.header(PROXY_AUTHORIZATION) != null
        return if (forTheProxy && answerable && !alreadySent) {
            request.newBuilder().header(PROXY_AUTHORIZATION, header).build()
        } else {
            null
        }
    }
}

/**
 * Adds [header] to plain-http requests, which travel to the proxy in absolute form.
 *
 * An application interceptor rather than a network one: the request it changes is the one OkHttp
 * hands back to [LocalProxyAuthenticator] with a 407, so the authenticator sees the credential was
 * already sent and gives up instead of retrying. https requests are left alone — their headers go
 * through the tunnel to the origin, which must not receive the proxy's account.
 */
private class PlainHttpProxyAuthorization(
    private val header: String,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        return chain.proceed(
            if (request.isHttps) request else request.newBuilder().header(PROXY_AUTHORIZATION, header).build(),
        )
    }
}
