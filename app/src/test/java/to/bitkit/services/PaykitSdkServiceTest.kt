package to.bitkit.services

import com.synonym.paykit.ContactProfileResolution
import com.synonym.paykit.ContactRecord
import com.synonym.paykit.EncryptedLinkRecoveryMarkerPolicy
import com.synonym.paykit.EndpointManagementScope
import com.synonym.paykit.IdentityStatus
import com.synonym.paykit.LinkedPeerRecord
import com.synonym.paykit.LinkedPeerState
import com.synonym.paykit.PaykitException
import com.synonym.paykit.PaykitReceiverCapabilities
import com.synonym.paykit.PaykitReceiverMarker
import com.synonym.paykit.PaykitSdk
import com.synonym.paykit.PaymentRequestLifecycleState
import com.synonym.paykit.PaymentRequestLocalRole
import com.synonym.paykit.PaymentRequestRecord
import com.synonym.paykit.PaymentRequestRecurrence
import com.synonym.paykit.PaymentRequestTerms
import com.synonym.paykit.PrivatePaymentListDeliveryReport
import com.synonym.paykit.PubkyClientConfig
import com.synonym.paykit.PubkyLocalSecretKey
import com.synonym.paykit.PubkySessionAccess
import com.synonym.paykit.PubkySessionBootstrap
import com.synonym.paykit.PubkySessionBootstrapResult
import com.synonym.paykit.PublicContactSharingPolicy
import com.synonym.paykit.ReceiverNoiseSecretKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.description
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.data.PubkyStore
import to.bitkit.data.PubkyStoreData
import to.bitkit.data.keychain.Keychain
import to.bitkit.data.keychain.KeychainError
import to.bitkit.data.sharedpubky.SharedPubkyClient
import to.bitkit.ext.fromHex
import to.bitkit.ext.toHex
import to.bitkit.models.PubkyAuthRequestError
import to.bitkit.models.PubkyProfileData
import to.bitkit.repositories.PubkyContactError
import to.bitkit.utils.AppError
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LargeClass")
class PaykitSdkServiceTest {
    companion object {
        private const val RING_PUBKY = "3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg"
        private const val FILE_URI = "pubky://$RING_PUBKY/pub/pubky.app/files/avatar"
    }

    @Test
    fun `wallet wipe drains initialization before cleanup and allows fresh work`() = runTest {
        val sdk = mock<PaykitSdk>()
        whenever(sdk.contactRecords()).thenReturn(emptyList())
        val releaseWipe = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        lateinit var service: PaykitSdkService
        lateinit var wipe: Deferred<Unit>
        service = PaykitSdkService(
            mock(),
            mock(),
            mock(),
            ioDispatcher = StandardTestDispatcher(testScheduler),
            platformInitializer = {
                wipe = async(start = CoroutineStart.UNDISPATCHED) {
                    service.withWalletWipe {
                        events.add("cleanup")
                        releaseWipe.await()
                    }
                }
            },
            sdkFactory = { sdk },
        )
        service.initialize()
        events.add("initialized")
        releaseWipe.complete(Unit)
        wipe.await()
        assertEquals(listOf("initialized", "cleanup"), events)
        assertEquals(emptyList(), service.contactRecords())
    }

    @Test
    fun `wallet wipe discards runtime handles before and after cleanup`() = runTest {
        val sdk = mock<PaykitSdk>()
        whenever(sdk.contactRecords()).thenReturn(emptyList())
        var handlesCreated = 0
        val service = PaykitSdkService(mock(), mock(), mock()) {
            handlesCreated++
            sdk
        }
        service.contactRecords()
        service.withWalletWipe {
            service.contactRecords()
            assertEquals(2, handlesCreated)
        }
        service.contactRecords()
        assertEquals(3, handlesCreated)
    }

    @Test
    fun `storage callbacks translate platform errors and preserve sdk errors`() {
        for (error in listOf(KeychainError.FailedToLoad("state"), KeychainError.FailedToSave("state"))) {
            val mapped = assertFailsWith<PaykitException.Storage> {
                paykitStorageCallback("state_save_failed") { throw error }
            }
            assertEquals("state_save_failed", mapped.code)
        }
        val conflict = PaykitException.Storage("revision_conflict", "State changed")
        assertSame(
            conflict,
            assertFailsWith<PaykitException.Storage> {
                paykitStorageCallback("state_save_failed") { throw conflict }
            },
        )
        assertEquals("healthy", paykitStorageCallback("state_save_failed") { "healthy" })
    }

