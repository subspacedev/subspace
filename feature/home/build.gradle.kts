// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
plugins {
    id("subspace.android.compose")
    id("subspace.android.hilt")
    // M8.6 spec §7.1: screenshot tests for every :core:ui component and Home state.
    alias(libs.plugins.paparazzi)
}

android {
    namespace = "space.getsub.feature.home"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:data"))
    implementation(project(":core:parser"))
    implementation(project(":core:ui"))
    implementation(project(":service"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    // subspace.android.library already wires junit + kotest-assertions for unit
    // tests, but not this: HomeViewModel drives its state through
    // viewModelScope, which needs a Main dispatcher. Without it, exercising
    // onConsentGranted in a JVM unit test throws before it does anything.
    testImplementation(libs.kotlinx.coroutines.test)

    // First Compose UI instrumented test in this module (Task 14:
    // HomeReconnectingTest). Same shape as feature/settings/build.gradle.kts.
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.kotest.assertions)
    debugImplementation(libs.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.test.espresso.core)
}

// M8.6 spec §1.2 item 3: a changed golden fails `check`, so an alpha BOM bump
// cannot silently restyle a component.
tasks.matching { it.name == "check" }.configureEach {
    dependsOn("verifyPaparazziDebug")
}
