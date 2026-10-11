// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.settings

import io.kotest.matchers.shouldBe
import org.junit.Test
import space.getsub.core.data.InstalledGeoAsset
import space.getsub.core.model.GeoDataKind
import space.getsub.core.model.GeoSourceCatalogue

class EffectiveGeoSourceIdsTest {
    @Test
    fun `with nothing stored or installed the catalogue defaults are in effect`() {
        effectiveGeoSourceIds(GeoSourceCatalogue.sources, emptySet(), emptyList()) shouldBe
            GeoSourceCatalogue.defaults().map { it.id }.toSet()
    }

    @Test
    fun `a stored choice replaces the default for its own filename only`() {
        effectiveGeoSourceIds(GeoSourceCatalogue.sources, setOf("runetfreedom-geosite"), emptyList()) shouldBe
            setOf("runetfreedom-geosite", "v2fly-geoip")
    }

    @Test
    fun `an installed asset is in effect when nothing is stored`() {
        val installed =
            InstalledGeoAsset(
                fileName = "geoip.dat",
                sourceUrl = GeoSourceCatalogue.source("loyalsoldier-geoip")!!.downloadUrl,
                geoType = GeoDataKind.IP,
                sizeBytes = 1L,
                installedAt = 1L,
                lastAttemptedAt = 1L,
                lastFailure = null,
            )

        effectiveGeoSourceIds(GeoSourceCatalogue.sources, emptySet(), listOf(installed)) shouldBe
            setOf("loyalsoldier-geoip", "v2fly-geosite")
    }
}
