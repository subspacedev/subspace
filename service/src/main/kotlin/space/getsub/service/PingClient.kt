// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import space.getsub.core.model.LatencyOutcome
import space.getsub.core.model.LatencyResult
import space.getsub.core.xray.PingTarget

/**
 * The binding to `:ping`, behind a seam so [PingClient] is JVM-testable.
 * [bind] binds with `BIND_AUTO_CREATE`; null means the system refused.
 */
internal interface PingConnector {
    fun bind(listener: Listener): Binding?

    interface Listener {
        fun onConnected(remote: PingRemote)

        /** `:ping` died, or the binding can never deliver. */
        fun onDisconnected()
    }

    fun interface Binding {
        fun unbind()
    }
}

/**
 * One connected `:ping`. The call is `oneway`: it returns once queued, and throws
 * [RemoteException] (a `DeadObjectException`) if `:ping` is already gone. The
 * answer — null if `:ping` sent none — arrives later on [onResult].
 */
internal fun interface PingRemote {
    fun pingBatch(
        request: PingBatchParcel,
        onResult: (PingBatchResultParcel?) -> Unit,
    )
}

/**
 * `:bg`'s handle on `:ping` (libXray stock upgrade spec §4).
 *
 * Binds on the first [measure]. [releaseAfterGrace] — which [LatencyRunner]
 * triggers when a run finishes, is superseded or is cancelled — keeps the
 * binding for [IdleTimer.IDLE_GRACE_MILLIS] and then unbinds; a [measure]
 * inside the grace cancels the unbind and reuses the binding. The unbind is
 * what ends `:ping` ([ExitWhenIdle]); the grace lives here because `:bg` is
 * active while a run can start, and an unbound `:ping` may be frozen (Ruling
 * P8, spec §9.7). [close] unbinds at once. Never fails a call by throwing: every
 * path that cannot produce a measurement fails the batch's servers as
 * [LatencyOutcome.UNREACHABLE].
 *
 * **Death.** `:ping` dying — killed, crashed, or idled out between runs — fails
 * every in-flight server and drops the binding, so `BIND_AUTO_CREATE` does not
 * resurrect a process nobody is waiting on; the next [measure] binds again.
 *
 * **One batch in flight.** `pingBatch` holds libXray's `coreServerMu` for the
 * whole call, so batches in `:ping` run strictly one after another. Sending
 * more than one would only queue them there, beyond the reach of cancellation:
 * a superseded run would then delay the next by every batch it had queued, not
 * just the one already running (spec §9.3). [calls] keeps the queue here, where
 * cancelling a run removes its waiting batches.
 */
