// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.data

import io.kotest.matchers.shouldBe
import org.junit.Test
import space.getsub.core.model.CidrRange
import space.getsub.core.model.ExcludeRoutesUserSetting
import space.getsub.core.model.ExcludedRoutes
import space.getsub.core.model.MuxSpec
import space.getsub.core.model.MuxUserSettings
import space.getsub.core.model.SniffingUserSetting
import space.getsub.core.model.TunMtuUserSetting
import space.getsub.core.model.TuningChecked
import space.getsub.core.model.TuningOrigin
import space.getsub.core.model.TuningProblem
import space.getsub.core.model.Udp443Mode

class DirectiveTuningTest {
    private val defaults =
        TuningUserInputs(
            SniffingUserSetting.DEFAULT,
            MuxUserSettings.DEFAULT,
            TunMtuUserSetting.DEFAULT,
            ExcludeRoutesUserSetting.DEFAULT,
        )

    private fun ranges(text: String): List<CidrRange> =
        (ExcludedRoutes.parse(text) as TuningChecked.Ok).value

    private val lan = ranges("172.16.0.0/12,192.168.0.0/16")

    private fun status(
        trust: RouteExclusionTrust,
        offered: List<CidrRange>,
    ): RouteExclusionStatus {
        val probe = RouteExclusionStatus(RouteExclusionTrust.Unasked, null, offered)
        val fingerprint = probe.applicable.takeIf { it.isNotEmpty() }?.let(ExcludedRoutes::fingerprint)
        return RouteExclusionStatus(trust, fingerprint, offered)
    }

    private fun resolve(
        directives: Map<String, String?> = emptyMap(),
        user: TuningUserInputs = defaults,
        routes: RouteExclusionStatus = RouteExclusionStatus.NONE,
    ) = resolveTuning(user, directives, routes)

    @Test fun `nothing set resolves to the defaults`() {
        resolve() shouldBe
            ResolvedTuning(
                sniffing = TuningValue(true, TuningOrigin.Default),
                mux = TuningValue(null, TuningOrigin.Default),
                tunMtu = TuningValue(8500, TuningOrigin.Default),
                excludedRoutes = TuningValue(emptyList(), TuningOrigin.Default),
            )
    }

    @Test fun `the directive keys cover everything the resolver reads`() {
        TUNING_DIRECTIVE_KEYS.toSet() shouldBe
            setOf(
                "sniffing-enable",
                "mux-enable",
                "mux-tcp-connections",
                "mux-xudp-connections",
                "mux-quic",
                "xray-tun-mtu",
                "exclude-routes",
                "exclude-routes-set",
            )
    }

    // ---- sniffing ----

    @Test fun `sniffing-enable 0 turns sniffing off from the subscription`() {
        resolve(mapOf("sniffing-enable" to "0")).sniffing shouldBe TuningValue(false, TuningOrigin.Subscription)
    }

    @Test fun `sniffing booleans follow A1 - true and 1 enable, other non-empty disables, blank is unset`() {
        resolve(mapOf("sniffing-enable" to "1")).sniffing shouldBe TuningValue(true, TuningOrigin.Subscription)
        resolve(mapOf("sniffing-enable" to "TRUE")).sniffing shouldBe TuningValue(true, TuningOrigin.Subscription)
        resolve(mapOf("sniffing-enable" to "yes")).sniffing shouldBe TuningValue(false, TuningOrigin.Subscription)
        resolve(mapOf("sniffing-enable" to " ")).sniffing shouldBe TuningValue(true, TuningOrigin.Default)
        resolve(mapOf("sniffing-enable" to null)).sniffing shouldBe TuningValue(true, TuningOrigin.Default)
    }

    @Test fun `your sniffing setting wins over the directive`() {
        val user = defaults.copy(sniffing = SniffingUserSetting(override = true, enabled = true))
        resolve(mapOf("sniffing-enable" to "false"), user).sniffing shouldBe TuningValue(true, TuningOrigin.Yours)
    }

    @Test fun `your sniffing values are ignored while your switch is off`() {
        val user = defaults.copy(sniffing = SniffingUserSetting(override = false, enabled = false))
        resolve(user = user).sniffing shouldBe TuningValue(true, TuningOrigin.Default)
    }

    // ---- MTU ----

    @Test fun `xray-tun-mtu is used from the subscription`() {
        resolve(mapOf("xray-tun-mtu" to "1500")).tunMtu shouldBe TuningValue(1500, TuningOrigin.Subscription)
    }

