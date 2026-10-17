// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.network.preresolve

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.InetAddress

private const val MAX_CANDIDATES = 8
private const val PROBE_TIMEOUT_MILLIS = 1_500L
private const val NANOS_PER_MILLI = 1_000_000L

public enum class PreResolveReason {
    Off,
    AlreadyIp,
    ConfigNotRecognised,
    InvalidUserResolver,
    DohTimeout,
    DohError,
    NoAddresses,
    NoneReachable,
}

public sealed interface PreResolution {
    /** [raced] is false when the first answer was taken unprobed (a UDP/QUIC server: a TCP connect proves nothing). */
    public data class Resolved(
        val ip: String,
        val candidates: Int,
        val millis: Long,
        val raced: Boolean = true,
    ) : PreResolution {
        override fun toString(): String =
            "Resolved(ip=<redacted>, candidates=$candidates, millis=$millis, raced=$raced)"
    }

    public data class Skipped(val reason: PreResolveReason) : PreResolution

    public data class Failed(val reason: PreResolveReason, val candidates: Int, val millis: Long) : PreResolution
}

/** M9-C spec §2: DoH, then pick. Never throws (bar cancellation); every outcome is a [PreResolution]. */
public class ServerAddressResolver(
    private val lookups: DohLookupFactory,
    private val racer: ConnectRacer,
    private val nanoTime: () -> Long = System::nanoTime,
    lookupDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * Where the DoH lookup runs, outside the caller's structure. Without a bootstrap IP, OkHttp resolves
     * the DoH host through the system resolver, which blocks a thread and ignores both `callTimeout` and
     * coroutine cancellation. A structured child would hold `withTimeout` until that call returned; a
     * detached one is abandoned at the budget and its late result is discarded (M9-C spec §2.2, §2.3).
     */
    private val lookupScope = CoroutineScope(SupervisorJob() + lookupDispatcher)

    /**
     * [race] false is for a server that speaks UDP (Hysteria2): a TCP connect to its port says nothing about
     * a QUIC listener, and with port hopping the probed port is not the one in use. The first DoH answer, in
     * the order the resolver gave it, is taken without probing (M9-D1 spec §5, §10.3).
     */
    public suspend fun resolve(host: String, port: Int, config: ResolverConfig, race: Boolean = true): PreResolution {
        if (IpLiteral.isLiteral(host)) return PreResolution.Skipped(PreResolveReason.AlreadyIp)
        val start = nanoTime()
        val answered = lookup(host, config)
        val answers = answered.getOrNull()
        val distinct = answers.orEmpty().distinct()
        val candidates = interleave(distinct).take(MAX_CANDIDATES)
        val elapsed = { (nanoTime() - start) / NANOS_PER_MILLI }
        return when {
            answers == null -> PreResolution.Failed(answered.reasonOrNull(), 0, elapsed())
            candidates.isEmpty() -> PreResolution.Failed(PreResolveReason.NoAddresses, 0, elapsed())
            !race ->
                PreResolution.Resolved(distinct.first().hostAddress.orEmpty(), distinct.size, elapsed(), raced = false)
            candidates.size == 1 -> PreResolution.Resolved(candidates.single().hostAddress.orEmpty(), 1, elapsed())
            else ->
                racer.firstToConnect(candidates, port, PROBE_TIMEOUT_MILLIS)
                    ?.let { PreResolution.Resolved(it.hostAddress.orEmpty(), candidates.size, elapsed()) }
                    ?: PreResolution.Failed(PreResolveReason.NoneReachable, candidates.size, elapsed())
        }
    }

    /** The answers, or why there are none. Never throws but for the caller's own cancellation. */
    private suspend fun lookup(host: String, config: ResolverConfig): Result<List<InetAddress>> {
        val pending = lookupScope.async { lookups.create(config).lookup(host) }
        return try {
            // await() returns as soon as the budget elapses or the caller is cancelled, blocked thread or not.
            // withTimeoutOrNull gives null only for its own budget, so a timeout the lookup throws is DohError.
            withTimeoutOrNull(DOH_TIMEOUT_MILLIS) { pending.await() }
                ?.let { Result.success(it) }
                ?: Result.failure(BudgetElapsed())
        } catch (e: CancellationException) {
            // The caller was cancelled: propagate. Otherwise the lookup itself ended in a cancellation.
            currentCoroutineContext().ensureActive()
            Result.failure(e)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // IOException, a malformed URL reaching OkHttp, anything else: pre-resolution never fails a connect.
            Result.failure(e)
        } finally {
            pending.cancel()
        }
    }

    private class BudgetElapsed : Exception()

    private fun Result<*>.reasonOrNull(): PreResolveReason =
        if (exceptionOrNull() is BudgetElapsed) PreResolveReason.DohTimeout else PreResolveReason.DohError

    private fun interleave(all: List<InetAddress>): List<InetAddress> {
        val v4 = all.filterIsInstance<Inet4Address>()
        val v6 = all.filterNot { it is Inet4Address }
        return (0 until maxOf(v4.size, v6.size)).flatMap { i -> listOfNotNull(v4.getOrNull(i), v6.getOrNull(i)) }
    }
}
