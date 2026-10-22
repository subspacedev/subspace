// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.network

import io.kotest.matchers.shouldBe
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Test

/**
 * Task 10 fix round 1: OkHttp hands the proxy authenticator *every* 407 on an HTTP-proxy route —
 * including one an https origin sends back through the established `CONNECT` tunnel. Answering that
 * would put the session's proxy account inside TLS, addressed to the origin. Only the proxy's own
 * questions get an answer.
 */
class LocalProxyAuthenticatorTest {
    private val authenticator = LocalProxyAuthenticator(HEADER)

    @Test
    fun `a Basic 407 from an https origin inside the tunnel gets no answer`() {
        val request = Request.Builder().url("https://sub.example/list").build()

        authenticator.authenticate(null, challenge(request, "Basic realm=\"origin\"")) shouldBe null
    }

    @Test
    fun `the preemptive CONNECT question is answered with the credential`() {
        val answer = authenticator.authenticate(null, challenge(connect(), "OkHttp-Preemptive"))

        answer?.method shouldBe "CONNECT"
        answer?.header("Proxy-Authorization") shouldBe HEADER
    }

    @Test
    fun `a Basic 407 on a CONNECT without the credential is answered`() {
        authenticator
            .authenticate(null, challenge(connect(), "Basic realm=\"proxy\""))
            ?.header("Proxy-Authorization") shouldBe HEADER
    }

    @Test
    fun `a 407 on a CONNECT that already carried the credential gets no answer`() {
        val sent = connect().newBuilder().header("Proxy-Authorization", HEADER).build()

        authenticator.authenticate(null, challenge(sent, "Basic realm=\"proxy\"")) shouldBe null
    }

    @Test
    fun `a 407 on a plain http request that already carried the credential gets no answer`() {
        val sent = Request.Builder().url("http://sub.example/list").header("Proxy-Authorization", HEADER).build()

        authenticator.authenticate(null, challenge(sent, "Basic realm=\"proxy\"")) shouldBe null
    }

    @Test
    fun `a non-Basic challenge on a CONNECT gets no answer`() {
        authenticator.authenticate(null, challenge(connect(), "Digest realm=\"proxy\"")) shouldBe null
    }

    /** Shaped like OkHttp's own tunnel request: `CONNECT` with the origin's https URL. */
    private fun connect() =
        Request.Builder().url("https://sub.example/").method("CONNECT", null).build()

    private fun challenge(
        request: Request,
        proxyAuthenticate: String,
    ) = Response
        .Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(407)
        .message("Proxy Authentication Required")
        .header("Proxy-Authenticate", proxyAuthenticate)
        .build()

    private companion object {
        const val HEADER = "Basic dHVubmVsVXNlcjpzM2NyZXRQYXNz"
    }
}
