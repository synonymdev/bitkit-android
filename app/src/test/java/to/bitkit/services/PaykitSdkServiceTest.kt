package to.bitkit.services

import com.synonym.paykit.ContactRecord
import com.synonym.paykit.ContactUpdate
import com.synonym.paykit.IdentityStatus
import com.synonym.paykit.LinkedPeerHandshakeReport
import com.synonym.paykit.LinkedPeerRecord
import com.synonym.paykit.LinkedPeerState
import com.synonym.paykit.ObservedBackupStateRevision
import com.synonym.paykit.OutboundPrivateCounterpartySendReport
import com.synonym.paykit.OutboundPrivateSendReport
import com.synonym.paykit.PaykitApp
import com.synonym.paykit.PaykitAppCapabilities
import com.synonym.paykit.PaykitAppRegistry
import com.synonym.paykit.PaykitException
import com.synonym.paykit.PaykitIdentitySecretKey
import com.synonym.paykit.PaykitSdk
import com.synonym.paykit.PaymentRequestLifecycleState
import com.synonym.paykit.PaymentRequestLocalRole
import com.synonym.paykit.PaymentRequestRecord
import com.synonym.paykit.PaymentRequestRecurrence
import com.synonym.paykit.PaymentRequestTerms
import com.synonym.paykit.PrivatePaymentListDeliveryReport
import com.synonym.paykit.PrivatePaymentListReservationUpdateInput
import com.synonym.paykit.PrivatePaymentListSyncChange
import com.synonym.paykit.PrivateStreamCounterpartyIntakeReport
import com.synonym.paykit.PrivateStreamIntakeReport
import com.synonym.paykit.ProfileResolution
import com.synonym.paykit.PubkyAuthCompanionClaim
import com.synonym.paykit.PubkyClientConfig
import com.synonym.paykit.PubkyIdentityCapability
import com.synonym.paykit.PubkyLocalSecretKey
import com.synonym.paykit.PubkySessionAccess
import com.synonym.paykit.PubkySessionBootstrap
import com.synonym.paykit.PubkySessionBootstrapResult
import com.synonym.paykit.PublicContactPaymentResolution
import com.synonym.paykit.PublicContactSharingPolicy
import com.synonym.paykit.PublicPaymentResolutionStatus
import com.synonym.paykit.paykitAuthorizerSessionCapabilities
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.any
import org.mockito.kotlin.description
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import to.bitkit.data.PubkyStore
import to.bitkit.data.PubkyStoreData
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.data.keychain.Keychain
import to.bitkit.data.keychain.KeychainError
import to.bitkit.data.sharedpubky.SharedPubkyClient
import to.bitkit.ext.runSuspendCatching
import to.bitkit.ext.toHex
import to.bitkit.models.PubkyAuthClaim
import to.bitkit.models.PubkyAuthClaim.Item
import to.bitkit.models.PubkyAuthRequestError
import to.bitkit.models.PubkyProfileData
import to.bitkit.repositories.PubkyContactError
import to.bitkit.services.PaykitSdkOperationLock.Priority
import to.bitkit.test.forEachCase
import to.bitkit.utils.AppError
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LargeClass")
class PaykitSdkServiceTest {
    companion object {
        private const val RING_PUBKY = "3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg"
        private const val FILE_URI = "pubky://$RING_PUBKY/pub/pubky.app/files/avatar"
        private val READ_TIMEOUT = 10.seconds
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `request discovery completes while unrelated SDK work is blocked`() = runTest {
        val sdk = mock<PaykitSdk>()
        val releaseContacts = CompletableDeferred<Unit>()
        whenever { sdk.contactRecords() }.doSuspendableAnswer {
            releaseContacts.await()
            emptyList()
        }
        whenever { sdk.paykitAppRegistry(RING_PUBKY) }.thenReturn(
            PaykitAppRegistry(
                1u,
                null,
                listOf(PaykitApp("bitkit", "Bitkit", PaykitAppCapabilities(true, true, false, true))),
                null,
                emptyMap(),
            ),
        )
        val service = PaykitSdkService(mock(), mock(), mock(), settingsStore = mock()) { sdk }
        val contacts = async { service.contactRecords() }
        runCurrent()
        val discovery = async { service.canReceivePaymentRequests(RING_PUBKY) }
        runCurrent()

        try {
            assertTrue(discovery.isCompleted)
            assertFalse(contacts.isCompleted)
            assertEquals(true, discovery.await())
        } finally {
            releaseContacts.complete(Unit)
        }
        contacts.await()
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `request discovery discards a result from a replaced runtime`() = runTest {
        val sdk = mock<PaykitSdk>()
        val releaseLookup = CompletableDeferred<Unit>()
        whenever { sdk.paykitAppRegistry(RING_PUBKY) }.doSuspendableAnswer {
            releaseLookup.await()
            null
        }
        val service = PaykitSdkService(mock(), mock(), mock(), settingsStore = mock()) { sdk }
        val discovery = async { service.canReceivePaymentRequests(RING_PUBKY) }
        runCurrent()
        service.clearState()
        releaseLookup.complete(Unit)

        assertNull(discovery.await())
        assertEquals(false, service.canReceivePaymentRequests(RING_PUBKY))
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `request discovery bounds registry lookup and propagates cancellation`() = runTest {
        val sdk = mock<PaykitSdk>()
        whenever { sdk.paykitAppRegistry(RING_PUBKY) }.doSuspendableAnswer { awaitCancellation() }
        val service = PaykitSdkService(mock(), mock(), mock(), settingsStore = mock()) { sdk }
        val discovery = async { service.canReceivePaymentRequests(RING_PUBKY) }
        advanceTimeBy(5.seconds.inWholeMilliseconds)
        runCurrent()
        assertNull(discovery.await())

        val cancelled = async { service.canReceivePaymentRequests(RING_PUBKY) }
        runCurrent()
        cancelled.cancel()
        assertFailsWith<CancellationException> { cancelled.await() }
        doReturn(null).whenever(sdk).paykitAppRegistry(RING_PUBKY)
        assertEquals(false, service.canReceivePaymentRequests(RING_PUBKY))
    }

    @Test
    fun `private link calls advance once and allow pending handshakes to resume`() = runTest {
        val sdk = mock<PaykitSdk>()
        whenever(sdk.stateRevision()).thenReturn("state")
        whenever(sdk.backupStateRevision()).thenReturn("backup")
        whenever(sdk.ensureLinkWithPeer(any(), any())).thenReturn(
            LinkedPeerHandshakeReport(RING_PUBKY, LinkedPeerState.LINKING, 1uL, null),
            LinkedPeerHandshakeReport(RING_PUBKY, LinkedPeerState.LINKED, 1uL, null),
        )
        val pending = PaykitException.RecoveryRequired("recovery_required", "Handshake pending")
        whenever(sdk.prepareAndResolvePrivateContactPayment(RING_PUBKY, null, null, 1u)).thenThrow(pending)
        whenever(sdk.prepareAndResolvePrivatePaymentRequest(RING_PUBKY, "request", null, 1u)).thenThrow(pending)
        val service = PaykitSdkService(mock(), mock(), mock(), settingsStore = mock()) { sdk }

        assertEquals(LinkedPeerState.LINKING, service.ensureLinkWithPeer(RING_PUBKY).state)
        assertEquals(LinkedPeerState.LINKED, service.ensureLinkWithPeer(RING_PUBKY).state)
        assertSame(
            pending,
            assertFailsWith<PaykitException.RecoveryRequired> {
                service.prepareAndResolvePrivateContactPayment(RING_PUBKY, null)
            },
        )
        assertSame(
            pending,
            assertFailsWith<PaykitException.RecoveryRequired> {
                service.prepareAndResolvePrivatePaymentRequest(RING_PUBKY, "request", null)
            },
        )
        verify(sdk, times(2)).ensureLinkWithPeer(RING_PUBKY, 1u)
        verify(sdk).prepareAndResolvePrivateContactPayment(RING_PUBKY, null, null, 1u)
        verify(sdk).prepareAndResolvePrivatePaymentRequest(RING_PUBKY, "request", null, 1u)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `scoped private receive holds mutation lock and tracks backup changes`() = runTest {
        for (cancelActive in listOf(false, true)) {
            val sdk = mock<PaykitSdk>()
            val report = mock<PrivateStreamIntakeReport>()
            val releaseReceive = CompletableDeferred<Unit>()
            var revision = "before"
            whenever(sdk.stateRevision()).thenAnswer { revision }
            whenever { sdk.backupStateRevision() }.thenAnswer { revision }
            whenever { sdk.receivePrivateMessages(RING_PUBKY) }.doSuspendableAnswer {
                releaseReceive.await()
                revision = "received"
                report
            }
            whenever { sdk.contactRecords() }.thenReturn(emptyList())
            val service = PaykitSdkService(mock(), mock(), mock(), settingsStore = mock()) { sdk }
            val receive = async { service.receivePrivateMessages(RING_PUBKY) }
            runCurrent()
            val contacts = async { service.contactRecords() }
            runCurrent()
            val cancelledQueued = async { service.processPendingPrivateMessages() }
            runCurrent()
            cancelledQueued.cancel()
            if (cancelActive) receive.cancel()
            runCurrent()
            try {
                assertFalse(receive.isCompleted)
                assertFalse(contacts.isCompleted)
                verify(sdk, never()).contactRecords()
            } finally {
                releaseReceive.complete(Unit)
            }

            if (cancelActive) {
                assertFailsWith<CancellationException> { receive.await() }
            } else {
                assertSame(report, receive.await())
            }
            assertEquals(emptyList(), contacts.await())
            assertFailsWith<CancellationException> { cancelledQueued.await() }
            assertEquals("received", revision)
            assertEquals(1L, service.backupStateVersion.value)
            verify(sdk).receivePrivateMessages(RING_PUBKY)
            verify(sdk, never()).receivePrivateMessagesFromLinkedPeers()
            verify(sdk, never()).processPendingPrivateMessages()
        }
    }

    @Test
    fun `cancelled private operation finishes the active SDK call before releasing the queue`() = runTest {
        val operations = listOf<suspend PaykitSdkService.() -> Any?>(
            { prepareAndResolvePrivateContactPayment(RING_PUBKY, null) },
            { prepareAndResolvePrivatePaymentRequest(RING_PUBKY, "request", null) },
            { processOutboundPrivateMessages(RING_PUBKY, Priority.Interactive) },
            { processPendingPrivateMessages(Priority.Background) },
            { receivePrivateMessagesFromLinkedPeers(Priority.Background) },
        )
        for (operation in operations) {
            val sdk = mock<PaykitSdk>()
            val release = CompletableDeferred<Unit>()
            var finished = false
            whenever { sdk.prepareAndResolvePrivateContactPayment(RING_PUBKY, null, null, 1u) }
                .doSuspendableAnswer {
                    release.await()
                    finished = true
                    mock()
                }
            whenever { sdk.prepareAndResolvePrivatePaymentRequest(RING_PUBKY, "request", null, 1u) }
                .doSuspendableAnswer {
                    release.await()
                    finished = true
                    mock()
                }
            whenever { sdk.processOutboundPrivateMessages(RING_PUBKY) }.doSuspendableAnswer {
                release.await()
                finished = true
                mock()
            }
            whenever { sdk.processPendingPrivateMessages() }.doSuspendableAnswer {
                release.await()
                finished = true
                emptyList()
            }
            whenever { sdk.receivePrivateMessagesFromLinkedPeers() }.doSuspendableAnswer {
                release.await()
                finished = true
                emptyList()
            }
            whenever { sdk.contactRecords() }.thenAnswer {
                assertTrue(finished)
                emptyList<ContactRecord>()
            }
            val service = PaykitSdkService(mock(), mock(), mock(), settingsStore = mock()) { sdk }
            val preparation = async { service.operation() }
            runCurrent()
            val next = async { service.contactRecords() }
            preparation.cancel()
            runCurrent()
            assertFalse(preparation.isCompleted)
            assertFalse(next.isCompleted)
            assertFalse(finished)
            release.complete(Unit)

            assertFailsWith<CancellationException> { preparation.await() }
            assertEquals(emptyList(), next.await())
            assertTrue(finished)
            assertEquals(1L, service.backupStateVersion.value)
        }
    }

    @Test
    fun `queued background work yields to foreground app sync while ordered work remains a barrier`() = runTest {
        val operations = listOf<suspend PaykitSdkService.(Priority) -> Any?>(
            { processPendingPrivateMessages(it) },
            { receivePrivateMessagesFromLinkedPeers(it) },
            { ensureLinkWithPeer(RING_PUBKY, priority = it) },
        )
        for (operation in operations) {
            val priorities = listOf(Priority.Background, Priority.Ordered)
                .flatMap { messages -> listOf(Priority.Ordered, Priority.Interactive).map { messages to it } }
            for ((messagePriority, appPriority) in priorities) {
                val sdk = mock<PaykitSdk>()
                val release = CompletableDeferred<Unit>()
                val events = mutableListOf<String>()
                whenever { sdk.contactRecords() }.doSuspendableAnswer {
                    release.await()
                    events += "active completed"
                    emptyList()
                }
                whenever { sdk.processPendingPrivateMessages() }.thenAnswer {
                    events += "messages"
                    emptyList<OutboundPrivateCounterpartySendReport>()
                }
                whenever { sdk.receivePrivateMessagesFromLinkedPeers() }.thenAnswer {
                    events += "messages"
                    emptyList<PrivateStreamCounterpartyIntakeReport>()
                }
                whenever { sdk.ensureLinkWithPeer(RING_PUBKY, 1u) }.thenAnswer {
                    events += "messages"
                    LinkedPeerHandshakeReport(RING_PUBKY, LinkedPeerState.LINKING, 1uL, null)
                }
                whenever { sdk.identityStatus() }
                    .thenReturn(IdentityStatus(RING_PUBKY, PubkyIdentityCapability.PRIVATE_LINK_CAPABLE))
                whenever { sdk.publishPaykitApp(any(), any()) }.thenAnswer {
                    events += "publication"
                    mock<PaykitApp>()
                }
                val service = PaykitSdkService(mock(), mock(), mock(), settingsStore = mock()) { sdk }
                val active = async { service.contactRecords() }
                runCurrent()
                val messages = async { service.operation(messagePriority) }
                val publication = async {
                    service.syncPaykitApp(privatePaymentsEnabled = false, priority = appPriority)
                }
                runCurrent()
                assertTrue(events.isEmpty())
                release.complete(Unit)
                awaitAll(active, messages, publication)
                val queued = if (messagePriority == Priority.Background && appPriority == Priority.Interactive) {
                    listOf("publication", "messages")
                } else {
                    listOf("messages", "publication")
                }
                assertEquals(listOf("active completed") + queued, events)
                verify(sdk).publishPaykitApp("Bitkit", PaykitAppCapabilities(false, true, false, true))
            }
        }
    }

    @Test
    fun `foreground request work overtakes queued routine reads but not active work`() = runTest {
        val operations = listOf<suspend PaykitSdkService.() -> Any?>(
            { identityStatus(Priority.Interactive) },
            { linkedPeers(Priority.Interactive) },
            { allPaymentRequests(null, Priority.Interactive) },
            { processOutboundPrivateMessages(RING_PUBKY, Priority.Interactive) },
        )
        for (operation in operations) {
            val sdk = mock<PaykitSdk>()
            val release = CompletableDeferred<Unit>()
            val events = mutableListOf<String>()
            whenever { sdk.contactRecords() }.doSuspendableAnswer {
                release.await()
                events += "active completed"
                emptyList()
            }
            whenever { sdk.pendingOutboundPrivateCounterparties() }.thenAnswer {
                events += "background"
                emptyList<String>()
            }
            whenever { sdk.identityStatus() }.thenAnswer {
                events += "interactive"
                IdentityStatus(RING_PUBKY, PubkyIdentityCapability.PRIVATE_LINK_CAPABLE)
            }
            whenever { sdk.linkedPeers() }.thenAnswer {
                events += "interactive"
                emptyList<LinkedPeerRecord>()
            }
            whenever { sdk.listPaymentRequests(any()) }.thenAnswer {
                events += "interactive"
                emptyList<PaymentRequestRecord>()
            }
            whenever { sdk.processOutboundPrivateMessages(RING_PUBKY) }.thenAnswer {
                events += "interactive"
                mock<OutboundPrivateSendReport>()
            }
            val service = PaykitSdkService(mock(), mock(), mock(), settingsStore = mock()) { sdk }
            val active = async { service.contactRecords() }
            runCurrent()
            val background = async { service.pendingOutboundPrivateCounterparties(Priority.Background) }
            val cancelled = async { service.operation() }
            runCurrent()
            cancelled.cancel()
            val foreground = async { service.operation() }
            runCurrent()
            assertTrue(events.isEmpty())
            release.complete(Unit)
            active.await()
            foreground.await()
            background.await()
            assertFailsWith<CancellationException> { cancelled.await() }
            assertEquals(listOf("active completed", "interactive", "background"), events)
        }
    }

    @Test
    fun `foreground identity read cannot overtake queued signout`() = runTest {
        val sdk = mock<PaykitSdk>()
        val release = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val signedOut = IdentityStatus(null, PubkyIdentityCapability.SIGNED_OUT)
        whenever { sdk.contactRecords() }.doSuspendableAnswer {
            release.await()
            emptyList()
        }
        whenever { sdk.pendingOutboundPrivateCounterparties() }.thenAnswer {
            events += "background"
            emptyList<String>()
        }
        whenever { sdk.signOut() }.thenAnswer {
            events += "signout"
            signedOut
        }
        whenever { sdk.identityStatus() }.thenAnswer {
            events += "identity"
            signedOut
        }
        val service = PaykitSdkService(mock(), mock(), mock(), settingsStore = mock()) { sdk }
        val active = async { service.contactRecords() }
        runCurrent()
        val background = async { service.pendingOutboundPrivateCounterparties(Priority.Background) }
        val signout = async { service.signOut() }
        val identity = async { service.identityStatus(Priority.Interactive) }
        runCurrent()
        release.complete(Unit)
        active.await()
        background.await()
        signout.await()
        assertEquals(signedOut, identity.await())
        assertEquals(listOf("background", "signout", "identity"), events)
    }

    @Test
    fun `payment mutations overtake background work without crossing signout barriers`() = runTest {
        val payments = listOf<suspend PaykitSdkService.() -> Any?>(
            { claimPaymentRequestForExecution(RING_PUBKY, "request") },
            { acceptPaymentRequest(RING_PUBKY, "request") },
        )
        for ((index, payment) in payments.withIndex()) {
            for (withBarrier in listOf(false, true)) {
                val sdk = mock<PaykitSdk>()
                val release = CompletableDeferred<Unit>()
                val events = mutableListOf<String>()
                whenever { sdk.contactRecords() }.doSuspendableAnswer {
                    release.await()
                    emptyList()
                }
                whenever { sdk.pendingOutboundPrivateCounterparties() }.thenAnswer {
                    events += "background"
                    emptyList<String>()
                }
                whenever { sdk.signOut() }.thenAnswer {
                    events += "signout"
                    IdentityStatus(null, PubkyIdentityCapability.SIGNED_OUT)
                }
                whenever { sdk.claimPaymentRequestForExecution(RING_PUBKY, "request") }.thenAnswer {
                    events += "payment"
                    mock<PaymentRequestRecord>()
                }
                whenever { sdk.claimAndAcceptPaymentRequest(RING_PUBKY, "request") }.thenAnswer {
                    events += "payment"
                    mock<PaymentRequestRecord>()
                }
                val service = PaykitSdkService(mock(), mock(), mock(), settingsStore = mock()) { sdk }
                val active = async { service.contactRecords() }
                runCurrent()
                val background = async { service.pendingOutboundPrivateCounterparties(Priority.Background) }
                val barrier = if (withBarrier) async { service.signOut() } else null
                val foreground = async { service.payment() }
                runCurrent()
                assertTrue(events.isEmpty())

                release.complete(Unit)
                active.await()
                background.await()
                barrier?.await()
                foreground.await()

                assertEquals(
                    if (withBarrier) listOf("background", "signout", "payment") else listOf("payment", "background"),
                    events,
                )
                verify(sdk, times(if (index == 0) 1 else 0)).claimPaymentRequestForExecution(RING_PUBKY, "request")
                verify(sdk, times(if (index == 1) 1 else 0)).claimAndAcceptPaymentRequest(RING_PUBKY, "request")
                verify(sdk, never()).acceptPaymentRequest(any(), any())
            }
        }
    }

    @Test
    fun `queued backup rechecks submission state and releases the queue while deferred`() = runTest {
        for (cancelBackup in listOf(false, true)) {
            val sdk = mock<PaykitSdk>()
            val release = CompletableDeferred<Unit>()
            whenever { sdk.contactRecords() }.doSuspendableAnswer {
                release.await()
                emptyList()
            }
            whenever { sdk.exportBackupString() }.thenReturn("pending-write-backup")
            val service = PaykitSdkService(mock(), mock(), mock(), settingsStore = mock()) { sdk }
            val active = async { service.contactRecords() }
            runCurrent()
            val backup = async { service.exportBackupState() }
            runCurrent()
            service.setPaymentSubmissionActive(true)
            release.complete(Unit)
            active.await()
            runCurrent()

            assertFalse(backup.isCompleted)
            verify(sdk, never()).exportBackupString()
            service.linkedPeers(Priority.Interactive)
            if (cancelBackup) {
                backup.cancel()
                assertFailsWith<CancellationException> { backup.await() }
            }
            service.setPaymentSubmissionActive(false)
            runCurrent()
            if (!cancelBackup) assertEquals("pending-write-backup", backup.await())
            verify(sdk, times(if (cancelBackup) 0 else 1)).exportBackupString()
        }
    }

    @Test
    fun `deferred backup cannot export a replaced identity or wiped wallet`() = runTest {
        val resets = listOf<suspend PaykitSdkService.() -> Unit>(
            { clearState() },
            { signOut() },
            { withWalletWipe {} },
        )
        for (reset in resets) {
            val sdk = mock<PaykitSdk>()
            whenever { sdk.exportBackupString() }.thenReturn("replacement-backup")
            val service = PaykitSdkService(mock(), mock(), mock(), settingsStore = mock()) { sdk }
            service.setPaymentSubmissionActive(true)
            val backup = async { runSuspendCatching { service.exportBackupState() } }
            runCurrent()

            service.reset()
            service.setPaymentSubmissionActive(false)

            assertTrue(backup.await().isFailure)
            verify(sdk, never()).exportBackupString()
        }
    }

    @Test
    fun `queued peer delivery rechecks submission and releases the queue while deferred`() = runTest {
        for (cancelDelivery in listOf(false, true)) {
            val sdk = mock<PaykitSdk>()
            val release = CompletableDeferred<Unit>()
            val report = mock<OutboundPrivateSendReport>()
            whenever { sdk.contactRecords() }.doSuspendableAnswer {
                release.await()
                emptyList()
            }
            whenever { sdk.identityStatus() }
                .thenReturn(IdentityStatus(RING_PUBKY, PubkyIdentityCapability.PRIVATE_LINK_CAPABLE))
            whenever { sdk.processOutboundPrivateMessages(RING_PUBKY) }.thenReturn(report)
            val service = PaykitSdkService(mock(), mock(), mock(), settingsStore = mock()) { sdk }
            val active = async { service.contactRecords() }
            runCurrent()
            val delivery = async {
                service.processOutboundPrivateMessages(RING_PUBKY, Priority.Background, RING_PUBKY)
            }
            runCurrent()
            service.setPaymentSubmissionActive(true)
            release.complete(Unit)
            active.await()
            runCurrent()

            assertFalse(delivery.isCompleted)
            verify(sdk, never()).identityStatus()
            verify(sdk, never()).processOutboundPrivateMessages(any())
            service.linkedPeers(Priority.Interactive)
            if (cancelDelivery) {
                delivery.cancel()
                assertFailsWith<CancellationException> { delivery.await() }
            }
            service.setPaymentSubmissionActive(false)
            runCurrent()
            if (!cancelDelivery) assertSame(report, delivery.await())
            verify(sdk, times(if (cancelDelivery) 0 else 1)).processOutboundPrivateMessages(RING_PUBKY)
        }
    }

    @Test
    fun `peer delivery defers when submission starts during its identity check`() = runTest {
        val sdk = mock<PaykitSdk>()
        val checkingIdentity = CompletableDeferred<Unit>()
        val releaseIdentity = CompletableDeferred<Unit>()
        val report = mock<OutboundPrivateSendReport>()
        whenever { sdk.identityStatus() }.doSuspendableAnswer {
            checkingIdentity.complete(Unit)
            releaseIdentity.await()
            IdentityStatus(RING_PUBKY, PubkyIdentityCapability.PRIVATE_LINK_CAPABLE)
        }
        whenever { sdk.processOutboundPrivateMessages(RING_PUBKY) }.thenReturn(report)
        val service = PaykitSdkService(mock(), mock(), mock(), settingsStore = mock()) { sdk }
        val delivery = async {
            service.processOutboundPrivateMessages(RING_PUBKY, Priority.Background, RING_PUBKY)
        }
        checkingIdentity.await()
        service.setPaymentSubmissionActive(true)
        releaseIdentity.complete(Unit)
        runCurrent()

        assertFalse(delivery.isCompleted)
        verify(sdk, never()).processOutboundPrivateMessages(any())
        service.linkedPeers(Priority.Interactive)
        service.setPaymentSubmissionActive(false)
        assertSame(report, delivery.await())
        verify(sdk).processOutboundPrivateMessages(RING_PUBKY)
    }

    @Test
    fun `deferred peer delivery cannot cross a runtime replacement or wallet wipe`() = runTest {
        val resets = listOf<suspend PaykitSdkService.() -> Unit>(
            { clearState() },
            { signOut() },
            { withWalletWipe {} },
        )
        for (reset in resets) {
            val sdk = mock<PaykitSdk>()
            whenever { sdk.identityStatus() }
                .thenReturn(IdentityStatus(RING_PUBKY, PubkyIdentityCapability.PRIVATE_LINK_CAPABLE))
            whenever { sdk.processOutboundPrivateMessages(RING_PUBKY) }.thenReturn(mock())
            val service = PaykitSdkService(mock(), mock(), mock(), settingsStore = mock()) { sdk }
            service.setPaymentSubmissionActive(true)
            val delivery = async {
                runSuspendCatching {
                    service.processOutboundPrivateMessages(RING_PUBKY, Priority.Background, RING_PUBKY)
                }
            }
            runCurrent()

            service.reset()
            service.setPaymentSubmissionActive(false)

            assertTrue(delivery.await().isFailure)
            verify(sdk, never()).processOutboundPrivateMessages(any())
        }
    }

    @Test
    fun `foreground peer delivery remains available during payment submission`() = runTest {
        for (priority in listOf(Priority.Ordered, Priority.Interactive)) {
            val sdk = mock<PaykitSdk>()
            val report = mock<OutboundPrivateSendReport>()
            whenever { sdk.identityStatus() }
                .thenReturn(IdentityStatus(RING_PUBKY, PubkyIdentityCapability.PRIVATE_LINK_CAPABLE))
            whenever { sdk.processOutboundPrivateMessages(RING_PUBKY) }.thenReturn(report)
            val service = PaykitSdkService(mock(), mock(), mock(), settingsStore = mock()) { sdk }
            service.setPaymentSubmissionActive(true)

            assertSame(report, service.processOutboundPrivateMessages(RING_PUBKY, priority, RING_PUBKY))
            verify(sdk).processOutboundPrivateMessages(RING_PUBKY)
        }
    }

    @Test
    fun `deferred peer delivery checks identity inside the queue`() = runTest {
        val sdk = mock<PaykitSdk>()
        val release = CompletableDeferred<Unit>()
        whenever { sdk.contactRecords() }.doSuspendableAnswer {
            release.await()
            emptyList()
        }
        whenever { sdk.identityStatus() }.thenReturn(IdentityStatus(null, PubkyIdentityCapability.SIGNED_OUT))
        val service = PaykitSdkService(mock(), mock(), mock(), settingsStore = mock()) { sdk }
        val active = async { service.contactRecords() }
        runCurrent()
        val delivery = async {
            runSuspendCatching { service.processOutboundPrivateMessages(RING_PUBKY, Priority.Background, RING_PUBKY) }
        }
        runCurrent()
        release.complete(Unit)
        active.await()

        assertIs<IllegalStateException>(delivery.await().exceptionOrNull())
        verify(sdk, never()).processOutboundPrivateMessages(any())
    }

    @Test
    fun `cancelling active acceptance waits for its durable call and preserves backup notification`() = runTest {
        val sdk = mock<PaykitSdk>()
        val releaseAcceptance = CompletableDeferred<Unit>()
        var acceptanceFinished = false
        whenever { sdk.claimAndAcceptPaymentRequest(RING_PUBKY, "request") }.doSuspendableAnswer {
            releaseAcceptance.await()
            acceptanceFinished = true
            mock()
        }
        val service = PaykitSdkService(mock(), mock(), mock(), settingsStore = mock()) { sdk }
        val acceptance = async { service.acceptPaymentRequest(RING_PUBKY, "request") }
        runCurrent()
        acceptance.cancel()
        runCurrent()
        assertFalse(acceptance.isCompleted)
        releaseAcceptance.complete(Unit)

        assertFailsWith<CancellationException> { acceptance.await() }
        assertTrue(acceptanceFinished)
        verify(sdk).claimAndAcceptPaymentRequest(RING_PUBKY, "request")
        verify(sdk, never()).claimPaymentRequestForExecution(any(), any())
        verify(sdk, never()).acceptPaymentRequest(any(), any())
        assertEquals(1L, service.backupStateVersion.value)
    }

    @Test
    fun `cancelling the initial backup read completes it without starting the mutation`() = runTest {
        val sdk = mock<PaykitSdk>()
        val releaseRead = CompletableDeferred<Unit>()
        var readFinished = false
        whenever { sdk.backupStateRevision() }.doSuspendableAnswer {
            releaseRead.await()
            readFinished = true
            "backup"
        }
        val service = PaykitSdkService(mock(), mock(), mock(), settingsStore = mock()) { sdk }
        val receive = async { service.receivePrivateMessages(RING_PUBKY) }
        runCurrent()
        receive.cancel()
        runCurrent()
        assertFalse(receive.isCompleted)
        releaseRead.complete(Unit)

        assertFailsWith<CancellationException> { receive.await() }
        assertTrue(readFinished)
        verify(sdk, never()).receivePrivateMessages(any())
        assertEquals(0L, service.backupStateVersion.value)
    }

    @Test
    fun `identity failure during cancellation invalidates the cached backup snapshot`() = runTest {
        val sdk = mock<PaykitSdk>()
        whenever(sdk.stateRevision()).thenReturn("state")
        whenever(sdk.observedBackupStateRevision()).thenReturn(ObservedBackupStateRevision("state", "backup"))
        whenever { sdk.backupStateRevision() }.thenReturn("backup")
        whenever { sdk.processPendingPrivateMessages() }.thenReturn(emptyList())
        val service = PaykitSdkService(mock(), mock(), mock(), settingsStore = mock()) { sdk }
        service.processPendingPrivateMessages()

        val releaseRead = CompletableDeferred<Unit>()
        whenever { sdk.identityStatus() }.doSuspendableAnswer {
            releaseRead.await()
            throw PaykitException.Identity("identity_error", "Identity changed")
        }
        val read = async { service.identityStatus() }
        runCurrent()
        read.cancel()
        releaseRead.complete(Unit)
        assertFailsWith<CancellationException> { read.await() }

        service.processPendingPrivateMessages()

        verify(sdk, times(2)).backupStateRevision()
    }

    @Test
    fun `initialization can be retried after shared state contention`() = runTest {
        val failure = PaykitException.ConcurrentUpdate("concurrent_update", "Resource locked")
        val sdk = mock<PaykitSdk>()
        whenever(sdk.initialize()).thenThrow(failure).thenReturn(mock())
        whenever(sdk.contactRecords()).thenReturn(emptyList())
        val service = PaykitSdkService(
            mock(),
            mock(),
            mock(),
            ioDispatcher = StandardTestDispatcher(testScheduler),
            platformInitializer = {},
            settingsStore = mock(),
            sdkFactory = { sdk },
        )

        assertSame(failure, assertFailsWith<PaykitException.ConcurrentUpdate> { service.initialize() })
        assertSame(failure, assertFailsWith<PaykitException.ConcurrentUpdate> { service.contactRecords() })
        service.initialize()
        assertEquals(emptyList(), service.contactRecords())
    }

    @Test
    fun `saved session setup activates once before admitting public reads`() = runTest {
        val keychain = mock<Keychain>()
        val store = mock<PubkyStore>()
        whenever(store.data).thenReturn(flowOf(PubkyStoreData()))
        val settings = mock<SettingsStore>()
        whenever(settings.data).thenReturn(flowOf(SettingsData(sharesPrivatePaykitEndpoints = false)))
        val bootstrap = mock<PubkySessionBootstrap>()
        val imported = CompletableDeferred<PubkySessionBootstrapResult>()
        whenever(bootstrap.importSession("saved-session", null, "capabilities"))
            .doSuspendableAnswer { imported.await() }
        val sdk = mock<PaykitSdk>()
        val initialized = CompletableDeferred<IdentityStatus>()
        whenever(sdk.initialize()).doSuspendableAnswer { initialized.await() }
        var handlesCreated = 0
        val service = PaykitSdkService(
            mock(),
            keychain,
            store,
            bootstrapFactory = { bootstrap },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            platformInitializer = {},
            settingsStore = settings,
        ) {
            handlesCreated++
            sdk
        }
        mockStatic(Class.forName("com.synonym.paykit.Paykit_androidKt")).use { native ->
            native.`when`<String> { paykitAuthorizerSessionCapabilities() }.thenReturn("capabilities")
            val restoration = async { service.initializeAndImportSession("saved-session") }
            val read = async { service.resolveContactProfile(RING_PUBKY, true) }
            runCurrent()
            assertFalse(read.isCompleted)
            assertEquals(0, handlesCreated)

            val access = localSessionAccess(ByteArray(32) { 1 })
            val result = PubkySessionBootstrapResult(access, RING_PUBKY, PubkyIdentityCapability.PRIVATE_LINK_CAPABLE)
            imported.complete(result)
            runCurrent()
            assertFalse(read.isCompleted)
            verify(sdk, never()).resolveProfile(any(), any())
            initialized.complete(IdentityStatus(RING_PUBKY, PubkyIdentityCapability.PRIVATE_LINK_CAPABLE))

            assertSame(result, restoration.await().getOrThrow())
            assertNull(read.await())
            service.initialize()
            assertEquals(1, handlesCreated)
            inOrder(keychain, sdk) {
                verify(keychain).upsertString(Keychain.Key.PAYKIT_SESSION.name, "new-session")
                verify(sdk).initialize()
                verify(sdk).publishPaykitNoiseKeyAuthorization()
                verify(sdk).publishPaykitApp("Bitkit", PaykitAppCapabilities(false, true, false, true))
                verify(sdk).resolveProfile(RING_PUBKY, true)
            }
            verify(sdk, times(1)).initialize()
            verify(sdk, times(1)).publishPaykitApp(any(), any())
        }
    }

    @Test
    fun `failed saved session import retains credentials and permits setup retry`() = runTest {
        val failures = listOf(
            PaykitException.Identity("identity_error", "Expired session"),
            PaykitException.Transport("transport_error", "Offline"),
            CancellationException("Cancelled"),
        )
        for (failure in failures) {
            val keychain = mock<Keychain>()
            val bootstrap = mock<PubkySessionBootstrap>()
            whenever(bootstrap.importSession("saved-session", null, "capabilities")).thenThrow(failure)
            val sdk = mock<PaykitSdk>()
            whenever(sdk.contactRecords()).thenReturn(emptyList())
            val service = PaykitSdkService(
                mock(),
                keychain,
                mock(),
                bootstrapFactory = { bootstrap },
                ioDispatcher = StandardTestDispatcher(testScheduler),
                platformInitializer = {},
                settingsStore = mock(),
            ) { sdk }

            mockStatic(Class.forName("com.synonym.paykit.Paykit_androidKt")).use { native ->
                native.`when`<String> { paykitAuthorizerSessionCapabilities() }.thenReturn("capabilities")
                if (failure is CancellationException) {
                    assertFailsWith<CancellationException> { service.initializeAndImportSession("saved-session") }
                    assertFailsWith<CancellationException> { service.contactRecords() }
                    service.initialize()
                    verify(sdk).initialize()
                } else {
                    assertSame(failure, service.initializeAndImportSession("saved-session").exceptionOrNull())
                    verify(sdk, never()).initialize()
                }
            }
            assertEquals(emptyList(), service.contactRecords())
            verify(keychain, never()).delete(any())
            verify(keychain, never()).upsertString(any(), any())
        }
    }

    @Test
    fun `session activation conflict returns without fallback and retry completes authorization`() = runTest {
        for (failAuthorization in listOf(false, true)) {
            val keychain = mock<Keychain>()
            val store = mock<PubkyStore>()
            whenever(store.data).thenReturn(flowOf(PubkyStoreData()))
            val settings = mock<SettingsStore>()
            whenever(settings.data).thenReturn(flowOf(SettingsData(sharesPrivatePaykitEndpoints = false)))
            val bootstrap = mock<PubkySessionBootstrap>()
            val result = PubkySessionBootstrapResult(
                localSessionAccess(ByteArray(32) { 1 }),
                RING_PUBKY,
                PubkyIdentityCapability.PRIVATE_LINK_CAPABLE,
            )
            whenever { bootstrap.importSession("saved-session", null, "capabilities") }.thenReturn(result)
            whenever { bootstrap.importSession("new-session", null, "capabilities") }.thenReturn(result)
            val sdk = mock<PaykitSdk>()
            val failure = PaykitException.ConcurrentUpdate("concurrent_update", "Resource locked")
            val status = IdentityStatus(RING_PUBKY, PubkyIdentityCapability.PRIVATE_LINK_CAPABLE)
            whenever { sdk.initialize() }.thenReturn(status)
            if (failAuthorization) {
                whenever { sdk.publishPaykitNoiseKeyAuthorization() }.thenThrow(failure).thenReturn(mock())
            } else {
                whenever { sdk.initialize() }.thenThrow(failure).thenReturn(status)
            }
            val service = PaykitSdkService(
                mock(),
                keychain,
                store,
                bootstrapFactory = { bootstrap },
                ioDispatcher = StandardTestDispatcher(testScheduler),
                platformInitializer = {},
                settingsStore = settings,
            ) { sdk }

            mockStatic(Class.forName("com.synonym.paykit.Paykit_androidKt")).use { native ->
                native.`when`<String> { paykitAuthorizerSessionCapabilities() }.thenReturn("capabilities")
                assertSame(failure, service.initializeAndImportSession("saved-session").exceptionOrNull())
                verify(sdk, times(1)).initialize()
                verify(sdk, never()).publishPaykitApp(any(), any())
                verify(keychain).upsertString(Keychain.Key.PAYKIT_SESSION.name, "new-session")
                verify(keychain, never()).delete(any())

                assertSame(result, service.importSession("new-session"))
                verify(sdk, times(2)).initialize()
                verify(sdk, times(if (failAuthorization) 2 else 1)).publishPaykitNoiseKeyAuthorization()
                verify(sdk).publishPaykitApp("Bitkit", PaykitAppCapabilities(false, true, false, true))
            }
        }
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
            settingsStore = mock(),
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
    fun `wallet wipe discards handles and backup fingerprints even when cleanup fails`() = runTest {
        val sdk = mock<PaykitSdk>()
        whenever(sdk.processPendingPrivateMessages()).thenReturn(emptyList())
        whenever(sdk.stateRevision()).thenReturn("state")
        whenever(sdk.observedBackupStateRevision()).thenReturn(ObservedBackupStateRevision("state", "backup"))
        whenever(sdk.backupStateRevision()).thenReturn("backup")
        var handlesCreated = 0
        val service = PaykitSdkService(mock(), mock(), mock(), settingsStore = mock()) {
            handlesCreated++
            sdk
        }
        repeat(2) { service.processPendingPrivateMessages() }
        assertFailsWith<AppError> {
            service.withWalletWipe {
                service.processPendingPrivateMessages()
                assertEquals(2, handlesCreated)
                throw AppError("Cleanup failed")
            }
        }
        service.processPendingPrivateMessages()
        assertEquals(3, handlesCreated)
        verify(sdk, times(3)).backupStateRevision()
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
    fun `payer ownership follows execution claims and released actionable work`() {
        data class Case(
            val state: PaymentRequestLifecycleState,
            val payer: String?,
            val claim: String?,
            val hasProof: Boolean = false,
            val visible: Boolean,
        )
        val cases = listOf(
            Case(PaymentRequestLifecycleState.PROPOSED, null, null, visible = true),
            Case(PaymentRequestLifecycleState.ACCEPTED, "other", "bitkit", visible = true),
            Case(PaymentRequestLifecycleState.ACCEPTED, "bitkit", "other", visible = false),
            Case(PaymentRequestLifecycleState.ACCEPTED, "other", null, visible = true),
            Case(PaymentRequestLifecycleState.ACCEPTED, "other", null, hasProof = true, visible = false),
            Case(PaymentRequestLifecycleState.ACTIVE_RECURRING, "other", null, hasProof = true, visible = true),
            Case(PaymentRequestLifecycleState.ACTIVE_RECURRING, "bitkit", "other", visible = false),
            Case(PaymentRequestLifecycleState.PROOF_SUBMITTED, "other", "bitkit", hasProof = true, visible = true),
            Case(PaymentRequestLifecycleState.PROOF_SUBMITTED, "other", null, hasProof = true, visible = false),
            Case(PaymentRequestLifecycleState.CANCELED, "other", null, visible = false),
            Case(PaymentRequestLifecycleState.CANCELED, "bitkit", null, visible = true),
            Case(PaymentRequestLifecycleState.PROPOSAL_EXPIRED, null, null, visible = true),
        )
        cases.forEach { case ->
            val record = mock<PaymentRequestRecord>()
            whenever(record.localRole).thenReturn(PaymentRequestLocalRole.PAYER)
            whenever(record.state).thenReturn(case.state)
            whenever(record.payerAppId).thenReturn(case.payer)
            whenever(record.executionClaimAppId).thenReturn(case.claim)
            whenever(record.paymentProofs).thenReturn(if (case.hasProof) listOf(mock()) else emptyList())

            assertEquals(case.visible, isBitkitPaymentRequest(record), case.toString())
        }
    }

    @Test
    fun `all payment requests retain server payee records while payment API remains app scoped`() = runTest {
        val server = mock<PaymentRequestRecord> {
            on { localRole }.thenReturn(PaymentRequestLocalRole.PAYEE)
            on { proposalAppId }.thenReturn("marketplace")
        }
        val bitkit = mock<PaymentRequestRecord> {
            on { localRole }.thenReturn(PaymentRequestLocalRole.PAYEE)
            on { proposalAppId }.thenReturn("bitkit")
        }
        val sdk = mock<PaykitSdk>()
        whenever(
            sdk.identityStatus()
        ).thenReturn(IdentityStatus(RING_PUBKY, PubkyIdentityCapability.PRIVATE_LINK_CAPABLE))
        whenever(sdk.listPaymentRequests(any())).thenReturn(listOf(server, bitkit))
        val service = PaykitSdkService(mock(), mock(), mock(), settingsStore = mock()) { sdk }

        assertEquals(listOf(server, bitkit), service.allPaymentRequests(RING_PUBKY))
        assertEquals(listOf(bitkit), service.paymentRequests())
    }

    @Test
    fun `all payment requests reject a different active identity before listing`() = runTest {
        val sdk = mock<PaykitSdk>()
        whenever(
            sdk.identityStatus()
        ).thenReturn(IdentityStatus(RING_PUBKY, PubkyIdentityCapability.PRIVATE_LINK_CAPABLE))
        val service = PaykitSdkService(mock(), mock(), mock(), settingsStore = mock()) { sdk }

        assertFailsWith<IllegalStateException> {
            service.allPaymentRequests("a3mduedw686dysw8ndr5c1dyry5h8k6i8hzbbmx9gf9k43zq4s9o")
        }
        verify(sdk, never()).listPaymentRequests(any())
    }

    @Test
    fun `companion approval rejects mismatched requests before accessing keys or transport`() = runTest {
        val watchOnly = PubkyAuthClaim(Item.WATCH_ONLY_ACCOUNT_V1)
        val claim = PubkyAuthCompanionClaim(
            queryParameter = PubkyAuthClaim.QUERY_PARAMETER,
            claimType = watchOnly.wireValue,
            unsignedPayload = ByteArray(84).apply { this[0] = 1 },
        )
        val url = "pubkyauth://signin?x-bitkit-claim=${watchOnly.wireValue}"
        val invalidRequests = listOf(
            "pubkyauth://signin" to claim,
            "$url&x-bitkit-claim=${watchOnly.wireValue}" to claim,
            "pubkyauth://signin?x-bitkit-claim=unknown" to claim,
            "$url." to claim,
            "$url.${watchOnly.wireValue}" to claim,
            "pubkyauth://signin?x-bitkit-claim=paykit-access-and-watch-only-account-v1" to claim,
            "pubkyauth://signin?x-bitkit-claim=paykit-access-v1.watch-only-account-v1" to
                claim.copy(claimType = "watch-only-account-v1.paykit-access-v1"),
            "pubkyauth://signin?x-bitkit-claim=watch-only-account-v1.paykit-access-v1" to
                claim.copy(claimType = "paykit-access-v1.watch-only-account-v1"),
            url to claim.copy(queryParameter = "other-claim"),
            url to claim.copy(claimType = PubkyAuthClaim(Item.PAYKIT_ACCESS_V1).wireValue),
            url to claim.copy(unsignedPayload = ByteArray(124)),
            "pubkyauth://signin?x-bitkit-claim=paykit-access-v1" to
                claim.copy(claimType = PubkyAuthClaim(Item.PAYKIT_ACCESS_V1).wireValue),
        )
        for ((authUrl, companion) in invalidRequests) {
            val keychain = mock<Keychain>()
            val sdk = mock<PaykitSdk>()
            val service = PaykitSdkService(mock(), keychain, mock(), settingsStore = mock()) { sdk }
            assertTrue(
                runSuspendCatching {
                    service.approveAuthWithCompanionClaim(
                        authUrl,
                        PubkyAuthClaim.REQUIRED_CAPABILITIES,
                        "test",
                        "secret",
                        companion,
                    )
                }.isFailure,
            )
            verifyNoInteractions(keychain, sdk)
        }
    }

    @Test
    fun `registered identity activation persists credentials or clears partial activation`() = runTest {
        for (failure in listOf(null, "session", "secret", "initialize", "authorize", "cancel")) {
            val keychain = mock<Keychain>()
            val blocking = mock<Keychain.BlockingAccess>()
            whenever(keychain.accessBlocking<Any?>(any())).doAnswer {
                it.getArgument<Keychain.BlockingAccess.() -> Any?>(0).invoke(blocking)
            }
            val bytes = ByteArray(32) { 1 }
            val sdk = mock<PaykitSdk>()
            whenever(sdk.contactRecords()).thenReturn(emptyList())
            whenever(sdk.initialize()).thenReturn(
                IdentityStatus(RING_PUBKY, PubkyIdentityCapability.PRIVATE_LINK_CAPABLE),
            )
            val access = localSessionAccess(bytes)
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
                "authorize" -> whenever(sdk.publishPaykitNoiseKeyAuthorization()).thenThrow(error)
            }
            var handlesCreated = 0
            val store = mock<PubkyStore>()
            whenever(store.data).thenReturn(flowOf(PubkyStoreData()))
            val settingsStore = mock<SettingsStore>()
            whenever(settingsStore.data).thenReturn(flowOf(SettingsData(sharesPrivatePaykitEndpoints = false)))
            val service = PaykitSdkService(mock(), keychain, store, settingsStore = settingsStore) {
                handlesCreated++
                sdk
            }
            val result = PubkySessionBootstrapResult(access, "pubky_test", PubkyIdentityCapability.PRIVATE_LINK_CAPABLE)

            if (failure == null) {
                service.activateRegisteredIdentity(result)
                inOrder(keychain, sdk) {
                    verify(keychain).upsertString(Keychain.Key.PAYKIT_SESSION.name, "new-session")
                    verify(keychain).upsertString(Keychain.Key.PUBKY_SECRET_KEY.name, bytes.toHex())
                    verify(sdk).initialize()
                    verify(sdk).publishPaykitNoiseKeyAuthorization()
                    verify(sdk).publishPaykitApp("Bitkit", PaykitAppCapabilities(false, true, false, true))
                }
                verify(sdk, never()).identityStatus()
                verify(blocking, never()).delete(any())
            } else {
                val thrown = assertFailsWith(error::class) { service.activateRegisteredIdentity(result) }
                if (failure == "cancel") assertEquals(error.message, thrown.message) else assertSame(error, thrown)
                verify(blocking).delete(Keychain.Key.PAYKIT_SESSION.name)
                verify(blocking).delete(Keychain.Key.PUBKY_SECRET_KEY.name)
                val handlesBeforeReload = handlesCreated
                service.contactRecords()
                assertEquals(handlesBeforeReload + 1, handlesCreated)
            }
        }
    }

    @Test
    fun `deletion blocks the identity before removing the contact`() = runTest {
        for ((state, failBlock) in listOf(
            LinkedPeerState.LINKED to false,
            LinkedPeerState.LINKED to true,
            null to false,
            null to true,
        )) {
            val sdk = mock<PaykitSdk>()
            whenever(sdk.paymentRequests()).thenReturn(emptyList())
            val contact = mock<ContactRecord>()
            whenever(sdk.contactRecord(RING_PUBKY)).thenReturn(contact)
            whenever(sdk.linkedPeers()).thenReturn(listOfNotNull(state?.let { contactPeer(it) }))
            if (failBlock) {
                whenever(sdk.blockPeer(RING_PUBKY))
                    .thenThrow(IllegalStateException("storage failure"))
            }
            val service = PaykitSdkService(mock(), mock(), mock(), settingsStore = mock()) { sdk }
            if (failBlock) {
                assertFailsWith<IllegalStateException> { service.removeContact(RING_PUBKY) }
                verify(sdk, never()).removeContact(any())
            } else {
                service.removeContact(RING_PUBKY)
                inOrder(sdk) {
                    verify(sdk).blockPeer(RING_PUBKY)
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
            val service = PaykitSdkService(mock(), mock(), mock(), settingsStore = mock()) { sdk }
            assertFailsWith<PubkyContactError.ActiveSubscription> { service.removeContact(RING_PUBKY) }
            assertTrue(service.removeContacts(listOf(RING_PUBKY)).isEmpty())
            verify(sdk, never()).blockPeer(any())
            verify(sdk, never()).removeContact(any())
            verify(sdk, never()).removeContactsAndBlockPeers(any())
            if (endsNaturally) {
                whenever(recurrence.endsAt).thenReturn("2026-02-01T00:00:00Z")
            } else {
                whenever(request.state).thenReturn(PaymentRequestLifecycleState.CANCELED)
            }
            service.removeContact(RING_PUBKY)
            verify(sdk).removeContact(RING_PUBKY)
            val removed = listOf(mock<ContactRecord>())
            whenever(sdk.removeContactsAndBlockPeers(listOf(RING_PUBKY))).thenReturn(removed)
            assertEquals(removed, service.removeContacts(listOf(RING_PUBKY)))
        }
    }

    @Test
    fun `unreadable profile cache stops activation`() = runTest {
        for (error in listOf(
            PaykitException.Identity("identity_error", "restore Pubky grant session from platform provider"),
            PaykitException.Storage("storage_error", "unavailable"),
        )) {
            val keychain = mock<Keychain>()
            val sdk = mock<PaykitSdk>()
            val store = mock<PubkyStore>()
            whenever(store.data).thenReturn(flow { throw error })
            val access = mock<PubkySessionAccess>()
            whenever(access.exportSessionSecret()).thenReturn("new-session")
            val service = PaykitSdkService(mock(), keychain, store, settingsStore = mock()) { sdk }

            val thrown = assertFailsWith<PaykitException> {
                service.activateRegisteredIdentity(
                    PubkySessionBootstrapResult(
                        access,
                        "pubky_test",
                        PubkyIdentityCapability.PRIVATE_LINK_CAPABLE
                    )
                )
            }

            assertEquals(error, thrown)
            verify(sdk, never()).initialize()
        }
    }

    @Test
    fun `deletion withdraws private endpoints before blocking even when withdrawal fails`() = runTest {
        for (failWithdrawal in listOf(false, true)) {
            val sdk = mock<PaykitSdk>()
            whenever(sdk.paymentRequests()).thenReturn(emptyList())
            whenever(sdk.linkedPeers()).thenReturn(
                listOf(contactPeer(LinkedPeerState.LINKED)),
            )
            val withdrawal = whenever(
                sdk.clearPrivatePaymentListAndProcessOutbound(RING_PUBKY),
            )
            if (failWithdrawal) {
                withdrawal.thenThrow(IllegalStateException("network unavailable"))
            } else {
                withdrawal.thenReturn(
                    PrivatePaymentListDeliveryReport(emptyList(), emptyList(), emptyList(), emptyList()),
                )
            }
            val service = PaykitSdkService(mock(), mock(), mock(), settingsStore = mock()) { sdk }
            service.removeContact(RING_PUBKY)
            inOrder(sdk) {
                verify(sdk).clearPrivatePaymentListAndProcessOutbound(RING_PUBKY)
                verify(sdk).blockPeer(RING_PUBKY)
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
            val sdk = mock<PaykitSdk>()
            whenever(
                sdk.identityStatus()
            ).thenReturn(IdentityStatus(previousKey, PubkyIdentityCapability.PUBLIC_ONLY))
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
            val noise = mock<PaykitIdentitySecretKey>()
            whenever(access.exportSessionSecret()).thenReturn("new-session")
            whenever(access.exportPaykitIdentitySecretKey()).thenReturn(noise)
            val service = PaykitSdkService(mock(), keychain, store, { bootstrap }, settingsStore = mock()) { sdk }

            val result =
                PubkySessionBootstrapResult(
                    access,
                    "pubky$originalKey",
                    PubkyIdentityCapability.PRIVATE_LINK_CAPABLE
                )
            if (resetFails) {
                assertEquals(resetError, assertFailsWith<AppError> { service.activateRegisteredIdentity(result) })
                verify(sdk, never()).initialize()
                assertEquals(originalCache, cache)
                continue
            }
            service.activateRegisteredIdentity(result)

            if (cachedOwner == differentKey) {
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
        val service = PaykitSdkService(mock(), mock(), mock(), settingsStore = mock()) { sdk }
        whenever(sdk.contactRecords()).thenReturn(emptyList())
        service.contactRecords()
        val readGate = CompletableDeferred<ProfileResolution?>()
        whenever(sdk.resolveProfile(RING_PUBKY, true))
            .doSuspendableAnswer { readGate.await() }

        val read = async { service.resolveContactProfile(RING_PUBKY, allowPubkyProfileFallback = true) }
        runCurrent()
        assertEquals(emptyList<ContactRecord>(), service.contactRecords())
        assertFalse(read.isCompleted)
        readGate.complete(null)
        assertNull(read.await())

        val lockedGate = CompletableDeferred<List<ContactRecord>>()
        whenever(sdk.contactRecords()).doSuspendableAnswer { lockedGate.await() }
        whenever(sdk.fetchPubkyFollows(RING_PUBKY, 10_000u)).thenReturn(listOf("follow"))
        whenever(sdk.resolvePublicContactPayment(RING_PUBKY, null)).thenReturn(
            PublicContactPaymentResolution(PublicPaymentResolutionStatus.NO_ENDPOINT, emptyList(), emptyList()),
        )
        val locked = async { service.contactRecords() }
        runCurrent()
        assertEquals(listOf("follow"), service.fetchPubkyFollows(RING_PUBKY))
        assertTrue(service.resolvePublicContactPayment(RING_PUBKY).payableEndpoints.isEmpty())
        assertFalse(locked.isCompleted)
        lockedGate.complete(emptyList())
        assertEquals(emptyList<ContactRecord>(), locked.await())
    }

    @Test
    fun `public payment resolution waits for setup`() = runTest {
        val sdk = mock<PaykitSdk>()
        whenever(sdk.resolvePublicContactPayment(RING_PUBKY, null)).thenReturn(
            PublicContactPaymentResolution(PublicPaymentResolutionStatus.NO_ENDPOINT, emptyList(), emptyList()),
        )
        val service = PaykitSdkService(
            mock(),
            mock(),
            mock(),
            ioDispatcher = StandardTestDispatcher(testScheduler),
            platformInitializer = {},
            settingsStore = mock(),
            sdkFactory = { sdk },
        )
        val read = async { service.resolvePublicContactPayment(RING_PUBKY) }
        runCurrent()
        assertFalse(read.isCompleted)
        verify(sdk, never()).resolvePublicContactPayment(RING_PUBKY, null)
        service.initialize()
        assertTrue(read.await().payableEndpoints.isEmpty())
    }

    @Test
    fun `public payment resolution rejects interruption and allows fresh reads`() = runTest {
        for (interruption in listOf("reset", "wipe", "cancel")) {
            val sdk = mock<PaykitSdk>()
            val gate = CompletableDeferred<Unit>()
            whenever(sdk.resolvePublicContactPayment(RING_PUBKY, null)).doSuspendableAnswer {
                gate.await()
                PublicContactPaymentResolution(PublicPaymentResolutionStatus.NO_ENDPOINT, emptyList(), emptyList())
            }
            val service = PaykitSdkService(mock(), mock(), mock(), settingsStore = mock()) { sdk }
            val read = async { runSuspendCatching { service.resolvePublicContactPayment(RING_PUBKY) } }
            runCurrent()
            when (interruption) {
                "reset" -> service.clearState()
                "wipe" -> service.withWalletWipe {}
                "cancel" -> read.cancel()
            }
            gate.complete(Unit)
            if (interruption == "cancel") {
                assertFailsWith<CancellationException> { read.await() }
            } else {
                assertTrue(read.await().isFailure)
            }
            assertTrue(service.resolvePublicContactPayment(RING_PUBKY).payableEndpoints.isEmpty())
        }
    }

    @Test
    fun `public reads run at most six at a time`() = runTest {
        val sdk = mock<PaykitSdk>()
        val service = PaykitSdkService(mock(), mock(), mock(), settingsStore = mock()) { sdk }
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
        whenever(keychain.upsertString(Keychain.Key.PAYKIT_SESSION.name, "session"))
            .doSuspendableAnswer { lockedGate.await() }
        val access = mock<PubkySessionAccess>()
        whenever(access.exportSessionSecret()).thenReturn("session")
        val store = mock<PubkyStore>()
        whenever(store.data).thenReturn(flowOf(PubkyStoreData()))
        val sdk = mock<PaykitSdk>()
        val readGate = CompletableDeferred<Unit>()
        var active = 0
        whenever(sdk.fetchPubkyFollows(RING_PUBKY, 10_000u)).doSuspendableAnswer {
            active++
            readGate.await()
            listOf("follow")
        }
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

        val locked = async {
            service.activateRegisteredIdentity(
                PubkySessionBootstrapResult(access, RING_PUBKY, PubkyIdentityCapability.PUBLIC_ONLY),
            )
        }
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
    fun `bulk reads leave two read permits to interactive reads`() = runTest {
        val sdk = mock<PaykitSdk>()
        val service = PaykitSdkService(mock(), mock(), mock(), settingsStore = mock()) { sdk }
        whenever(sdk.contactRecords()).thenReturn(emptyList())
        service.contactRecords()
        val gate = CompletableDeferred<Unit>()
        var bulkActive = 0
        var interactiveActive = 0
        whenever(sdk.resolveProfile(any(), any())).doSuspendableAnswer {
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
        verify(sdk, times(10)).resolveProfile(any(), any())
        verify(sdk, times(3)).fetchPubkyFileBounded(FILE_URI, 1uL)
    }

    @Test
    fun `bulk reads start in request order and a cancelled one frees both permits`() = runTest {
        val sdk = mock<PaykitSdk>()
        val service = PaykitSdkService(mock(), mock(), mock(), settingsStore = mock()) { sdk }
        whenever(sdk.contactRecords()).thenReturn(emptyList())
        service.contactRecords()
        val started = mutableListOf<String>()
        val gates = List(6) { CompletableDeferred<Unit>() }
        whenever(sdk.resolveProfile(any(), any())).doSuspendableAnswer {
            val key = it.getArgument<String>(0)
            started += key
            gates[key.removePrefix(RING_PUBKY).toInt()].await()
            null
        }
        val reads = List(6) {
            async { service.resolveContactProfile("$RING_PUBKY$it", true, PaykitReadLane.Bulk) }
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
    fun `a freed read slot goes to a waiting interactive read before bulk reads queued for a slot`() = runTest {
        val interactive = List(7) { "$FILE_URI$it" }
        val bulk = List(4) { "$RING_PUBKY$it" }
        val gates = (interactive + bulk).associateWith { CompletableDeferred<Unit>() }
        val started = mutableListOf<String>()
        val service = gatedReadService(gates, started)

        val reads = interactive.take(6).map { async { service.fetchFile(it, 1uL) } } +
            bulk.map { async { service.resolveContactProfile(it, true, PaykitReadLane.Bulk) } }
        runCurrent()
        val lateRead = async { service.fetchFile(interactive[6], 1uL) }
        runCurrent()
        assertEquals(interactive.take(6), started)

        gates.getValue(interactive[0]).complete(Unit)
        runCurrent()
        assertEquals(interactive, started)

        gates.getValue(interactive[1]).complete(Unit)
        runCurrent()
        assertEquals(interactive + bulk.first(), started)

        gates.values.forEach { it.complete(Unit) }
        (reads + lateRead).awaitAll()
        assertEquals(interactive + bulk, started)
    }

    @Test
    fun `a cancelled read slot waiter takes no slot and passes on one it was handed`() = runTest {
        listOf("cancelled while queued" to false, "cancelled once handed a slot" to true)
            .forEachCase({ it.first }) { (case, handed) ->
                val interactive = List(7) { "$FILE_URI$it" }
                val bulk = RING_PUBKY
                val gates = (interactive + bulk).associateWith { CompletableDeferred<Unit>() }
                val started = mutableListOf<String>()
                val service = gatedReadService(gates, started)
                val reads = interactive.take(6).map { async { service.fetchFile(it, 1uL) } } +
                    async { service.resolveContactProfile(bulk, true, PaykitReadLane.Bulk) }
                runCurrent()
                val lateRead = async { service.fetchFile(interactive[6], 1uL) }
                runCurrent()

                if (handed) {
                    reads.first().invokeOnCompletion { lateRead.cancel() }
                } else {
                    lateRead.cancel()
                    runCurrent()
                    assertEquals(interactive.take(6), started, case)
                }
                gates.getValue(interactive[0]).complete(Unit)
                runCurrent()

                assertTrue(lateRead.isCancelled, case)
                assertEquals(interactive.take(6) + bulk, started, case)
                gates.values.forEach { it.complete(Unit) }
                reads.awaitAll()
            }
    }

    @Test
    fun `a read timeout counts only the time the read holds its slot and fails with its own error`() = runTest {
        val sdk = mock<PaykitSdk>()
        val service = PaykitSdkService(mock(), mock(), mock(), settingsStore = mock()) { sdk }
        val busy = List(6) { "$RING_PUBKY-busy-$it" }
        val freeSlots = CompletableDeferred<Unit>()
        whenever(sdk.resolveProfile(any(), any())).doSuspendableAnswer {
            when (it.getArgument<String>(0)) {
                in busy -> freeSlots.await()
                "$RING_PUBKY-stuck" -> awaitCancellation()
            }
            null
        }
        val busyReads = busy.map { async { service.resolveContactProfile(it, true) } }
        runCurrent()
        val start = currentTime

        val timedOut = async {
            runCatching { service.resolveContactProfile("$RING_PUBKY-stuck", true, timeout = READ_TIMEOUT) }
        }
        advanceTimeBy(READ_TIMEOUT * 2)
        freeSlots.complete(Unit)
        busyReads.awaitAll()
        advanceTimeBy(READ_TIMEOUT - 1.milliseconds)
        runCurrent()
        assertFalse(timedOut.isCompleted)
        advanceTimeBy(1.milliseconds)
        runCurrent()

        assertIs<PaykitReadTimeoutError>(timedOut.await().exceptionOrNull())
        assertEquals((READ_TIMEOUT * 3).inWholeMilliseconds, currentTime - start)
        assertNull(service.resolveContactProfile("$RING_PUBKY-null", true, timeout = READ_TIMEOUT))
    }

    @Test
    fun `activation returns while identity publication runs and approval republish joins it until the cap`() = runTest {
        listOf("publication finishes" to true, "cap" to false).forEachCase({ it.first }) { (case, gateOpens) ->
            val keychain = mock<Keychain>()
            val store = mock<PubkyStore>()
            whenever(store.data).thenReturn(flowOf(PubkyStoreData()))
            val publicationGate = CompletableDeferred<Boolean>()
            var published = false
            val bootstrap = mock<PubkySessionBootstrap>()
            whenever(bootstrap.republishIdentity(any())).doSuspendableAnswer {
                publicationGate.await().also { published = true }
            }
            val access = mock<PubkySessionAccess>()
            whenever(access.exportSessionSecret()).thenReturn("new-session")
            val sdk = mock<PaykitSdk>()
            val service = PaykitSdkService(
                context = mock(),
                keychain = keychain,
                pubkyStore = store,
                bootstrapFactory = { bootstrap },
                ioDispatcher = StandardTestDispatcher(testScheduler),
                settingsStore = mock(),
                sdkFactory = { sdk },
            )
            val activationStart = currentTime

            val activation = async {
                service.activateRegisteredIdentity(
                    PubkySessionBootstrapResult(
                        access,
                        "pubky$RING_PUBKY",
                        PubkyIdentityCapability.PRIVATE_LINK_CAPABLE
                    )
                )
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
    fun `only explicit readd restores private connections`() = runTest {
        for (restoreConnection in listOf(false, true)) {
            val sdk = mock<PaykitSdk>()
            val record = mock<ContactRecord>()
            whenever(sdk.contactRecord(RING_PUBKY)).thenReturn(record)
            whenever(sdk.saveContact(any())).thenReturn(record)
            whenever(sdk.saveContactsAndUnblockPeers(any())).thenReturn(listOf(record))
            val service = PaykitSdkService(mock(), mock(), mock(), settingsStore = mock()) { sdk }

            assertSame(record, service.saveContact(RING_PUBKY, "Contact", restorePrivateConnection = restoreConnection))

            if (restoreConnection) {
                verify(sdk).saveContactsAndUnblockPeers(listOf(ContactUpdate(RING_PUBKY, "Contact")))
                verify(sdk, never()).contactRecord(any())
                verify(sdk, never()).saveContact(any())
            } else {
                verify(sdk).saveContact(ContactUpdate(RING_PUBKY, "Contact"))
                verify(sdk, never()).saveContactsAndUnblockPeers(any())
            }
            verify(sdk, never()).linkedPeers()
            verify(sdk, never()).unblockPeer(any())
        }
    }

    @Test
    fun `editing a removed contact does not restore it`() = runTest {
        val sdk = mock<PaykitSdk>()
        val service = PaykitSdkService(mock(), mock(), mock(), settingsStore = mock()) { sdk }

        assertFailsWith<IllegalStateException> { service.saveContact(RING_PUBKY, "Contact") }

        verify(sdk, never()).saveContact(any())
        verify(sdk, never()).saveContactsAndUnblockPeers(any())
    }

    @Test
    fun `bulk contact restore uses one SDK mutation and tracks its backup change`() = runTest {
        val sdk = mock<PaykitSdk>()
        val updates = listOf(ContactUpdate("pubky$RING_PUBKY", "Contact"))
        val records = listOf(mock<ContactRecord>())
        var revision = "before"
        whenever(sdk.stateRevision()).thenAnswer { revision }
        whenever(sdk.backupStateRevision()).thenAnswer { revision }
        whenever(sdk.saveContactsAndUnblockPeers(updates)).thenAnswer {
            revision = "saved"
            records
        }
        val service = PaykitSdkService(mock(), mock(), mock(), settingsStore = mock()) { sdk }

        assertEquals(emptyList(), service.saveContacts(emptyList()))
        verifyNoInteractions(sdk)
        assertSame(records, service.saveContacts(updates))

        verify(sdk).saveContactsAndUnblockPeers(updates)
        verify(sdk, never()).linkedPeers()
        verify(sdk, never()).unblockPeer(any())
        verify(sdk, never()).saveContacts(any())
        verify(sdk, never()).saveContact(any())
        verify(sdk, never()).contactRecord(any())
        verify(sdk, never()).blockPeer(any())
        assertEquals(1L, service.backupStateVersion.value)
    }

    @Test
    fun `failed contact restoration permits retry without compensating writes`() = runTest {
        for (bulk in listOf(false, true)) {
            val sdk = mock<PaykitSdk>()
            val updates = listOf(ContactUpdate(RING_PUBKY, "Contact"))
            val record = mock<ContactRecord>()
            val failure = IllegalStateException("storage failure")
            whenever(sdk.saveContactsAndUnblockPeers(updates)).thenThrow(failure).thenReturn(listOf(record))
            val service = PaykitSdkService(mock(), mock(), mock(), settingsStore = mock()) { sdk }
            suspend fun save() = if (bulk) {
                service.saveContacts(updates)
            } else {
                listOf(service.saveContact(RING_PUBKY, "Contact", restorePrivateConnection = true))
            }

            assertSame(failure, assertFailsWith<IllegalStateException> { save() })
            assertEquals(listOf(record), save())

            verify(sdk, times(2)).saveContactsAndUnblockPeers(updates)
            verify(sdk, never()).blockPeer(any())
            verify(sdk, never()).unblockPeer(any())
        }
    }

    @Test
    fun `queued bulk save rejects changed identity ended sign-in and cancellation`() = runTest {
        for (change in listOf("identity", "sign-in", "cancel")) {
            val sdk = mock<PaykitSdk>()
            val identity = "pubky$RING_PUBKY"
            var currentIdentity = identity
            var isCurrent = true
            val releaseRead = CompletableDeferred<Unit>()
            whenever(sdk.identityStatus()).thenAnswer {
                IdentityStatus(currentIdentity, PubkyIdentityCapability.PRIVATE_LINK_CAPABLE)
            }
            whenever(sdk.contactRecords()).doSuspendableAnswer {
                releaseRead.await()
                emptyList()
            }
            whenever(sdk.saveContactsAndUnblockPeers(any())).thenReturn(emptyList())
            val service = PaykitSdkService(mock(), mock(), mock(), settingsStore = mock()) { sdk }
            val read = async { service.contactRecords() }
            runCurrent()
            val updates = listOf(ContactUpdate("pubky5${RING_PUBKY.drop(1)}", "Contact"))
            val save = async {
                runSuspendCatching { service.saveContacts(updates, identity) { isCurrent } }
            }
            runCurrent()
            assertFalse(save.isCompleted)
            when (change) {
                "identity" -> currentIdentity = "pubky8${RING_PUBKY.drop(1)}"
                "sign-in" -> isCurrent = false
                "cancel" -> {
                    save.cancel()
                    runCurrent()
                    assertTrue(save.isCompleted)
                }
            }
            releaseRead.complete(Unit)
            read.await()

            when (change) {
                "identity" -> assertIs<IllegalStateException>(save.await().exceptionOrNull())
                "sign-in" -> assertSame(PubkyContactError.SignInChanged, save.await().exceptionOrNull())
                "cancel" -> assertFailsWith<CancellationException> { save.await() }
            }
            verify(sdk, never()).saveContactsAndUnblockPeers(any())
            service.saveContacts(updates, currentIdentity) { true }
            verify(sdk).saveContactsAndUnblockPeers(updates)
        }
    }

    @Test
    fun `cancelled contact restore completes its atomic mutation before releasing the queue`() = runTest {
        for (bulk in listOf(false, true)) {
            val sdk = mock<PaykitSdk>()
            val releaseSave = CompletableDeferred<Unit>()
            val events = mutableListOf<String>()
            whenever(sdk.saveContactsAndUnblockPeers(any())).doSuspendableAnswer {
                releaseSave.await()
                events += "saved"
                listOf(mock<ContactRecord>())
            }
            whenever(sdk.contactRecords()).thenAnswer {
                events += "next"
                emptyList<ContactRecord>()
            }
            val service = PaykitSdkService(mock(), mock(), mock(), settingsStore = mock()) { sdk }
            val updates = listOf(ContactUpdate(RING_PUBKY, "Contact"))
            val save = async {
                if (bulk) {
                    service.saveContacts(updates)
                } else {
                    service.saveContact(RING_PUBKY, "Contact", restorePrivateConnection = true)
                }
            }
            runCurrent()
            save.cancel()
            val next = async { service.contactRecords() }
            runCurrent()
            assertEquals(emptyList(), events)
            assertFalse(next.isCompleted)
            releaseSave.complete(Unit)

            assertFailsWith<CancellationException> { save.await() }
            next.await()
            assertEquals(listOf("saved", "next"), events)
            verify(sdk).saveContactsAndUnblockPeers(updates)
            verify(sdk, never()).blockPeer(any())
            assertEquals(1L, service.backupStateVersion.value)
        }
    }

    @Test
    fun `a contact save queued behind an identity change is not saved to the new identity`() = runTest {
        val originalIdentity = "pubky$RING_PUBKY"
        val newIdentity = "pubky5${RING_PUBKY.drop(1)}"
        val contactKey = "pubky8${RING_PUBKY.drop(1)}"
        val keychain = mock<Keychain>()
        val identityChangeGate = CompletableDeferred<Unit>()
        whenever(keychain.upsertString(Keychain.Key.PAYKIT_SESSION.name, "new-session"))
            .doSuspendableAnswer { identityChangeGate.await() }
        val store = mock<PubkyStore>()
        whenever(store.data).thenReturn(flowOf(PubkyStoreData()))
        val access = mock<PubkySessionAccess>()
        whenever(access.exportSessionSecret()).thenReturn("new-session")
        val originalSdk = mock<PaykitSdk>()
        whenever(
            originalSdk.identityStatus()
        ).thenReturn(IdentityStatus(originalIdentity, PubkyIdentityCapability.PRIVATE_LINK_CAPABLE))
        val newSdk = mock<PaykitSdk>()
        whenever(
            newSdk.identityStatus()
        ).thenReturn(IdentityStatus(newIdentity, PubkyIdentityCapability.PRIVATE_LINK_CAPABLE))
        whenever(newSdk.saveContactsAndUnblockPeers(any())).thenReturn(listOf(mock()))
        val handles = ArrayDeque(listOf(originalSdk, newSdk))
        val service = PaykitSdkService(
            context = mock(),
            keychain = keychain,
            pubkyStore = store,
            bootstrapFactory = { mock() },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            settingsStore = mock(),
            sdkFactory = { handles.removeFirst() },
        )
        assertEquals(originalIdentity, service.identityStatus()?.publicKey)

        val identityChange = async {
            service.activateRegisteredIdentity(
                PubkySessionBootstrapResult(access, newIdentity, PubkyIdentityCapability.PRIVATE_LINK_CAPABLE),
            )
        }
        runCurrent()
        val save = async {
            assertFailsWith<IllegalStateException> {
                service.saveContact(
                    contactKey,
                    "Contact",
                    restorePrivateConnection = true,
                    expectedIdentity = originalIdentity,
                )
            }
        }
        runCurrent()
        assertFalse(save.isCompleted)
        identityChangeGate.complete(Unit)
        identityChange.await()
        save.await()

        verify(newSdk, never()).saveContactsAndUnblockPeers(any())
        service.saveContact(contactKey, "Contact", restorePrivateConnection = true, expectedIdentity = newIdentity)
        verify(newSdk).saveContactsAndUnblockPeers(any())
    }

    /**
     * A new session of the same identity passes the identity check, so only the sign-in check stops the save. The check
     * runs once the save holds the lock, after the session is installed, so it sees the sign-in that ended meanwhile.
     */
    @Test
    fun `a contact save queued behind a new session of its identity is not saved once its sign-in ended`() = runTest {
        val identity = "pubky$RING_PUBKY"
        val contactKey = "pubky8${RING_PUBKY.drop(1)}"
        val keychain = mock<Keychain>()
        val sessionChangeGate = CompletableDeferred<Unit>()
        whenever(keychain.upsertString(Keychain.Key.PAYKIT_SESSION.name, "new-session"))
            .doSuspendableAnswer { sessionChangeGate.await() }
        val store = mock<PubkyStore>()
        whenever(store.data).thenReturn(flowOf(PubkyStoreData()))
        val access = mock<PubkySessionAccess>()
        whenever(access.exportSessionSecret()).thenReturn("new-session")
        val originalSdk = mock<PaykitSdk>()
        whenever(
            originalSdk.identityStatus()
        ).thenReturn(IdentityStatus(identity, PubkyIdentityCapability.PRIVATE_LINK_CAPABLE))
        val newSdk = mock<PaykitSdk>()
        whenever(
            newSdk.identityStatus()
        ).thenReturn(IdentityStatus(identity, PubkyIdentityCapability.PRIVATE_LINK_CAPABLE))
        whenever(newSdk.saveContactsAndUnblockPeers(any())).thenReturn(listOf(mock()))
        val handles = ArrayDeque(listOf(originalSdk, newSdk))
        val service = PaykitSdkService(
            context = mock(),
            keychain = keychain,
            pubkyStore = store,
            bootstrapFactory = { mock() },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            settingsStore = mock(),
            sdkFactory = { handles.removeFirst() },
        )
        assertEquals(identity, service.identityStatus()?.publicKey)
        suspend fun save(isStillCurrent: () -> Boolean) = service.saveContact(
            contactKey,
            "Contact",
            restorePrivateConnection = true,
            expectedIdentity = identity,
            isStillCurrent = isStillCurrent,
        )
        var isSignInCurrent = true

        val sessionChange = async {
            service.activateRegisteredIdentity(
                PubkySessionBootstrapResult(access, identity, PubkyIdentityCapability.PRIVATE_LINK_CAPABLE),
            )
        }
        runCurrent()
        val queuedSave = async { assertFailsWith<PubkyContactError.SignInChanged> { save { isSignInCurrent } } }
        runCurrent()
        assertFalse(queuedSave.isCompleted)
        isSignInCurrent = false
        sessionChangeGate.complete(Unit)
        sessionChange.await()
        queuedSave.await()

        verify(newSdk, never()).saveContactsAndUnblockPeers(any())
        save { true }
        verify(newSdk).saveContactsAndUnblockPeers(any())
    }

    @Test
    fun `blocked peer cleanup does not attempt network delivery`() = runTest {
        val sdk = mock<PaykitSdk>()
        whenever(sdk.linkedPeers()).thenReturn(listOf(contactPeer(LinkedPeerState.BLOCKED)))
        val service = PaykitSdkService(mock(), mock(), mock(), settingsStore = mock()) { sdk }
        assertNull(service.clearPrivatePaymentLists(listOf(RING_PUBKY)))
        verify(sdk, never()).syncPrivatePaymentListsWithReservationsAndProcessOutbound(any(), any())
    }

    @Test
    fun `disabled private capability does not queue withdrawal`() = runTest {
        val sdk = mock<PaykitSdk>()
        val capabilities = PaykitAppCapabilities(false, true, false, true)
        whenever(sdk.linkedPeers()).thenReturn(listOf(contactPeer(LinkedPeerState.LINKED)))
        whenever(sdk.identityStatus()).thenReturn(
            IdentityStatus(RING_PUBKY, PubkyIdentityCapability.PRIVATE_LINK_CAPABLE),
        )
        whenever(sdk.paykitAppRegistry(RING_PUBKY)).thenReturn(
            PaykitAppRegistry(1u, null, listOf(PaykitApp("bitkit", "Bitkit", capabilities)), null, emptyMap()),
        )
        val service = PaykitSdkService(mock(), mock(), mock(), settingsStore = mock()) { sdk }

        assertNull(service.clearPrivatePaymentLists(listOf(RING_PUBKY)))

        verify(sdk, never()).syncPrivatePaymentListsWithReservationsAndProcessOutbound(any(), any())
        verify(sdk, never()).publishPaykitApp(any(), any())
    }

    @Test
    fun `withdrawal batches preflight and delegates repeated empty lists`() = runTest {
        val sdk = mock<PaykitSdk>()
        val other = "pubky5rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg"
        val blocked = "pubky6rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg"
        val blockedPeer = contactPeer(LinkedPeerState.BLOCKED).copy(counterparty = blocked)
        val capabilities = PaykitAppCapabilities(true, true, false, true)
        whenever(sdk.linkedPeers()).thenReturn(listOf(blockedPeer))
        whenever(sdk.identityStatus()).thenReturn(
            IdentityStatus(RING_PUBKY, PubkyIdentityCapability.PRIVATE_LINK_CAPABLE),
        )
        whenever(sdk.paykitAppRegistry(RING_PUBKY)).thenReturn(
            PaykitAppRegistry(1u, null, listOf(PaykitApp("bitkit", "Bitkit", capabilities)), null, emptyMap()),
        )
        val report = PrivatePaymentListDeliveryReport(emptyList(), emptyList(), emptyList(), emptyList())
        val updates = listOf(RING_PUBKY, other).map { PrivatePaymentListReservationUpdateInput(it, emptyList()) }
        whenever(sdk.syncPrivatePaymentListsWithReservationsAndProcessOutbound(updates, false)).thenReturn(report)
        val service = PaykitSdkService(mock(), mock(), mock(), settingsStore = mock()) { sdk }

        assertNull(service.clearPrivatePaymentLists(emptyList()))
        verifyNoInteractions(sdk)
        val recreatedService = PaykitSdkService(mock(), mock(), mock(), settingsStore = mock()) { sdk }
        for (client in listOf(service, service, recreatedService)) {
            assertEquals(report, client.clearPrivatePaymentLists(listOf(RING_PUBKY, other, blocked)))
        }

        verify(sdk, times(3)).linkedPeers()
        verify(sdk, times(3)).identityStatus()
        verify(sdk, times(3)).paykitAppRegistry(RING_PUBKY)
        verify(sdk, times(3)).syncPrivatePaymentListsWithReservationsAndProcessOutbound(updates, false)
        verify(sdk, never()).clearPrivatePaymentListAndProcessOutbound(any())
    }

    @Test
    fun `withdrawal recovers peer before queueing and retains recovery failure`() = runTest {
        val sdk = mock<PaykitSdk>()
        val peer = contactPeer(LinkedPeerState.RECOVERY_REQUIRED)
        val other = "pubky5rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg"
        whenever(sdk.linkedPeers()).thenReturn(
            listOf(peer, contactPeer(LinkedPeerState.LINKED).copy(counterparty = other)),
        )
        val failure = AppError("Peer recovery unavailable")
        var failRecovery = true
        whenever(sdk.ensureLinkWithPeer(RING_PUBKY, 1u)).thenAnswer {
            if (failRecovery) throw failure
            LinkedPeerHandshakeReport(RING_PUBKY, LinkedPeerState.LINKING, 1uL, null)
        }
        val keys = listOf(RING_PUBKY, other)
        val updates = keys.map { PrivatePaymentListReservationUpdateInput(it, emptyList()) }
        whenever(sdk.syncPrivatePaymentListsWithReservationsAndProcessOutbound(updates, false)).thenAnswer {
            PrivatePaymentListDeliveryReport(
                queued = emptyList(),
                cleared = (if (failRecovery) listOf(other) else keys).map {
                    PrivatePaymentListSyncChange(it, 1uL, null)
                },
                failedToQueue = if (failRecovery) {
                    listOf(PrivatePaymentListSyncChange(RING_PUBKY, null, null))
                } else {
                    emptyList()
                },
                failedToDeliver = emptyList(),
            )
        }
        val service = PaykitSdkService(mock(), mock(), mock(), settingsStore = mock()) { sdk }

        val pending = service.clearPrivatePaymentLists(keys)
        assertEquals(listOf(RING_PUBKY), pending?.failedToQueue?.map { it.counterparty })
        assertEquals(listOf(other), pending?.cleared?.map { it.counterparty })

        failRecovery = false
        val complete = service.clearPrivatePaymentLists(keys)
        assertTrue(complete?.failedToQueue?.isEmpty() == true)
        assertEquals(keys, complete?.cleared?.map { it.counterparty })
        inOrder(sdk) {
            verify(sdk).ensureLinkWithPeer(RING_PUBKY, 1u)
            verify(sdk).syncPrivatePaymentListsWithReservationsAndProcessOutbound(updates, false)
            verify(sdk).ensureLinkWithPeer(RING_PUBKY, 1u)
            verify(sdk).syncPrivatePaymentListsWithReservationsAndProcessOutbound(updates, false)
        }
        verify(sdk, never()).ensureLinkWithPeer(other, 1u)
        verify(sdk, never()).unblockPeer(any())
    }

    @Test
    fun `initialization publishes the saved private sharing preference`() = runTest {
        for (enabled in listOf(false, true)) {
            val sdk = mock<PaykitSdk>()
            val settingsStore = mock<SettingsStore>()
            whenever(settingsStore.data).thenReturn(flowOf(SettingsData(sharesPrivatePaykitEndpoints = enabled)))
            whenever(sdk.initialize()).thenReturn(
                IdentityStatus(RING_PUBKY, PubkyIdentityCapability.PRIVATE_LINK_CAPABLE),
            )
            val service = PaykitSdkService(
                mock(),
                mock(),
                mock(),
                ioDispatcher = StandardTestDispatcher(testScheduler),
                platformInitializer = {},
                settingsStore = settingsStore,
            ) { sdk }

            service.initialize()

            verify(sdk).publishPaykitApp("Bitkit", PaykitAppCapabilities(enabled, true, false, true))
            verify(sdk, never()).identityStatus()
        }
    }

    @Test
    fun `initialization without private access does not publish the app`() = runTest {
        for (capability in listOf(PubkyIdentityCapability.SIGNED_OUT, PubkyIdentityCapability.PUBLIC_ONLY)) {
            val sdk = mock<PaykitSdk>()
            whenever(sdk.initialize()).thenReturn(IdentityStatus(RING_PUBKY, capability))
            val service = PaykitSdkService(
                mock(),
                mock(),
                mock(),
                ioDispatcher = StandardTestDispatcher(testScheduler),
                platformInitializer = {},
                settingsStore = mock(),
            ) { sdk }

            service.initialize()

            verify(sdk, never()).publishPaykitApp(any(), any())
            verify(sdk, never()).identityStatus()
        }
    }

    private fun localSessionAccess(bytes: ByteArray): PubkySessionAccess {
        val access = mock<PubkySessionAccess>()
        val secret = mock<PubkyLocalSecretKey>()
        val noise = mock<PaykitIdentitySecretKey>()
        whenever(secret.exportBytes()).thenReturn(bytes)
        whenever(noise.exportBytes()).thenReturn(bytes)
        whenever(access.exportSessionSecret()).thenReturn("new-session")
        whenever(access.exportLocalSecretKey()).thenReturn(secret)
        whenever(access.exportPaykitIdentitySecretKey()).thenReturn(noise)
        return access
    }

    private suspend fun gatedReadService(
        gates: Map<String, CompletableDeferred<Unit>>,
        started: MutableList<String>,
    ): PaykitSdkService {
        val sdk = mock<PaykitSdk>()
        val read: suspend (String) -> Unit = { key ->
            started += key
            gates.getValue(key).await()
        }
        whenever { sdk.contactRecords() }.thenReturn(emptyList())
        gates.keys.forEach { key ->
            whenever { sdk.fetchPubkyFileBounded(key, 1uL) }.doSuspendableAnswer {
                read(key)
                byteArrayOf(1)
            }
        }
        whenever { sdk.resolveProfile(any(), any()) }.doSuspendableAnswer {
            read(it.getArgument(0))
            null
        }
        return PaykitSdkService(mock(), mock(), mock(), settingsStore = mock()) { sdk }.also { it.contactRecords() }
    }

    private fun contactPeer(state: LinkedPeerState) = LinkedPeerRecord(
        counterparty = RING_PUBKY,
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
    fun `config keeps contacts private`() {
        assertEquals("staging.bitkit.to", BitkitPaykitSdkConfig.clientId)
        assertEquals(PublicContactSharingPolicy.PRIVATE_ONLY, BitkitPaykitSdkConfig.publicContactSharing)
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
        whenever(keychain.loadString(Keychain.Key.SHARED_PUBKY_SOURCE.name)).thenReturn("to.pubky.ring:$RING_PUBKY")
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

    private fun identityCacheCases(originalKey: String, differentKey: String) = listOf(
        Triple(originalKey, null, false),
        Triple("pubky$originalKey", null, false),
        Triple(differentKey, null, false),
        Triple(null, null, false),
        Triple(null, originalKey, false),
        Triple(null, differentKey, false),
        Triple(originalKey, differentKey, false),
        Triple(originalKey, differentKey, true),
    )
}