    @Test
    fun `registered identity activation persists credentials or clears partial activation`() = runTest {
        for (failure in listOf(null, "session", "secret", "initialize", "cancel")) {
            val keychain = mock<Keychain>()
            val blocking = mock<Keychain.BlockingAccess>()
            whenever(keychain.accessBlocking<Any?>(any())).doAnswer {
                it.getArgument<Keychain.BlockingAccess.() -> Any?>(0).invoke(blocking)
            }
            val bytes = ByteArray(32) { 1 }
            whenever(blocking.load(Keychain.Key.PAYKIT_RECEIVER_NOISE_SECRET_KEY.name)).thenReturn(bytes)
            val sdk = mock<PaykitSdk>()
            whenever(sdk.contactRecords()).thenReturn(emptyList())
            val access = mock<PubkySessionAccess>()
            val secret = mock<PubkyLocalSecretKey>()
            val noise = mock<ReceiverNoiseSecretKey>()
            whenever(secret.exportBytes()).thenReturn(bytes)
            whenever(noise.exportBytes()).thenReturn(bytes)
            whenever(access.exportSessionSecret()).thenReturn("new-session")
            whenever(access.exportLocalSecretKey()).thenReturn(secret)
            whenever(access.exportReceiverNoiseSecretKey()).thenReturn(noise)
            val error = if (failure == "cancel") {
                CancellationException("cancelled")
            } else {
                IllegalStateException("activation failed")
            }
            when (failure) {
                "session" -> whenever(keychain.upsertString(Keychain.Key.PAYKIT_SESSION.name, "new-session"))
                    .thenThrow(error)
                "secret" -> whenever(keychain.upsertString(Keychain.Key.PUBKY_SECRET_KEY.name, bytes.toHex()))
                    .thenThrow(error)
                "initialize", "cancel" -> whenever(sdk.initialize()).thenThrow(error)
            }
            var handlesCreated = 0
            val store = mock<PubkyStore>()
            whenever(store.data).thenReturn(flowOf(PubkyStoreData()))
            val service = PaykitSdkService(mock(), keychain, store) {
                handlesCreated++
                sdk
            }
            val result = PubkySessionBootstrapResult(access, "pubky_test")

            if (failure == null) {
                service.activateRegisteredIdentity(result)
                inOrder(keychain, sdk) {
                    verify(keychain).upsertString(Keychain.Key.PAYKIT_SESSION.name, "new-session")
                    verify(keychain).upsertString(Keychain.Key.PUBKY_SECRET_KEY.name, bytes.toHex())
                    verify(sdk).initialize()
                }
                verify(blocking, never()).delete(any())
            } else {
                val thrown = assertFailsWith(error::class) { service.activateRegisteredIdentity(result) }
                assertEquals(error, thrown)
                verify(blocking).delete(Keychain.Key.PAYKIT_SESSION.name)
                verify(blocking).delete(Keychain.Key.PUBKY_SECRET_KEY.name)
                verify(keychain, atLeastOnce()).delete(Keychain.Key.PAYKIT_SDK_STATE.name)
                val handlesBeforeReload = handlesCreated
                service.contactRecords()
                assertEquals(handlesBeforeReload + 1, handlesCreated)
            }
        }
    }

    @Test
    fun `deletion blocks all known receivers before removing the contact`() = runTest {
        for (failBlock in listOf(false, true)) {
            val sdk = mock<PaykitSdk>()
            whenever(sdk.paymentRequests()).thenReturn(emptyList())
            val contact = mock<ContactRecord> { on { receiverPaths } doReturn listOf(PaykitReceiverPaths.WALLET) }
            whenever(sdk.contactRecord(RING_PUBKY)).thenReturn(contact)
            val peer = contactPeer(PaykitReceiverPaths.SERVER, LinkedPeerState.LINKED)
            whenever(sdk.linkedPeers()).thenReturn(listOf(peer))
            if (failBlock) {
                whenever(sdk.blockPeer(RING_PUBKY, PaykitReceiverPaths.SERVER))
                    .thenThrow(IllegalStateException("storage failure"))
            }
            val service = PaykitSdkService(mock(), mock(), mock()) { sdk }
            if (failBlock) {
                assertFailsWith<IllegalStateException> { service.removeContact(RING_PUBKY) }
                verify(sdk, never()).removeContact(any())
            } else {
                service.removeContact(RING_PUBKY)
                inOrder(sdk) {
                    verify(sdk).blockPeer(RING_PUBKY, PaykitReceiverPaths.WALLET)
                    verify(sdk).blockPeer(RING_PUBKY, PaykitReceiverPaths.SERVER)
                    verify(sdk).removeContact(RING_PUBKY)
                }
            }
        }
    }

