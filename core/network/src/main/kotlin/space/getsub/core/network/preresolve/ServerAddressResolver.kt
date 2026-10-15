// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.network.preresolve

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
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
    public data class Resolved(val ip: String, val candidates: Int, val millis: Long) : PreResolution {
        override fun toString(): String = "Resolved(ip=<redacted>, candidates=$candidates, millis=$millis)"
    }

    public data class Skipped(val reason: PreResolveReason) : PreResolution

    public data class Failed(val reason: PreResolveReason, val candidates: Int, val millis: Long) : PreResolution
}

/** M9-C spec §2: DoH, then pick. Never throws (bar cancellation); every outcome is a [PreResolution]. */
public class ServerAddressResolver(
    private val lookups: DohLookupFactory,
    private val racer: ConnectRacer,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    public suspend fun resolve(host: String, port: Int, config: ResolverConfig): PreResolution {
        if (IpLiteral.isLiteral(host)) return PreResolution.Skipped(PreResolveReason.AlreadyIp)
        val start = nanoTime()
        val answered = lookup(host, config)
        val answers = answered.getOrNull()
        val candidates = interleave(answers.orEmpty().distinct()).take(MAX_CANDIDATES)
        val elapsed = { (nanoTime() - start) / NANOS_PER_MILLI }
        return when {
            answers == null -> PreResolution.Failed(answered.reasonOrNull(), 0, elapsed())
            candidates.isEmpty() -> PreResolution.Failed(PreResolveReason.NoAddresses, 0, elapsed())
            candidates.size == 1 -> PreResolution.Resolved(candidates.single().hostAddress.orEmpty(), 1, elapsed())
            else ->
                racer.firstToConnect(candidates, port, PROBE_TIMEOUT_MILLIS)
                    ?.let { PreResolution.Resolved(it.hostAddress.orEmpty(), candidates.size, elapsed()) }
                    ?: PreResolution.Failed(PreResolveReason.NoneReachable, candidates.size, elapsed())
        }
    }

    private suspend fun lookup(host: String, config: ResolverConfig): Result<List<InetAddress>> =
        try {
            Result.success(withTimeout(DOH_TIMEOUT_MILLIS) { lookups.create(config).lookup(host) })
        } catch (e: TimeoutCancellationException) {
            Result.failure(e)
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // IOException, a malformed URL reaching OkHttp, anything else: pre-resolution never fails a connect.
            Result.failure(e)
        }

    private fun Result<*>.reasonOrNull(): PreResolveReason =
        if (exceptionOrNull() is TimeoutCancellationException) {
            PreResolveReason.DohTimeout
        } else {
            PreResolveReason.DohError
        }

    private fun interleave(all: List<InetAddress>): List<InetAddress> {
        val v4 = all.filterIsInstance<Inet4Address>()
        val v6 = all.filterNot { it is Inet4Address }
        return (0 until maxOf(v4.size, v6.size)).flatMap { i -> listOfNotNull(v4.getOrNull(i), v6.getOrNull(i)) }
    }
}
