// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The grace period between `:bg` finishing with `:ping` and letting it go
 * (libXray stock upgrade spec §4, §9.7; Ruling P8).
 *
 * [arm] when a run ends; [disarm] when the next batch is about to go out. When
 * a full [graceMillis] passes armed, [onExpired] runs — in [PingClient] it
 * unbinds, and `:ping` then ends its own process at once ([ExitWhenIdle]).
 *
 * The timer lives in `:bg`, not in `:ping`, deliberately: an unbound `:ping` is
 * a cached process, and Android 14's cached-app freezer can freeze it within
 * about ten seconds — a timer there may never fire, leaving the second core
 * resident. `:bg` is active whenever a run can start (the tunnel's foreground
 * service, or `:main` bound to it).
 *
 * The clock is [scope]'s dispatcher, so a test drives it with virtual time.
 */
internal class IdleTimer(
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
                    // Decided under the lock so a racing disarm either wins outright
                    // or finds the timer already spent.
                    val fire =
                        synchronized(lock) {
                            (timer === self).also { if (it) timer = null }
                        }
                    if (fire) onExpired()
                }
        }
    }

    /** Cancels a pending expiry. Harmless when none is pending. */
    fun disarm() {
        synchronized(lock) {
            timer?.cancel()
            timer = null
        }
    }

    companion object {
        /**
         * Long enough that "Test all" tapped again, or a superseding run, reuses
         * the warm `:ping`; short enough that its heap is not held for nothing.
         * The spec's default; tune only with a device measurement.
         */
        const val IDLE_GRACE_MILLIS = 30_000L
    }
}
