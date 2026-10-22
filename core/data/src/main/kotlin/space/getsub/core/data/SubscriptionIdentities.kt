// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.data

import space.getsub.core.data.db.SubscriptionEntity
import space.getsub.core.model.identity.DeviceHeader
import space.getsub.core.model.identity.HeaderSetting
import space.getsub.core.model.identity.HeaderValue
import space.getsub.core.model.identity.SubscriptionIdentity
import space.getsub.core.network.DeviceInfo
import space.getsub.core.network.di.AppVersion
import javax.inject.Inject
import javax.inject.Singleton

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

internal fun DeviceInfo.valueOf(
    header: DeviceHeader,
    appVersion: String,
): String =
    when (header) {
        DeviceHeader.Os -> DEVICE_OS
        DeviceHeader.OsVersion -> osVersion
        DeviceHeader.Model -> model
        DeviceHeader.AppVersion -> appVersion
    }

/**
 * What each device header would carry if it followed this device, after the same sanitising a fetch
 * applies (M10-B spec §3.5). The subscription screen shows it as "follows this device: ..." without
 * `:feature:profiles` depending on `:core:network`. A null entry is a value with nothing sendable
 * left, so that header is not sent.
 */
@Singleton
public class DeviceHeaderValues
@Inject
internal constructor(
    private val device: DeviceInfo,
    @param:AppVersion private val appVersion: String,
) {
    /** Read on every call: the values are live, and an OS or app update changes them. */
    public fun sanitised(): Map<DeviceHeader, String?> =
        DeviceHeader.entries.associateWith { HeaderValue.sanitiseDevice(device.valueOf(it, appVersion)) }

    /**
     * The headers whose device value had characters removed to become sendable but still has
     * something left to send. The screen says so (M10-B spec §3.5), as the sanitised value alone
     * would read as the device's own.
     */
    public fun adjusted(): Set<DeviceHeader> =
        DeviceHeader.entries
            .filterTo(mutableSetOf()) {
                val raw = device.valueOf(it, appVersion)
                val sent = HeaderValue.sanitiseDevice(raw)
                sent != null && sent != raw
            }
}
