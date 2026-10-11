// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.settings

import android.os.Build
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The device's API level, behind a port so [SettingsViewModel]'s "Dynamic colour needs API 31"
 * decision is testable on both sides of the boundary. `Build.VERSION.SDK_INT` is a process-global
 * that Paparazzi overwrites and never restores, so a JVM test cannot rely on reading it.
 */
internal interface PlatformSdk {
    val sdkInt: Int
}

/** Production binding, via [SettingsModule]. */
@Singleton
internal class BoundPlatformSdk
@Inject
constructor() : PlatformSdk {
    override val sdkInt: Int get() = Build.VERSION.SDK_INT
}

/** [SettingsViewModel]'s constructor default, the same shape as [DisconnectedTunnelSessionSource]. */
internal object DevicePlatformSdk : PlatformSdk {
    override val sdkInt: Int get() = Build.VERSION.SDK_INT
}
