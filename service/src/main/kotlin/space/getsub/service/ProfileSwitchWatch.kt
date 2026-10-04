// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

/**
 * Whether a change of `active_profile_id` should move the live session
 * (M8.6 Part 3 D1). Only when a session is live and is not already running
 * the chosen row: tapping the server that is already connected restarts
 * nothing (review focus 1).
 */
internal fun switchRequested(
    liveRowId: Long?,
    activeRowId: Long?,
): Boolean = liveRowId != null && activeRowId != null && liveRowId != activeRowId
