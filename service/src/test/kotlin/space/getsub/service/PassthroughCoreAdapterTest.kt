// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.Test
import space.getsub.core.xray.XrayException
import java.io.File

/**
 * [validateOnCore] hoisted out of [ServiceModule.boundPassthroughValidator]'s `@Provides` lambda
 * (review Important 5) precisely so its exception conversion can be pinned here, on the JVM,
 * without Android or a mocking library.
 */
class PassthroughCoreAdapterTest {
    /**
     * libXray v26.10.10's `testXray` takes the config as JSON (`docs/agent/research/libxray-api.md`
     * §2), so nothing is written for it any more: the temp file this adapter used to create in the
     * cache directory held the UUID and REALITY key (ARCHITECTURE.md §5.6). The cache directory is
     * not a parameter now; what is checked is the one place a JVM temp file would land, and that
     * the core is handed the very text it was asked to judge.
     */
    @Test
    fun `validateOnCoreWritesNoFile`() =
        runTest {
            val tmp = File(requireNotNull(System.getProperty("java.io.tmpdir")))
            val before = tmp.list().orEmpty().toSet()
            var seen: String? = null

            val accepted = validateOnCore(validate = { json -> seen = json }, json = "{}")

            accepted shouldBe true
            seen shouldBe "{}"
            (tmp.list().orEmpty().toSet() - before).filter { it.startsWith("passthrough-validate") } shouldBe
                emptyList()
        }

    @Test
    fun `a core refusal returns false`() =
        runTest {
            val accepted = validateOnCore(validate = { throw XrayException("refused") }, json = "{}")

            accepted shouldBe false
        }

    @Test
    fun `a non-core failure propagates rather than being reported as a verdict`() =
        runTest {
            shouldThrow<IllegalStateException> {
                validateOnCore(validate = { throw IllegalStateException("boom") }, json = "{}")
            }
        }
}
