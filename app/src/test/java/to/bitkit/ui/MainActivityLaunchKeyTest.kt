package to.bitkit.ui

import android.content.Intent
import android.net.Uri
import org.junit.Test
import org.mockito.kotlin.mock
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MainActivityLaunchKeyTest {
    private companion object {
        private const val SAMROCK_SETUP_URL =
            "https://btcpay.example.com/plugins/store/samrock/protocol?setup=btc-chain&otp=secret"
    }

    @Test
    fun `launch key redacts SamRock query values`() {
        val key = assertNotNull(viewIntent(SAMROCK_SETUP_URL).launchKey())

        assertTrue(key.startsWith("https://btcpay.example.com/plugins/store/samrock/protocol#"))
        assertFalse(key.contains("otp"))
        assertFalse(key.contains("secret"))
        assertFalse(key.contains("setup"))
    }

    @Test
    fun `launch key keeps SamRock setup links with different OTPs distinct`() {
        val first = assertNotNull(viewIntent(SAMROCK_SETUP_URL).launchKey())
        val second = assertNotNull(
            viewIntent("https://btcpay.example.com/plugins/store/samrock/protocol?setup=btc-chain&otp=other")
                .launchKey()
        )

        assertNotEquals(first, second)
    }

    @Test
    fun `launch key redacts Bitkit SamRock deeplink query values`() {
        val key = assertNotNull(viewIntent(samRockDeepLink(SAMROCK_SETUP_URL)).launchKey())

        assertTrue(key.startsWith("https://btcpay.example.com/plugins/store/samrock/protocol#"))
        assertFalse(key.contains("otp"))
        assertFalse(key.contains("secret"))
        assertFalse(key.contains("setup"))
    }

    @Test
    fun `launch key ignores non view intents`() {
        val intent = mock<Intent> {
            on { action }.thenReturn(Intent.ACTION_SEND)
        }

        assertNull(intent.launchKey())
    }

    private fun viewIntent(url: String): Intent {
        val uri = mock<Uri> {
            on { toString() }.thenReturn(url)
        }
        return mock {
            on { action }.thenReturn(Intent.ACTION_VIEW)
            on { data }.thenReturn(uri)
        }
    }

    private fun samRockDeepLink(setupUrl: String): String {
        return "bitkit://btcpay/samrock?url=${setupUrl.urlEncode()}"
    }

    private fun String.urlEncode(): String {
        return URLEncoder.encode(this, StandardCharsets.UTF_8.name()).replace("+", "%20")
    }
}