    @Test fun `a provider MTU under 1280 is ignored for the default`() {
        resolve(mapOf("xray-tun-mtu" to "1000")).tunMtu shouldBe
            TuningValue(8500, TuningOrigin.Default, TuningProblem.OutOfRange(1280, 65_535, "xray-tun-mtu"))
    }

    @Test fun `a provider MTU that is not a number names the directive`() {
        resolve(mapOf("xray-tun-mtu" to "big")).tunMtu shouldBe
            TuningValue(8500, TuningOrigin.Default, TuningProblem.BadValue("xray-tun-mtu"))
    }

    @Test fun `your MTU wins over the directive`() {
        val user = defaults.copy(mtu = TunMtuUserSetting(override = true, value = "9000"))
        resolve(mapOf("xray-tun-mtu" to "1500"), user).tunMtu shouldBe TuningValue(9000, TuningOrigin.Yours)
    }

    @Test fun `your invalid MTU falls back to the default with the reason`() {
        val user = defaults.copy(mtu = TunMtuUserSetting(override = true, value = "600"))
        resolve(mapOf("xray-tun-mtu" to "1500"), user).tunMtu shouldBe
            TuningValue(8500, TuningOrigin.Default, TuningProblem.OutOfRange(1280, 65_535))
    }

    // ---- mux ----

    @Test fun `mux-enable with valid fields turns mux on from the subscription`() {
        val d =
            mapOf(
                "mux-enable" to "true",
                "mux-tcp-connections" to "4",
                "mux-xudp-connections" to "-1",
                "mux-quic" to "skip",
            )
        resolve(d).mux shouldBe TuningValue(MuxSpec(4, -1, Udp443Mode.Skip), TuningOrigin.Subscription)
    }

    @Test fun `mux-enable alone takes the default fields`() {
        resolve(mapOf("mux-enable" to "1")).mux shouldBe
            TuningValue(MuxSpec(8, 16, Udp443Mode.Reject), TuningOrigin.Subscription)
    }

    @Test fun `mux fields without mux-enable do nothing`() {
        resolve(mapOf("mux-tcp-connections" to "4")).mux shouldBe TuningValue(null, TuningOrigin.Default)
    }

    @Test fun `an explicit mux-enable off is the subscription's answer`() {
        resolve(mapOf("mux-enable" to "0")).mux shouldBe TuningValue(null, TuningOrigin.Subscription)
        resolve(mapOf("mux-enable" to "false", "mux-tcp-connections" to "4")).mux shouldBe
            TuningValue(null, TuningOrigin.Subscription)
    }

    @Test fun `mux-enable with an out-of-range count leaves mux off with the reason`() {
        resolve(mapOf("mux-enable" to "true", "mux-tcp-connections" to "5000")).mux shouldBe
            TuningValue(null, TuningOrigin.Default, TuningProblem.OutOfRange(-1, 1024, "mux-tcp-connections"))
        resolve(mapOf("mux-enable" to "true", "mux-xudp-connections" to "-2")).mux shouldBe
            TuningValue(null, TuningOrigin.Default, TuningProblem.OutOfRange(-1, 1024, "mux-xudp-connections"))
    }

    @Test fun `mux-enable with a bad value names the directive`() {
        resolve(mapOf("mux-enable" to "true", "mux-xudp-connections" to "lots")).mux shouldBe
            TuningValue(null, TuningOrigin.Default, TuningProblem.BadValue("mux-xudp-connections"))
        resolve(mapOf("mux-enable" to "true", "mux-quic" to "drop")).mux shouldBe
            TuningValue(null, TuningOrigin.Default, TuningProblem.BadValue("mux-quic"))
    }

    @Test fun `your mux setting wins over the directive, on or off`() {
        val on = defaults.copy(mux = MuxUserSettings(true, true, "2", "3", "allow"))
        resolve(mapOf("mux-enable" to "true"), on).mux shouldBe
            TuningValue(MuxSpec(2, 3, Udp443Mode.Allow), TuningOrigin.Yours)
        val off = defaults.copy(mux = MuxUserSettings.DEFAULT.copy(override = true, enabled = false))
        resolve(mapOf("mux-enable" to "true"), off).mux shouldBe TuningValue(null, TuningOrigin.Yours)
    }

