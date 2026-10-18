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
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
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

/**
 * One proxied exchange to [target] (`host:port`) through the HTTP inbound on loopback [proxyPort].
 * Throws on any failure; the caller ignores the outcome, because the verdict is in the core's log.
 */
public fun interface ProbeConnect {
    public fun exchange(
        proxyPort: Int,
        target: String,
    )
}

/**
 * Builds the probe's config: the row's own proxy outbound behind one loopback inbound, with the
 * core logging at `info` to a private file (M9-D1 spec §2, "How the error is obtained").
 *
 * Why a probe at all: `pingBatch` reports a bad certificate, wrong auth and a down server as the
 * same `io: read/write on closed pipe`, and the core names the certificate failure only in an
 * `Info` log line. `info` also logs destinations, so it is never raised on the tunnel's own
 * instance — only here, where the one destination is the check URL's host and the log is a file that
 * never reaches logcat.
 */
public object CertificateProbe {
    /** Probes per `pingBatch` answer, at most; each holds `:ping`'s single worker. */
    public const val MAX_PER_BATCH: Int = 3

    /**
     * Bound on one probe: the exchange's single overall deadline, the log settle wait and the
     * delayed re-sweep. The native start and stop are not bounded here; each is one quick call
     * into Go.
     */
    public const val BOUND_MILLIS: Long = EXCHANGE_DEADLINE_MILLIS + LOG_SETTLE_MILLIS + RESWEEP_DELAY_MILLIS

