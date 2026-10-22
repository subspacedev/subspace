// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.model.subscription

import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

private const val SOON_DAYS = 3L

/**
 * The `subscription-userinfo` counters, mirrored here because :core:parser depends on
 * :core:model and so its `UserInfo` is not visible. The mapping is a field-for-field copy
 * (`upload`, `download`, `total`, `expiresAtEpochSeconds`).
 */
public data class ProviderQuota(
    val uploadBytes: Long?,
    val downloadBytes: Long?,
    val totalBytes: Long?,
    val expiresAtEpochSeconds: Long?,
)

public sealed interface Usage {
    public data class Limited(
        val usedBytes: Long,
        val totalBytes: Long,
    ) : Usage

    public data class Unlimited(
        val usedBytes: Long,
    ) : Usage
}

public sealed interface Expiry {
    public data object None : Expiry

    public data class On(
        val date: LocalDate,
        val daysLeft: Long,
    ) : Expiry

    public data object Today : Expiry

    public data object Expired : Expiry
}

/** A provider-supplied button. [url] is already scheme-checked by [safeLink]. */
public data class LinkButton(
    val text: String,
    val url: String,
) {
    // ARCHITECTURE.md §5.6: provider links are never logged.
    override fun toString(): String = "LinkButton(text=<redacted>, url=<redacted>)"
}

/**
 * Everything a provider says about a subscription, ready to draw (M10-B spec §3).
 * There is deliberately no colour field: `sub-info-color` is ignored.
 * Link fields carry URLs, so [toString] redacts (ARCHITECTURE.md §5.6).
 */
public data class SubscriptionInfo(
    val title: String?,
    val refreshHours: Int?,
    val usage: Usage?,
    val expiry: Expiry?,
    val announce: String?,
    val infoText: String?,
    val infoButton: LinkButton?,
    val renewLink: String?,
    val supportLink: String?,
    val accountLink: String?,
) {
    override fun toString(): String = "SubscriptionInfo(<redacted>)"
}

/** Within 3 calendar days, today, or already past. */
public val Expiry.isSoon: Boolean
    get() =
        when (this) {
            Expiry.None -> false
            is Expiry.On -> daysLeft <= SOON_DAYS
            Expiry.Today, Expiry.Expired -> true
        }

/** The red expiry notice replaces the info banner while it shows. */
public val SubscriptionInfo.showInfoBanner: Boolean
    get() = infoText != null && expiry?.isSoon != true

/**
 * Builds the read model from stored directives and the quota counters.
 *
 * [directives] are the values as stored: `profile-title` and `announce` were already
 * base64-decoded at parse time (DirectiveKind.canonicaliseText), so nothing is decoded here.
 */
public fun subscriptionInfo(
    directives: Map<String, String>,
    userInfo: ProviderQuota?,
    today: LocalDate,
    zone: ZoneId,
): SubscriptionInfo {
    val buttonText = directives["sub-info-button-text"]?.takeIf { it.isNotBlank() }
    val buttonUrl = safeLink(directives["sub-info-button-link"])
    return SubscriptionInfo(
        title = directives["profile-title"],
        refreshHours = directives["profile-update-interval"]?.trim()?.toIntOrNull(),
        usage = userInfo?.let(::usageOf),
        expiry = userInfo?.let { expiryOf(it.expiresAtEpochSeconds, today, zone) },
        announce = directives["announce"],
        infoText = directives["sub-info-text"]?.takeIf { it.isNotBlank() },
        infoButton = if (buttonText != null && buttonUrl != null) LinkButton(buttonText, buttonUrl) else null,
        renewLink = safeLink(directives["sub-expire-button-link"]),
        supportLink = safeLink(directives["support-url"]),
        accountLink = safeLink(directives["profile-web-page-url"]),
    )
}

private fun usageOf(q: ProviderQuota): Usage? {
    val total = q.totalBytes
    val hasCounters = q.uploadBytes != null || q.downloadBytes != null
    val used = (q.uploadBytes ?: 0) + (q.downloadBytes ?: 0)
    return when {
        total != null && total > 0 -> Usage.Limited(used, total)
        total == 0L && hasCounters -> Usage.Unlimited(used)
        else -> null
    }
}

private fun expiryOf(
    epochSeconds: Long?,
    today: LocalDate,
    zone: ZoneId,
): Expiry {
    if (epochSeconds == null || epochSeconds == 0L) return Expiry.None
    val date = Instant.ofEpochSecond(epochSeconds).atZone(zone).toLocalDate()
    val days = ChronoUnit.DAYS.between(today, date)
    return when {
        days < 0 -> Expiry.Expired
        days == 0L -> Expiry.Today
        else -> Expiry.On(date, days)
    }
}

/** [raw] only when it is an absolute `http`/`https` URL with a host; anything else is `null`. */
internal fun safeLink(raw: String?): String? {
    val uri = raw?.trim()?.let { runCatching { URI(it) }.getOrNull() }
    val scheme = uri?.scheme?.lowercase()
    return if ((scheme == "https" || scheme == "http") && !uri.host.isNullOrBlank()) raw.trim() else null
}
