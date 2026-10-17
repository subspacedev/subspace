// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import android.os.RemoteException
import android.util.Log
import space.getsub.core.model.LatencyOutcome
import space.getsub.core.model.LatencyResult
import space.getsub.core.xray.CertificateProbe
import space.getsub.core.xray.CertificateProbeResult
import space.getsub.core.xray.CertificateProbeRunner
import space.getsub.core.xray.LibXrayPingBatchCall
import space.getsub.core.xray.PingBatch
import space.getsub.core.xray.PingBatchCall
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private const val TAG = "PingService"

/**
 * Hosts libXray's `pingBatch` in the `:ping` process (libXray stock upgrade spec §4).
 *
 * `pingBatch` refuses to run in a process that holds a managed Xray instance,
 * and the tunnel's lives in `:bg`. libXray allows one Go runtime per process, so
 * a second process may load the same AAR. This one never calls
 * `registerDialerController`: there is no `VpnService` here to protect a
 * socket with. It needs none — the app's own package is excluded from the VPN
 * in every per-app mode, and that exclusion is by UID, which `:ping` shares, so
 * its sockets go direct exactly as `:bg`'s in-process pings did.
 *
 * **`runXray` only for the certificate probe (M9-D1 spec §2).** The one managed
 * instance this process ever starts is [CertificateProbeRunner]'s: short-lived,
 * strictly between batches, inside the single [worker], for at most
 * [CertificateProbe.MAX_PER_BATCH] failed servers `:bg` marked as UDP servers,
 * and always stopped (`finally`) before the worker takes the next batch — so no
 * `pingBatch` ever meets a managed instance. Its sockets go direct for the same
 * UID reason. If a stop ever fails, this process is **poisoned**
 * ([PingBatchWorker.poisoned]): it answers that batch, refuses every later one
 * without calling `pingBatch`, and ends itself after the reply; `:bg` sees the
 * death and the next run binds a fresh `:ping`.
 *
 * **Lifetime (Ruling P8).** Started by `:bg`'s bind ([PingClient]). `:bg`
 * holds the binding for [IdleTimer.IDLE_GRACE_MILLIS] after its last batch and
 * then unbinds; this process ends itself on that unbind if nothing is queued or
 * running, or right after the last batch otherwise ([ExitWhenIdle]). No timer
 * runs here: an unbound `:ping` is a cached process the freezer can stop.
 * The exit state and the worker are process-wide rather than per instance,
 * because a bound-only service is destroyed as soon as its last client unbinds.
 */
public class PingService : Service() {
    override fun onCreate() {
        super.onCreate()
        current = this
    }

    override fun onBind(intent: Intent?): IBinder {
        exitWhenIdle.onBind()
        return binder
    }

    override fun onRebind(intent: Intent?) {
        exitWhenIdle.onBind()
    }

    /** True so [onRebind] is called if this instance is still alive at the next bind. */
    override fun onUnbind(intent: Intent?): Boolean {
        exitWhenIdle.onUnbind()
        return true
    }

    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }

    private val binder =
        object : IPingService.Stub() {
            override fun pingBatch(
                request: PingBatchParcel?,
                callback: IPingCallback?,
            ) {
                if (request == null || callback == null) {
                    Log.w(TAG, "ping batch refused: incomplete request")
                    return
                }
                exitWhenIdle.batchQueued()
                val probeDir = File(cacheDir, CERT_PROBE_DIR)
                worker.execute {
                    try {
                        val reply = batches(probeDir).answer(request)
                        deliver { callback.onResult(reply) }
                    } finally {
                        exitWhenIdle.batchDone()
                    }
                    // After the reply, so `:bg` gets this batch's answer; nothing else is queued
                    // behind it (PingClient keeps one batch in flight), and a poisoned worker
                    // would refuse anything that were.
                    if (batches(probeDir).poisoned) exitProcess()
                }
            }
        }

    private companion object {
        /** Under `cacheDir`: private to the app, and swept by every probe (see [CertificateProbeRunner]). */
        const val CERT_PROBE_DIR = "cert-probe"

        /** Process-wide; see the class KDoc. Only `:ping` loads this class's statics in practice. */
        @Volatile
        var current: PingService? = null

        /** Touched only on [worker]'s thread. */
        private var batchWorker: PingBatchWorker? = null

        fun batches(probeDir: File): PingBatchWorker =
            batchWorker ?: PingBatchWorker(
                call = LibXrayPingBatchCall(),
                probe = CertificateProbeRunner(probeDir)::probe,
                log = { Log.i(TAG, it) },
                onError = { name -> Log.w(TAG, "ping batch failed: $name") },
            ).also { batchWorker = it }

        /**
         * One batch at a time. libXray holds `coreServerMu` for a whole
         * `pingBatch`, timeouts included, so a second thread would only block
         * inside Go; queuing here keeps that wait visible and ordered.
         */
        val worker: ExecutorService by lazy {
            Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "ping-batch").apply { isDaemon = true } }
        }

        val exitWhenIdle: ExitWhenIdle by lazy { ExitWhenIdle { exitProcess() } }

        fun exitProcess() {
            // stopSelf first so the system records the service as stopped, then
            // end the process: a cached process is not reclaimed on our schedule.
            current?.stopSelf()
            Process.killProcess(Process.myPid())
        }

        /** `:bg` going away mid-batch is ordinary; nothing is listening. */
        @Suppress("SwallowedException")
        inline fun deliver(send: () -> Unit) {
            try {
                send()
            } catch (e: RemoteException) {
                // :bg died or dropped the run. The result has no reader.
            }
        }
    }
}

