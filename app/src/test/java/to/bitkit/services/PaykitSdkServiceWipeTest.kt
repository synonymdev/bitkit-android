package to.bitkit.services

import com.synonym.paykit.PaykitException
import com.synonym.paykit.PaykitSdk
import com.synonym.paykit.PaykitSdkConfig
import com.synonym.paykit.PubkySessionAccess
import com.synonym.paykit.PubkySessionBootstrap
import com.synonym.paykit.PubkySessionBootstrapResult
import com.synonym.paykit.ReceiverNoiseSecretKey
import com.synonym.paykit.SdkStateBlob
import com.synonym.paykit.SdkStateBlobSnapshot
import com.synonym.paykit.SdkStateBlobStore
import com.synonym.paykit.decodeSdkStateBlobSnapshot
import com.synonym.paykit.defaultConfig
import com.synonym.paykit.encodeSdkStateBlobSnapshot
import com.synonym.paykit.requiredSessionCapabilities
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.data.PubkyStore
import to.bitkit.data.PubkyStoreData
import to.bitkit.data.keychain.Keychain
import to.bitkit.data.keychain.KeychainError
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame

class PaykitSdkServiceWipeTest {
    companion object {
        private const val RING_PUBKY = "3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg"
    }

    private val sdkConfig = PaykitSdkConfig(
        receiverPath = PaykitReceiverPaths.WALLET,
        profileNamespace = BitkitPaykitSdkConfig.profileNamespace,
        endpointManagementScope = BitkitPaykitSdkConfig.endpointManagementScope,
        encryptedLinkRecoveryMarkers = BitkitPaykitSdkConfig.encryptedLinkRecoveryMarkers,
        publicContactSharing = BitkitPaykitSdkConfig.publicContactSharing,
        peerLinkOperationLeaseTimeoutSecs = 1uL,
        outboundPrivateSendLeaseTimeoutSecs = 1uL,
        outboundPrivateRetryBackoffSecs = 1uL,
    )

    @Test
    fun `state storage callbacks translate keychain failures and recover on retry`() {
        val keychain = mock<Keychain>()
        val blocking = mock<Keychain.BlockingAccess>()
        whenever(keychain.accessBlocking<Any?>(any())).doAnswer {
            it.getArgument<Keychain.BlockingAccess.() -> Any?>(0).invoke(blocking)
        }
        val service = PaykitSdkService(mock(), keychain, mock()) { mock() }
        val store = PaykitSdkService::class.java.getDeclaredField("stateStore")
            .apply { isAccessible = true }.get(service) as SdkStateBlobStore
        val key = Keychain.Key.PAYKIT_SDK_STATE.name
        whenever(blocking.load(key)).thenAnswer { throw KeychainError.FailedToLoad(key) }.thenReturn(null)

        assertEquals("state_load_failed", assertFailsWith<PaykitException.Storage> { store.loadStateBlob() }.code)
        assertNull(store.loadStateBlob())

        val blob = mock<SdkStateBlob>()
        val encoded = byteArrayOf(1, 2, 3)
        lateinit var snapshot: SdkStateBlobSnapshot
        mockStatic(Class.forName("com.synonym.paykit.Paykit_androidKt")).use { native ->
            native.`when`<ByteArray> { encodeSdkStateBlobSnapshot(any()) }.thenAnswer {
                snapshot = it.getArgument(0)
                encoded
            }
            native.`when`<SdkStateBlobSnapshot> { decodeSdkStateBlobSnapshot(encoded) }.thenAnswer { snapshot }
            whenever(blocking.upsert(key, encoded)).thenAnswer { throw KeychainError.FailedToSave(key) }.thenAnswer {
                whenever(blocking.load(key)).thenReturn(encoded)
            }

            assertEquals(
                "state_save_failed",
                assertFailsWith<PaykitException.Storage> { store.saveStateBlobAtomically(blob, null) }.code,
            )
            assertNull(store.loadStateBlob())
            val revision = store.saveStateBlobAtomically(blob, null)
            val restored = store.loadStateBlob()
            assertSame(blob, restored?.blob)
            assertEquals(revision, restored?.revision)
            assertEquals(
                "revision_conflict",
                assertFailsWith<PaykitException.Storage> { store.saveStateBlobAtomically(blob, null) }.code,
            )
        }
    }

