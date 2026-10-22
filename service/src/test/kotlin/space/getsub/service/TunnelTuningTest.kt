// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import io.kotest.matchers.shouldBe
import org.junit.Test
import space.getsub.core.data.ResolvedTuning
import space.getsub.core.data.TuningValue
import space.getsub.core.model.Applied
import space.getsub.core.model.CidrRange
import space.getsub.core.model.MuxSpec
import space.getsub.core.model.TuningOrigin
import space.getsub.core.model.Udp443Mode
import space.getsub.core.xray.TunnelSettings

/** `TunnelService.startCore` hands the config writers its one per-start [ResolvedTuning] through [withTuning]. */
class TunnelTuningTest {
    private val base =
        TunnelSettings(socksPort = 1, dnsServer = "1.1.1.1", enableSniffing = true, proxyCredentials = TEST_CREDENTIALS)
    private val spec = MuxSpec(8, 16, Udp443Mode.Reject)

    private fun tuning(
        mux: TuningValue<MuxSpec?>,
        sniffing: TuningValue<Boolean> = TuningValue(true, TuningOrigin.Default),
    ) = ResolvedTuning(
        sniffing = sniffing,
        mux = mux,
        tunMtu = TuningValue(8500, TuningOrigin.Default),
        excludedRoutes = TuningValue(emptyList<CidrRange>(), TuningOrigin.Default),
    )

    @Test fun `your mux replaces a config's own, a directive's does not`() {
        base.withTuning(tuning(TuningValue(spec, TuningOrigin.Yours))).mux shouldBe Applied(spec, replaceOwn = true)
        base.withTuning(tuning(TuningValue(spec, TuningOrigin.Subscription))).mux shouldBe
            Applied(spec, replaceOwn = false)
    }

    @Test fun `your switch On with mux off is passed on, a default off is nothing`() {
        base.withTuning(tuning(TuningValue(null, TuningOrigin.Yours))).mux shouldBe Applied(null, replaceOwn = true)
        base.withTuning(tuning(TuningValue(null, TuningOrigin.Default))).mux shouldBe null
        base.withTuning(tuning(TuningValue(null, TuningOrigin.Subscription))).mux shouldBe null
    }

    @Test fun `sniffing is carried with its origin`() {
        val settings = base.withTuning(
            tuning(TuningValue(null, TuningOrigin.Default), TuningValue(false, TuningOrigin.Yours))
        )
        settings.enableSniffing shouldBe false
        settings.sniffingReplacesOwn shouldBe true
        base.withTuning(tuning(TuningValue(null, TuningOrigin.Default), TuningValue(false, TuningOrigin.Subscription)))
            .sniffingReplacesOwn shouldBe false
    }
}
