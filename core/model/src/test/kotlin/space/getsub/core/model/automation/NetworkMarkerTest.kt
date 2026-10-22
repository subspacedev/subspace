// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.model.automation

import io.kotest.matchers.shouldBe
import org.junit.Test

class NetworkMarkerTest {
    @Test
    fun wifiWords() {
        networkMarkerOf("Frankfurt WIFI") shouldBe NetworkMarker.WifiOnly
        networkMarkerOf("🇩🇪 Berlin | Wi-Fi") shouldBe NetworkMarker.WifiOnly
    }

    @Test
    fun mobileWords() {
        networkMarkerOf("Amsterdam LTE") shouldBe NetworkMarker.MobileOnly
        networkMarkerOf("Paris [4G]") shouldBe NetworkMarker.MobileOnly
        networkMarkerOf("Oslo 5g") shouldBe NetworkMarker.MobileOnly
        networkMarkerOf("Rome mobile") shouldBe NetworkMarker.MobileOnly
    }

    @Test
    fun insideALongerWordIsNotAMarker() {
        networkMarkerOf("Satellite") shouldBe null
        networkMarkerOf("Wifiland") shouldBe null
        networkMarkerOf("Alte Brücke") shouldBe null
    }

    @Test
    fun wordsFromBothListsCountAsUnmarked() {
        networkMarkerOf("WIFI and LTE") shouldBe null
    }

    @Test
    fun allowedOnTruthTable() {
        NetworkMarker.WifiOnly.allowedOn(NetworkKind.Wifi) shouldBe true
        NetworkMarker.WifiOnly.allowedOn(NetworkKind.Mobile) shouldBe false
        NetworkMarker.WifiOnly.allowedOn(NetworkKind.Other) shouldBe true
        NetworkMarker.MobileOnly.allowedOn(NetworkKind.Wifi) shouldBe false
        NetworkMarker.MobileOnly.allowedOn(NetworkKind.Mobile) shouldBe true
        NetworkMarker.MobileOnly.allowedOn(NetworkKind.Other) shouldBe true
        val none: NetworkMarker? = null
        NetworkKind.entries.forEach { none.allowedOn(it) shouldBe true }
    }
}
