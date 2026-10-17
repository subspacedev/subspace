// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The `:ping` process's idle timer (libXray stock upgrade spec §4, §9.7).
 *
 * [arm] when the last client unbinds; [disarm] on the next bind. When a full
 * [graceMillis] passes armed, [onExpired] runs — in production it ends the
 * process, so the second Go runtime's heap goes back to the system instead of
 * waiting for Android to evict a cached process.
 *
 * The clock is [scope]'s dispatcher, so a test drives it with virtual time.
 */
internal class IdleExit(
    private val scope: CoroutineScope,
    private val graceMillis: Long = IDLE_GRACE_MILLIS,
    private val onExpired: () -> Unit,
) {
    private val lock = Any()

    // Guarded by `lock`.
    private var timer: Job? = null

    /** Starts a full grace period, replacing any running one. */
    fun arm() {
        synchronized(lock) {
            timer?.cancel()
            timer =
                scope.launch {
                    delay(graceMillis)
                    val self = coroutineContext[Job]
                    // Decided and acted on under the lock: a bind racing the expiry
                    // either disarms first, or waits and then finds the process gone —
                    // which `:bg`'s PingClient treats as binder death and rebinds.
                    synchronized(lock) {
                        if (timer === self) {
                            timer = null
                            onExpired()
                        }
                    }
                }
        }
    }

    /** Cancels a pending exit. Harmless when none is pending. */
    fun disarm() {
        synchronized(lock) {
            timer?.cancel()
            timer = null
        }
    }

    companion object {
        /**
         * Long enough that "Test all" tapped again, or a superseding run, rebinds
         * before the process goes; short enough that the heap is not held for
         * nothing. The spec's default; tune only with a device measurement.
         */
        const val IDLE_GRACE_MILLIS = 30_000L
    }
}
