// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.home.launch

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import space.getsub.core.data.ProfileRepository
import space.getsub.core.data.SettingsRepository
import space.getsub.core.data.automation.LaunchCandidate
import space.getsub.core.data.automation.LaunchCandidates
import space.getsub.core.data.toProfile
import space.getsub.core.model.LatencyResult
import space.getsub.core.model.PingMode
import space.getsub.core.model.Profile
import space.getsub.core.model.automation.AutoConnectMode
import space.getsub.core.model.automation.LaunchTestScope
import space.getsub.service.latency.LatencyRuns
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton

/*
 * Seams for LaunchAutomation's collaborators.
 *
 * SettingsRepository, ProfileRepository and LaunchCandidates' dependencies have constructors
 * scoped to :core:data, and LatencyRuns' testable constructor is scoped to :service, so this
 * module cannot build any of them in a JVM test. The same reason ActiveProfileSource exists.
 */

/** The application-lifetime scope the fresh-open sequence runs in, on the main thread. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
internal annotation class LaunchAutomationScope

/** The settings one fresh-open sequence reads, taken together at its start. */
internal data class LaunchSettingsSnapshot(
    val autoConnect: Boolean,
    val mode: AutoConnectMode,
    val useMarkers: Boolean,
    val testScope: LaunchTestScope,
    val pingOnLaunch: Boolean,
    val pingOnLaunchMetered: Boolean,
    val pingTimeoutSeconds: Int,
)

internal interface LaunchSettings {
    suspend fun snapshot(): LaunchSettingsSnapshot

    /** Watched during a Fastest choice: a change the automation did not write is a user pick. */
    val activeProfileId: Flow<Long?>

    suspend fun activeProfileIdNow(): Long?

    suspend fun setActiveProfile(id: Long)
}

internal interface LaunchServers {
    suspend fun all(): List<LaunchCandidate>

    suspend fun inGroup(groupId: Long): List<LaunchCandidate>

    /** The row as something to connect to, or null when it is gone or cannot be connected. */
    suspend fun profile(id: Long): Profile?
}

internal interface LaunchLatency {
    val results: StateFlow<Map<Long, LatencyResult>>

    /** @param onResult each result of this run alone; see `LatencyRuns.start`. Binder thread. */
    suspend fun start(
        profileIds: List<Long>,
        modes: Map<Long, PingMode>,
        onResult: ((Long, LatencyResult) -> Unit)? = null,
    ): Boolean

    suspend fun awaitDone(
        profileIds: Collection<Long>,
        timeoutMillis: Long,
    ): Boolean

    /** See `LatencyRuns.currentRun`: moves on every start, from any caller. */
    val currentRun: Long

    /** Stops the current run; rows still testing return to idle. See `LatencyRuns.cancel`. */
    fun cancel()
}

@Singleton
internal class BoundLaunchSettings
@Inject
constructor(
    private val settings: SettingsRepository,
) : LaunchSettings {
    override suspend fun snapshot(): LaunchSettingsSnapshot =
        LaunchSettingsSnapshot(
            autoConnect = settings.autoConnectOnLaunch.first(),
            mode = settings.autoConnectMode.first(),
            useMarkers = settings.useNetworkMarkers.first(),
            testScope = settings.launchTestScope.first(),
            pingOnLaunch = settings.pingOnLaunch.first(),
            pingOnLaunchMetered = settings.pingOnLaunchMetered.first(),
            pingTimeoutSeconds = settings.pingTimeoutSeconds.first(),
        )

    override val activeProfileId: Flow<Long?> get() = settings.activeProfileId

    override suspend fun activeProfileIdNow(): Long? = settings.activeProfileIdNow()

    override suspend fun setActiveProfile(id: Long) = settings.setActiveProfile(id)
}

@Singleton
internal class BoundLaunchServers
@Inject
constructor(
    private val candidates: LaunchCandidates,
    private val profiles: ProfileRepository,
) : LaunchServers {
    override suspend fun all(): List<LaunchCandidate> = candidates.all()

    override suspend fun inGroup(groupId: Long): List<LaunchCandidate> = candidates.inGroup(groupId)

    // `connectable` is the same gate Home's canConnect applies: a decodable outbound in a
    // transport :core:xray can emit. Re-read here, at consent time, for the same reason
    // HomeViewModel.onConsentGranted re-reads it.
    override suspend fun profile(id: Long): Profile? = profiles.profile(id)?.takeIf { it.connectable }?.toProfile()
}

@Singleton
internal class BoundLaunchLatency
@Inject
constructor(
    private val runs: LatencyRuns,
) : LaunchLatency {
    override val results: StateFlow<Map<Long, LatencyResult>> get() = runs.results

    override suspend fun start(
        profileIds: List<Long>,
        modes: Map<Long, PingMode>,
        onResult: ((Long, LatencyResult) -> Unit)?,
    ): Boolean = runs.start(profileIds, modes, onResult)

    override suspend fun awaitDone(
        profileIds: Collection<Long>,
        timeoutMillis: Long,
    ): Boolean = runs.awaitDone(profileIds, timeoutMillis)

    override val currentRun: Long get() = runs.currentRun

    override fun cancel() = runs.cancel()
}