    @Test
    fun `active subscription prevents deletion until it ends`() = runTest {
        for ((role, endsNaturally) in listOf(
            PaymentRequestLocalRole.PAYER to false,
            PaymentRequestLocalRole.PAYER to true,
            PaymentRequestLocalRole.PAYEE to false,
        )) {
            val sdk = mock<PaykitSdk>()
            val recurrence = mock<PaymentRequestRecurrence>()
            val requestTerms = mock<PaymentRequestTerms> { on { this.recurrence } doReturn recurrence }
            val request = mock<PaymentRequestRecord> {
                on { counterparty } doReturn RING_PUBKY
                on { localRole } doReturn role
                on { state } doReturn PaymentRequestLifecycleState.ACTIVE_RECURRING
                on { terms } doReturn requestTerms
            }
            whenever(sdk.linkedPeers()).thenReturn(emptyList())
            whenever(sdk.paymentRequests()).thenReturn(listOf(request))
            val service = PaykitSdkService(mock(), mock(), mock()) { sdk }
            assertFailsWith<PubkyContactError.ActiveSubscription> { service.removeContact(RING_PUBKY) }
            verify(sdk, never()).blockPeer(any(), any())
            verify(sdk, never()).removeContact(any())
            if (endsNaturally) {
                whenever(recurrence.endsAt).thenReturn("2026-02-01T00:00:00Z")
            } else {
                whenever(request.state).thenReturn(PaymentRequestLifecycleState.CANCELED)
            }
            service.removeContact(RING_PUBKY)
            verify(sdk).removeContact(RING_PUBKY)
        }
    }

    @Test
    fun `identity lookup failure preserves stored state and stops activation`() = runTest {
        for (error in listOf(
            PaykitException.Identity("identity_error", "restore Pubky grant session from platform provider"),
            PaykitException.Storage("storage_error", "unavailable"),
        )) {
            val keychain = mock<Keychain>()
            val sdk = mock<PaykitSdk>()
            whenever(sdk.identityStatus()).thenThrow(error)
            val service = PaykitSdkService(mock(), keychain, mock()) { sdk }

            val thrown = assertFailsWith<PaykitException> {
                service.activateRegisteredIdentity(PubkySessionBootstrapResult(mock(), "pubky_test"))
            }

            assertEquals(error, thrown)
            verify(keychain, never()).delete(any())
            verify(keychain, never()).upsertString(any(), any())
        }
    }

    @Test
    fun `deletion withdraws private endpoints before blocking even when withdrawal fails`() = runTest {
        for (failWithdrawal in listOf(false, true)) {
            val sdk = mock<PaykitSdk>()
            whenever(sdk.paymentRequests()).thenReturn(emptyList())
            whenever(sdk.linkedPeers()).thenReturn(
                listOf(contactPeer(PaykitReceiverPaths.SERVER, LinkedPeerState.LINKED)),
            )
            val withdrawal = whenever(
                sdk.clearPrivatePaymentListAndProcessOutbound(RING_PUBKY, PaykitReceiverPaths.SERVER),
            )
            if (failWithdrawal) {
                withdrawal.thenThrow(IllegalStateException("network unavailable"))
            } else {
                withdrawal.thenReturn(
                    PrivatePaymentListDeliveryReport(emptyList(), emptyList(), emptyList(), emptyList()),
                )
            }
            val service = PaykitSdkService(mock(), mock(), mock()) { sdk }
            service.removeContact(RING_PUBKY)
            inOrder(sdk) {
                verify(sdk).clearPrivatePaymentListAndProcessOutbound(RING_PUBKY, PaykitReceiverPaths.SERVER)
                verify(sdk).blockPeer(RING_PUBKY, PaykitReceiverPaths.SERVER)
                verify(sdk).removeContact(RING_PUBKY)
            }
        }
    }

    @Test
    fun `activation isolates cached identity data by sdk or cache owner`() = runTest {
        val originalKey = "3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg"
        val differentKey = "5" + originalKey.drop(1)
        for ((previousKey, cachedOwner, resetFails) in identityCacheCases(originalKey, differentKey)) {
            val keychain = mock<Keychain>()
            stubReceiverNoiseSecret(keychain)
            val sdk = mock<PaykitSdk>()
            whenever(sdk.identityStatus()).thenReturn(IdentityStatus(previousKey, false))
            val originalCache = PubkyStoreData(
                ownerPublicKey = cachedOwner,
                cachedName = "Original profile",
                cachedImageUri = "pubky://original/avatar",
                contactProfileOverrides = mapOf(originalKey to PubkyProfileData("Private label", "")),
            )
            var cache = originalCache
            val store = mock<PubkyStore>()
            whenever(store.data).thenReturn(flowOf(cache))
            val resetError = AppError("Cache unavailable")
            whenever(store.reset()).thenAnswer {
                if (resetFails) throw resetError
                cache = PubkyStoreData()
            }
            val bootstrap = mock<PubkySessionBootstrap>()
            whenever(bootstrap.republishIdentity(any())).thenReturn(true)
            val access = mock<PubkySessionAccess>()
            val noise = mock<ReceiverNoiseSecretKey>()
            whenever(noise.exportBytes()).thenReturn(ByteArray(32) { 1 })
            whenever(access.exportSessionSecret()).thenReturn("new-session")
            whenever(access.exportReceiverNoiseSecretKey()).thenReturn(noise)
            val service = PaykitSdkService(mock(), keychain, store, { bootstrap }) { sdk }

            val result = PubkySessionBootstrapResult(access, "pubky$originalKey")
            if (resetFails) {
                assertEquals(resetError, assertFailsWith<AppError> { service.activateRegisteredIdentity(result) })
                verify(sdk, never()).initialize()
                assertEquals(originalCache, cache)
                continue
            }
            service.activateRegisteredIdentity(result)

            if (previousKey == differentKey || cachedOwner == differentKey) {
                assertEquals(PubkyStoreData(), cache)
                inOrder(store, sdk) {
                    verify(store).reset()
                    verify(sdk).initialize()
                }
            } else {
                assertEquals(originalCache, cache)
                verify(store, never()).reset()
            }
        }
    }

