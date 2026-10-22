// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.home.launch

import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The part of [LaunchAutomation] Home shows and steers (M10-C spec §3.1): its state, Cancel,
 * a manual takeover, and clearing a note once shown.
 *
 * A seam so HomeViewModel's tests need no whole automation; the trigger and consent stay with
 * MainActivity, which talks to [LaunchAutomation] directly.
 */
internal interface LaunchProgress {
    val state: StateFlow<LaunchAutomationState>

    /** See [LaunchAutomation.running]. */
    val running: StateFlow<Boolean>

    fun cancel()

    fun onUserTookOver()

    fun onNoteShown()
}

@Singleton
internal class BoundLaunchProgress
@Inject
constructor(
    private val automation: LaunchAutomation,
) : LaunchProgress {
    override val state: StateFlow<LaunchAutomationState> get() = automation.state

    override val running: StateFlow<Boolean> get() = automation.running

    override fun cancel() = automation.cancel()

    override fun onUserTookOver() = automation.onUserTookOver()

    override fun onNoteShown() = automation.onNoteShown()
}
