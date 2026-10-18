// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import space.getsub.core.model.ProxyAuthMode
import space.getsub.core.model.ProxyAuthSettings
import space.getsub.core.model.ProxyCredentials
import space.getsub.core.model.ProxyCredentialsFactory

/**
 * The account the loopback inbounds require, from the user's setting (M9-E spec §5.2).
 *
 * Manual uses what the user typed when both fields are usable. An empty field falls back to Auto
 * rather than to an open inbound; the Settings page shows the field error. So does a field SOCKS5
 * cannot carry: RFC 1929 caps each at 255 bytes (hev truncates there, `src/hev-config.c:177-178`,
 * and the core would then refuse the truncated value), and a control character would break the
 * single-line YAML scalar hev reads. Auto keeps [existing] when there is one and otherwise mints a
 * random alphanumeric account, which also keeps hev's YAML free of anything that needs escaping.
 */
internal fun sessionCredentials(
    mode: ProxyAuthSettings,
    existing: ProxyCredentials?,
): ProxyCredentials =
    if (mode.mode == ProxyAuthMode.Manual && usableField(mode.manualUser) && usableField(mode.manualPass)) {
        ProxyCredentials(mode.manualUser, mode.manualPass)
    } else {
        existing ?: ProxyCredentialsFactory.random()
    }

private const val RFC1929_FIELD_MAX_BYTES = 255

private fun usableField(value: String): Boolean =
    value.isNotEmpty() &&
        value.toByteArray(Charsets.UTF_8).size <= RFC1929_FIELD_MAX_BYTES &&
        value.none { it.isISOControl() }

/**
 * The credentials one core start uses: the session's own once it holds some, otherwise a value
 * minted now from [settings] (Task 9 Review Focus 1).
 *
 * A session mints once. Every later start in it (a retained-TUN restart, a server switch) reuses
 * that value without reading [settings], so a Manual edit mid-session cannot leave hev, the core
 * and the `Connected` state `:main` holds on different accounts. A new session starts with
 * [sessionOwn] null and may mint a new value. The caller stores the result with the session.
 */
internal suspend fun credentialsForCoreStart(
    sessionOwn: ProxyCredentials?,
    settings: suspend () -> ProxyAuthSettings,
): ProxyCredentials = sessionOwn ?: sessionCredentials(settings(), existing = null)
