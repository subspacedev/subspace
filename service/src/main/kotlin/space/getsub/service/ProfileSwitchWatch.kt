// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

/**
 * Whether a change of `active_profile_id` should move the live session
 * (M8.6 Part 3 D1). Only when a session is live and is not already running
 * the chosen row: tapping the server that is already connected restarts
 * nothing (review focus 1).
 *
 * [activeRunnable] is whether this build can run the chosen row: it decodes
 * (`toProfile() != null`) and is `connectable`. A row that is not runnable
 * never moves a live session (Task 1 review, controller ruling). Restarting
 * onto it would either clear intent and stop the tunnel, leaving the device
 * unprotected with nothing to reconnect, or settle a terminal `Failed` that
 * closes the TUN. The pick itself still persists; the session stays where it
 * is.
 *
 * `TunnelService` judges [activeRunnable] only at dequeue, on the command
 * coordinator. Its enqueue sites pass `true` and leave runnability to that
 * check.
 */
internal fun switchRequested(
    liveRowId: Long?,
    activeRowId: Long?,
    activeRunnable: Boolean,
): Boolean = activeRunnable && liveRowId != null && activeRowId != null && liveRowId != activeRowId
