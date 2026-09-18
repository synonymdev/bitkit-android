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
        assertNull(PubkyRingAuthCallback.parse("bitkit://pubky-auth/setup".toUri()))
        assertNull(PubkyRingAuthCallback.parse("bitkit://pubky-auth/unknown".toUri()))
        assertNull(PubkyRingAuthCallback.parse("bitkit://pubky-auth/success/".toUri()))
        assertNull(PubkyRingAuthCallback.parse("BITKIT://pubky-auth/success".toUri()))
        assertNull(PubkyRingAuthCallback.parse("bitkit://PUBKY-AUTH/success".toUri()))
    }
}
