// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.xray

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class LibXrayInvokeRequestTest {
    @Test
    fun `requestUsesApiVersion3AndHasNoEnvKey`() {
        val request =
            Json
                .parseToJsonElement(LibXrayInvoke.request("testXray", """{"configPath":"/c.json"}"""))
                .jsonObject

        request.getValue("apiVersion").jsonPrimitive.int shouldBe 3
        request.getValue("method").jsonPrimitive.content shouldBe "testXray"
        request.containsKey("env") shouldBe false
        request.getValue("payload").jsonObject.getValue("configPath").jsonPrimitive.content shouldBe "/c.json"
    }

    @Test
    fun `a request without a payload has no payload key`() {
        val request = Json.parseToJsonElement(LibXrayInvoke.request("stopXray", null)).jsonObject
        request.containsKey("payload") shouldBe false
    }
}
