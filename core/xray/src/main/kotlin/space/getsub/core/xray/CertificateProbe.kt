// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.xray

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.URL
import java.util.UUID

/**
 * What one certificate probe found. A verdict, never text: the core's log can carry the
 * server address, so nothing but this enum leaves [CertificateProbeRunner.probe]
 * (ARCHITECTURE.md §5.6).
 */
public enum class CertificateProbeResult {
    /** The core reported the server's certificate as not trusted (pin mismatch, unknown CA, wrong name). */
    UNTRUSTED,

    /** The probe ran and the core reported no certificate failure: the cause is something else. */
    NOT_A_CERTIFICATE_FAILURE,

    /** The probe could not run (no probe-able outbound, no port, the core refused to start). */
    DID_NOT_RUN,

    /**
     * The probe's instance would not stop. The calling process must not run `pingBatch` again:
     * libXray's `pingBatch` assumes no managed instance, and this one may still hold sockets.
     */
    STOP_FAILED,
}

/**
 * Seam over the managed-instance calls the probe makes, so [CertificateProbeRunner] is
 * JVM-testable. Both throw on failure.
 */
public interface ProbeCore {
    public fun start(xrayJson: String)

    public fun stop()
}

/**
 * Production [ProbeCore]: libXray's `runXray`/`stopXray` and nothing else.
 *
 * Deliberately **not** [XrayController]: that one registers the dialer controller on its first
 * start, and the `:ping` process has no `VpnService` to protect with. It needs none — the app's
 * UID is excluded from the VPN, so the probe's sockets go direct exactly as `pingBatch`'s do.
 */
public class LibXrayProbeCore : ProbeCore {
    override fun start(xrayJson: String) {
        LibXrayInvoke.callJson("runXray", xrayJsonPayload(xrayJson))
    }

    override fun stop() {
        LibXrayInvoke.call("stopXray")
    }
}

/** One HEAD to [url] through the HTTP proxy on loopback [proxyPort]. Throws on any failure. */
public fun interface ProbeHead {
    public fun head(
        proxyPort: Int,
        url: String,
    )
}

/**
 * Builds the probe's config: the row's own proxy outbound behind one loopback inbound, with the
 * core logging at `info` to a private file (M9-D1 spec §2, "How the error is obtained").
 *
 * Why a probe at all: `pingBatch` reports a bad certificate, wrong auth and a down server as the
 * same `io: read/write on closed pipe`, and the core names the certificate failure only in an
 * `Info` log line. `info` also logs destinations, so it is never raised on the tunnel's own
 * instance — only here, where the one destination is the probe URL and the log is a file that
 * never reaches logcat.
 */
public object CertificateProbe {
    /** Probes per `pingBatch` answer, at most; each holds `:ping`'s single worker. */
    public const val MAX_PER_BATCH: Int = 3

    /**
     * Nominal bound on one probe: the HEAD's connect and read timeouts plus the log settle wait.
     * The native start and stop are not bounded here; both are one quick call into Go.
     */
    public const val BOUND_MILLIS: Long =
        HEAD_CONNECT_TIMEOUT_MILLIS + HEAD_READ_TIMEOUT_MILLIS + LOG_SETTLE_MILLIS

    private const val PROXY_TAG = "proxy"
    private const val INBOUND_TAG = "cert-probe-in"

