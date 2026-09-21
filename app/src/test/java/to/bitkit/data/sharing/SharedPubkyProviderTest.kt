package to.bitkit.data.sharing

import org.junit.Test
import to.bitkit.data.keychain.Keychain
import to.bitkit.data.keychain.KeychainError
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SharedPubkyProviderTest {
    companion object {
        private const val WIRE_PUBKY = "3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg"
        private const val SECRET_KEY = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
    }

    @Test
    fun `borrowed active identity without a local secret is never exported`() {
        val identity = localSharedPubkyIdentity(
            exportEnabled = true,
            managedSecretQuarantine = Result.success(false),
            secretKeyHex = null,
            publicKeyFromSecret = { error("Must not derive a borrowed identity") },
        )

        assertNull(identity)
    }

    @Test
    fun `disabled local identity is never exported`() {
        val identity = localSharedPubkyIdentity(
            exportEnabled = false,
            managedSecretQuarantine = Result.success(false),
            secretKeyHex = SECRET_KEY,
            publicKeyFromSecret = { WIRE_PUBKY },
        )

        assertNull(identity)
    }

    @Test
    fun `quarantined managed identity is never exported`() {
        val identity = localSharedPubkyIdentity(
            exportEnabled = true,
            managedSecretQuarantine = Result.success(true),
            secretKeyHex = SECRET_KEY,
            publicKeyFromSecret = { error("Must not derive a quarantined identity") },
        )

        assertNull(identity)
    }

    @Test
    fun `unreadable managed secret quarantine never exports a readable identity`() {
        val identity = localSharedPubkyIdentity(
            exportEnabled = true,
            managedSecretQuarantine = Result.failure(
                KeychainError.FailedToLoad(Keychain.Key.PUBKY_MANAGED_SECRET_QUARANTINED.name),
            ),
            secretKeyHex = SECRET_KEY,
            publicKeyFromSecret = { error("Must not derive identity with unreadable quarantine state") },
        )

        assertNull(identity)
    }

    @Test
    fun `public discovery row excludes the local secret`() {
        val identity = localSharedPubkyIdentity(
            exportEnabled = true,
            managedSecretQuarantine = Result.success(false),
            secretKeyHex = SECRET_KEY,
            publicKeyFromSecret = { "pubky$WIRE_PUBKY" },
        )

        assertEquals(WIRE_PUBKY, identity?.pubky)
        assertContentEquals(
            arrayOf<Any?>(
                SharedPubkyContract.PROTOCOL_VERSION,
                SharedPubkyContract.BITKIT_SOURCE,
                WIRE_PUBKY,
            ),
            identity?.publicRow(),
        )
        assertContentEquals(
            arrayOf<Any?>(
                SharedPubkyContract.PROTOCOL_VERSION,
                SharedPubkyContract.BITKIT_SOURCE,
                WIRE_PUBKY,
                SECRET_KEY,
            ),
            identity?.credentialRow(),
        )
    }
}
