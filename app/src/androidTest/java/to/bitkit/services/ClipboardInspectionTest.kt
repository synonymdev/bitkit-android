package to.bitkit.services

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.synonym.bitkitcore.decode
import org.junit.Test
import org.junit.runner.RunWith
import to.bitkit.ext.runSuspendCatching
import to.bitkit.test.BaseAndroidTest
import to.bitkit.test.annotations.CoreServiceIntegration
import to.bitkit.test.annotations.DeviceIntegration
import to.bitkit.utils.ClipboardPromptValidator
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@DeviceIntegration
@CoreServiceIntegration
class ClipboardInspectionTest : BaseAndroidTest() {
    companion object {
        /** LNURL for the owned loopback HTTP fixture at port 33337. */
        private const val LNURL = "lnurl1dp68gup69uhnzv3h9cczuvpwxyarxvenxvmj7mrww4excuqjyxh6d"
        /** Local-only fixture port encoded in LNURL. */
        private const val PORT = 33337
        /** Valid regtest recipient from the native BIP21 fixture. */
        private const val ADDRESS = "bcrt1qr289x0fhg62672e8urudfnxnsr8tcax64xk2vk"
    }

    @Test
    fun inspectionDoesNotContactLnurlBeforeConfirmation() = test {
        ServerSocket(PORT, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 1000
            var decoderCalls = 0
            for (input in listOf(
                LNURL,
                "lightning:$LNURL",
                "bitkit://$LNURL",
                "lightning:bitkit://$LNURL",
                "\u0085$LNURL",
                "bitkit://lightning: $LNURL",
                "bitkit://bitkbitkit://it://$LNURL",
            )) {
                assertTrue(ClipboardPromptValidator.isSupported(input) {
                    decoderCalls++
                    decode(it)
                })
            }
            for ((input, expected) in listOf(
                "bitcoin:$ADDRESS?message=bitcoin:donation&lightning=$LNURL" to true,
                "bitcoin:$ADDRESS?amount=0.0000002bitcoin:$ADDRESS?amount=0.0000003&lightning=$LNURL" to false,
            )) {
                assertEquals(expected, ClipboardPromptValidator.isSupported(input) { decode(it) })
            }
            assertEquals(0, decoderCalls)
            assertFailsWith<SocketTimeoutException> { server.accept() }
        }
    }

    @Test
    fun confirmedNativeDecodeContactsTheSameLnurlFixture() = test {
        ServerSocket(PORT, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 5000
            val requests = AtomicInteger()
            val responder = thread(isDaemon = true) {
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    socket.getInputStream().bufferedReader().lineSequence().takeWhile { it.isNotEmpty() }.count()
                    requests.incrementAndGet()
                    socket.getOutputStream().write(
                        "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(),
                    )
                }
            }
            assertTrue(runSuspendCatching { decode(LNURL) }.isFailure)
            responder.join(5000)
            assertFalse(responder.isAlive)
            assertEquals(1, requests.get())
        }
    }
}
