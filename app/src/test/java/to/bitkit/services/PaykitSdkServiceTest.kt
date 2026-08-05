package to.bitkit.services

import com.synonym.paykit.EncryptedLinkRecoveryMarkerPolicy
import com.synonym.paykit.EndpointManagementScope
import com.synonym.paykit.PubkyClientConfig
import com.synonym.paykit.PublicContactSharingPolicy
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import to.bitkit.data.keychain.Keychain
import to.bitkit.ext.fromHex
import to.bitkit.ext.toHex
import to.bitkit.utils.AppError
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PaykitSdkServiceTest {
    private val basePubkyClientConfig = PubkyClientConfig(
        requestTimeoutSecs = 30uL,
        localTestnetHost = null,
    )

    @Test
    fun `config scopes public endpoint sync to Bitkit managed endpoints`() {
        assertEquals(EndpointManagementScope.MANAGED_ONLY, BitkitPaykitSdkConfig.endpointManagementScope)
        assertEquals(PublicContactSharingPolicy.LOCAL_ONLY, BitkitPaykitSdkConfig.publicContactSharing)
        assertEquals(EncryptedLinkRecoveryMarkerPolicy.ENABLED, BitkitPaykitSdkConfig.encryptedLinkRecoveryMarkers)
    }

    @Test
    fun `production preserves Pubky client config`() {
        val config = paykitPubkyClientConfig(
            isLocalE2eBackend = false,
            baseConfig = basePubkyClientConfig,
        )

        assertEquals(basePubkyClientConfig, config)
    }

    @Test
    fun `local E2E uses emulator host for Pubky testnet`() {
        val config = paykitPubkyClientConfig(
            isLocalE2eBackend = true,
            baseConfig = basePubkyClientConfig,
        )

        assertEquals("10.0.2.2", config.localTestnetHost)
        assertEquals(basePubkyClientConfig.requestTimeoutSecs, config.requestTimeoutSecs)
    }

    @Test
    fun `receiver noise derivation matches cross platform vector`() {
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
    fun `receiver noise key is persisted and reused`() {
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

        assertContentEquals(first, persistedBytes)
        assertContentEquals(first, second)
        assertContentEquals(first, restored)
        assertEquals("PAYKIT_RECEIVER_NOISE_SECRET_KEY", Keychain.Key.PAYKIT_RECEIVER_NOISE_SECRET_KEY.name)
    }

    @Test
    fun `receiver noise key loads for external session without wallet seed`() {
        val persistedBytes = ByteArray(32) { 7 }
        val store = keyStore(
            loadBytes = { persistedBytes },
            deriveBytes = { throw AppError("wallet seed unavailable") },
        )

        assertContentEquals(persistedBytes, store.loadOrDeriveBytes())
    }

    @Test
    fun `receiver noise key cannot be replaced`() {
        val store = keyStore(
            loadBytes = { ByteArray(32) { 1 } },
            deriveBytes = { ByteArray(32) { 1 } },
        )

        assertFailsWith<AppError> {
            store.persistBytes(ByteArray(32) { 2 })
        }
    }

    @Test
    fun `receiver noise key follows wallet replacement after keychain wipe`() {
        var persistedBytes: ByteArray? = null
        var derivedBytes = ByteArray(32) { 1 }
        val store = keyStore(
            loadBytes = { persistedBytes },
            upsertBytes = { persistedBytes = it.copyOf() },
            deriveBytes = { derivedBytes },
        )
        store.loadOrDeriveBytes()

        persistedBytes = null
        derivedBytes = ByteArray(32) { 2 }

        assertContentEquals(derivedBytes, store.loadOrDeriveBytes())
        assertContentEquals(derivedBytes, persistedBytes)
    }

    @Test
    fun `external session retains private payment access`() {
        val keychain = mock<Keychain>()
        val sessionSecret = "external-session"
        val provider = PaykitSdkSessionProvider(keychain)
        whenever(keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)).thenReturn(sessionSecret)
        whenever(keychain.loadString(Keychain.Key.PUBKY_SECRET_KEY.name)).thenReturn(null)

        assertTrue(provider.hasSessionAccess())
        assertNull(provider.loadLocalSecretKey())
    }

    private fun keyStore(
        loadBytes: () -> ByteArray?,
        upsertBytes: (ByteArray) -> Unit = {},
        deriveBytes: () -> ByteArray,
    ) = PaykitReceiverNoiseKeyStore(loadBytes, upsertBytes, deriveBytes)
}
