// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import android.os.RemoteException
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import space.getsub.core.model.LatencyResult
import space.getsub.core.xray.LibXrayPingBatchCall
import space.getsub.core.xray.PingBatch
import space.getsub.core.xray.PingBatchCall
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private const val TAG = "PingService"

/**
 * Hosts libXray's `pingBatch` in the `:ping` process (libXray stock upgrade spec §4).
 *
 * `pingBatch` refuses to run in a process that holds a managed Xray instance,
 * and the tunnel's lives in `:bg`. libXray allows one Go runtime per process, so
 * a second process may load the same AAR. This one **never** calls `runXray`
 * or `registerDialerController`: there is no `VpnService` here to protect a
 * socket with. It needs none — the app's own package is excluded from the VPN
 * in every per-app mode, and that exclusion is by UID, which `:ping` shares, so
 * its sockets go direct exactly as `:bg`'s in-process pings did.
 *
 * **Lifetime.** Started by `:bg`'s bind ([PingClient]); idle after the last
 * unbind; ends its own process [IdleExit.IDLE_GRACE_MILLIS] later. The timer
 * and the worker are process-wide rather than per instance: a bound-only
 * service is destroyed as soon as its last client unbinds, and a new bind then
 * creates a fresh instance in the same process — whose `onBind` is what must
 * cancel the pending exit.
 */
public class PingService : Service() {
    override fun onCreate() {
        super.onCreate()
        current = this
    }

    override fun onBind(intent: Intent?): IBinder {
        idleExit.disarm()
        return binder
    }

    override fun onRebind(intent: Intent?) {
        idleExit.disarm()
    }

    /** True so [onRebind] is called if this instance is still alive at the next bind. */
    override fun onUnbind(intent: Intent?): Boolean {
        idleExit.arm()
        return true
    }

    override fun onDestroy() {
        // The idle timer is deliberately left running: destruction follows the
        // last unbind, which is exactly when it should count.
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
                worker.execute {
                    val reply =
                        answerPingBatch(request, LibXrayPingBatchCall()) { name ->
                            Log.w(TAG, "ping batch failed: $name")
                        }
                    deliver { callback.onResult(reply) }
                }
            }
        }

    private companion object {
        /** Process-wide; see the class KDoc. Only `:ping` loads this class's statics in practice. */
        @Volatile
        var current: PingService? = null

        /**
         * One batch at a time. libXray holds `coreServerMu` for a whole
         * `pingBatch`, timeouts included, so a second thread would only block
         * inside Go; queuing here keeps that wait visible and ordered.
         */
        val worker: ExecutorService by lazy {
            Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "ping-batch").apply { isDaemon = true } }
        }

        @Suppress("InjectDispatcher") // A process-wide timer with no Hilt graph in :ping.
        val idleExit: IdleExit by lazy {
            IdleExit(CoroutineScope(SupervisorJob() + Dispatchers.Default)) {
                // stopSelf first so the system records the service as stopped, then
                // end the process: a cached process is not reclaimed on our schedule.
                current?.stopSelf()
                Process.killProcess(Process.myPid())
            }
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

/**
 * The `:ping` side of one request, with the native call injected so it is
 * JVM-testable. Never throws, never quotes a config: a malformed request or any
 * failure fails every server in it, and [onError] gets the class name only
 * (ARCHITECTURE.md §5.6).
 */
// TooGenericExceptionCaught: a throw here would crash :ping; every type must fail closed.
@Suppress("TooGenericExceptionCaught")
internal fun answerPingBatch(
    request: PingBatchParcel,
    call: PingBatchCall,
    onError: (String) -> Unit = {},
): PingBatchResultParcel {
    val targets = request.targets()
    val results =
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
    // A key with no result — every key, on the failure paths above — is failed by `from`.
    return PingBatchResultParcel.from(request.keys, results)
}
