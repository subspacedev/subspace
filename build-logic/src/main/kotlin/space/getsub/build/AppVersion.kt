// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.build

import java.io.File
import java.util.Properties

/**
 * ABI split ids and their version-code offsets (M10-A spec §6.2). Stores
 * require every APK of one release to carry a distinct versionCode, and the
 * offset sits in the lowest digit so that no ABI of release N can outrank
 * release N+1.
 */
enum class Abi(val id: String, val offset: Int) {
    Universal("universal", 0),
    ArmeabiV7a("armeabi-v7a", 1),
    Arm64V8a("arm64-v8a", 2),
    X86_64("x86_64", 3),
    ;

    companion object {
        /** `null` means "no ABI filter", i.e. the universal APK. */
        fun fromFilter(filter: String?): Abi {
            if (filter == null) return Universal
            return entries.firstOrNull { it.id == filter }
                ?: throw IllegalArgumentException("Unknown ABI filter '$filter'; expected one of ${entries.joinToString { it.id }}")
        }
    }
}

enum class Stage(val rank: Int) { Alpha(0), Beta(1), Rc(2), Final(3) }

data class AppVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val stage: Stage,
    val preNumber: Int,
) {
    /**
     * M10-A spec §6.2. A final release sorts above every prerelease of the same
     * triple (300 > alpha 1..99 / beta 101..199 / rc 201..299). Bounds: major
     * 0..51, because 51.99.99 is the largest code under Play's
     * 2_100_000_000 ceiling (52.99.99 would reach about 2.12e9).
     */
    fun versionCode(abi: Abi): Int {
        val stageValue = if (stage == Stage.Final) FINAL_STAGE_VALUE else stage.rank * 100 + preNumber
        return major * 40_000_000 + minor * 400_000 + patch * 4_000 + stageValue * 10 + abi.offset
    }

    companion object {
        private const val FINAL_STAGE_VALUE = 300
        private const val MAX_MAJOR = 51
        private const val MAX_MINOR_PATCH = 99
        private const val MAX_PRE = 99
        private val PATTERN = Regex("""(\d+)\.(\d+)\.(\d+)(?:-(alpha|beta|rc)(\d+))?""")

        internal fun invalid(name: String, why: String? = null): IllegalArgumentException =
            IllegalArgumentException(
                "Invalid version name '$name': expected MAJOR.MINOR.PATCH[-(alpha|beta|rc)N]" +
                    " (major 0..$MAX_MAJOR, minor/patch 0..$MAX_MINOR_PATCH, N 1..$MAX_PRE)" +
                    (why?.let { "; $it" } ?: ""),
            )

        internal fun parseChecked(name: String): AppVersion {
            val m = PATTERN.matchEntire(name) ?: throw invalid(name)
            val nums = m.groupValues.slice(1..3).map { it.toIntOrNull() ?: throw invalid(name, "number too large") }
            val (major, minor, patch) = nums
            if (major > MAX_MAJOR || minor > MAX_MINOR_PATCH || patch > MAX_MINOR_PATCH) throw invalid(name, "out of range")
            val tag = m.groupValues[4]
            if (tag.isEmpty()) return AppVersion(major, minor, patch, Stage.Final, 0)
            val pre = m.groupValues[5].toIntOrNull() ?: throw invalid(name, "number too large")
            if (pre !in 1..MAX_PRE) throw invalid(name, "prerelease number out of range")
            val stage = when (tag) {
                "alpha" -> Stage.Alpha
                "beta" -> Stage.Beta
                else -> Stage.Rc
            }
            return AppVersion(major, minor, patch, stage, pre)
        }
    }
}

fun AppVersion.Companion.parse(name: String): AppVersion = parseChecked(name)

/** Reads `versionName` from the committed `version.properties` (M10-A spec §6.2). */
fun readVersionName(propertiesFile: File): String {
    val props = Properties()
    propertiesFile.inputStream().use { props.load(it) }
    return requireNotNull(props.getProperty("versionName")?.trim()) {
        "versionName missing from ${propertiesFile.path}"
    }
}
