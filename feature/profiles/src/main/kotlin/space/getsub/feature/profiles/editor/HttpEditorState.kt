// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.profiles.editor

import space.getsub.core.model.HttpOutbound
import space.getsub.core.model.Security

/**
 * The HTTP proxy form shares the state the SOCKS and TLS forms already use: [EditorState.primaryCredential]
 * is the username and [EditorState.secondaryCredential] the password (both optional), and TLS is
 * [EditorState.securityKind] with its SNI and fingerprint. There is no draft of its own, so these two
 * functions are the whole mapping. Every input is trimmed (ruling D2-R7).
 */
internal fun EditorState.toHttpOutbound(portValue: Int): HttpOutbound =
    HttpOutbound(
        address = address.trim(),
        port = portValue,
        // Verbatim, like the mKCP seed (D2-R6): the importers do not trim either, and a password may have a
        // leading or trailing space. Only "" means none.
        username = primaryCredential.ifEmpty { null },
        password = secondaryCredential.ifEmpty { null },
        security =
        when (securityKind) {
            EditorSecurityKind.Tls -> Security.Tls(tlsServerName.trim(), tlsFingerprint.trim(), tlsAllowInsecure)
            // The form only offers TLS on/off; a stored REALITY row is rejected by save validation
            // ([EditorState.realityTransportConflict]) until the user switches TLS on or off.
            EditorSecurityKind.Reality ->
                Security.Reality(
                    realityServerName.trim(),
                    realityPublicKey.trim(),
                    realityShortId.trim(),
                    realityFingerprint.trim(),
                    realitySpiderX.trim(),
                )
            EditorSecurityKind.None -> Security.None
        },
    )

internal fun EditorState.withHttp(outbound: HttpOutbound): EditorState {
    val base =
        copy(
            primaryCredential = outbound.username.orEmpty(),
            secondaryCredential = outbound.password.orEmpty(),
        )
    return when (val security = outbound.security) {
        Security.None -> base.copy(securityKind = EditorSecurityKind.None)
        is Security.Tls ->
            base.copy(
                securityKind = EditorSecurityKind.Tls,
                tlsServerName = security.serverName,
                tlsFingerprint = security.fingerprint,
                tlsAllowInsecure = security.allowInsecure,
            )
        is Security.Reality ->
            base.copy(
                securityKind = EditorSecurityKind.Reality,
                realityServerName = security.serverName,
                realityPublicKey = security.publicKey,
                realityShortId = security.shortId,
                realityFingerprint = security.fingerprint,
                realitySpiderX = security.spiderX,
            )
    }
}
