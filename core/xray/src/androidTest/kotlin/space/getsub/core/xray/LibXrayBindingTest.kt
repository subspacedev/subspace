// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.xray

import libXray.LibXray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the libXray AAR loads and its Go runtime initialises on a real device.
 *
 * This is deliberately the cheapest possible call. If the JNI binding or the Go
 * runtime is broken, it fails here — where the cause is obvious — rather than
 * inside the tunnel start sequence, where ARCHITECTURE.md §10.4 warns it would
 * present as an unattributable failure.
 *
 * The API shape asserted here is recorded verbatim in
 * docs/agent/research/libxray-api.md. `apiVersion` must be exactly 3 for libXray v26.10.10.
 */
class LibXrayBindingTest {

    @Test
    fun coreReportsItsVersionThroughTheInvokeEnvelope() {
        val request = JSONObject()
            .put("apiVersion", 3)
            .put("method", "xrayVersion")
            .toString()

        val response = JSONObject(LibXray.invoke(request))

        assertTrue(
            "expected success, got: ${response.optString("error")}",
            response.getBoolean("success"),
        )
        // core.Version() is x.y.z with no leading "v" (docs/agent/research/libxray-api.md §2).
        assertEquals("26.10.10", response.getJSONObject("data").getString("version"))
    }

    @Test
    fun anyOtherApiVersionIsRefusedInTheEnvelope() {
        val request = JSONObject()
            .put("apiVersion", 1)
            .put("method", "xrayVersion")
            .toString()

        val response = JSONObject(LibXray.invoke(request))

        assertEquals(false, response.getBoolean("success"))
        assertTrue(response.getString("error").contains("unsupported apiVersion"))
    }

    @Test
    fun unknownMethodFailsInTheEnvelopeRatherThanThrowing() {
        // §10.4: failures must be legible. libXray never throws — it encodes the
        // error in the response. Pin that behaviour so XrayController can rely on it.
        val request = JSONObject()
            .put("apiVersion", 3)
            .put("method", "definitelyNotAMethod")
            .toString()

        val response = JSONObject(LibXray.invoke(request))

        assertEquals(false, response.getBoolean("success"))
        assertEquals("unknown method", response.getString("error"))
    }
}
