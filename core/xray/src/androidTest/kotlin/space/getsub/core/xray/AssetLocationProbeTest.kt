// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.xray

import android.system.Os
import androidx.test.platform.app.InstrumentationRegistry
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.json.JSONObject
import org.junit.Test
import java.io.File

/**
 * Proves how — and only how — xray-core can be pointed at the geo directory on
 * Android. This is the mechanism ARCHITECTURE.md §10.2 calls load-bearing, and
 * the one M5 originally got wrong.
 *
 * ## Why both cases are here
 *
 * The negative half is not a curiosity: the shipped design used
 * `android.system.Os.setenv` and every automated test passed, because
 * `GeoAssetPathTest` only ever asserted that `Os.getenv` reads the value back —
 * which exercises libc's `environ`, not Go's. Go's Android shared-library entry
 * point (`runtime/rt0_android_arm64.s`) starts the runtime with a synthetic argv
 * and an **empty envv**, so a gomobile-built library has no environment at all
 * and a C `setenv` can never reach it. If someone "simplifies" the config `env`
 * block away and goes back to `Os.setenv`, this test is what fails.
 *
 * The positive pins the channel the app uses: the config's own root
 * `"env": {"xray.location.asset": <dir>}` block, which xray-core applies to the
 * Go environment while loading the config. The official v26.10.10 AAR has no
 * invoke-level `env`; **this test is the check that the stock AAR honours the
 * config block.**
 *
 * ## Why the negative is phrased as it is
 *
 * The config `env` block calls `os.Setenv` **inside Go**, which mutates that
 * process's Go environment for its whole lifetime, and the app puts that block on
 * every config. Every instrumented class shares one process and their order is
 * not guaranteed, so by the time this test runs Go's `XRAY_LOCATION_ASSET` may
 * already name some directory an earlier test used. The negative therefore does
 * not assert *where* the core looked (it is `/system/bin` only in a fresh
 * process). It asserts the claim that matters: with no `env` block the
 * `geosite:` rule is rejected, and the rejection does not name the directory that
 * was handed over via `Os.setenv`, so libc's environment never reached Go.
 *
 * Residual false-pass: if an earlier test left a `geosite.dat` containing the
 * `TEST` code in a directory that Go's environment still names, the no-env config
 * would resolve and the "must fail" assertion would trip. No test does that
 * today; keep it so.
 *
 * ## Why this is one test method and not two
 *
 * The positive sets Go's environment, which only ever grows. Run before the
 * negative it could make the negative resolve the rule through the `env` it set,
 * so both halves live in one method, negative first. Re-sending the same `env` is
 * idempotent.
 */
class AssetLocationProbeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** `GeoSiteList { entry { country_code: "TEST", domain { type: Domain, value: "example.com" } } }`. */
    private fun geoSiteBytes(): ByteArray {
        val domain =
            byteArrayOf(0x08, 0x02) +
                byteArrayOf(0x12, 0x0b) + "example.com".toByteArray()
        val geoSite =
            byteArrayOf(0x0a, 0x04) + "TEST".toByteArray() +
                byteArrayOf(0x12, domain.size.toByte()) + domain
        return byteArrayOf(0x0a, geoSite.size.toByte()) + geoSite
    }

    private fun stagedGeoDir(): File {
        val dir = File(context.cacheDir, "asset-probe-${System.nanoTime()}").apply { mkdirs() }
        File(dir, "geosite.dat").writeBytes(geoSiteBytes())
        return dir
    }

    /**
     * A config whose only routing rule needs `geosite.dat` to resolve, carrying a
     * root `env` block pointing at [envDir] when that is non-null.
     */
    private fun config(envDir: File?): String {
        val env =
            envDir
                ?.let { """"env": { "xray.location.asset": ${JSONObject.quote(it.absolutePath)} },""" }
                .orEmpty()
        return """
        {
          $env
          "log": { "loglevel": "warning" },
          "inbounds": [
            {
              "tag": "socks-in",
              "protocol": "socks",
              "listen": "127.0.0.1",
              "port": 10800,
              "settings": { "udp": true }
            }
          ],
          "outbounds": [
            { "tag": "direct", "protocol": "freedom" },
            { "tag": "block", "protocol": "blackhole" }
          ],
          "routing": {
            "domainStrategy": "IPIfNonMatch",
            "rules": [
              { "type": "field", "domain": ["geosite:TEST"], "outboundTag": "block" }
            ]
          }
        }
        """.trimIndent()
    }

    private fun testXray(config: String) {
        LibXrayInvoke.callJson("testXray", xrayJsonPayload(config))
    }

    @Test
    fun onlyTheConfigEnvBlockPointsTheCoreAtTheGeoDirectory() {
        // ---- negative, first: Go's environment is still empty ----
        val setenvDir = stagedGeoDir()
        Os.setenv("XRAY_LOCATION_ASSET", setenvDir.absolutePath, true)
        // libc's environ has it...
        Os.getenv("XRAY_LOCATION_ASSET") shouldBe setenvDir.absolutePath

        val failure = runCatching { testXray(config(envDir = null)) }.exceptionOrNull()

        // ...and Go cannot see it: the rule is rejected, and not for want of the
        // directory libc was given. Order-independent: see the class KDoc.
        requireNotNull(failure) { "expected the core to reject a geosite: rule with no asset location" }
        (failure is XrayException) shouldBe true
        failure.message.orEmpty() shouldNotContain setenvDir.absolutePath

        // ---- positive: the same rule, resolved through the config's env block ----
        // Throws XrayException if the core rejected it; a clean return means the
        // geosite: reference resolved out of envDir.
        testXray(config(envDir = stagedGeoDir()))
    }
}