    @Test fun `your invalid mux leaves mux off with the reason`() {
        val bad = defaults.copy(mux = MuxUserSettings(true, true, "2000", "3", "allow"))
        resolve(user = bad).mux shouldBe TuningValue(null, TuningOrigin.Default, TuningProblem.OutOfRange(-1, 1024))
    }

    // ---- excluded routes ----

    @Test fun `provider ranges without consent apply nothing`() {
        resolve(routes = status(RouteExclusionTrust.Unasked, lan)).excludedRoutes shouldBe
            TuningValue(emptyList(), TuningOrigin.Default, TuningProblem.NeedsConsent)
        resolve(routes = status(RouteExclusionTrust.Denied, lan)).excludedRoutes shouldBe
            TuningValue(emptyList(), TuningOrigin.Default, TuningProblem.Declined)
    }

    @Test fun `provider ranges allowed for the current list apply`() {
        resolve(routes = status(RouteExclusionTrust.Allowed, lan)).excludedRoutes shouldBe
            TuningValue(lan, TuningOrigin.Subscription)
    }

    @Test fun `an answer for an older list reads as unasked and needs consent`() {
        // The repository reads trust as Unasked whenever the stored fingerprint is not the current one.
        val offered = "172.16.0.0/12"
        val stale = routeExclusionStatusOf(offered, "ALLOWED", ExcludedRoutes.fingerprint(ranges("10.0.0.0/8")))
        resolve(routes = stale).excludedRoutes shouldBe
            TuningValue(emptyList(), TuningOrigin.Default, TuningProblem.NeedsConsent)
        val current = routeExclusionStatusOf(offered, "ALLOWED", ExcludedRoutes.fingerprint(ranges(offered)))
        resolve(routes = current).excludedRoutes shouldBe TuningValue(ranges(offered), TuningOrigin.Subscription)
    }

    @Test fun `an allowed list drops a whole-family range with the reason`() {
        val offered = ranges("0.0.0.0/0,172.16.0.0/12")
        resolve(routes = status(RouteExclusionTrust.Allowed, offered)).excludedRoutes shouldBe
            TuningValue(ranges("172.16.0.0/12"), TuningOrigin.Subscription, TuningProblem.RangeTooWide(0))
    }

    @Test fun `a list of only whole-family ranges applies nothing with the reason`() {
        resolve(routes = status(RouteExclusionTrust.Unasked, ranges("::/0"))).excludedRoutes shouldBe
            TuningValue(emptyList(), TuningOrigin.Default, TuningProblem.RangeTooWide(0))
    }

    @Test fun `an unparseable provider list is ignored with the reason`() {
        resolve(mapOf("exclude-routes" to "172.16.0.0/12,nope")).excludedRoutes shouldBe
            TuningValue(emptyList(), TuningOrigin.Default, TuningProblem.BadValue("exclude-routes"))
        resolve(mapOf("exclude-routes" to "10.0.0.0/8", "exclude-routes-set" to "nope")).excludedRoutes shouldBe
            TuningValue(emptyList(), TuningOrigin.Default, TuningProblem.BadValue("exclude-routes-set"))
    }

    @Test fun `your ranges win over the provider's and need no consent`() {
        val user = defaults.copy(excludeRoutes = ExcludeRoutesUserSetting(true, "10.0.0.0/8"))
        resolve(user = user, routes = status(RouteExclusionTrust.Allowed, lan)).excludedRoutes shouldBe
            TuningValue(ranges("10.0.0.0/8"), TuningOrigin.Yours)
    }

    @Test fun `your empty list with your switch on excludes nothing`() {
        val user = defaults.copy(excludeRoutes = ExcludeRoutesUserSetting(true, ""))
        resolve(user = user, routes = status(RouteExclusionTrust.Allowed, lan)).excludedRoutes shouldBe
            TuningValue(emptyList(), TuningOrigin.Yours)
    }

    @Test fun `your unparseable list falls back to none with the reason`() {
        val user = defaults.copy(excludeRoutes = ExcludeRoutesUserSetting(true, "10.0.0.0/8 nope"))
        resolve(user = user).excludedRoutes shouldBe
            TuningValue(emptyList(), TuningOrigin.Default, TuningProblem.BadValue("exclude-routes"))
    }

    @Test fun `your whole-family range is dropped with the reason`() {
        val user = defaults.copy(excludeRoutes = ExcludeRoutesUserSetting(true, "10.0.0.0/8 ::/0"))
        resolve(user = user).excludedRoutes shouldBe
            TuningValue(ranges("10.0.0.0/8"), TuningOrigin.Yours, TuningProblem.RangeTooWide(1))
    }