    /**
     * The `CONNECT` target for [checkUrl]: `host:port`, the port being the URL's own or the
     * scheme's default (443 https, 80 http), an IPv6 literal bracketed. Null when the URL has no
     * host or another scheme. A raw `CONNECT` rather than an HTTP client, so the check URL's
     * scheme never matters: Android's cleartext policy is checked against the *target* host, and
     * an `http://` check URL would make `HttpURLConnection` throw before any traffic.
     */
    @Suppress("SwallowedException", "TooGenericExceptionCaught")
    public fun connectTarget(checkUrl: String): String? {
        val uri =
            try {
                URI(checkUrl)
            } catch (e: Exception) {
                null
            }
        val host = uri?.host?.takeIf { it.isNotEmpty() }
        val defaultPort =
            when (uri?.scheme?.lowercase()) {
                "https" -> HTTPS_PORT
                "http" -> HTTP_PORT
                else -> null
            }
        val port = defaultPort?.let { uri?.port?.takeIf { p -> p in 1..MAX_PORT } ?: it }
        if (host == null || port == null) return null
        val bracketed = if (':' in host && !host.startsWith("[")) "[$host]" else host
        return "$bracketed:$port"
    }

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
                        put("listen", PROBE_LOOPBACK.hostAddress)
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

/** One deadline across the whole proxied exchange: connect, CONNECT, status line, drain. */
private const val EXCHANGE_DEADLINE_MILLIS = 6_000L

/**
 * How long after a probe's final sweep the runner sweeps again. Xray's logger goroutine opens its
 * file lazily with `O_CREATE` (`common/log/logger.go:104-113,173-174`), so a writer racing
 * `stopXray` can re-create the file just after the delete.
 */
private const val RESWEEP_DELAY_MILLIS = 200L

private const val HTTP_PORT = 80
private const val HTTPS_PORT = 443
private const val MAX_PORT = 65_535

/** After the exchange, how long the core's asynchronous log writer gets to put the line on disk. */
private const val LOG_SETTLE_MILLIS = 1_500L
private const val LOG_POLL_MILLIS = 100L

/** More than any probe's log; a guard against reading an unbounded file into memory. */
private const val MAX_LOG_BYTES = 64 * 1024

/**
 * Production [ProbeConnect]: a raw socket to the probe's loopback HTTP inbound, one
 * `CONNECT <target>`, the status line, one byte, then read until EOF or the deadline. Xray's http
 * inbound dispatches the CONNECT to the outbound, which dials the server — that dial is what
 * produces the certificate verdict; the byte only keeps the inbound's copy loop busy.
 *
 * One deadline ([EXCHANGE_DEADLINE_MILLIS] unless given) across the whole exchange: the connect
 * timeout and every read's `soTimeout` are the time left, and a read loop stops when it runs out.
 */
public class SocketProbeConnect(
    private val deadlineMillis: Long = EXCHANGE_DEADLINE_MILLIS,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : ProbeConnect {
    override fun exchange(
        proxyPort: Int,
        target: String,
    ) {
        val deadline = nowMillis() + deadlineMillis
        fun left(): Int = (deadline - nowMillis()).coerceAtLeast(1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        Socket().use { socket ->
            socket.connect(InetSocketAddress(PROBE_LOOPBACK, proxyPort), left())
            val output = socket.getOutputStream()
            output.write("CONNECT $target HTTP/1.1\r\nHost: $target\r\n\r\n".toByteArray(Charsets.US_ASCII))
            output.flush()
            val input = socket.getInputStream()
            // Status line and headers end at the first blank line; anything after is the tunnel.
            var tail = 0
            while (tail != HEADER_END && nowMillis() < deadline) {
                socket.soTimeout = left()
                val b = input.read()
                if (b < 0) return
                // An Int holds exactly the last four bytes; older ones shift out.
                tail = (tail shl BYTE_BITS) or b
            }
            output.write(0)
            output.flush()
            val buffer = ByteArray(DRAIN_CHUNK)
            while (nowMillis() < deadline) {
                socket.soTimeout = left()
                if (input.read(buffer) < 0) return
            }
        }
    }

    private companion object {
        /** `\r\n\r\n` as the last four bytes read. */
        const val HEADER_END = 0x0D0A0D0A
        const val BYTE_BITS = 8
        const val DRAIN_CHUNK = 512
    }
}

/**
 * A free loopback port, the way libXray's `getFreePorts` finds one: bind port 0, read it, close.
 * Same small race between close and the core's bind; a lost race only fails the probe's start.
 */
public fun loopbackFreePort(): Int = ServerSocket(0, 1, PROBE_LOOPBACK).use { it.localPort }

/**
 * The one loopback address the probe's inbound listens on and its client dials: IPv4
 * `127.0.0.1`, spelled out. Not `InetAddress.getLoopbackAddress()`: on Android that is `::1`
 * (measured on the Pixel 8, 2026-10-18), so the client dialled an address the inbound never
 * listened on, every exchange was refused in milliseconds, and every probe read "not a
 * certificate failure". The JVM returns `127.0.0.1`, which is why unit tests could not see it.
 */
public val PROBE_LOOPBACK: InetAddress = InetAddress.getByName(IPV4_LOOPBACK_LITERAL)

private const val IPV4_LOOPBACK_LITERAL = "127.0.0.1"

/**
 * Deletes every file in [dir] (the probe's private log directory). Never throws. `:ping` calls it
 * at process start, before every batch, around every probe and before the process exits
 * (ARCHITECTURE.md §5.6's sanctioned exception for this directory).
 */
@Suppress("TooGenericExceptionCaught", "SwallowedException")
public fun sweepCertificateProbeDir(dir: File) {
    try {
        dir.listFiles()?.forEach { it.delete() }
    } catch (e: Exception) {
        // A sweep that cannot list is retried at the next sweep point; nothing to report.
    }
}

/**
 * Runs one certificate probe: start a short-lived instance from [CertificateProbe.config], one
 * proxied `CONNECT` through its loopback inbound, stop it, read and delete the log, classify with
 * [classifyCertificateFailure].
 *
 * The caller (`:ping`'s single worker) runs this strictly between `pingBatch` calls, so no
 * managed instance outlives it: the stop is in a `finally`, and [CertificateProbeResult.STOP_FAILED]
 * tells the caller when it could not be made. A failed *start* is followed by a stop too, and a
 * stop that then fails reports STOP_FAILED even though there may have been nothing to stop. That
 * over-reports on purpose: the cost is one retired `:ping` process, while under-reporting would let
 * `pingBatch` run beside an instance.
 *
 * **The loopback inbound is unauthenticated.** Anything on the device could proxy through it while
 * it lives. Acceptable here: it lives for seconds (one [CertificateProbe.BOUND_MILLIS] probe), it
 * listens on 127.0.0.1 only, its one outbound is a server that just failed to answer `pingBatch`
 * (so it most likely carries nothing), and the access log is off, so nothing that did use it is
 * recorded anywhere.
 *
 * [logDir] is private to the probe (`:ping`'s `cacheDir/cert-probe`; the core creates the file
 * mode 0600, `common/log/logger.go`). Every file in it is deleted before and after each probe, on
 * every path, and once more [RESWEEP_DELAY_MILLIS] after that: the core's logger goroutine can
 * re-create its file with `O_CREATE` after `stopXray` returns. The text is read into local
 * variables only and never returned, stored or logged.
 */
// LongParameterList: every parameter is a seam the JVM tests replace; each defaults to production.
@Suppress("LongParameterList")
public class CertificateProbeRunner(
    private val logDir: File,
    private val core: ProbeCore = LibXrayProbeCore(),
    private val connect: ProbeConnect = SocketProbeConnect(),
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
        sweepCertificateProbeDir(logDir)
        var started = false
        var stopped = false
        return try {
            val target = CertificateProbe.connectTarget(url) ?: return CertificateProbeResult.DID_NOT_RUN
            logDir.mkdirs()
            val file = File(logDir, "${UUID.randomUUID()}.log")
            val port = freePort()
            val config =
                CertificateProbe.config(xrayJson, port, file.absolutePath)
                    ?: return CertificateProbeResult.DID_NOT_RUN
            started = true
            try {
                core.start(config)
            } catch (e: Exception) {
                // libXray clears its instance on a failed start (xray/xray.go RunXray), but a stop
                // costs nothing and proves it; see the class KDoc for why a failed one over-reports.
                stopped = stopQuietly()
                return if (stopped) CertificateProbeResult.DID_NOT_RUN else CertificateProbeResult.STOP_FAILED
            }
            var untrusted: Boolean
            try {
                try {
                    connect.exchange(port, target)
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
            sweepCertificateProbeDir(logDir)
            if (started) {
                sleepQuietly(RESWEEP_DELAY_MILLIS)
                sweepCertificateProbeDir(logDir)
            }
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

    @Suppress("SwallowedException")
    private fun sleepQuietly(millis: Long) {
        try {
            sleep(millis)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
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
