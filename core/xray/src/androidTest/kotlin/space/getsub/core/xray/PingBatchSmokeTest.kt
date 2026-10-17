// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.xray

import io.kotest.matchers.shouldBe
import org.json.JSONObject
import org.junit.After
import org.junit.Test
import space.getsub.core.model.LatencyOutcome
import space.getsub.core.model.LatencyResult
import space.getsub.core.model.Profile
import space.getsub.core.model.Security
import space.getsub.core.model.StreamSettings
import space.getsub.core.model.VlessOutbound

/**
 * Runs `pingBatch` against the real libXray v26.10.10 AAR with one server that
 * cannot answer, and proves a failure arrives as a failure and never as a number
 * (ARCHITECTURE.md §10.1).
 *
 * The target is `127.0.0.1:1` behind VLESS over TLS: nothing listens there, so the
 * dial is refused, and TLS keeps the core's refusal of VLESS without TLS to a
 * public server out of the picture (a loopback address is private anyway).
 * libXray answers with `success:false` and a sentinel `delay` of `10000`; that
 * sentinel must not reach the caller.
 *
 * `pingBatch` refuses to run in a process holding a managed instance
 * (`runXray`). No test in this module starts one, and [stopAnyManagedInstance]
 * makes sure a future one cannot leave it running underneath this class.
 */
class PingBatchSmokeTest {
    private val profile =
        Profile(
            id = "unreachable",
            name = "unreachable",
            outbound =
            VlessOutbound(
                address = "127.0.0.1",
                port = 1,
                uuid = "70cc48c5-b2f4-4a1e-9f3d-0123456789ab",
                flow = null,
                stream = StreamSettings(network = "tcp", security = Security.Tls("localhost", "chrome", false)),
            ),
        )

    private val target: PingTarget
        get() = PingTarget(key = 7L, xrayJson = checkNotNull(pingConfig(profile, obfuscation = null)))

    @After
    fun stopAnyManagedInstance() {
        // The one process-wide precondition of pingBatch; stopXray with nothing running succeeds.
        LibXrayInvoke.call("stopXray")
    }

    @Test
    fun anUnreachableServerComesBackAsAFailureEntryNotAWholeCallError() {
        val request = PingBatch.request(listOf(target), timeoutSeconds = 3, url = CHECK_URL)

        val data = checkNotNull(LibXrayPingBatchCall().invoke(request)) { "pingBatch returned no data" }

        val results = JSONObject(data).getJSONArray("results")
        results.length() shouldBe 1
        results.getJSONObject(0).getBoolean("success") shouldBe false
    }

    @Test
    fun anUnreachableServerIsMappedToFailedNeverToANumber() {
        val results = PingBatch.measure(LibXrayPingBatchCall(), listOf(target), timeoutSeconds = 3, url = CHECK_URL)

        results shouldBe mapOf(7L to LatencyResult.failed(LatencyOutcome.UNREACHABLE))
    }

    private companion object {
        const val CHECK_URL = "https://www.gstatic.com/generate_204"
    }
}
