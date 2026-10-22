// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.model.identity

public enum class DeviceHeader(
    val wireName: String,
) {
    Os("x-device-os"),
    OsVersion("x-ver-os"),
    Model("x-device-model"),
    AppVersion("x-app-version"),
}

/** A null [literal] means "follow this device". */
public data class HeaderSetting(
    val enabled: Boolean,
    val literal: String?,
) {
    override fun toString(): String {
        val shown = if (literal == null) "null" else "<redacted>"
        return "HeaderSetting(enabled=$enabled, literal=$shown)"
    }
}

public data class SubscriptionIdentity(
    val hwid: String,
    val hwidEnabled: Boolean,
    val headers: Map<DeviceHeader, HeaderSetting>,
) {
    /** Redacts the HWID and every literal (ARCHITECTURE.md §5.6). */
    override fun toString(): String =
        "SubscriptionIdentity(hwid=<redacted>, hwidEnabled=$hwidEnabled, " +
            "headers=${headers.mapValues { (_, s) -> s.enabled }})"
}