    @Test
    fun `public reads and locked operations do not wait for each other`() = runTest {
        val sdk = mock<PaykitSdk>()
        val service = PaykitSdkService(mock(), mock(), mock()) { sdk }
        whenever(sdk.contactRecords()).thenReturn(emptyList())
        service.contactRecords()
        val readGate = CompletableDeferred<ContactProfileResolution?>()
        whenever(sdk.resolveContactProfile(RING_PUBKY, PaykitReceiverPaths.WALLET, true))
            .doSuspendableAnswer { readGate.await() }

        val read = async { service.resolveContactProfile(RING_PUBKY, allowPubkyProfileFallback = true) }
        runCurrent()
        assertEquals(emptyList<ContactRecord>(), service.contactRecords())
        assertFalse(read.isCompleted)
        readGate.complete(null)
        assertNull(read.await())

        val lockedGate = CompletableDeferred<List<ContactRecord>>()
        whenever(sdk.contactRecords()).doSuspendableAnswer { lockedGate.await() }
        whenever(sdk.fetchPubkyFollows(RING_PUBKY)).thenReturn(listOf("follow"))
        val locked = async { service.contactRecords() }
        runCurrent()
        assertEquals(listOf("follow"), service.fetchPubkyFollows(RING_PUBKY))
        assertFalse(locked.isCompleted)
        lockedGate.complete(emptyList())
        assertEquals(emptyList<ContactRecord>(), locked.await())
    }

    @Test
    fun `public reads run at most six at a time`() = runTest {
        val sdk = mock<PaykitSdk>()
        val service = PaykitSdkService(mock(), mock(), mock()) { sdk }
        whenever(sdk.contactRecords()).thenReturn(emptyList())
        service.contactRecords()
        val gate = CompletableDeferred<Unit>()
        var active = 0
        var maxActive = 0
        whenever(sdk.fetchPubkyFileBounded(FILE_URI, 1uL)).doSuspendableAnswer {
            maxActive = maxOf(maxActive, ++active)
            gate.await()
            active--
            byteArrayOf(1)
        }

        val reads = List(10) { async { service.fetchFile(FILE_URI, 1uL) } }
        runCurrent()
        assertEquals(6, active)
        gate.complete(Unit)

        reads.awaitAll().forEach { assertContentEquals(byteArrayOf(1), it) }
        assertEquals(6, maxActive)
        verify(sdk, times(10)).fetchPubkyFileBounded(FILE_URI, 1uL)
    }

    @Test
    fun `public reads started without an sdk instance wait for the operation lock then run concurrently`() = runTest {
        val keychain = mock<Keychain>()
        val lockedGate = CompletableDeferred<Unit>()
        whenever(keychain.delete(Keychain.Key.PAYKIT_SDK_STATE.name)).doSuspendableAnswer { lockedGate.await() }
        val sdk = mock<PaykitSdk>()
        val readGate = CompletableDeferred<Unit>()
        var active = 0
        whenever(sdk.fetchPubkyFollows(RING_PUBKY)).doSuspendableAnswer {
            active++
            readGate.await()
            listOf("follow")
        }
        var handlesCreated = 0
        val service = PaykitSdkService(mock(), keychain, mock()) {
            handlesCreated++
            sdk
        }

        val locked = async { service.clearState() }
        runCurrent()
        val reads = List(3) { async { service.fetchPubkyFollows(RING_PUBKY) } }
        runCurrent()
        assertEquals(0, active)
        assertTrue(reads.none { it.isCompleted })
        assertEquals(0, handlesCreated)

        lockedGate.complete(Unit)
        runCurrent()
        assertEquals(3, active)
        assertEquals(1, handlesCreated)
        readGate.complete(Unit)
        reads.awaitAll().forEach { assertEquals(listOf("follow"), it) }
        locked.await()
    }

