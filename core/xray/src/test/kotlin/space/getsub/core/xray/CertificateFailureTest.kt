// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.xray

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.Test

/**
 * The certificate classes the core reports, verbatim from a real v26.10.10 client log
 * (`docs/agent/research/2026-10-17-m9d-hysteria2.md`, "Verified for D1" (b)).
 */
class CertificateFailureTest {
    private val prefix =
        "2026/10/17 12:00:00.000000 [Info] [1234567] app/proxyman/outbound: app/proxyman/outbound: " +
            "failed to process outbound traffic > proxy/hysteria: failed to find an available destination > " +
            "INTERNAL_ERROR (local): "

    @Test
    fun everyVerifiedCertificateClassIsNamed() {
        listOf(
            "transport/internet/tls: peer cert is unrecognized (against pinnedPeerCertSha256)",
            "tls: failed to verify certificate: x509: certificate signed by unknown authority",
            "tls: failed to verify certificate: x509: certificate signed by unknown authority " +
                "(possibly because of \"x509: invalid signature\" while trying to verify candidate authority)",
            "tls: failed to verify certificate: x509: certificate is valid for hy.test, not wrong.example",
            "tls: failed to verify certificate: x509: cannot validate certificate for 127.0.0.1 " +
                "because it doesn't contain any IP SANs",
            "tls: failed to verify certificate: x509: certificate is valid for 2 names, but none matched a.b",
        ).forEach { tail ->
            withClue(tail) { classifyCertificateFailure(prefix + tail) shouldBe true }
        }
    }

    @Test
    fun failuresThatAreNotTheCertificateAreNotNamedAsOne() {
        listOf(
            prefix + "timeout: no recent network activity",
            "Head \"https://www.gstatic.com/generate_204\": io: read/write on closed pipe",
            prefix + "dial udp 203.0.113.5:443: connect: connection refused",
            prefix + "authentication failed, status code: 404",
            prefix + "auth error",
            "context deadline exceeded (Client.Timeout exceeded while awaiting headers)",
            "[Info] [1] proxy/http: request to Method [CONNECT] www.gstatic.com:443",
            "",
        ).forEach { line ->
            withClue(line) { classifyCertificateFailure(line) shouldBe false }
        }
    }
}
