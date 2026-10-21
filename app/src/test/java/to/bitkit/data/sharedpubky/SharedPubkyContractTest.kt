package to.bitkit.data.sharedpubky

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SharedPubkyContractTest {
    companion object {
        private const val SECRET_KEY_HEX = "8f1a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f708192a3b4c5d6e7f8"
        private const val PUBKY = "3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg"
        private const val OTHER_PUBKY = "1rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg"
    }

    @Test
    fun `isValidSecret accepts a secret deriving the pubky`() {
        assertTrue(SharedPubkyContract.isValidSecret(SECRET_KEY_HEX, PUBKY) { PUBKY })
        assertTrue(SharedPubkyContract.isValidSecret(SECRET_KEY_HEX, "pubky$PUBKY") { PUBKY })
    }

    @Test
    fun `isValidSecret rejects a malformed secret`() {
        assertFalse(SharedPubkyContract.isValidSecret(SECRET_KEY_HEX.drop(2), PUBKY) { PUBKY })
        assertFalse(SharedPubkyContract.isValidSecret(SECRET_KEY_HEX.uppercase(), PUBKY) { PUBKY })
        assertFalse(SharedPubkyContract.isValidSecret("z".repeat(SECRET_KEY_HEX.length), PUBKY) { PUBKY })
    }

    @Test
    fun `isValidSecret rejects a secret deriving another pubky`() {
        assertFalse(SharedPubkyContract.isValidSecret(SECRET_KEY_HEX, OTHER_PUBKY) { PUBKY })
    }

    @Test
    fun `isValidSecret rejects a secret the sdk cannot derive`() {
        assertFalse(SharedPubkyContract.isValidSecret(SECRET_KEY_HEX, PUBKY) { error("invalid secret") })
    }
}
