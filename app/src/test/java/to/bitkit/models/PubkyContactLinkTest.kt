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
class PubkyContactLinkTest {
    private val key = "pubky3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg"

    @Test
    fun `accepts raw prefixed and encoded keys`() {
        listOf(key.removePrefix("pubky"), key, key.uppercase(), key.replace("pubky", "%70ubky")).forEach { value ->
            assertEquals(key, PubkyContactLink.publicKey("bitkit://contact?pubky=$value".toUri()))
        }
    }

    @Test
    fun `rejects malformed links and non-key payloads`() {
        listOf(
            "https://contact?pubky=$key",
            "bitkit://other?pubky=$key",
            "bitkit://user@contact?pubky=$key",
            "bitkit://contact:123?pubky=$key",
            "bitkit://contact:invalid?pubky=$key",
            "bitkit://contact/path?pubky=$key",
            "bitkit://contact?pubky=$key#fragment",
            "bitkit://contact",
            "bitkit://contact?pubky=",
            "bitkit://contact?pubky=$key&pubky=$key",
            "bitkit://contact?pubky=$key&other=value",
            "bitkit://contact?pubky=${key}extra",
            "bitkit://contact?pubky=invalid",
            "bitkit://contact?pubky=bitcoin%3Abc1example",
            "bitkit://contact?pubky=pubkyauth%3A%2F%2Fsignin_grant",
        ).forEach { link ->
            assertNull(PubkyContactLink.publicKey(link.toUri()), link)
        }
    }
}
