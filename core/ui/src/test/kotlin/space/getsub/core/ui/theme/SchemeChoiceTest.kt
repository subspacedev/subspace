// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.ui.theme

import io.kotest.matchers.shouldBe
import org.junit.Test

class SchemeChoiceTest {
    @Test
    fun dynamicColourOnAndroid12AndLaterFollowsTheWallpaper() {
        chooseScheme(apiLevel = 31, dynamicColor = true, dark = true) shouldBe SchemeChoice.DynamicDark
        chooseScheme(apiLevel = 37, dynamicColor = true, dark = false) shouldBe SchemeChoice.DynamicLight
    }

    /** Review Focus 3: dynamicDarkColorScheme does not exist below API 31. */
    @Test
    fun belowAndroid12TheSwitchIsIgnoredAndTheFallbackIsUsed() {
        chooseScheme(apiLevel = 30, dynamicColor = true, dark = true) shouldBe SchemeChoice.FallbackDark
        chooseScheme(apiLevel = 26, dynamicColor = true, dark = false) shouldBe SchemeChoice.FallbackLight
    }

    @Test
    fun switchingDynamicColourOffUsesTheFallbackOnEveryVersion() {
        chooseScheme(apiLevel = 37, dynamicColor = false, dark = true) shouldBe SchemeChoice.FallbackDark
        chooseScheme(apiLevel = 31, dynamicColor = false, dark = false) shouldBe SchemeChoice.FallbackLight
    }

    @Test
    fun lightweightModeReducesMotion() {
        reducedMotion(lightweight = true, animatorDurationScale = 1f) shouldBe true
    }

    /** Review Focus 4: the system setting alone must be enough. */
    @Test
    fun systemRemoveAnimationsReducesMotionEvenWithLightweightOff() {
        reducedMotion(lightweight = false, animatorDurationScale = 0f) shouldBe true
    }

    @Test
    fun normalSettingsKeepMotion() {
        reducedMotion(lightweight = false, animatorDurationScale = 1f) shouldBe false
        reducedMotion(lightweight = false, animatorDurationScale = 0.5f) shouldBe false
    }
}
