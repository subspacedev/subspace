// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.build

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeStrictlyIncreasing
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import org.junit.Test

class AppVersionTest {
    private fun code(name: String, abi: Abi = Abi.Universal) = AppVersion.parse(name).versionCode(abi)

    @Test
    fun `parses a prerelease and codes it above the old hard-coded 1`() {
        AppVersion.parse("0.1.0-alpha01") shouldBe AppVersion(0, 1, 0, Stage.Alpha, 1)
        code("0.1.0-alpha01") shouldBe 400_010
    }

    @Test
    fun `final release codes and abi offsets`() {
        code("1.0.0") shouldBe 40_003_000
        code("1.0.0", Abi.Arm64V8a) shouldBe 40_003_002
    }

    @Test
    fun `universal codes increase with release order`() {
        val names = listOf("1.0.0-alpha99", "1.0.0-beta1", "1.0.0-rc1", "1.0.0", "1.0.1-alpha1", "1.1.0", "2.0.0")
        names.map { code(it) }.shouldBeStrictlyIncreasing()
    }

    @Test
    fun `no abi of one release outranks the next release`() {
        val highest = Abi.entries.maxOf { code("1.0.0", it) }
        highest shouldBeLessThan code("1.0.1-alpha1")
    }

    @Test
    fun `largest version stays within the play store limit`() {
        code("51.99.99", Abi.X86_64) shouldBeLessThanOrEqual 2_100_000_000
    }

    @Test
    fun `rejects malformed or out-of-range names and quotes them`() {
        listOf("1.0", "v1.0.0", "1.0.0-beta", "1.0.0-beta100", "1.0.0-RC1", "52.0.0", "53.0.0", "1.100.0", "").forEach { bad ->
            val e = shouldThrow<IllegalArgumentException> { AppVersion.parse(bad) }
            e.message!! shouldContain "'$bad'"
            e.message!! shouldContain "MAJOR.MINOR.PATCH[-(alpha|beta|rc)N]"
        }
    }

    @Test
    fun `rejects prerelease number zero`() {
        shouldThrow<IllegalArgumentException> { AppVersion.parse("1.0.0-alpha00") }
    }

    @Test
    fun `abi from filter`() {
        Abi.fromFilter(null) shouldBe Abi.Universal
        Abi.fromFilter("arm64-v8a") shouldBe Abi.Arm64V8a
        shouldThrow<IllegalArgumentException> { Abi.fromFilter("x86") }
    }

    @Test
    fun `reads versionName from a properties file`() {
        val f = File.createTempFile("version", ".properties")
        try {
            f.writeText("# c\nversionName=2.3.4-rc2\n")
            readVersionName(f) shouldBe "2.3.4-rc2"
        } finally {
            f.delete()
        }
    }
}
