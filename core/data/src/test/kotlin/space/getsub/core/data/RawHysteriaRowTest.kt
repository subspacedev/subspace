// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.data

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.Test
import space.getsub.core.data.db.ProfileEntity
import space.getsub.core.data.serialization.toJson
import space.getsub.core.data.serialization.toOutbound
import space.getsub.core.model.Hysteria2Outbound
import space.getsub.core.parser.SubscriptionParser

/**
 * The service keys "this is a UDP server" off a stored row's decoded outbound, so a raw `hysteria` row has
 * to come back out of storage as [Hysteria2Outbound], not null and not something else.
 */
class RawHysteriaRowTest {
    private val raw =
        """{"remarks":"Raw","outbounds":[{"protocol":"hysteria","tag":"proxy",
        "settings":{"version":2,"address":"h.example","port":8443},
        "streamSettings":{"network":"hysteria","hysteriaSettings":{"version":2,"auth":"s3cret"},
        "security":"tls","tlsSettings":{"serverName":"sni.example","alpn":["h3"]}}}]}"""

    @Test
    fun aStoredRawHysteriaRowDecodesToAHysteria2Outbound() {
        val profile = SubscriptionParser.parse(raw).profiles.single()
        val entity =
            ProfileEntity(
                groupId = 1,
                kind = ProfileKind.RAW_JSON.name,
                identityHash = "h",
                name = profile.name,
                protocol = "hysteria2",
                address = profile.outbound.address,
                port = profile.outbound.port,
                transport = "",
                outbound = profile.outbound.toJson(),
                rawJson = profile.rawJson,
                position = 0,
                lastConnectedAt = null,
                lastError = null,
                createdAt = 0,
            )
        val stored =
            StoredProfile(
                id = 1,
                groupId = 1,
                kind = ProfileKind.RAW_JSON,
                name = entity.name,
                protocol = entity.protocol,
                address = entity.address,
                port = entity.port,
                transport = entity.transport,
                outbound = entity.outbound.toOutbound(),
                rawJson = entity.rawJson,
                lastConnectedAt = null,
                lastError = null,
            )

        stored.rawJson.shouldNotBeNull()
        val outbound = stored.toProfile().shouldNotBeNull().outbound
        outbound.shouldBeInstanceOf<Hysteria2Outbound>().address shouldBe "h.example"
    }
}