    @Test
    fun `receiver reads run outside the operation lock with their filtering intact`() = runTest {
        val sdk = mock<PaykitSdk>()
        val service = PaykitSdkService(mock(), mock(), mock()) { sdk }
        whenever(sdk.contactRecords()).thenReturn(emptyList())
        service.contactRecords()
        whenever(sdk.paykitReceiverPaths(RING_PUBKY))
            .thenReturn(listOf(PaykitReceiverPaths.WALLET, PaykitReceiverPaths.SERVER, "other/path"))
        whenever(sdk.paykitReceiverMarker(RING_PUBKY, PaykitReceiverPaths.WALLET))
            .thenReturn(receiverMarker(PaykitReceiverPaths.WALLET, paymentRequests = true, outgoingPayments = true))
        whenever(sdk.paykitReceiverMarker(RING_PUBKY, PaykitReceiverPaths.SERVER))
            .thenReturn(receiverMarker(PaykitReceiverPaths.SERVER, paymentRequests = false, outgoingPayments = false))
        val lockedGate = CompletableDeferred<List<ContactRecord>>()
        whenever(sdk.contactRecords()).doSuspendableAnswer { lockedGate.await() }
        val locked = async { service.contactRecords() }
        runCurrent()

        assertEquals(
            listOf(PaykitReceiverPaths.WALLET, PaykitReceiverPaths.SERVER),
            service.discoverRelevantReceiverPaths(RING_PUBKY, PaykitReadLane.Bulk),
        )
        assertEquals(
            listOf(PaykitReceiverPaths.WALLET),
            service.paymentRequestReceiverPaths(RING_PUBKY, PaykitReadLane.Bulk),
        )
        val selection =
            service.privateReceiverPathSelection(RING_PUBKY, listOf(PaykitReceiverPaths.SERVER), PaykitReadLane.Bulk)
        assertEquals(listOf(PaykitReceiverPaths.WALLET, PaykitReceiverPaths.SERVER), selection.linkableReceiverPaths)
        assertEquals(listOf(PaykitReceiverPaths.WALLET), selection.publishableReceiverPaths)
        assertEquals(emptyList(), selection.cleanupProtectedReceiverPaths)
        assertNull(selection.error)
        assertFalse(locked.isCompleted)

        lockedGate.complete(emptyList())
        assertEquals(emptyList(), locked.await())
    }

    @Test
    fun `private receiver selection protects a path whose marker read fails`() = runTest {
        val sdk = mock<PaykitSdk>()
        val service = PaykitSdkService(mock(), mock(), mock()) { sdk }
        val failure = AppError("Marker unavailable")
        whenever(sdk.paykitReceiverMarker(RING_PUBKY, PaykitReceiverPaths.WALLET))
            .thenReturn(receiverMarker(PaykitReceiverPaths.WALLET, paymentRequests = true, outgoingPayments = true))
        whenever(sdk.paykitReceiverMarker(RING_PUBKY, PaykitReceiverPaths.SERVER)).thenAnswer { throw failure }

        val selection =
            service.privateReceiverPathSelection(RING_PUBKY, listOf(PaykitReceiverPaths.SERVER), PaykitReadLane.Bulk)

        assertEquals(listOf(PaykitReceiverPaths.WALLET), selection.linkableReceiverPaths)
        assertEquals(listOf(PaykitReceiverPaths.WALLET), selection.publishableReceiverPaths)
        assertEquals(listOf(PaykitReceiverPaths.SERVER), selection.cleanupProtectedReceiverPaths)
        assertSame(failure, selection.error)
    }

    @Test
    fun `bulk reads leave two read permits to interactive reads`() = runTest {
        val sdk = mock<PaykitSdk>()
        val service = PaykitSdkService(mock(), mock(), mock()) { sdk }
        whenever(sdk.contactRecords()).thenReturn(emptyList())
        service.contactRecords()
        val gate = CompletableDeferred<Unit>()
        var bulkActive = 0
        var interactiveActive = 0
        whenever(sdk.resolveContactProfile(any(), any(), any())).doSuspendableAnswer {
            bulkActive++
            gate.await()
            bulkActive--
            null
        }
        whenever(sdk.fetchPubkyFileBounded(FILE_URI, 1uL)).doSuspendableAnswer {
            interactiveActive++
            gate.await()
            interactiveActive--
            byteArrayOf(1)
        }

        val bulkReads = List(10) {
            async { service.resolveContactProfile("$RING_PUBKY$it", true, PaykitReadLane.Bulk) }
        }
        runCurrent()
        assertEquals(4, bulkActive)

        val interactiveReads = List(3) { async { service.fetchFile(FILE_URI, 1uL) } }
        runCurrent()
        assertEquals(4, bulkActive)
        assertEquals(2, interactiveActive)

        gate.complete(Unit)
        bulkReads.awaitAll()
        interactiveReads.awaitAll()
        verify(sdk, times(10)).resolveContactProfile(any(), any(), any())
        verify(sdk, times(3)).fetchPubkyFileBounded(FILE_URI, 1uL)
    }

