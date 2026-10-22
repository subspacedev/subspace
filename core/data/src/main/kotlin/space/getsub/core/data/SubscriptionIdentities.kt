// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.data

import space.getsub.core.data.db.SubscriptionEntity
import space.getsub.core.model.identity.DeviceHeader
import space.getsub.core.model.identity.HeaderSetting
import space.getsub.core.model.identity.HeaderValue
import space.getsub.core.model.identity.SubscriptionIdentity
import space.getsub.core.network.DeviceInfo

/** The `x-device-os` value the app has always sent; a subscription following the device keeps it. */
private const val DEVICE_OS = "Android"

/** The row's identity columns as the model type (M10-B spec §3.5). */
internal fun SubscriptionEntity.identity(): SubscriptionIdentity =
    SubscriptionIdentity(
        hwid = hwid,
        hwidEnabled = hwidEnabled,
        headers =
        mapOf(
            DeviceHeader.Os to HeaderSetting(deviceOsEnabled, deviceOsLiteral),
            DeviceHeader.OsVersion to HeaderSetting(osVersionEnabled, osVersionLiteral),
            DeviceHeader.Model to HeaderSetting(deviceModelEnabled, deviceModelLiteral),
            DeviceHeader.AppVersion to HeaderSetting(appVersionEnabled, appVersionLiteral),
        ),
    )

/**
 * What one fetch sends: the HWID, or null when its switch is off, and the device headers to send.
 *
 * M10-B spec §3.5. A header that is switched off, or has no setting at all, is not sent. A literal
 * the user typed wins; otherwise the header follows this device, read now, so an OS or app update
 * changes what is sent. Both pass through [HeaderValue]: OkHttp throws on a non-ASCII header value,
 * and `Build.MODEL` can be one. A value with nothing sendable left drops its header rather than
 * sending it empty. A stored literal was validated when it was saved and is checked again here, so
 * a row written some other way cannot crash a fetch.
 */
internal fun SubscriptionIdentity.resolve(
    device: DeviceInfo,
    appVersion: String,
): Pair<String?, Map<DeviceHeader, String>> {
    val sent =
        DeviceHeader.entries.mapNotNull { header ->
            val setting = headers[header]?.takeIf { it.enabled } ?: return@mapNotNull null
            val value =
                when (val literal = setting.literal) {
                    null -> HeaderValue.sanitiseDevice(device.valueOf(header, appVersion))
                    else -> HeaderValue.validateLiteral(literal)
                }
            value?.let { header to it }
        }
    return (hwid.takeIf { hwidEnabled }) to sent.toMap()
}

private fun DeviceInfo.valueOf(
    header: DeviceHeader,
    appVersion: String,
): String =
    when (header) {
        DeviceHeader.Os -> DEVICE_OS
        DeviceHeader.OsVersion -> osVersion
        DeviceHeader.Model -> model
        DeviceHeader.AppVersion -> appVersion
    }
