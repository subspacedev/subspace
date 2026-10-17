// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import space.getsub.core.xray.XrayException

/**
 * The real `testXray` adapter: hands [json] to [validate] and converts a thrown
 * [XrayException] to `false`.
 *
 * Hoisted out of [ServiceModule.boundPassthroughValidator]'s `@Provides` lambda (review
 * Important 5) so it can be exercised on the JVM without Android or a mocking library — the
 * whole point of this function is that it is the one piece of Task 8's wiring that swallows a
 * config-quoting exception, and an inline lambda inside an `internal object`'s `@Provides`
 * function cannot be called from a test at all.
 *
 * Nothing touches disk. libXray v26.10.10's `testXray` takes the config as JSON text
 * (`docs/agent/research/libxray-api.md` §2), so the temp file this adapter used to write to the
 * cache directory — holding the UUID and keys for one native call (ARCHITECTURE.md §5.6) — is gone.
 *
 * Runs in the default process: [ServiceModule.boundPassthroughValidator] is reached only from
 * `:feature:profiles`' import path, and that process never starts a core. `testXray` refuses to
 * run beside a managed instance (`xray/validation.go:13-14`), so moving this caller into `:bg`
 * would make it fail during every live session.
 *
 * @param validate the core's own check — `XrayController.validate`'s signature exactly, so the
 *   caller passes `controller::validate` and this function stays ignorant of `XrayController`.
 * @return `true` when the core accepts [json]. Never returns `false` for anything other than a
 *   real [XrayException] — any other throwable (a cancellation, a binding failure) propagates
 *   rather than being reported as a verdict; [BoundPassthroughValidator] is where that
 *   distinction is turned into "undetermined, leave the verdict alone" (review Important 2/4).
 */
@Suppress("SwallowedException")
internal suspend fun validateOnCore(
    validate: suspend (String) -> Unit,
    json: String,
): Boolean =
    try {
        validate(json)
        true
    } catch (e: XrayException) {
        // Deliberately swallowed, not logged: this is the real core refusal, whose message can
        // quote the config back (ARCHITECTURE.md §5.6, XrayException's own KDoc).
        false
    }