    @Test
    fun `session storage callbacks translate keychain failures and recover on retry`() {
        val keychain = mock<Keychain>()
        val blocking = mock<Keychain.BlockingAccess>()
        whenever(keychain.accessBlocking<Any?>(any())).doAnswer {
            it.getArgument<Keychain.BlockingAccess.() -> Any?>(0).invoke(blocking)
        }
        val provider = PaykitSdkSessionProvider(keychain, mock())
        val session = mock<PubkySessionAccess>()
        whenever(session.exportSessionSecret()).thenReturn("saved-session")
        provider.setLiveSessionAccess(session)
        val key = Keychain.Key.PAYKIT_SESSION.name
        whenever(keychain.loadString(key)).thenAnswer { throw KeychainError.FailedToLoad(key) }
            .thenReturn("saved-session")

        assertEquals(
            "session_load_failed",
            assertFailsWith<PaykitException.Storage> { provider.loadSessionAccess() }.code,
        )
        assertSame(session, provider.loadSessionAccess())
        whenever(blocking.delete(key)).thenAnswer { throw KeychainError.FailedToDelete(key) }.thenAnswer {
            whenever(keychain.loadString(key)).thenReturn(null)
        }

        assertEquals(
            "session_clear_failed",
            assertFailsWith<PaykitException.Storage> { provider.clearSessionAccess() }.code,
        )
        provider.clearSessionAccess()
        assertNull(provider.loadSessionAccess())
    }

    @Test
    fun `wallet wipe drains identity bootstrap and persistence and rejects queued imports`() = runTest {
        val keychain = mock<Keychain>()
        stubReceiverNoiseSecret(keychain)
        val sdk = mock<PaykitSdk>()
        val bootstrap = mock<PubkySessionBootstrap>()
        val access = mock<PubkySessionAccess>()
        val noise = mock<ReceiverNoiseSecretKey>()
        whenever(noise.exportBytes()).thenReturn(ByteArray(32) { 1 })
        whenever(access.exportReceiverNoiseSecretKey()).thenReturn(noise)
        whenever(access.exportSessionSecret()).thenReturn("new-session")
        val result = PubkySessionBootstrapResult(access, RING_PUBKY)
        val bootstrapStarted = CompletableDeferred<Unit>()
        val releaseBootstrap = CompletableDeferred<Unit>()
        val persistenceStarted = CompletableDeferred<Unit>()
        val releasePersistence = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        whenever(bootstrap.importSession(eq("active"), anyOrNull(), any(), any())).doSuspendableAnswer {
            events.add("bootstrap")
            bootstrapStarted.complete(Unit)
            releaseBootstrap.await()
            result
        }
        whenever(bootstrap.republishIdentity(any())).thenReturn(true)
        whenever(keychain.upsertString(Keychain.Key.PAYKIT_SESSION.name, "new-session")).doSuspendableAnswer {
            persistenceStarted.complete(Unit)
            releasePersistence.await()
            events.add("persisted")
            Unit
        }
        val store = mock<PubkyStore> { on { data } doReturn flowOf(PubkyStoreData()) }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val service = PaykitSdkService(mock(), keychain, store, { bootstrap }, dispatcher) { sdk }

        mockStatic(Class.forName("com.synonym.paykit.Paykit_androidKt")).use { native ->
            native.`when`<PaykitSdkConfig> { defaultConfig(PaykitReceiverPaths.WALLET) }.thenReturn(sdkConfig)
            native.`when`<String> { requiredSessionCapabilities(any()) }.thenReturn("capabilities")
            mockConstruction(ReceiverNoiseSecretKey::class.java).use {
                val active = async(start = CoroutineStart.UNDISPATCHED) { service.importSession("active") }
                bootstrapStarted.await()
                val queued = async(start = CoroutineStart.UNDISPATCHED) {
                    assertFailsWith<PaykitException.Storage> { service.importSession("queued") }
                }
                val wipe = async(start = CoroutineStart.UNDISPATCHED) {
                    service.withWalletWipe { events.add("cleanup") }
                }
                assertFalse(wipe.isCompleted)
                assertEquals(listOf("bootstrap"), events)

                releaseBootstrap.complete(Unit)
                persistenceStarted.await()
                assertFalse(wipe.isCompleted)
                assertEquals(listOf("bootstrap"), events)
                releasePersistence.complete(Unit)

                assertSame(result, active.await())
                assertEquals("wallet_wipe_in_progress", queued.await().code)
                wipe.await()
                assertEquals(listOf("bootstrap", "persisted", "cleanup"), events)
                verify(bootstrap, never()).importSession(eq("queued"), anyOrNull(), any(), any())
            }
        }
    }

    private fun stubReceiverNoiseSecret(keychain: Keychain) {
        val blocking = mock<Keychain.BlockingAccess>()
        whenever(keychain.accessBlocking<Any?>(any())).doAnswer {
            it.getArgument<Keychain.BlockingAccess.() -> Any?>(0).invoke(blocking)
        }
        whenever(blocking.load(Keychain.Key.PAYKIT_RECEIVER_NOISE_SECRET_KEY.name))
            .thenReturn(ByteArray(32) { 1 })
    }
}