    @Test
    fun `bulk reads start in request order and a cancelled one frees both permits`() = runTest {
        val sdk = mock<PaykitSdk>()
        val service = PaykitSdkService(mock(), mock(), mock()) { sdk }
        whenever(sdk.contactRecords()).thenReturn(emptyList())
        service.contactRecords()
        val started = mutableListOf<String>()
        val gates = List(6) { CompletableDeferred<Unit>() }
        whenever(sdk.paykitReceiverPaths(any())).doSuspendableAnswer {
            val key = it.getArgument<String>(0)
            started += key
            gates[key.removePrefix(RING_PUBKY).toInt()].await()
            emptyList<String>()
        }
        val reads = List(6) {
            async { service.discoverRelevantReceiverPaths("$RING_PUBKY$it", PaykitReadLane.Bulk) }
        }
        runCurrent()
        assertEquals(List(4) { "$RING_PUBKY$it" }, started)

        reads[1].cancel()
        runCurrent()
        assertEquals(List(5) { "$RING_PUBKY$it" }, started)

        val interactiveGate = CompletableDeferred<ByteArray?>()
        var interactiveActive = 0
        whenever(sdk.fetchPubkyFileBounded(FILE_URI, 1uL)).doSuspendableAnswer {
            interactiveActive++
            interactiveGate.await()
        }
        val interactiveReads = List(2) { async { service.fetchFile(FILE_URI, 1uL) } }
        runCurrent()
        assertEquals(2, interactiveActive)

        gates[0].complete(Unit)
        runCurrent()
        assertEquals(List(6) { "$RING_PUBKY$it" }, started)

        gates.forEach { it.complete(Unit) }
        interactiveGate.complete(byteArrayOf(1))
        reads.filterIndexed { index, _ -> index != 1 }.awaitAll()
        interactiveReads.awaitAll()
    }

    @Test
    fun `activation returns while identity publication runs and approval republish joins it until the cap`() = runTest {
        listOf("publication finishes" to true, "cap" to false).forEach { (case, gateOpens) ->
            val keychain = mock<Keychain>()
            stubReceiverNoiseSecret(keychain)
            val store = mock<PubkyStore>()
            whenever(store.data).thenReturn(flowOf(PubkyStoreData()))
            val publicationGate = CompletableDeferred<Boolean>()
            var published = false
            val bootstrap = mock<PubkySessionBootstrap>()
            whenever(bootstrap.republishIdentity(any())).doSuspendableAnswer {
                publicationGate.await().also { published = true }
            }
            val access = mock<PubkySessionAccess>()
            val noise = mock<ReceiverNoiseSecretKey>()
            whenever(noise.exportBytes()).thenReturn(ByteArray(32) { 1 })
            whenever(access.exportSessionSecret()).thenReturn("new-session")
            whenever(access.exportReceiverNoiseSecretKey()).thenReturn(noise)
            val sdk = mock<PaykitSdk>()
            val service = PaykitSdkService(
                context = mock(),
                keychain = keychain,
                pubkyStore = store,
                bootstrapFactory = { bootstrap },
                ioDispatcher = StandardTestDispatcher(testScheduler),
                sdkFactory = { sdk },
            )
            val activationStart = currentTime

            val activation = async {
                service.activateRegisteredIdentity(PubkySessionBootstrapResult(access, "pubky$RING_PUBKY"))
            }
            runCurrent()

            assertTrue(activation.isCompleted, case)
            assertEquals(activationStart, currentTime, case)
            verify(sdk, description(case)).initialize()
            verify(bootstrap, description(case)).republishIdentity("pubky$RING_PUBKY")
            assertFalse(published, case)
            val start = currentTime

            val approval = async { service.republishIdentityIfNeeded(RING_PUBKY) }
            advanceTimeBy(4_999)
            runCurrent()
            assertFalse(approval.isCompleted, case)
            if (gateOpens) publicationGate.complete(true) else advanceTimeBy(1)
            runCurrent()

            assertTrue(approval.isCompleted, case)
            assertEquals(start + if (gateOpens) 4_999 else 5_000, currentTime, case)
            verify(bootstrap, description(case)).republishIdentity("pubky$RING_PUBKY")
            publicationGate.complete(true)
            runCurrent()
            assertTrue(published, case)
        }
    }

    @Test
    fun `only explicit readd unblocks every saved private receiver`() = runTest {
        for (restoreConnection in listOf(false, true)) {
            val sdk = mock<PaykitSdk>()
            whenever(sdk.saveContact(any())).thenReturn(mock())
            whenever(sdk.linkedPeers()).thenReturn(
                listOf(
                    contactPeer(PaykitReceiverPaths.WALLET, LinkedPeerState.BLOCKED),
                    contactPeer(PaykitReceiverPaths.SERVER, LinkedPeerState.BLOCKED),
                ),
            )
            val service = PaykitSdkService(mock(), mock(), mock()) { sdk }
            if (restoreConnection) {
                service.saveContact(RING_PUBKY, "Contact", restorePrivateConnection = true)
                verify(sdk).unblockPeer(RING_PUBKY, PaykitReceiverPaths.WALLET)
                verify(sdk).unblockPeer(RING_PUBKY, PaykitReceiverPaths.SERVER)
            } else {
                assertFailsWith<IllegalStateException> { service.saveContact(RING_PUBKY, "Contact") }
                verify(sdk, never()).saveContact(any())
                verify(sdk, never()).unblockPeer(any(), any())
            }
        }
    }

