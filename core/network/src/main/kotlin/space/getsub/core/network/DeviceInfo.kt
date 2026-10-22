// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.network

import javax.inject.Inject

/**
 * This device's values for `x-ver-os` and `x-device-model`, used when a subscription's header
 * follows the device (M10-B spec §3.5). `:core:data` resolves them into each
 * [SubscriptionRequest]. Behind a seam, same reasoning as [HwidProvider]: the resolution stays
 * provable on the plain JVM rather than needing Robolectric or `androidTest` —
 * `android.os.Build.VERSION.RELEASE`/`Build.MODEL` are not live values under a JVM unit test.
 * The raw values may be non-ASCII; the caller sanitises them before they become header values.
 */
public interface DeviceInfo {
    /** e.g. `"14"`. Empty string if unavailable. */
    public val osVersion: String

    /** e.g. `"Pixel 8"`. Empty string if unavailable. */
    public val model: String
}

/**
 * A [DeviceInfo] with both fields empty.
 *
 * For tests and fixtures that have no reason to touch `android.os.Build`. An empty value is
 * dropped rather than sent. Production wiring binds the real one in `NetworkModule`.
 */
public object EmptyDeviceInfo : DeviceInfo {
    override val osVersion: String = ""
    override val model: String = ""
}

/** Reads the two fields from `android.os.Build` — the only Android-coupled part. */
public class AndroidDeviceInfo
@Inject
constructor() : DeviceInfo {
    override val osVersion: String get() = android.os.Build.VERSION.RELEASE.orEmpty()
    override val model: String get() = android.os.Build.MODEL.orEmpty()
}
