// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.build

/**
 * The four values that make a release signable (M10-A spec §6.3). Each can come from a
 * Gradle property (a developer's `~/.gradle/gradle.properties` or `-P`) or an environment
 * variable (CI); the property wins when both are set.
 */
enum class SigningInput(val gradleProperty: String, val envVar: String) {
    StoreFile("subspace.signing.storeFile", "SUBSPACE_SIGNING_STORE_FILE"),
    StorePassword("subspace.signing.storePassword", "SUBSPACE_SIGNING_STORE_PASSWORD"),
    KeyAlias("subspace.signing.keyAlias", "SUBSPACE_SIGNING_KEY_ALIAS"),
    KeyPassword("subspace.signing.keyPassword", "SUBSPACE_SIGNING_KEY_PASSWORD"),
}

sealed interface SigningResolution {
    /** Every input present. [toString] withholds the secrets so a stray log line cannot leak them. */
    data class Signed(
        val storeFile: String,
        val storePassword: String,
        val keyAlias: String,
        val keyPassword: String,
    ) : SigningResolution {
        override fun toString(): String = "Signed(storeFile=$storeFile, storePassword=***, keyAlias=***, keyPassword=***)"
    }

    /** No input at all: an unsigned release, which is the honest result for a fork or a plain checkout. */
    data object Unsigned : SigningResolution

    /** Some but not all: a half-configured release must fail, not quietly build unsigned. */
    data class Incomplete(val missing: List<SigningInput>) : SigningResolution
}

fun resolveSigning(
    gradleProperty: (String) -> String?,
    env: (String) -> String?,
): SigningResolution {
    fun lookup(input: SigningInput): String? =
        gradleProperty(input.gradleProperty)?.takeIf { it.isNotBlank() }
            ?: env(input.envVar)?.takeIf { it.isNotBlank() }

    val found = SigningInput.entries.associateWith { lookup(it) }
    val missing = found.filterValues { it == null }.keys.toList()
    if (missing.size == SigningInput.entries.size) return SigningResolution.Unsigned
    if (missing.isNotEmpty()) return SigningResolution.Incomplete(missing)
    // Nothing is missing here, so every lookup is non-null; requireNotNull keeps the
    // compiler honest without `!!` (ARCHITECTURE.md §12).
    fun value(input: SigningInput): String = requireNotNull(found[input])
    return SigningResolution.Signed(
        storeFile = value(SigningInput.StoreFile),
        storePassword = value(SigningInput.StorePassword),
        keyAlias = value(SigningInput.KeyAlias),
        keyPassword = value(SigningInput.KeyPassword),
    )
}

/** Names each missing input by property and variable; never a value. */
fun SigningResolution.Incomplete.message(): String =
    "Release signing is half-configured. Missing: " +
        missing.joinToString("; ") { "${it.gradleProperty} (or env ${it.envVar})" } +
        ". Set all four signing inputs, or none for an unsigned release."
