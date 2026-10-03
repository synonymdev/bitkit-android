package to.bitkit.services

import com.synonym.paykit.PaykitException
import com.synonym.paykit.PaykitSdk
import com.synonym.paykit.PubkyIdentityCapability
import com.synonym.paykit.PubkySessionAccess
import com.synonym.paykit.PubkySessionBootstrap
import com.synonym.paykit.PubkySessionBootstrapResult
import com.synonym.paykit.paykitAuthorizerSessionCapabilities
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
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
        val sdk = mock<PaykitSdk>()
        val bootstrap = mock<PubkySessionBootstrap>()
        val access = mock<PubkySessionAccess>()
        whenever(access.exportSessionSecret()).thenReturn("new-session")
        val result = PubkySessionBootstrapResult(access, RING_PUBKY, PubkyIdentityCapability.PRIVATE_LINK_CAPABLE)
        val bootstrapStarted = CompletableDeferred<Unit>()
        val releaseBootstrap = CompletableDeferred<Unit>()
        val persistenceStarted = CompletableDeferred<Unit>()
        val releasePersistence = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        whenever(bootstrap.importSession(eq("active"), anyOrNull(), any())).doSuspendableAnswer {
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
        val service = PaykitSdkService(
            mock(),
            keychain,
            store,
            { bootstrap },
            dispatcher,
            settingsStore = mock(),
        ) { sdk }

        mockStatic(Class.forName("com.synonym.paykit.Paykit_androidKt")).use { native ->
            native.`when`<String> { paykitAuthorizerSessionCapabilities() }.thenReturn("capabilities")
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
            verify(bootstrap, never()).importSession(eq("queued"), anyOrNull(), any())
        }
    }

    @Test
    fun `public read waiting to build an sdk when a wipe starts fails without building one`() = runTest {
        val keychain = mock<Keychain>()
        val releaseLocked = CompletableDeferred<Unit>()
        whenever(keychain.upsertString(Keychain.Key.PAYKIT_SESSION.name, "session"))
            .doSuspendableAnswer { releaseLocked.await() }
        val access = mock<PubkySessionAccess>()
        whenever(access.exportSessionSecret()).thenReturn("session")
        val store = mock<PubkyStore>()
        whenever(store.data).thenReturn(flowOf(PubkyStoreData()))
        val sdk = mock<PaykitSdk>()
        whenever(sdk.fetchPubkyFollows(RING_PUBKY, 10_000u)).thenReturn(listOf("follow"))
        var handlesCreated = 0
        val service = PaykitSdkService(
            mock(),
            keychain,
            store,
            bootstrapFactory = { mock() },
            settingsStore = mock(),
        ) {
            handlesCreated++
            sdk
        }

        val locked = async(start = CoroutineStart.UNDISPATCHED) {
            service.activateRegisteredIdentity(
                PubkySessionBootstrapResult(access, RING_PUBKY, PubkyIdentityCapability.PUBLIC_ONLY),
            )
        }
        val read = async(start = CoroutineStart.UNDISPATCHED) {
            assertFailsWith<PaykitException.Storage> { service.fetchPubkyFollows(RING_PUBKY) }
        }
        val wipe = async(start = CoroutineStart.UNDISPATCHED) { service.withWalletWipe { handlesCreated } }
        releaseLocked.complete(Unit)

        locked.await()
        assertEquals("wallet_wipe_in_progress", read.await().code)
        assertEquals(1, wipe.await())
        assertEquals(1, handlesCreated)
        verify(sdk, never()).fetchPubkyFollows(any(), any())
    }

    @Test
    fun `public reads during a wipe are rejected except for the wipe itself`() = runTest {
        val sdk = mock<PaykitSdk>()
        whenever(sdk.contactRecords()).thenReturn(emptyList())
        whenever(sdk.fetchPubkyFollows(RING_PUBKY, 10_000u)).thenReturn(listOf("follow"))
        var handlesCreated = 0
        val service = PaykitSdkService(mock(), mock(), mock(), settingsStore = mock()) {
            handlesCreated++
            sdk
        }
        service.contactRecords()
        val cleanupStarted = CompletableDeferred<Unit>()
        val releaseWipe = CompletableDeferred<Unit>()
        val wipe = async {
            service.withWalletWipe {
                service.contactRecords()
                assertEquals(listOf("follow"), service.fetchPubkyFollows(RING_PUBKY))
                cleanupStarted.complete(Unit)
                releaseWipe.await()
            }
        }
        cleanupStarted.await()

        val rejected = assertFailsWith<PaykitException.Storage> { service.fetchPubkyFollows(RING_PUBKY) }
        assertEquals("wallet_wipe_in_progress", rejected.code)
        verify(sdk, times(1)).fetchPubkyFollows(RING_PUBKY, 10_000u)
        assertEquals(2, handlesCreated)

        releaseWipe.complete(Unit)
        wipe.await()
        assertEquals(listOf("follow"), service.fetchPubkyFollows(RING_PUBKY))
        assertEquals(3, handlesCreated)
    }

    @Test
    fun `public read that a wipe overtakes fails without delaying the wipe`() = runTest {
        val sdk = mock<PaykitSdk>()
        whenever(sdk.contactRecords()).thenReturn(emptyList())
        val releaseRead = CompletableDeferred<List<String>>()
        whenever(sdk.fetchPubkyFollows(RING_PUBKY, 10_000u)).doSuspendableAnswer { releaseRead.await() }
        val service = PaykitSdkService(mock(), mock(), mock(), settingsStore = mock()) { sdk }
        service.contactRecords()
        val read = async(start = CoroutineStart.UNDISPATCHED) {
            assertFailsWith<PaykitException.Storage> { service.fetchPubkyFollows(RING_PUBKY) }
        }

        service.withWalletWipe {}
        assertFalse(read.isCompleted)
        releaseRead.complete(listOf("follow"))

        assertEquals("wallet_wipe_in_progress", read.await().code)
    }
}
