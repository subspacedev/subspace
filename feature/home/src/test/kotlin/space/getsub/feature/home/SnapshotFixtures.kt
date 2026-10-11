// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.home

import space.getsub.core.data.ProfileKind
import space.getsub.core.data.StoredProfile
import space.getsub.core.model.Security
import space.getsub.core.model.StreamSettings
import space.getsub.core.model.VlessOutbound

/** A typed (generated-config) VLESS profile for goldens. The address is a documentation-range placeholder. */
internal fun snapshotProfile(name: String) =
    StoredProfile(
        id = 1,
        groupId = 1,
        kind = ProfileKind.TYPED,
        name = name,
        protocol = "vless",
        address = "198.51.100.1",
        port = 443,
        transport = "tcp",
        outbound =
        VlessOutbound(
            address = "198.51.100.1",
            port = 443,
            uuid = "1e0f2a2e-6b2b-4b9a-9a3b-000000000000",
            flow = null,
            stream = StreamSettings(network = "tcp", security = Security.None),
        ),
        rawJson = null,
        lastConnectedAt = null,
        lastError = null,
    )
