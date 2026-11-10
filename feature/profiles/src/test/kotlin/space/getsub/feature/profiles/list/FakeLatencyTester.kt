// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.profiles.list

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import space.getsub.core.model.LatencyResult
import space.getsub.core.model.PingMode

/**
 * A [LatencyTester] that records what it was asked to measure and answers with a
 * fixed result, so the Servers screen's wiring can be exercised without `:bg`,
 * libXray, or a bound service.
 *
 * [resultFor] is settable because a tester that only ever succeeds is how a
 * failure-rendering bug survives to a device run — the same lesson
 * `FakeProfileSource.syncResultToReturn` records for sync.
 */
internal class FakeLatencyTester(
    private val autoComplete: Boolean = true,
) : LatencyTester {
    private val _results = MutableStateFlow<Map<Long, LatencyResult>>(emptyMap())
    override val results: StateFlow<Map<Long, LatencyResult>> = _results.asStateFlow()

    private val _testing = MutableStateFlow<Set<Long>>(emptySet())
    override val testing: StateFlow<Set<Long>> = _testing.asStateFlow()

    var resultFor: (Long) -> LatencyResult = { LatencyResult.ok(DEFAULT_DELAY_MS) }

    var testedIds: List<Long> = emptyList()
        private set
    var testCallCount: Int = 0
        private set
    var cancelCallCount: Int = 0
        private set

    var lastModes: Map<Long, PingMode> = emptyMap()
        private set

    /** Settable so a test can drive the not-bound path, which returns false. */
    var startSucceeds: Boolean = true

    override suspend fun test(
        profileIds: List<Long>,
        modes: Map<Long, PingMode>,
    ): Boolean {
        if (profileIds.isEmpty()) return false
        // Mirrors BoundLatencyTester: a run supersedes any run in flight, and the
        // superseded one never reaches its own onFinished, so its rows must be
        // released here or they stay marked forever.
        _testing.value = emptySet()
        testCallCount++
        testedIds = profileIds
        lastModes = modes
        if (autoComplete) {
            _results.value = _results.value + profileIds.associateWith { id -> resultFor(id) }
            _testing.value = _testing.value - profileIds.toSet()
        } else {
            // Leaves the rows mid-flight, so a test can assert on the "testing"
            // state a real run passes through.
            _testing.value = _testing.value + profileIds
        }
        return startSucceeds
    }

    override fun cancel() {
        cancelCallCount++
        _testing.value = emptySet()
    }

    private companion object {
        const val DEFAULT_DELAY_MS = 42
    }
}
