// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.profiles.subscription

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test
import space.getsub.core.data.FetchVia
import space.getsub.core.data.RedirectTrust
import space.getsub.core.data.ResolverTrust
import space.getsub.core.data.preresolve.ProviderResolver
import space.getsub.core.model.identity.DeviceHeader
import space.getsub.core.ui.preview.SubspacePreviewTheme
import space.getsub.core.ui.preview.ThemeVariant
import space.getsub.feature.profiles.R
import space.getsub.feature.profiles.add.UserMessage

/**
 * M8.6 Part 3 Task 7: the subscription details screen in all four themes — a fetched subscription
 * with quota, a pinned directive, a toggle and a refresh result, and a never-fetched one. Drives
 * the stateless [SubscriptionDetailContent] with hand-built state.
 */
class SubscriptionDetailSnapshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(screenHeight = 3400))

    private val noActions =
        SubscriptionDetailActions(
            onBack = {},
            onRefreshNow = {},
            onDismissRefreshResult = {},
            onPinRow = { _, _ -> },
            onUnpinRow = {},
            onHwidEnabledChanged = {},
            onUserAgentOverrideChanged = {},
            onDeleteConfirmed = {},
            onAllowRedirects = {},
            onDenyRedirects = {},
            onRedirectsAllowedChanged = {},
            onFrontingSaved = { _, _ -> },
            onLinkFragmentSaved = {},
        )

    private val fetched =
        SubscriptionDetailState(
            subscriptionId = 1L,
            loading = false,
            groupName = "Example Provider",
            profileCount = 12,
            redactedUrl = "https://panel.example.com/…",
            lastFetchState = LastFetchState.Succeeded(System.currentTimeMillis() - TWO_AND_A_HALF_HOURS_MS),
            quotaUsedBytes = 37_580_963_840L,
            quotaTotalBytes = 107_374_182_400L,
            hwidEnabled = true,
            hwid = "k3Fq9ZxT0mWb7LdVa2NcRe8UyHs5JgPo",
            hwidDraft = "k3Fq9ZxT0mWb7LdVa2NcRe8UyHs5JgPo",
            headers =
            mapOf(
                DeviceHeader.Os to HeaderRowState(enabled = true, literal = null, deviceValue = "Android"),
                DeviceHeader.OsVersion to HeaderRowState(enabled = true, literal = null, deviceValue = "14"),
                DeviceHeader.Model to
                    HeaderRowState(enabled = true, literal = "Example Phone 1", deviceValue = "Pixel 8"),
                DeviceHeader.AppVersion to HeaderRowState(enabled = false, literal = null, deviceValue = "0.1.0"),
            ),
            timeoutRow =
            DirectiveRow(
                DirectiveRowKind.Seconds,
                R.string.subscription_row_timeout_label,
                SettingRowState("subscription-request-timeout", "9", declinedProviderValue = null, isPinned = false),
            ),
            userAgentOverride = "Subspace/1.0 (Android)",
            providerUserAgent = "v2rayNG/1.8",
            refreshResult = UserMessage(R.plurals.subscription_refreshed, 3),
            rows =
            listOf(
                DirectiveRow(
                    DirectiveRowKind.Hours,
                    R.string.subscription_row_interval_label,
                    SettingRowState("profile-update-interval", "24", declinedProviderValue = "6", isPinned = true),
                ),
                DirectiveRow(
                    DirectiveRowKind.Toggle,
                    R.string.subscription_row_auto_update_label,
                    SettingRowState("update-always", "true", declinedProviderValue = null, isPinned = false),
                ),
            ),
        )

    // M10-B spec §3.5: a device value with nothing sendable left says so.
    private val notSent =
        fetched.copy(
            refreshResult = null,
            rows = emptyList(),
            headers =
            fetched.headers +
                (DeviceHeader.Model to HeaderRowState(enabled = true, literal = null, deviceValue = null)),
        )

    // M10-B spec §3.5: a device value that had to be cleaned up says so.
    private val adjusted =
        fetched.copy(
            refreshResult = null,
            rows = emptyList(),
            headers =
            fetched.headers +
                (
                    DeviceHeader.Model to
                        HeaderRowState(
                            enabled = true,
                            literal = null,
                            deviceValue = "Pixel 8",
                            deviceValueAdjusted = true,
                        )
                    ),
        )

    private val neverFetched =
        SubscriptionDetailState(
            subscriptionId = 2L,
            loading = false,
            groupName = "New Provider",
            profileCount = 0,
            redactedUrl = "https://sub.example.org:8443/…",
            lastFetchState = LastFetchState.NeverFetched,
        )

    private val reach =
        // Without the refresh banner and the directive rows, so the whole new section fits above the fold.
        fetched.copy(
            refreshResult = null,
            rows = emptyList(),
            redirectTrust = RedirectTrust.Unasked,
            redirectTargetHosts = listOf("new.example"),
            resolveAddress = "front.example",
            hostHeader = "real.example",
            linkFragment = "80-250,10-100,tlshello",
            lastFetchVia = FetchVia.Fallback,
            insecureIgnored = true,
            movedFromHost = "old.example",
            movedAt = System.currentTimeMillis() - TWO_AND_A_HALF_HOURS_MS,
        )

    // M9-C spec §4.2: the resolver consent card, and the switch with its line once answered.
    private val resolverRequest =
        fetched.copy(
            refreshResult = null,
            rows = emptyList(),
            resolverProvider = ProviderResolver.Valid("https://dns.example/dns-query", "dns.example", null),
            resolverTrust = ResolverTrust.Unasked,
        )

    private val resolverSwitch = resolverRequest.copy(resolverTrust = ResolverTrust.Allowed)

    private fun shoot(
        name: String,
        state: SubscriptionDetailState,
    ) {
        ThemeVariant.entries.forEach { v ->
            paparazzi.snapshot(name = "$name-${v.fileSuffix}") {
                SubspacePreviewTheme(v) { SubscriptionDetailContent(state = state, actions = noActions) }
            }
        }
    }

    @Test
    fun fetchedWithQuotaAndDirectives() = shoot("subscription-fetched", fetched)

    @Test
    fun aDeviceValueThatCannotBeSentSaysSo() = shoot("subscription-device-value-not-sent", notSent)

    @Test
    fun aDeviceValueThatWasAdjustedSaysSo() = shoot("subscription-device-value-adjusted", adjusted)

    @Test
    fun neverFetched() = shoot("subscription-never-fetched", neverFetched)

    @Test
    fun reachingTheSubscription() = shoot("subscription-reach", reach)

    @Test
    fun resolverRequest() = shoot("subscription-resolver-request", resolverRequest)

    @Test
    fun resolverSwitch() = shoot("subscription-resolver-switch", resolverSwitch)

    private companion object {
        // DateUtils floors to whole hours, so 2.5 h renders "2 hours ago" for
        // anything from 2 h to 3 h: a 30-minute margin each side instead of the
        // 30 s a minutes-based age leaves before the text flips.
        const val TWO_AND_A_HALF_HOURS_MS = 9_000_000L
    }
}
