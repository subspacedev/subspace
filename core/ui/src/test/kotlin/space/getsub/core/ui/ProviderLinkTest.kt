// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderLinkTest {
    @Test fun `http and https with a host open`() {
        assertTrue(isOpenableProviderLink("https://example.com/help"))
        assertTrue(isOpenableProviderLink("http://example.com"))
    }

    @Test fun `the scheme is matched case-insensitively`() = assertTrue(isOpenableProviderLink("HTTPS://example.com"))

    @Test fun `other schemes never open`() {
        assertFalse(isOpenableProviderLink("intent://example.com#Intent;scheme=https;end"))
        assertFalse(isOpenableProviderLink("file:///sdcard/x"))
        assertFalse(isOpenableProviderLink("javascript:alert(1)"))
        assertFalse(isOpenableProviderLink("tg://resolve?domain=example"))
    }

    @Test fun `a link with no host or no scheme does not open`() {
        assertFalse(isOpenableProviderLink("https://"))
        assertFalse(isOpenableProviderLink("https:///path"))
        assertFalse(isOpenableProviderLink("example.com/help"))
        assertFalse(isOpenableProviderLink("not a url"))
        assertFalse(isOpenableProviderLink(""))
    }
}
