// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.profiles.add

import io.kotest.matchers.shouldBe
import org.junit.Test

/** The single-input router: only "the whole input is one http(s) URL" is a subscription (M8.6 Part 3 Task 7b). */
class RouteImportTest {
    private val vless = "vless://11111111-1111-1111-1111-111111111111@198.51.100.7:443?security=none#a"

    @Test
    fun `blank input has no route`() {
        routeImport("") shouldBe null
        routeImport("  \n\t ") shouldBe null
    }

    @Test
    fun `a lone https url is a subscription`() {
        routeImport("https://example.com/sub/abc") shouldBe ImportRoute.Subscription("https://example.com/sub/abc")
    }

    @Test
    fun `surrounding whitespace is trimmed from the subscription url`() {
        routeImport("  https://example.com/sub\n") shouldBe ImportRoute.Subscription("https://example.com/sub")
    }

    @Test
    fun `a lone http url is a subscription`() {
        routeImport("http://example.com/sub") shouldBe ImportRoute.Subscription("http://example.com/sub")
    }

    @Test
    fun `a share link is parsed`() {
        routeImport(vless) shouldBe ImportRoute.Parse(vless)
    }

    @Test
    fun `several share links are parsed`() {
        val raw = "$vless\n$vless"
        routeImport(raw) shouldBe ImportRoute.Parse(raw)
    }

    @Test
    fun `xray json is parsed`() {
        val raw = """{"outbounds":[{"protocol":"vless"}]}"""
        routeImport(raw) shouldBe ImportRoute.Parse(raw)
    }

    @Test
    fun `base64 is parsed`() {
        routeImport("dmxlc3M6Ly94QDE5OC41MS4xMDAuNzo0NDM=") shouldBe
            ImportRoute.Parse("dmxlc3M6Ly94QDE5OC41MS4xMDAuNzo0NDM=")
    }

    @Test
    fun `links plus a url line are parsed, not guessed at`() {
        val raw = "$vless\nhttps://example.com/sub"
        routeImport(raw) shouldBe ImportRoute.Parse(raw)
    }

    @Test
    fun `a url with no host is parsed so the parser reports it`() {
        routeImport("https://") shouldBe ImportRoute.Parse("https://")
    }

    @Test
    fun `a non-http scheme is parsed`() {
        routeImport("file:///etc/passwd") shouldBe ImportRoute.Parse("file:///etc/passwd")
    }
}
