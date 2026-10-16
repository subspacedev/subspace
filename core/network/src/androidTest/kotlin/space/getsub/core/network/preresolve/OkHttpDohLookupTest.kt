// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.network.preresolve

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.kotest.matchers.collections.shouldNotBeEmpty
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OkHttpDohLookupTest {
    @Test
    fun cloudflareAnswersOverTheBootstrapAddress(): Unit = runBlocking {
        val lookup =
            OkHttpDohLookupFactory { true }
                .create(ResolverConfig("https://cloudflare-dns.com/dns-query", "1.1.1.1", fragment = null))
        lookup.lookup("example.com").shouldNotBeEmpty()
    }
}
