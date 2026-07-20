package to.bitkit.services

import com.synonym.paykit.EncryptedLinkRecoveryMarkerPolicy
import com.synonym.paykit.EndpointManagementScope
import com.synonym.paykit.PublicContactSharingPolicy
import org.junit.Test
import to.bitkit.data.keychain.Keychain
import to.bitkit.utils.AppError
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PaykitSdkServiceTest {
    @Test
    fun `config scopes public endpoint sync to Bitkit managed endpoints`() {
        assertEquals(EndpointManagementScope.MANAGED_ONLY, BitkitPaykitSdkConfig.endpointManagementScope)
        assertEquals(PublicContactSharingPolicy.LOCAL_ONLY, BitkitPaykitSdkConfig.publicContactSharing)
        assertEquals(EncryptedLinkRecoveryMarkerPolicy.ENABLED, BitkitPaykitSdkConfig.encryptedLinkRecoveryMarkers)
    }

    @Test
    fun `receiver noise key is generated persisted and reused`() {
        var persistedBytes: ByteArray? = null
        val store = keyStore(
            loadBytes = { persistedBytes },
            upsertBytes = { persistedBytes = it.copyOf() },
        )

        val first = store.loadOrCreateBytes { ByteArray(32) { 7 } }
        val second = store.loadOrCreateBytes { error("Existing key must be reused") }
        val restored = keyStore(loadBytes = { persistedBytes })
            .loadOrCreateBytes { error("Persisted key must be reused") }

        assertEquals(32, first.size)
        assertContentEquals(first, persistedBytes)
        assertContentEquals(first, second)
        assertContentEquals(first, restored)
        assertEquals("PAYKIT_RECEIVER_NOISE_SECRET_KEY", Keychain.Key.PAYKIT_RECEIVER_NOISE_SECRET_KEY.name)
    }

    @Test
    fun `receiver noise key cannot be replaced`() {
        val persistedBytes = ByteArray(32) { 1 }
        val store = keyStore(loadBytes = { persistedBytes })

        assertFailsWith<AppError> {
            store.persistBytes(ByteArray(32) { 2 })
        }
        assertContentEquals(ByteArray(32) { 1 }, persistedBytes)
    }

    @Test
    fun `invalid persisted receiver noise key fails closed`() {
        val store = keyStore(loadBytes = { ByteArray(31) })

        assertFailsWith<AppError> { store.loadOrCreateBytes { ByteArray(32) } }
    }

    private fun keyStore(
        loadBytes: () -> ByteArray?,
        upsertBytes: (ByteArray) -> Unit = {},
    ) = PaykitReceiverNoiseKeyStore(loadBytes, upsertBytes)
}
