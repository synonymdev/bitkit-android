package to.bitkit.services

import com.synonym.paykit.IdentityStatus
import com.synonym.paykit.PaykitAppRegistry
import com.synonym.paykit.PaykitIdentitySecretKey
import com.synonym.paykit.PaykitSdk
import com.synonym.paykit.PubkyIdentityCapability
import com.synonym.paykit.PubkyLocalSecretKey
import com.synonym.paykit.PubkySessionAccess
import com.synonym.paykit.pubkyPublicKeyFromSecret
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.any
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.data.keychain.Keychain
import to.bitkit.ext.toHex
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class PaykitKeyGenerationTest {
    companion object {
        /** Fixture identity for generation-scoped storage keys. */
        private const val RING_PUBKY = "3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg"
    }

    @Test
    fun `authorization derives the registry generation and persists its identity scoped floor`() = runTest {
        for (registryGeneration in listOf(null, 7uL)) {
            val generation = registryGeneration ?: 1uL
            val registry = registryGeneration?.let {
                mock<PaykitAppRegistry> { on { keyGeneration }.thenReturn(generation) }
            }
            val sdk = mock<PaykitSdk>()
            whenever(sdk.paykitAppRegistry(RING_PUBKY)).thenReturn(registry)
            val keychain = mock<Keychain>()
            val storageKey = "${Keychain.Key.PAYKIT_KEY_GENERATION.name}:$RING_PUBKY"
            whenever(keychain.loadString(storageKey)).thenReturn(null, generation.toString())
            val key = mock<PaykitIdentitySecretKey>()
            val bytes = ByteArray(32) { 1 }
            val service = PaykitSdkService(mock(), keychain, mock()) { sdk }

            mockStatic(Class.forName("com.synonym.paykit.Paykit_androidKt")).use { native ->
                native.`when`<String> { pubkyPublicKeyFromSecret(any()) }.thenReturn(RING_PUBKY)
                mockConstruction(PubkyLocalSecretKey::class.java) { root, context ->
                    assertContentEquals(bytes, context.arguments().single() as ByteArray)
                    whenever(root.derivePaykitIdentitySecretKey(generation)).thenReturn(key)
                }.use { roots ->
                    repeat(2) {
                        assertSame(key, service.paykitKeyForAuthorization(bytes.toHex()))
                    }
                    assertEquals(2, roots.constructed().size)
                    roots.constructed().forEach { verify(it).derivePaykitIdentitySecretKey(generation) }
                    verify(keychain).upsertString(storageKey, generation.toString())
                    verify(sdk, times(2)).paykitAppRegistry(RING_PUBKY)
                }
            }
        }
    }

    @Test
    fun `stored root refresh rotates the session key and rejects registry rollback`() = runTest {
        val initialRegistry = mock<PaykitAppRegistry> { on { keyGeneration }.thenReturn(2uL) }
        val rotatedRegistry = mock<PaykitAppRegistry> { on { keyGeneration }.thenReturn(3uL) }
        val sdk = mock<PaykitSdk>()
        whenever(sdk.paykitAppRegistry(RING_PUBKY))
            .thenReturn(initialRegistry, rotatedRegistry, initialRegistry, null)
        whenever(sdk.identityStatus())
            .thenReturn(IdentityStatus(RING_PUBKY, PubkyIdentityCapability.PRIVATE_LINK_CAPABLE))
        val keychain = mock<Keychain>()
        val storageKey = "${Keychain.Key.PAYKIT_KEY_GENERATION.name}:$RING_PUBKY"
        whenever(keychain.loadString(storageKey)).thenReturn("2", "2", "3", "3")
        val root = mock<PubkyLocalSecretKey>()
        val initialKey = mock<PaykitIdentitySecretKey>()
        val rotatedKey = mock<PaykitIdentitySecretKey>()
        whenever(root.derivePaykitIdentitySecretKey(2uL)).thenReturn(initialKey)
        whenever(root.derivePaykitIdentitySecretKey(3uL)).thenReturn(rotatedKey)

        mockStatic(Class.forName("com.synonym.paykit.Paykit_androidKt")).use { native ->
            native.`when`<String> { pubkyPublicKeyFromSecret(root) }.thenReturn(RING_PUBKY)
            mockConstruction(PaykitSdkSessionProvider::class.java) { provider, _ ->
                whenever(provider.loadLocalSecretKey()).thenReturn(root)
            }.use { providers ->
                val service = PaykitSdkService(mock(), keychain, mock()) { sdk }
                val provider = providers.constructed().single()
                repeat(2) { assertEquals(RING_PUBKY, service.currentPublicKey()) }
                repeat(2) {
                    val error = assertFailsWith<IllegalStateException> { service.currentPublicKey() }
                    assertEquals("The Paykit App Registry has an older key generation", error.message)
                }

                inOrder(provider, keychain, root) {
                    verify(root).derivePaykitIdentitySecretKey(2uL)
                    verify(provider).setPaykitIdentitySecretKey(initialKey)
                    verify(keychain).upsertString(storageKey, "3")
                    verify(root).derivePaykitIdentitySecretKey(3uL)
                    verify(provider).setPaykitIdentitySecretKey(rotatedKey)
                }
                verify(root, times(2)).derivePaykitIdentitySecretKey(any())
                verify(keychain).upsertString(any(), any())
                verify(sdk, times(2)).identityStatus()
                verify(provider, times(2)).setPaykitIdentitySecretKey(any())
            }
        }
    }

    @Test
    fun `session key rotation rebuilds cached session access with the refreshed key`() {
        val keychain = mock<Keychain>()
        whenever(keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)).thenReturn("saved-session")
        val initialKey = mock<PaykitIdentitySecretKey> { on { keyGeneration() }.thenReturn(2uL) }
        val rotatedKey = mock<PaykitIdentitySecretKey> { on { keyGeneration() }.thenReturn(3uL) }
        val initialAccess = mock<PubkySessionAccess>()
        whenever(initialAccess.exportSessionSecret()).thenReturn("saved-session")
        whenever(initialAccess.exportPaykitIdentitySecretKey()).thenReturn(initialKey)
        val provider = PaykitSdkSessionProvider(keychain, mock())
        provider.setLiveSessionAccess(initialAccess)
        provider.setPaykitIdentitySecretKey(initialKey)
        assertSame(initialAccess, provider.loadSessionAccess())

        mockConstruction(PubkySessionAccess::class.java) { access, context ->
            assertEquals(BitkitPaykitSdkConfig.clientId, context.arguments()[0])
            assertEquals("saved-session", context.arguments()[1])
            assertSame(rotatedKey, context.arguments()[3])
            whenever(access.exportSessionSecret()).thenReturn("saved-session")
        }.use { sessions ->
            provider.setPaykitIdentitySecretKey(rotatedKey)
            val refreshedAccess = provider.loadSessionAccess()
            assertSame(sessions.constructed().single(), refreshedAccess)
            assertSame(refreshedAccess, provider.loadSessionAccess())
            assertEquals(1, sessions.constructed().size)
        }
    }
}
