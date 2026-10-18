// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.xray

/**
 * The fixed substrings Xray v26.10.10 writes when a TLS-over-QUIC handshake rejects the server's
 * certificate (research `2026-10-17-m9d-hysteria2.md`, "Verified for D1" (b), each copied from a real
 * client log). quic-go keeps the Go verify text verbatim inside `INTERNAL_ERROR (local): …`, so these
 * match whatever wraps them. `INTERNAL_ERROR`/`CRYPTO_ERROR` themselves are deliberately absent: they
 * also cover failures that have nothing to do with the certificate.
 */
private val CERTIFICATE_FAILURE_MARKERS =
    listOf(
        // pinnedPeerCertSha256 set, leaf not among the pins (transport/internet/tls/config.go:299-303).
        "peer cert is unrecognized",
        // Self-signed or private CA, name matches (crypto/x509/verify.go:156-157).
        "x509: certificate signed by unknown authority",
        // Name mismatch, every HostnameError shape (x509/verify.go:124,134,142).
        "x509: certificate is valid for",
        // Dialing an IP with a certificate that has no IP SAN (x509/verify.go:121).
        "doesn't contain any IP SANs",
        // crypto/tls's prefix for every verify failure (crypto/tls/common.go:1817).
        "failed to verify certificate",
    )

/**
 * True when [coreLogLine] — one line of the core's own log — reports that the server's certificate
 * was not trusted. Returns a yes/no only, never the text: a core log line can carry the server
 * address, and nothing outside the certificate probe may hold it (ARCHITECTURE.md §5.6).
 */
public fun classifyCertificateFailure(coreLogLine: String): Boolean =
    CERTIFICATE_FAILURE_MARKERS.any { coreLogLine.contains(it) }