/** One certificate probe of a server's generated config against the batch's URL. */
internal typealias CertificateProber = (xrayJson: String, url: String) -> CertificateProbeResult

/**
 * `:ping`'s worker state across batches: answers each with [answerPingBatch] and
 * remembers a failed probe stop. Once [poisoned], no batch reaches [call] again
 * — every server fails — and the service ends the process after the reply.
 * Touched only from the single worker thread.
 *
 * [log] gets fixed phrases only, never core text (ARCHITECTURE.md §5.6).
 */
internal class PingBatchWorker(
    private val call: PingBatchCall,
    private val probe: CertificateProber?,
    private val log: (String) -> Unit = {},
    private val onError: (String) -> Unit = {},
) {
    var poisoned: Boolean = false
        private set

    fun answer(request: PingBatchParcel): PingBatchResultParcel {
        if (poisoned) return PingBatchResultParcel.from(request.keys, emptyMap())
        return answerPingBatch(request, call, probe, onProbe = ::onProbe, onError = onError)
    }

    private fun onProbe(result: CertificateProbeResult) {
        if (result == CertificateProbeResult.STOP_FAILED) poisoned = true
        log(
            when (result) {
                CertificateProbeResult.UNTRUSTED -> "cert probe: untrusted"
                CertificateProbeResult.NOT_A_CERTIFICATE_FAILURE -> "cert probe: not a certificate failure"
                CertificateProbeResult.DID_NOT_RUN -> "cert probe: did not run"
                CertificateProbeResult.STOP_FAILED -> "cert probe: stop failed; :ping retires after this batch"
            },
        )
    }
}

/**
 * The `:ping` side of one request, with the native call injected so it is
 * JVM-testable. Never throws, never quotes a config: a malformed request or any
 * failure fails every server in it, and [onError] gets the class name only
 * (ARCHITECTURE.md §5.6).
 *
 * Then, with a [probe], the certificate probe for each server that failed and
 * that `:bg` marked ([PingBatchParcel.certProbe]) — the first
 * [CertificateProbe.MAX_PER_BATCH] of them, in request order, one at a time —
 * before the answer goes back (M9-D1 spec §2). [CertificateProbeResult.UNTRUSTED]
 * turns that server's failure into [LatencyOutcome.CERT_UNTRUSTED]; every other
 * verdict keeps the failure it had. [CertificateProbeResult.STOP_FAILED] ends
 * probing for the batch: the next start would only fail against the instance
 * still there.
 */
// TooGenericExceptionCaught: a throw here would crash :ping; every type must fail closed.
@Suppress("TooGenericExceptionCaught")
internal fun answerPingBatch(
    request: PingBatchParcel,
    call: PingBatchCall,
    probe: CertificateProber? = null,
    onProbe: (CertificateProbeResult) -> Unit = {},
    onError: (String) -> Unit = {},
): PingBatchResultParcel {
    val targets = request.targets()
    val measured =
        if (targets == null || targets.isEmpty() || targets.size > PingBatch.MAX_CONFIGS) {
            emptyMap()
        } else {
            try {
                PingBatch.measure(call, targets, request.timeoutSeconds, request.url)
            } catch (e: Exception) {
                onError(e.javaClass.simpleName)
                emptyMap<Long, LatencyResult>()
            }
        }
    val results = measured.toMutableMap()
    if (probe != null && targets != null && measured.isNotEmpty()) {
        val candidates =
            targets
                .filter { it.certProbe && measured[it.key]?.outcome != LatencyOutcome.OK }
                .take(CertificateProbe.MAX_PER_BATCH)
        for (target in candidates) {
            val verdict =
                try {
                    probe(target.xrayJson, request.url)
                } catch (e: Exception) {
                    onError(e.javaClass.simpleName)
                    CertificateProbeResult.DID_NOT_RUN
                }
            onProbe(verdict)
            if (verdict == CertificateProbeResult.UNTRUSTED) {
                results[target.key] = LatencyResult.failed(LatencyOutcome.CERT_UNTRUSTED)
            }
            if (verdict == CertificateProbeResult.STOP_FAILED) break
        }
    }
    // A key with no result — every key, on the failure paths above — is failed by `from`.
    return PingBatchResultParcel.from(request.keys, results)
}