internal class PingClient(
    private val connector: PingConnector,
    idleScope: CoroutineScope,
    graceMillis: Long = IdleTimer.IDLE_GRACE_MILLIS,
    private val bindTimeoutMillis: Long = BIND_TIMEOUT_MILLIS,
) {
    private val calls = Mutex()
    private val lock = Any()
    private val idle = IdleTimer(idleScope, graceMillis) { dropCurrent() }

    // Guarded by `lock`.
    private var session: Session? = null

    private inner class Session : PingConnector.Listener {
        val remote = CompletableDeferred<PingRemote?>()

        // All guarded by `lock`.
        var binding: PingConnector.Binding? = null
        var dropped = false
        val inFlight = mutableSetOf<CompletableDeferred<PingBatchResultParcel?>>()

        override fun onConnected(remote: PingRemote) {
            this.remote.complete(remote)
        }

        override fun onDisconnected() = drop(this)
    }

    suspend fun measure(
        batch: List<PingTarget>,
        timeoutSeconds: Int,
        url: String,
    ): Map<Long, LatencyResult> {
        if (batch.isEmpty()) return emptyMap()
        idle.disarm()
        val reply = calls.withLock { exchange(PingBatchParcel.of(batch, timeoutSeconds, url), timeoutSeconds) }
        val results = reply?.toResults().orEmpty()
        return batch.associate { it.key to (results[it.key] ?: LatencyResult.failed(LatencyOutcome.UNREACHABLE)) }
    }

    /** A run is done with `:ping`: unbind once [IdleTimer.IDLE_GRACE_MILLIS] passes with no [measure]. */
    fun releaseAfterGrace() = idle.arm()

    /** Unbinds now, failing anything still waiting. Harmless when not bound. */
    fun close() {
        idle.disarm()
        dropCurrent()
    }

    private fun dropCurrent() {
        synchronized(lock) { session }?.let(::drop)
    }

    private suspend fun exchange(
        request: PingBatchParcel,
        timeoutSeconds: Int,
    ): PingBatchResultParcel? {
        val s = sessionOrBind() ?: return null
        val remote = withTimeoutOrNull(bindTimeoutMillis) { s.remote.await() }
        if (remote == null) drop(s)
        val reply = CompletableDeferred<PingBatchResultParcel?>()
        val tracked = remote != null && synchronized(lock) { !s.dropped && s.inFlight.add(reply) }
        return if (remote == null || !tracked) {
            null
        } else {
            try {
                if (send(s, remote, request, reply)) {
                    withTimeoutOrNull(replyWatchdogMillis(timeoutSeconds)) { reply.await() }
                } else {
                    null
                }
            } finally {
                synchronized(lock) { s.inFlight.remove(reply) }
            }
        }
    }

    // Deliberate: a RemoteException here means `:ping` is gone, and that is
    // reported as failed servers, not as a message (ARCHITECTURE.md §5.6).
    @Suppress("SwallowedException")
    private fun send(
        s: Session,
        remote: PingRemote,
        request: PingBatchParcel,
        reply: CompletableDeferred<PingBatchResultParcel?>,
    ): Boolean =
        try {
            remote.pingBatch(request) { reply.complete(it) }
            true
        } catch (e: RemoteException) {
            drop(s)
            false
        }

    /** The live session, binding a new one if there is none; null if binding failed. */
    private fun sessionOrBind(): Session? {
        var fresh: Session? = null
        val s =
            synchronized(lock) {
                session ?: Session().also {
                    session = it
                    fresh = it
                }
            }
        fresh?.let { created ->
            val binding = connector.bind(created)
            // Death reported synchronously during bind already dropped the session.
            val orphan =
                synchronized(lock) {
                    if (created.dropped) {
                        binding
                    } else {
                        created.binding = binding
                        null
                    }
                }
            orphan?.unbind()
            if (binding == null) drop(created)
        }
        return s.takeUnless { synchronized(lock) { it.dropped } }
    }

    private fun drop(s: Session) {
        val (binding, pending) =
            synchronized(lock) {
                if (s.dropped) return
                s.dropped = true
                if (session === s) session = null
                (s.binding to s.inFlight.toList()).also { s.inFlight.clear() }
            }
        s.remote.complete(null)
        pending.forEach { it.complete(null) }
        binding?.unbind()
    }

    companion object {
        /** `onServiceConnected` normally lands within a process start; past this, `:ping` is not coming. */
        const val BIND_TIMEOUT_MILLIS = 10_000L

        /**
         * How long one batch may take before its servers are failed. A batch can
         * wait in `:ping` behind one abandoned batch (a superseded run's, which
         * cancellation cannot stop) and then run its own: twice the timeout, plus
         * room for two instance starts. Only a wedged `:ping` reaches it.
         */
        fun replyWatchdogMillis(timeoutSeconds: Int): Long =
            2L * timeoutSeconds * MILLIS_PER_SECOND + WATCHDOG_SLACK_MILLIS

        private const val MILLIS_PER_SECOND = 1_000L
        private const val WATCHDOG_SLACK_MILLIS = 15_000L
    }
}

/** The production [PingConnector]: binds [PingService] in `:ping`. */
internal class AndroidPingConnector(
    private val context: Context,
) : PingConnector {
    // Both swallowed deliberately: unbindService throws IllegalArgumentException
    // for a connection already gone, and bindService a SecurityException that
    // carries nothing useful. Either way the outcome is "not bound".
    @Suppress("SwallowedException")
    override fun bind(listener: PingConnector.Listener): PingConnector.Binding? {
        val connection =
            object : ServiceConnection {
                override fun onServiceConnected(
                    name: ComponentName?,
                    service: IBinder?,
                ) {
                    val api = service?.let(IPingService.Stub::asInterface)
                    if (api == null) {
                        listener.onDisconnected()
                        return
                    }
                    listener.onConnected { request, onResult ->
                        api.pingBatch(
                            request,
                            object : IPingCallback.Stub() {
                                override fun onResult(result: PingBatchResultParcel?) = onResult(result)
                            },
                        )
                    }
                }

                override fun onServiceDisconnected(name: ComponentName?) = listener.onDisconnected()

                override fun onBindingDied(name: ComponentName?) = listener.onDisconnected()

                override fun onNullBinding(name: ComponentName?) = listener.onDisconnected()
            }
        val unbind = {
            try {
                context.unbindService(connection)
            } catch (e: IllegalArgumentException) {
                // Not bound; nothing to undo.
            }
        }
        val bound =
            try {
                context.bindService(Intent(context, PingService::class.java), connection, Context.BIND_AUTO_CREATE)
            } catch (e: SecurityException) {
                false
            }
        if (!bound) {
            // The platform asks for an unbind even when bindService returned false.
            unbind()
            return null
        }
        return PingConnector.Binding { unbind() }
    }
}