    // ---- connect-time DNS check ----

    @Test fun `a range covering a session DNS server is dropped for the session`() {
        val resolved = resolve(routes = status(RouteExclusionTrust.Allowed, ranges("1.1.1.0/24,172.16.0.0/12")))
        dropRangesCoveringDns(resolved, listOf("1.1.1.1")).excludedRoutes shouldBe
            TuningValue(ranges("172.16.0.0/12"), TuningOrigin.Subscription, TuningProblem.RangeCoversDns(0))
    }

    @Test fun `the DNS check applies to your ranges and keeps the first problem`() {
        val user = defaults.copy(excludeRoutes = ExcludeRoutesUserSetting(true, "::/0 10.0.0.0/8 8.8.8.0/24"))
        val resolved = resolve(user = user)
        dropRangesCoveringDns(resolved, listOf("10.7.0.2", "8.8.8.8")).excludedRoutes shouldBe
            TuningValue(emptyList(), TuningOrigin.Yours, TuningProblem.RangeTooWide(0))
    }

    @Test fun `the DNS check leaves everything else alone`() {
        val resolved = resolve(mapOf("xray-tun-mtu" to "1500"), routes = status(RouteExclusionTrust.Allowed, lan))
        dropRangesCoveringDns(resolved, listOf("1.1.1.1", "dns.google")) shouldBe resolved
    }

    // ---- what subscription details say (M9-E spec §3, §4, §7.1) ----

    private fun issues(
        directives: Map<String, String?> = emptyMap(),
        routes: RouteExclusionStatus = RouteExclusionStatus.NONE,
    ) = tuningDirectiveIssues(directives, routes)

    @Test fun `a subscription with valid or no tuning directives has no issues`() {
        issues() shouldBe TuningDirectiveIssues.NONE
        issues(mapOf("xray-tun-mtu" to "1400", "mux-enable" to "1")) shouldBe TuningDirectiveIssues.NONE
    }

    @Test fun `issues name the directive the provider can fix`() {
        val found =
            issues(mapOf("xray-tun-mtu" to "1000", "mux-enable" to "1", "mux-quic" to "drop", "exclude-routes" to "x"))
        found.tunMtu shouldBe
            TuningDirectiveIssue("xray-tun-mtu", TuningProblem.OutOfRange(1280, 65_535, "xray-tun-mtu"))
        found.mux shouldBe TuningDirectiveIssue("mux-quic", TuningProblem.BadValue("mux-quic"))
        found.excludedRoutes shouldBe TuningDirectiveIssue("exclude-routes", TuningProblem.BadValue("exclude-routes"))
        found.excludedRoutesApply shouldBe false
    }

    @Test fun `issues ignore your overrides, which subscription details do not describe`() {
        // The resolver core runs with every switch of yours off, so a provider's bad value still shows.
        issues(mapOf("xray-tun-mtu" to "abc")).tunMtu shouldBe
            TuningDirectiveIssue("xray-tun-mtu", TuningProblem.BadValue("xray-tun-mtu"))
    }

    @Test fun `provider routes awaiting an answer or declined are not applied, under the key that sent them`() {
        val offered = mapOf("exclude-routes-set" to "172.16.0.0/12")
        issues(offered, status(RouteExclusionTrust.Unasked, lan)).excludedRoutes shouldBe
            TuningDirectiveIssue("exclude-routes-set", TuningProblem.NeedsConsent)
        issues(offered, status(RouteExclusionTrust.Denied, lan)).excludedRoutes shouldBe
            TuningDirectiveIssue("exclude-routes-set", TuningProblem.Declined)
    }

    @Test fun `allowed provider routes apply, with a dropped whole-family range as a partial issue`() {
        val allowed = issues(mapOf("exclude-routes" to "x"), status(RouteExclusionTrust.Allowed, lan))
        allowed.excludedRoutes shouldBe null
        allowed.excludedRoutesApply shouldBe true
        val partly =
            issues(
                mapOf("exclude-routes" to "x"),
                status(RouteExclusionTrust.Allowed, ranges("0.0.0.0/0,172.16.0.0/12")),
            )
        partly.excludedRoutes shouldBe
            TuningDirectiveIssue("exclude-routes", TuningProblem.RangeTooWide(0), partial = true)
        partly.excludedRoutesApply shouldBe true
    }
}
