// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.model

import java.security.SecureRandom

/** Credentials of the local SOCKS inbound. Never printed. */
public data class ProxyCredentials(
    val user: String,
    val pass: String,
) {
    override fun toString(): String = "ProxyCredentials(<redacted>)"
}

public data class LocalProxy(
    val port: Int,
    val credentials: ProxyCredentials,
)

public enum class ProxyAuthMode { Auto, Manual }

public data class ProxyAuthSettings(
    val mode: ProxyAuthMode,
    val manualUser: String,
    val manualPass: String,
) {
    override fun toString(): String = "ProxyAuthSettings(mode=$mode, <redacted>)"

    public companion object {
        public val DEFAULT: ProxyAuthSettings = ProxyAuthSettings(ProxyAuthMode.Auto, "", "")
    }
}

/** Why a Manual user name or password cannot be used ([ProxyCredentialRules.check]). */
public enum class ProxyCredentialProblem { Empty, TooLong, ControlCharacter }

/**
 * Whether a Manual user name or password can go on the local inbounds. RFC 1929 caps each SOCKS5 field
 * at 255 bytes (hev truncates there, `src/hev-config.c:177-178`, and the core would then refuse the
 * truncated value), and a control character would break the single-line YAML scalar hev reads.
 * `:bg` falls back to Auto for a field that fails; the Settings page shows the error.
 */
public object ProxyCredentialRules {
    public const val MAX_BYTES: Int = 255

    public fun check(value: String): ProxyCredentialProblem? =
        when {
            value.isEmpty() -> ProxyCredentialProblem.Empty
            value.toByteArray(Charsets.UTF_8).size > MAX_BYTES -> ProxyCredentialProblem.TooLong
            value.any { it.isISOControl() } -> ProxyCredentialProblem.ControlCharacter
            else -> null
        }
}

public data class SniffingUserSetting(
    val override: Boolean,
    val enabled: Boolean,
) {
    public companion object {
        public val DEFAULT: SniffingUserSetting = SniffingUserSetting(override = false, enabled = true)
    }
}

public data class MuxUserSettings(
    val override: Boolean,
    val enabled: Boolean,
    val tcpConnections: String,
    val xudpConnections: String,
    val quic: String,
) {
    public companion object {
        // "reject" is the core's own default for xudpProxyUDP443 (research R2.4).
        public val DEFAULT: MuxUserSettings =
            MuxUserSettings(
                override = false,
                enabled = false,
                tcpConnections = "8",
                xudpConnections = "16",
                quic = "reject",
            )
    }
}

public data class TunMtuUserSetting(
    val override: Boolean,
    val value: String,
) {
    public companion object {
        public val DEFAULT: TunMtuUserSetting = TunMtuUserSetting(override = false, value = TunMtu.DEFAULT.toString())
    }
}

/** [ranges] holds one range per line, or comma/space separated; see [ExcludedRoutes.parse]. */
public data class ExcludeRoutesUserSetting(
    val override: Boolean,
    val ranges: String,
) {
    public companion object {
        public val DEFAULT: ExcludeRoutesUserSetting = ExcludeRoutesUserSetting(override = false, ranges = "")
    }
}

/** Where a resolved tuning value came from. */
public enum class TuningOrigin { Yours, Subscription, Config, Default }

public sealed interface TuningProblem {
    public data class OutOfRange(
        val min: Int,
        val max: Int,
    ) : TuningProblem

    public data class BadValue(
        val key: String,
    ) : TuningProblem

    public data class RangeCoversDns(
        val index: Int,
    ) : TuningProblem

    public data class RangeTooWide(
        val index: Int,
    ) : TuningProblem

    public data object NeedsConsent : TuningProblem
}

/**
 * Result of a tuning check. [Checked] carries only an [ObfuscationProblem] and is matched exhaustively
 * across the obfuscation code, so tuning gets this parallel type rather than a widened one.
 */
public sealed interface TuningChecked<out T> {
    public data class Ok<T>(
        val value: T,
    ) : TuningChecked<T>

    public data class Invalid(
        val problem: TuningProblem,
    ) : TuningChecked<Nothing>
}

public object TunMtu {
    public const val DEFAULT: Int = 8500
    public const val MIN: Int = 1280
    public const val MAX: Int = 65_535

    public fun check(text: String): TuningChecked<Int> {
        val parsed = text.trim().toIntOrNull()
        return when {
            parsed == null -> TuningChecked.Invalid(TuningProblem.BadValue("mtu"))
            parsed !in MIN..MAX -> TuningChecked.Invalid(TuningProblem.OutOfRange(MIN, MAX))
            else -> TuningChecked.Ok(parsed)
        }
    }
}

/**
 * The TUN interface's own addresses, the one source `:service` builds the interface from.
 *
 * ARCHITECTURE.md §5.2: without the IPv6 address and route, every AAAA-answered lookup and every
 * IPv6-capable socket leaves outside the tunnel on a dual-stack network. That passes a leak test on an
 * IPv4-only Wi-Fi and fails on carrier IPv6.
 *
 * Here beside [ExcludedRoutes] and [TunMtu] because they describe the same interface. An excluded range
 * may contain these addresses (controller ruling, see [ExcludedRoutes.validate]); only `/0` is refused.
 */
public object TunAddresses {
    public const val V4: String = "10.7.0.1"
    public const val V4_PREFIX: Int = 30
    public const val V6: String = "fd00:1:2:3::1"
    public const val V6_PREFIX: Int = 126
}

public object ProxyCredentialsFactory {
    private const val USER_LENGTH = 16
    private const val PASS_LENGTH = 32
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

    public fun random(random: SecureRandom = SecureRandom()): ProxyCredentials =
        ProxyCredentials(token(random, USER_LENGTH), token(random, PASS_LENGTH))

    private fun token(
        random: SecureRandom,
        length: Int,
    ): String = buildString { repeat(length) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }
}
