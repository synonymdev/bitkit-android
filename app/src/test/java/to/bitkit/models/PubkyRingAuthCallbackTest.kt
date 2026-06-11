package to.bitkit.models

import androidx.core.net.toUri
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PubkyRingAuthCallbackTest {
    @Test
    fun `addCallbacks adds Ring x-callback params`() {
        val url = checkNotNull(
            PubkyRingAuthUrlBuilder.addCallbacks(
                authUrl = "pubkyauth://auth?relay=https%3A%2F%2Frelay.example",
                nonce = "12345678-1234-1234-1234-123456789ABC",
            ),
        ) { "Auth URL should be valid" }
        val uri = url.toUri()

        assertEquals("https://relay.example", uri.getQueryParameter("relay"))
        assertEquals(
            "bitkit://pubky-auth/success?nonce=12345678-1234-1234-1234-123456789ABC",
            uri.getQueryParameter("x-success"),
        )
        assertEquals(
            "bitkit://pubky-auth/cancel?nonce=12345678-1234-1234-1234-123456789ABC",
            uri.getQueryParameter("x-cancel"),
        )
        assertEquals(
            "bitkit://pubky-auth/error?nonce=12345678-1234-1234-1234-123456789ABC",
            uri.getQueryParameter("x-error"),
        )
        assertEquals(PubkyRingAuthUrlBuilder.SOURCE, uri.getQueryParameter("x-source"))
    }

    @Test
    fun `parse returns success cancel and error callbacks`() {
        assertEquals(
            PubkyRingAuthCallback.Success(nonce = null),
            PubkyRingAuthCallback.parse("bitkit://pubky-auth/success".toUri()),
        )
        assertEquals(
            PubkyRingAuthCallback.Cancel(nonce = null),
            PubkyRingAuthCallback.parse("bitkit://pubky-auth/cancel".toUri()),
        )
        assertEquals(
            PubkyRingAuthCallback.Error(message = "Denied", nonce = null),
            PubkyRingAuthCallback.parse("bitkit://pubky-auth/error?errorMessage=Denied".toUri()),
        )
    }

    @Test
    fun `parse returns nonce when callback includes value`() {
        assertEquals(
            PubkyRingAuthCallback.Error(message = "Denied", nonce = "abc"),
            PubkyRingAuthCallback.parse("bitkit://pubky-auth/error?nonce=abc&errorMessage=Denied".toUri()),
        )
    }

    @Test
    fun `parse treats bare nonce as missing`() {
        assertEquals(
            PubkyRingAuthCallback.Cancel(nonce = null),
            PubkyRingAuthCallback.parse("bitkit://pubky-auth/cancel?nonce".toUri()),
        )
    }

    @Test
    fun `parse rejects other deeplinks`() {
        assertNull(PubkyRingAuthCallback.parse("bitkit://wallet/success".toUri()))
        assertNull(PubkyRingAuthCallback.parse("https://pubky-auth/success".toUri()))
    }
}