    @Test
    fun `failed private connection restoration leaves contact creation retryable`() = runTest {
        for (failurePoint in listOf("lookup", "unblock", "save", "cancel")) {
            val sdk = mock<PaykitSdk>()
            val peers = listOf(
                contactPeer(PaykitReceiverPaths.WALLET, LinkedPeerState.BLOCKED),
                contactPeer(PaykitReceiverPaths.SERVER, LinkedPeerState.BLOCKED),
            )
            val failure = if (failurePoint == "cancel") {
                CancellationException("cancelled")
            } else {
                IllegalStateException("storage failure")
            }
            whenever(sdk.linkedPeers()).thenReturn(peers)
            whenever(sdk.saveContact(any())).thenReturn(mock())
            when (failurePoint) {
                "lookup" -> whenever(sdk.linkedPeers()).thenThrow(failure).thenReturn(peers)
                "unblock", "cancel" -> {
                    whenever(sdk.unblockPeer(RING_PUBKY, PaykitReceiverPaths.SERVER))
                        .thenThrow(failure).thenReturn(peers.last().copy(state = LinkedPeerState.NOT_LINKED))
                }
                "save" -> whenever(sdk.saveContact(any())).thenThrow(failure).thenReturn(mock())
            }
            val service = PaykitSdkService(mock(), mock(), mock()) { sdk }
            val thrown = assertFailsWith<Throwable> {
                service.saveContact(RING_PUBKY, "Contact", restorePrivateConnection = true)
            }
            assertSame(failure, thrown)
            if (failurePoint == "lookup") {
                verify(sdk, never()).blockPeer(any(), any())
            } else {
                verify(sdk).blockPeer(RING_PUBKY, PaykitReceiverPaths.WALLET)
                verify(sdk).blockPeer(RING_PUBKY, PaykitReceiverPaths.SERVER)
            }
            service.saveContact(RING_PUBKY, "Contact", restorePrivateConnection = true)
            verify(sdk, atLeastOnce()).saveContact(any())
        }
    }

    @Test
    fun `private connection restoration preserves failures from rollback`() = runTest {
        val sdk = mock<PaykitSdk>()
        val peers = listOf(
            contactPeer(PaykitReceiverPaths.WALLET, LinkedPeerState.BLOCKED),
            contactPeer(PaykitReceiverPaths.SERVER, LinkedPeerState.BLOCKED),
        )
        val restorationFailure = IllegalStateException("save failed")
        val rollbackFailure = IllegalStateException("block failed")
        whenever(sdk.linkedPeers()).thenReturn(peers)
        whenever(sdk.saveContact(any())).thenThrow(restorationFailure)
        whenever(sdk.blockPeer(RING_PUBKY, PaykitReceiverPaths.WALLET)).thenThrow(rollbackFailure)
        val service = PaykitSdkService(mock(), mock(), mock()) { sdk }

        val thrown = assertFailsWith<IllegalStateException> {
            service.saveContact(RING_PUBKY, "Contact", restorePrivateConnection = true)
        }

        assertSame(restorationFailure, thrown)
        assertEquals(listOf(rollbackFailure), thrown.suppressed.toList())
        verify(sdk).blockPeer(RING_PUBKY, PaykitReceiverPaths.WALLET)
        verify(sdk).blockPeer(RING_PUBKY, PaykitReceiverPaths.SERVER)
    }

    @Test
    fun `blocked peer cleanup does not attempt network delivery`() = runTest {
        val sdk = mock<PaykitSdk>()
        whenever(sdk.linkedPeers()).thenReturn(listOf(contactPeer(PaykitReceiverPaths.SERVER, LinkedPeerState.BLOCKED)))
        val service = PaykitSdkService(mock(), mock(), mock()) { sdk }
        assertNull(service.clearPrivatePaymentList(RING_PUBKY, PaykitReceiverPaths.SERVER))
        verify(sdk, never()).clearPrivatePaymentListAndProcessOutbound(any(), any())
    }

    private fun receiverMarker(path: String, paymentRequests: Boolean, outgoingPayments: Boolean) =
        PaykitReceiverMarker(
            receiverPath = path,
            capabilities = PaykitReceiverCapabilities(
                privatePayments = true,
                paymentRequests = paymentRequests,
                receipts = false,
                outgoingPayments = outgoingPayments,
            ),
            noisePublicKey = "noise",
        )

    private fun contactPeer(path: String, state: LinkedPeerState) = LinkedPeerRecord(
        counterparty = RING_PUBKY,
        counterpartyReceiverPath = path,
        state = state,
        lastSyncAt = null,
        lastPrivateReceiveAt = null,
        failureCount = 0u,
        localRecoveryAttemptId = null,
        localRecoveryMarkerCreatedAt = null,
        localRecoveryMarkerLastError = null,
        remoteRecoveryAttemptId = null,
        remoteRecoveryMarkerObservedAt = null,
    )

    private val basePubkyClientConfig = PubkyClientConfig(
        requestTimeoutSecs = 30uL,
        localTestnetHost = null,
        authRelayUrl = null,
    )

