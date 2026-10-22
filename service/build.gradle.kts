// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
import java.time.Duration

plugins {
    id("subspace.android.library")
    id("subspace.android.hilt")
}

// The tunnel is a git submodule pinned at 2.16.0. An unpinned or absent
// submodule in a VPN client is a supply-chain question, so fail early and
// usefully rather than with a screen of ndk-build errors.
val hevDir = rootProject.file("third_party/hev-socks5-tunnel")
val hevBaseCommit = "0a05221275a51a884d93328c55fc2fbc9e9b6974"
val hevPatchFiles =
    listOf(
        rootProject.file("third_party/hev-patches/0001-nonblocking-pending-stop.patch"),
        rootProject.file("third_party/hev-patches/0002-skip-timeout-if-not-alive.patch"),
    )
val prepareHevScript = rootProject.file("scripts/prepare-hev-socks5-tunnel.sh")
val prepareHevLockedScript = rootProject.file("scripts/prepare-hev-socks5-tunnel-locked.sh")
val prepareHevLockWrapper = rootProject.file("scripts/with-hev-prepare-lock.pl")
val testHevSourceIsolationScript = rootProject.file("scripts/test-hev-source-isolation.sh")
val patchedHevDir = layout.buildDirectory.dir("generated/hev-socks5-tunnel")
// Checks a NESTED submodule, not the outer one. `git submodule update --init`
// without --recursive leaves the outer Android.mk present and the vendored
// dependency trees empty, so guarding on the outer file would pass and hand the
// developer exactly the raw ndk-build error this guard exists to prevent.
if (!hevDir.resolve("third-part/lwip/Android.mk").exists()) {
    throw GradleException(
        "third_party/hev-socks5-tunnel is missing or incompletely checked out.\n" +
            "Run: git submodule update --init --recursive",
    )
}

val preparePatchedHev =
    tasks.register<Exec>("preparePatchedHev") {
        group = "build setup"
        description = "Verifies the pinned HEV submodule and applies Subspace's parent-owned patch"

        inputs.property("hevBaseCommit", hevBaseCommit)
        inputs.files(hevPatchFiles)
        inputs.file(prepareHevScript)
        inputs.file(prepareHevLockedScript)
        inputs.file(prepareHevLockWrapper)
        outputs.dir(patchedHevDir)
        // The parent gitlink lives in Git's index, outside the declared source
        // tree. Always re-run the verifier so an index-only pin change
        // cannot reuse an otherwise byte-identical generated directory.
        outputs.upToDateWhen { false }

        commandLine(
            "bash",
            prepareHevScript.absolutePath,
            hevDir.absolutePath,
            patchedHevDir.get().asFile.absolutePath,
            *hevPatchFiles.map { it.absolutePath }.toTypedArray(),
        )
    }

val testHevSourceIsolation =
    tasks.register<Exec>("testHevSourceIsolation") {
        group = "verification"
        description = "Proves isolated HEV source filtering and concurrent atomic publication"
        dependsOn(preparePatchedHev)

        inputs.property("hevBaseCommit", hevBaseCommit)
        inputs.files(hevPatchFiles)
        inputs.file(prepareHevScript)
        inputs.file(prepareHevLockedScript)
        inputs.file(prepareHevLockWrapper)
        inputs.file(testHevSourceIsolationScript)
        outputs.upToDateWhen { false }

        // M8.5 spec §6: a hang that did not reproduce in 50 looped runs. Bounded so
        // it fails instead of wedging CI, and the script dumps its process tree on
        // the way out (see test-hev-source-isolation.sh's watchdog).
        timeout.set(Duration.ofMinutes(10))

        commandLine("bash", testHevSourceIsolationScript.absolutePath)
    }

// Native configuration must never see the pristine submodule tree. This makes
// the parent-owned patch a build dependency instead of an optional setup step.
tasks.matching { it.name.startsWith("configureNdkBuild") }.configureEach {
    dependsOn(preparePatchedHev)
    mustRunAfter(testHevSourceIsolation)
}
tasks.named("check").configure {
    dependsOn(testHevSourceIsolation)
}

android {
    namespace = "space.getsub.service"
    // Pinned for reproducibility (M10-A spec §6.5): see the note on `ndk` in
    // gradle/libs.versions.toml. :app pins the same version for its strip task.
    ndkVersion = libs.versions.ndk.get()

    buildFeatures {
        aidl = true
    }

    defaultConfig {
        ndk {
            // Mirrors service/src/main/jni/Application.mk. 32-bit x86 is omitted
            // deliberately — effectively dead on real devices, and it doubles
            // native build time.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        externalNativeBuild {
            ndkBuild {
                // hev's build.mk turns REV_ID into -DCOMMIT_ID. Left unset it falls back to
                // `git -C $(SRCDIR) rev-parse --short HEAD`, and SRCDIR is the patched copy
                // under service/build/generated/, which is not a repository: git walks up
                // and answers with the PARENT repo's HEAD, or nothing ("unknown") in a
                // checkout without .git. Today COMMIT_ID is read only by hev-main.c's
                // show_help, compiled out under ENABLE_LIBRARY, so the bytes do not move
                // yet; the pin keeps a hev bump that reads it from making the library
                // depend on Subspace's HEAD (M10-A spec §6.5). Derived from the pin
                // preparePatchedHev verifies, so a submodule bump moves both. Android.mk
                // refuses to build without it.
                arguments += "REV_ID=${hevBaseCommit.take(7)}"
            }
        }
    }

    externalNativeBuild {
        // ndk-build, not CMake: upstream ships its own Android.mk and documents
        // ndk-build as the Android path. It compiles three vendored static
        // libraries with per-ABI flags, and reimplementing that in CMake would
        // be a standing source of silent drift.
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:parser"))
    implementation(project(":core:xray"))
    implementation(project(":core:data"))

    // MetricsClient parses xray's JSON metrics endpoint using just JsonElement
    // navigation (no @Serializable class) — already an implementation dependency of
    // :core:parser, but declared explicitly here since this module uses it directly.
    implementation(libs.kotlinx.serialization.json)

    // TerminalOutcomeTest drives a deliberately-delayed persistence write against a
    // concurrent teardown, which needs runTest's scheduler to be deterministic rather
    // than a real-clock sleep. The convention plugin puts this on androidTest only;
    // :feature:home already adds it to the JVM test set the same way.
    testImplementation(libs.kotlinx.coroutines.test)

    // ConnectionStateParcelRoundTripTest asserts on a data class (a real-Parcel
    // round trip) rather than a handful of booleans, so it wants the same
    // matcher library the JVM tests above already use instead of a pile of
    // assertEquals. Declared here, not in the convention plugin — see
    // feature/settings/build.gradle.kts for the same per-module shape.
    androidTestImplementation(libs.kotest.assertions)
}
