// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.network

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Test
import org.junit.runner.RunWith
import space.getsub.core.model.FragmentPackets
import space.getsub.core.model.FragmentSpec
import space.getsub.core.model.XrayRange
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * M9-B spec §3.2's unverified claim: Conscrypt, layered over a socket from our factory, writes the
 * ClientHello through that socket's OutputStream. If this fails, the fetcher's fragmentation cannot
 * work as designed. Stop and report, do not paper over it.
 */
@RunWith(AndroidJUnit4::class)
class FragmentingTlsProbeTest {
    private data class Read(val atNanos: Long, val bytes: ByteArray)

    @Test
    fun clientHelloLeavesInRecordFramedPiecesWithGaps() {
        val reads = CopyOnWriteArrayList<Read>()
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val reader =
            thread {
                server.accept().use { socket ->
                    socket.soTimeout = 3_000
                    val input = socket.getInputStream()
                    val buffer = ByteArray(4096)
                    runCatching {
                        while (reads.size < 6) {
                            val n = input.read(buffer)
                            if (n <= 0) break
                            reads += Read(System.nanoTime(), buffer.copyOf(n))
                        }
                    }
                }
            }

        val spec =
            FragmentSpec(FragmentPackets.TlsHello, XrayRange(20, 20), XrayRange(60, 60), maxSplit = null)
        val client =
            OkHttpClient.Builder()
                .socketFactory(FragmentingSocketFactory(spec))
                .callTimeout(4, TimeUnit.SECONDS)
                .build()
        runCatching {
            client.newCall(Request.Builder().url("https://127.0.0.1:${server.localPort}/").build()).execute().close()
        }
        reader.join(5_000)
        server.close()

        reads.size shouldBeGreaterThanOrEqual 2
        reads[0].bytes[0] shouldBe 0x16.toByte()
        reads[0].bytes.size shouldBe 25
        val gapMillis = (reads[1].atNanos - reads[0].atNanos) / 1_000_000
        gapMillis shouldBeGreaterThanOrEqual 40L
    }
}