    @Test
    fun `config scopes public endpoint sync to Bitkit managed endpoints`() {
        assertEquals(BitkitPaykitSdkConfig.profileNamespace, BitkitPaykitSdkConfig.clientId)
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
    fun `local E2E uses configured host for Pubky testnet`() {
        val config = paykitPubkyClientConfig(
            isLocalE2eBackend = true,
            localTestnetHost = "192.0.2.1",
            baseConfig = basePubkyClientConfig,
        )

        assertEquals("192.0.2.1", config.localTestnetHost)
        assertEquals(basePubkyClientConfig.requestTimeoutSecs, config.requestTimeoutSecs)
    }

    @Test
    fun `approval uses external requester client id`() {
        assertEquals("paykit.test", validatedApprovalClientId("paykit.test", "paykit.test"))
    }

    @Test
    fun `approval rejects a mismatched client id`() {
        assertFailsWith<PubkyAuthRequestError.RequesterChanged> {
            validatedApprovalClientId("paykit.test", "different.test")
        }
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
        val provider = PaykitSdkSessionProvider(keychain, mock())
        whenever(keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)).thenReturn(sessionSecret)
        whenever(keychain.loadString(Keychain.Key.PUBKY_SECRET_KEY.name)).thenReturn(null)

        assertTrue(provider.hasSessionAccess())
        assertNull(provider.loadLocalSecretKey())
    }

    @Test
    fun `adopted identity loads its secret key from the ring provider`() {
        val keychain = mock<Keychain>()
        val sharedPubky = mock<SharedPubkyClient>()
        val provider = PaykitSdkSessionProvider(keychain, sharedPubky)
        whenever(keychain.loadString(Keychain.Key.PUBKY_SECRET_KEY.name)).thenReturn(null)
        whenever(keychain.loadString(Keychain.Key.SHARED_PUBKY_SOURCE.name)).thenReturn("app.pubkyring:$RING_PUBKY")
        whenever(sharedPubky.readCredential(RING_PUBKY)).thenReturn(null)

        assertEquals(RING_PUBKY, provider.adoptedPubky())
        assertNull(provider.loadLocalSecretKey())
        verify(sharedPubky).readCredential(RING_PUBKY)
    }

    @Test
    fun `stale session can be deferred until sdk initialization completes`() {
        val keychain = mock<Keychain>()
        val provider = PaykitSdkSessionProvider(keychain, mock())
        whenever(keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)).thenReturn("saved-session")

        assertTrue(provider.canDeferStaleSession("restore Pubky grant session from platform provider"))
        assertTrue(!provider.canDeferStaleSession("local Pubky secret key does not match session public key"))
        provider.suspendStoredSessionAccess()
        assertNull(provider.loadSessionAccess())
    }

    @Test
    fun `missing session or unrelated identity failures are not deferred`() {
        val keychain = mock<Keychain>()
        val provider = PaykitSdkSessionProvider(keychain, mock())
        whenever(keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)).thenReturn(null)

        assertTrue(!provider.canDeferStaleSession("restore Pubky grant session from platform provider"))
        assertTrue(!provider.canDeferStaleSession("local Pubky secret key does not match session public key"))
    }

    @Test
    fun `session teardown attempts both credentials with session first`() {
        val attemptedKeys = mutableListOf<String>()

        assertFailsWith<AppError> {
            clearPubkySessionCredentials {
                attemptedKeys += it
                if (it == Keychain.Key.PAYKIT_SESSION.name) throw AppError("Delete failed")
            }
        }

        assertEquals(
            listOf(Keychain.Key.PAYKIT_SESSION.name, Keychain.Key.PUBKY_SECRET_KEY.name),
            attemptedKeys,
        )
    }

    private fun keyStore(
        loadBytes: () -> ByteArray?,
        upsertBytes: (ByteArray) -> Unit = {},
        deriveBytes: () -> ByteArray,
    ) = PaykitReceiverNoiseKeyStore(loadBytes, upsertBytes, deriveBytes)

    private fun stubReceiverNoiseSecret(keychain: Keychain) {
        val blocking = mock<Keychain.BlockingAccess>()
        whenever(keychain.accessBlocking<Any?>(any())).doAnswer {
            it.getArgument<Keychain.BlockingAccess.() -> Any?>(0).invoke(blocking)
        }
        whenever(blocking.load(Keychain.Key.PAYKIT_RECEIVER_NOISE_SECRET_KEY.name))
            .thenReturn(ByteArray(32) { 1 })
    }

    private fun identityCacheCases(originalKey: String, differentKey: String) = listOf(
        Triple(originalKey, null, false),
        Triple("pubky$originalKey", null, false),
        Triple(differentKey, null, false),
        Triple(null, null, false),
        Triple(null, originalKey, false),
        Triple(null, differentKey, false),
        Triple(originalKey, differentKey, false),
        Triple(differentKey, null, true),
    )
}
