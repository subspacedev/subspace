// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.data.serialization

import space.getsub.core.model.Outbound

/**
 * The stored JSON form of [outbound], for a caller outside this module (the `:service` parcel,
 * which carries Hysteria2's nested fields as one string). Same bytes as the Room column.
 * Contains secrets: never log it (ARCHITECTURE.md §5.6).
 */
public fun encodeOutboundJson(outbound: Outbound): String = outbound.toJson()

/** Inverse of [encodeOutboundJson]; `null` when [json] is not a readable outbound. */
public fun decodeOutboundJson(json: String): Outbound? = json.toOutbound()
