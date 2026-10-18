// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
@file:Suppress("Filename", "ktlint:standard:filename")

package space.getsub.core.model

// The one home for which `streamSettings.network` spellings name the M9-D2 transports. Parsers, the editor,
// the generator, `StoredProfile.connectable` and the tunnel service all ask here, so a stored row's
// network (kept as its source spelled it, any case) is read the same way everywhere.

/** `kcp` and `mkcp` name the same transport in Xray-core (`infra/conf/transport_internet.go:22-23`), any case. */
public fun isKcpNetwork(network: String): Boolean = network.lowercase().let { it == "kcp" || it == "mkcp" }

/** `httpupgrade`, any case. */
public fun isHttpUpgradeNetwork(network: String): Boolean = network.equals("httpupgrade", ignoreCase = true)
