package to.bitkit.services

import com.synonym.paykit.EncryptedLinkRecoveryMarkerPolicy
import com.synonym.paykit.EndpointManagementScope
import com.synonym.paykit.PublicContactSharingPolicy
import org.junit.Test
import to.bitkit.data.keychain.Keychain
import to.bitkit.ext.fromHex
import to.bitkit.ext.toHex
import to.bitkit.utils.AppError
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PaykitSdkServiceTest {
    @Test
    fun `config scopes public endpoint sync to Bitkit managed endpoints`() {
        assertEquals(EndpointManagementScope.MANAGED_ONLY, BitkitPaykitSdkConfig.endpointManagementScope)
        assertEquals(PublicContactSharingPolicy.LOCAL_ONLY, BitkitPaykitSdkConfig.publicContactSharing)
        assertEquals(EncryptedLinkRecoveryMarkerPolicy.ENABLED, BitkitPaykitSdkConfig.encryptedLinkRecoveryMarkers)
    }

    @Test
    fun `receiver noise derivation matches versioned cross platform vector`() {
        val seed = (
            "c55257c360c07c72029aebc1b53c05ed0362ada38ead3e3e9efa3708e534955" +
                "31f09a6987599d18264c1e1c92f2cf141630c7a3c4ab7c81b2f001698e7463b04"
            ).fromHex()

        val key = PaykitReceiverNoiseKeyDerivation.derive(
            seed = seed,
            network = "bitcoin",
            receiverPath = "bitkit/wallet",
        )

        assertEquals("500f4799bbb2d02103e3b74b365ddb478a3187333c053fa9eb62f4052ba6a327", key.toHex())
    }

    @Test
    fun `receiver noise key is derived persisted and reused`() {
        var persistedBytes: ByteArray? = null
        val derivedBytes = ByteArray(32) { 7 }
        val store = keyStore(
            loadBytes = { persistedBytes },
            upsertBytes = { persistedBytes = it.copyOf() },
            deriveBytes = { derivedBytes },
        )

        val first = store.loadOrDeriveBytes()
        val second = store.loadOrDeriveBytes()
        val restored = keyStore(
            loadBytes = { persistedBytes },
            deriveBytes = { derivedBytes },
        ).loadOrDeriveBytes()

        assertEquals(32, first.size)
        assertContentEquals(first, persistedBytes)
        assertContentEquals(first, second)
        assertContentEquals(first, restored)
        assertEquals("PAYKIT_RECEIVER_NOISE_SECRET_KEY", Keychain.Key.PAYKIT_RECEIVER_NOISE_SECRET_KEY.name)
    }

    @Test
    fun `receiver noise key cannot be replaced`() {
        val persistedBytes = ByteArray(32) { 1 }
        val store = keyStore(
            loadBytes = { persistedBytes },
            deriveBytes = { ByteArray(32) { 1 } },
        )

        assertFailsWith<AppError> {
            store.persistBytes(ByteArray(32) { 2 })
        }
        assertContentEquals(ByteArray(32) { 1 }, persistedBytes)
    }

    @Test
    fun `invalid persisted receiver noise key fails closed`() {
        val store = keyStore(
            loadBytes = { ByteArray(31) },
            deriveBytes = { ByteArray(32) },
        )

        assertFailsWith<AppError> { store.loadOrDeriveBytes() }
    }

    @Test
    fun `cached receiver noise key from another wallet seed fails closed`() {
        val store = keyStore(
            loadBytes = { ByteArray(32) { 1 } },
            deriveBytes = { ByteArray(32) { 2 } },
        )

        assertFailsWith<AppError> { store.loadOrDeriveBytes() }
    }

    @Test
    fun `owned session requires and persists its exported local secret`() {
        val secretKeyHex = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"

        assertEquals(
            secretKeyHex,
            managedSecretForSessionPersistence(
                shouldStoreLocalSecret = true,
                exportedLocalSecretKeyHex = secretKeyHex,
                existingManagedSecretKeyHex = null,
            ),
        )
        assertFailsWith<IllegalArgumentException> {
            managedSecretForSessionPersistence(
                shouldStoreLocalSecret = true,
                exportedLocalSecretKeyHex = null,
                existingManagedSecretKeyHex = null,
            )
        }
        assertFailsWith<IllegalStateException> {
            managedSecretForSessionPersistence(
                shouldStoreLocalSecret = true,
                exportedLocalSecretKeyHex = secretKeyHex,
                existingManagedSecretKeyHex = "different-secret",
            )
        }
    }

    @Test
    fun `external session refuses to replace a managed local secret`() {
        assertNull(
            managedSecretForSessionPersistence(
                shouldStoreLocalSecret = false,
                exportedLocalSecretKeyHex = null,
                existingManagedSecretKeyHex = null,
            ),
        )
        assertFailsWith<IllegalStateException> {
            managedSecretForSessionPersistence(
                shouldStoreLocalSecret = false,
                exportedLocalSecretKeyHex = null,
                existingManagedSecretKeyHex = "managed-secret",
            )
        }
    }

    private fun keyStore(
        loadBytes: () -> ByteArray?,
        upsertBytes: (ByteArray) -> Unit = {},
        deriveBytes: () -> ByteArray,
    ) = PaykitReceiverNoiseKeyStore(loadBytes, upsertBytes, deriveBytes)
}
