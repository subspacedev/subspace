// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

/**
 * When the `:ping` process ends itself (Ruling P8).
 *
 * `:bg` holds the grace period ([IdleTimer]) and unbinds only once it has
 * passed, so an unbind here means "done": [onUnbind] exits at once when no batch
 * is queued or running, and otherwise the last [batchDone] does. Exiting
 * promptly matters because an unbound `:ping` is a cached process the freezer
 * can stop mid-wait, leaving its Go heap resident.
 *
 * Plain state behind one lock so it is JVM-testable; [exit] runs under that
 * lock, which in production ends the process.
 */
internal class ExitWhenIdle(
    private val exit: () -> Unit,
) {
    private val lock = Any()

    // Both guarded by `lock`.
    private var bound = false
    private var busy = 0

    fun onBind() {
        synchronized(lock) { bound = true }
    }

    fun onUnbind() {
        synchronized(lock) {
            bound = false
            if (busy == 0) exit()
        }
    }

    /** A batch was accepted onto the worker queue. */
    fun batchQueued() {
        synchronized(lock) { busy++ }
    }

    /** A batch finished and its answer was sent (or had nobody to go to). */
    fun batchDone() {
        synchronized(lock) {
            busy--
            if (busy == 0 && !bound) exit()
        }
    }
}