    /**
     * The probe config for [xrayJson] (a generated config with a `proxy` outbound), or null when
     * there is nothing to probe: no JSON, no `proxy` outbound, or a `dialerProxy` naming an
     * outbound that is not there. Never throws, so no exception can quote the config.
     *
     * The outbound is copied as is, with any `sockopt.dialerProxy` chain it depends on — the same
     * selection libXray's `pingBatch` makes (`xray/ping_batch.go`, `pingOutboundDependencyTags`).
     * No `dns`, `stats`, `api` or `env`: nothing here needs them, and the core resolves the
     * server's name through the system resolver as `pingBatch` does.
     */
    @Suppress("SwallowedException", "TooGenericExceptionCaught")
    public fun config(
        xrayJson: String,
        port: Int,
        logFile: String,
    ): String? {
        val outbounds =
            try {
                Json.parseToJsonElement(xrayJson).let { it as? JsonObject }?.get("outbounds") as? JsonArray
            } catch (e: Exception) {
                // Deliberate: a parse error's message can quote the config (ARCHITECTURE.md §5.6).
                null
            }
        val selected = outbounds?.let { all -> selectWithDependencies(all.mapNotNull { it as? JsonObject }) }
        if (selected == null) return null
        return buildJsonObject {
            putJsonObject("log") {
                put("loglevel", "info")
                put("error", logFile)
                put("access", "none")
            }
            putJsonArray("inbounds") {
                add(
                    buildJsonObject {
                        put("tag", INBOUND_TAG)
                        put("protocol", "http")
                        // ARCHITECTURE.md §6: loopback only, never 0.0.0.0.
                        put("listen", "127.0.0.1")
                        put("port", port)
                        putJsonObject("settings") {}
                    },
                )
            }
            put("outbounds", buildJsonArray { selected.forEach { add(it) } })
            putJsonObject("routing") {
                putJsonArray("rules") {
                    add(
                        buildJsonObject {
                            put("type", "field")
                            putJsonArray("inboundTag") { add(JsonPrimitive(INBOUND_TAG)) }
                            put("outboundTag", PROXY_TAG)
                        },
                    )
                }
            }
        }.toString()
    }

    /** `proxy` first, then each `dialerProxy` it reaches, once each; null if one is missing. */
    private fun selectWithDependencies(outbounds: List<JsonObject>): List<JsonObject>? {
        val byTag = outbounds.associateBy { it.stringAt("tag") }
        val selected = LinkedHashMap<String, JsonObject>()
        var next: String? = PROXY_TAG
        while (next != null && next !in selected) {
            val outbound = byTag[next] ?: return null
            selected[next] = outbound
            val sockopt = (outbound["streamSettings"] as? JsonObject)?.get("sockopt") as? JsonObject
            next = sockopt?.stringAt("dialerProxy")
        }
        return selected.values.toList()
    }

    private fun JsonObject.stringAt(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}

private const val HEAD_CONNECT_TIMEOUT_MILLIS = 2_000L
private const val HEAD_READ_TIMEOUT_MILLIS = 4_000L

/** After the HEAD, how long the core's asynchronous log writer gets to put the line on disk. */
private const val LOG_SETTLE_MILLIS = 1_500L
private const val LOG_POLL_MILLIS = 100L

/** More than any probe's log; a guard against reading an unbounded file into memory. */
private const val MAX_LOG_BYTES = 64 * 1024

/**
 * Production [ProbeHead]: `HttpURLConnection` through the probe's loopback HTTP inbound. An
 * explicit [Proxy], so no system proxy setting can send the HEAD elsewhere.
 */
public val httpProxyHead: ProbeHead =
    ProbeHead { proxyPort, url ->
        val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(InetAddress.getLoopbackAddress(), proxyPort))
        val connection = URL(url).openConnection(proxy) as HttpURLConnection
        try {
            connection.requestMethod = "HEAD"
            connection.connectTimeout = HEAD_CONNECT_TIMEOUT_MILLIS.toInt()
            connection.readTimeout = HEAD_READ_TIMEOUT_MILLIS.toInt()
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.responseCode
        } finally {
            connection.disconnect()
        }
    }

/**
 * A free loopback port, the way libXray's `getFreePorts` finds one: bind port 0, read it, close.
 * Same small race between close and the core's bind; a lost race only fails the probe's start.
 */
public fun loopbackFreePort(): Int = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }

/**
 * Runs one certificate probe: start a short-lived instance from [CertificateProbe.config], one HEAD
 * through its loopback inbound, stop it, read and delete the log, classify with
 * [classifyCertificateFailure].
 *
 * The caller (`:ping`'s single worker) runs this strictly between `pingBatch` calls, so no
 * managed instance outlives it: the stop is in a `finally`, and [CertificateProbeResult.STOP_FAILED]
 * tells the caller when it could not be made.
 *
 * [logDir] is private to the probe (`:ping`'s `cacheDir/cert-probe`). Every file in it is deleted
 * before and after each probe, on every path: a probe from a process killed mid-run, or the core's
 * asynchronous log writer re-creating its file just after the delete, cannot leave text behind
 * beyond the next probe. The text is read into local variables only and never returned, stored
 * or logged.
 */
// LongParameterList: every parameter is a seam the JVM tests replace; each defaults to production.
@Suppress("LongParameterList")
public class CertificateProbeRunner(
    private val logDir: File,
    private val core: ProbeCore = LibXrayProbeCore(),
    private val head: ProbeHead = httpProxyHead,
    private val freePort: () -> Int = ::loopbackFreePort,
    private val sleep: (Long) -> Unit = Thread::sleep,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    // TooGenericExceptionCaught/SwallowedException: every seam may throw anything, and its message
    // can quote the config (a libXray error does); each one maps to a verdict, never to text.
    @Suppress("TooGenericExceptionCaught", "SwallowedException", "ReturnCount")
    public fun probe(
        xrayJson: String,
        url: String,
    ): CertificateProbeResult {
        sweep()
        var started = false
        var stopped = false
        return try {
            logDir.mkdirs()
            val file = File(logDir, "${UUID.randomUUID()}.log")
            val port = freePort()
            val config =
                CertificateProbe.config(xrayJson, port, file.absolutePath)
                    ?: return CertificateProbeResult.DID_NOT_RUN
            try {
                core.start(config)
            } catch (e: Exception) {
                // libXray clears its instance on a failed start (xray/xray.go RunXray), so nothing to stop.
                return CertificateProbeResult.DID_NOT_RUN
            }
            started = true
            var untrusted: Boolean
            try {
                try {
                    head.head(port, url)
                } catch (e: Exception) {
                    // Expected: an untrusted server closes the proxied connection. The verdict is in the log.
                }
                untrusted = settle(file)
            } finally {
                stopped = stopQuietly()
            }
            if (!stopped) return CertificateProbeResult.STOP_FAILED
            if (untrusted || logShowsUntrusted(file)) {
                CertificateProbeResult.UNTRUSTED
            } else {
                CertificateProbeResult.NOT_A_CERTIFICATE_FAILURE
            }
        } catch (e: Exception) {
            if (started && !stopped) CertificateProbeResult.STOP_FAILED else CertificateProbeResult.DID_NOT_RUN
        } finally {
            sweep()
        }
    }

    /** The core writes its log on a goroutine; give the line a moment to reach the file. */
    private fun settle(file: File): Boolean {
        val deadline = nowMillis() + LOG_SETTLE_MILLIS
        var untrusted = logShowsUntrusted(file)
        while (!untrusted && nowMillis() < deadline) {
            sleep(LOG_POLL_MILLIS)
            untrusted = logShowsUntrusted(file)
        }
        return untrusted
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun stopQuietly(): Boolean =
        try {
            core.stop()
            true
        } catch (e: Exception) {
            false
        }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun logShowsUntrusted(file: File): Boolean =
        try {
            if (!file.isFile) {
                false
            } else {
                val bytes = file.inputStream().use { it.readNBytesCompat(MAX_LOG_BYTES) }
                String(bytes, Charsets.UTF_8).lineSequence().any(::classifyCertificateFailure)
            }
        } catch (e: Exception) {
            false
        }

    private fun sweep() {
        logDir.listFiles()?.forEach { it.delete() }
    }
}

/** `InputStream.readNBytes(n)` is API 33 on Android; this is the same, for API 26. */
private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(minOf(limit, DEFAULT_READ_CHUNK))
    while (out.size() < limit) {
        val read = read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (read < 0) break
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}

private const val DEFAULT_READ_CHUNK = 8 * 1024
