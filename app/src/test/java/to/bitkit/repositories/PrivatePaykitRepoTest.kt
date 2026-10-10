package to.bitkit.repositories

import android.app.Activity
import com.synonym.bitkitcore.LightningInvoice
import com.synonym.bitkitcore.NetworkType
import com.synonym.bitkitcore.Scanner
import com.synonym.paykit.ContactRecord
import com.synonym.paykit.IdentityStatus
import com.synonym.paykit.LinkedPeerHandshakeReport
import com.synonym.paykit.LinkedPeerRecord
import com.synonym.paykit.LinkedPeerState
import com.synonym.paykit.OutboundPrivateSendReport
import com.synonym.paykit.PaykitException
import com.synonym.paykit.PaymentRequestLifecycleState
import com.synonym.paykit.PrivatePaymentListDeliveryFailure
import com.synonym.paykit.PrivatePaymentListDeliveryReport
import com.synonym.paykit.PrivatePaymentListReservationUpdateInput
import com.synonym.paykit.PrivatePaymentListSyncChange
import com.synonym.paykit.PrivatePaymentResolutionState
import com.synonym.paykit.PrivatePaymentResolutionStatus
import com.synonym.paykit.PubkyIdentityCapability
import com.synonym.paykit.PublicationStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeast
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import to.bitkit.App
import to.bitkit.CurrentActivity
import to.bitkit.data.PrivatePaykitCacheData
import to.bitkit.data.PrivatePaykitCacheStore
import to.bitkit.data.PrivatePaykitContactCacheData
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.models.NodeLifecycleState
import to.bitkit.services.CoreService
import to.bitkit.services.PaykitPreparedPrivateContactPayment
import to.bitkit.services.PaykitPrivateContactPaymentResolution
import to.bitkit.services.PaykitResolvedPaymentEndpoint
import to.bitkit.services.PaykitSdkOperationLock.Priority
import to.bitkit.services.PaykitSdkService
import to.bitkit.services.PubkyService
import to.bitkit.test.BaseUnitTest
import to.bitkit.utils.AppError
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
@Suppress("LargeClass")
class PrivatePaykitRepoTest : BaseUnitTest(StandardTestDispatcher()) {
    companion object {
        private const val CONTACT_KEY = "pubky3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg"
        private const val OTHER_CONTACT_KEY = "pubky5rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg"
        private const val OWN_KEY = "pubky1rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg"
        private const val PRIVATE_ADDRESS = "bcrt1qs04g2ka4pr9s3mv73nu32tvfy7r3cxd27wkyu8"
        private const val OTHER_PRIVATE_ADDRESS = "bcrt1q9x0pz2tqf8clz0lq6m9wj8t47zffnrdz2tkt6v"
        private const val PRIVATE_BOLT11 = "lnbcrt1private"
        private const val SERVER_PRIVATE_BOLT11 = "lnbcrt1serverprivate"
        private const val PRIVATE_BOLT11_EXPIRY_SECONDS = 86_400u
        private const val NOW_SECONDS = 1_700_000_000L
    }

    private val paykitSdkService = mock<PaykitSdkService>()
    private val pubkyService = mock<PubkyService>()
    private val cacheStore = mock<PrivatePaykitCacheStore>()
    private val settingsStore = mock<SettingsStore>()
    private val addressReservationRepo = mock<PrivatePaykitAddressReservationRepo>()
    private val lightningRepo = mock<LightningRepo>()
    private val walletRepo = mock<WalletRepo>()
    private val publicPaykitRepo = mock<PublicPaykitRepo>()
    private val paymentRequestRepo = mock<PaykitPaymentRequestRepo>()
    private val coreService = mock<CoreService>()
    private val clock = mock<Clock>()

    private val cacheData = MutableStateFlow(PrivatePaykitCacheData())
    private val settingsData = MutableStateFlow(SettingsData())
    private val lightningState = MutableStateFlow(
        LightningState(nodeLifecycleState = NodeLifecycleState.Running),
    )

    private lateinit var sut: PrivatePaykitRepo

    @Before
    fun setUp() = test {
        cacheData.value = PrivatePaykitCacheData()
        settingsData.value = SettingsData()

        whenever(cacheStore.data).thenReturn(cacheData)
        whenever { cacheStore.update(any()) }.thenAnswer {
            val transform = it.getArgument<(PrivatePaykitCacheData) -> PrivatePaykitCacheData>(0)
            cacheData.value = transform(cacheData.value)
        }
        whenever { cacheStore.reset() }.thenAnswer {
            cacheData.value = PrivatePaykitCacheData()
        }
        whenever(settingsStore.data).thenReturn(settingsData)
        whenever(settingsStore.update(any())).thenAnswer {
            val transform = it.getArgument<(SettingsData) -> SettingsData>(0)
            settingsData.value = transform(settingsData.value)
        }
        whenever(lightningRepo.lightningState).thenReturn(lightningState)
        whenever(clock.now()).thenReturn(Instant.fromEpochSeconds(NOW_SECONDS))
        whenever(pubkyService.currentPublicKey()).thenReturn(OWN_KEY)
        whenever(paykitSdkService.hasPrivatePaymentAccess()).thenReturn(true)
        whenever(paykitSdkService.identityStatus())
            .thenReturn(IdentityStatus(OWN_KEY, PubkyIdentityCapability.PRIVATE_LINK_CAPABLE))
        whenever(walletRepo.walletExists()).thenReturn(true)
        whenever { walletRepo.refreshReusableReceiveAddressIfReserved() }.thenReturn(Result.success(Unit))
        whenever { addressReservationRepo.reconcileReservedIndexesWithLdk() }.thenReturn(Result.success(Unit))
        whenever { addressReservationRepo.currentOrRotatedAddress(CONTACT_KEY) }
            .thenReturn(Result.success(PRIVATE_ADDRESS))
        whenever { paykitSdkService.syncPrivatePaymentListsWithReservations(any(), any()) }
            .thenReturn(privateListDeliveryReport(queuedCounterparties = listOf(CONTACT_KEY)))
        whenever { paykitSdkService.linkedPeers() }.thenReturn(emptyList())
        whenever { paykitSdkService.ensureLinkWithPeer(any(), any(), eq(Priority.Ordered)) }.thenAnswer {
            LinkedPeerHandshakeReport(it.getArgument(0), LinkedPeerState.LINKED, 1uL, null)
        }
        stubContactPreparationPriorities()
        whenever { paykitSdkService.pendingOutboundPrivateCounterparties() }.thenReturn(emptyList())
        whenever(paykitSdkService.linkedPeers(any())).doSuspendableAnswer { paykitSdkService.linkedPeers() }
        whenever(paykitSdkService.pendingOutboundPrivateCounterparties(any())).doSuspendableAnswer {
            paykitSdkService.pendingOutboundPrivateCounterparties()
        }
        whenever(paykitSdkService.processOutboundPrivateMessages(any(), any())).doSuspendableAnswer {
            paykitSdkService.processOutboundPrivateMessages(it.getArgument(0))
        }
        whenever(paykitSdkService.receivePrivateMessages(any(), any())).doSuspendableAnswer {
            paykitSdkService.receivePrivateMessages(it.getArgument(0))
        }
        whenever { paykitSdkService.clearPrivatePaymentLists(any(), anyOrNull()) }.thenAnswer {
            privateListDeliveryReport(clearedCounterparties = it.getArgument(0))
        }
        whenever { publicPaykitRepo.beginPayment(any()) }
            .thenReturn(Result.success(PublicPaykitPaymentResult.Opened("bitcoin:bcrt1qpublic")))
        whenever { publicPaykitRepo.payableEndpoints(any()) }.thenAnswer { it.getArgument<List<Endpoint>>(0) }
        whenever { publicPaykitRepo.syncPaykitApp(anyOrNull()) }.thenReturn(Result.success(Unit))
        whenever { paymentRequestRepo.refreshEligibleTarget(any()) }
            .thenReturn(Result.success(PaykitPaymentRequestTargetCheck(null, isComplete = true)))
        whenever(lightningRepo.getPayments()).thenReturn(Result.success(emptyList()))

        PublicPaykitRepo.lightningRouteHintsValidator = { true }
        App.currentActivity = CurrentActivity().also { it.onActivityStarted(mock<Activity>()) }
        sut = createSut()
    }

    @After
    fun tearDown() {
        PublicPaykitRepo.lightningRouteHintsValidator = null
        App.currentActivity = null
    }

    @Test
    fun `obsolete contact sync preserves the admitted retry and its original priority deadline`() = test {
        whenever(clock.now()).thenAnswer {
            Instant.fromEpochSeconds(NOW_SECONDS) + testDispatcher.scheduler.currentTime.milliseconds
        }
        whenever(paykitSdkService.linkedPeers()).thenReturn(listOf(linkedPeer(CONTACT_KEY, LinkedPeerState.LINKING)))
        val resumeAdvance = CompletableDeferred<Unit>()
        var finished = false
        whenever(paykitSdkService.ensureLinkWithPeer(CONTACT_KEY)).doSuspendableAnswer {
            resumeAdvance.await()
            finished = true
            LinkedPeerHandshakeReport(CONTACT_KEY, LinkedPeerState.LINKING, 1uL, null)
        }
        sut.refreshSavedContactEndpoints(CONTACT_KEY, listOf(CONTACT_KEY)).getOrThrow()
        runCurrent()
        advanceTimeBy(12_000)

        sut.enableSharingAndPrepareSavedContacts({ listOf(CONTACT_KEY) }).getOrThrow()
        sut.scheduleSavedContactPreparation(emptyList()) { false }.getOrThrow()
        sut.pruneUnsavedContactState(emptyList()) { false }.getOrThrow()
        sut.removeSavedContacts(listOf(CONTACT_KEY)) { false }.getOrThrow()
        sut.enableSharingAndPrepareSavedContacts({ emptyList() }) { false }.getOrThrow()

        verifyNoInteractions(addressReservationRepo)
        verify(paykitSdkService).ensureLinkWithPeer(CONTACT_KEY, 1u, Priority.Interactive)
        resumeAdvance.complete(Unit)
        runCurrent()
        assertTrue(finished)
        advanceTimeBy(12_000)
        runCurrent()
        verify(paykitSdkService, times(3)).ensureLinkWithPeer(CONTACT_KEY, 1u, Priority.Interactive)
        verify(paykitSdkService).ensureLinkWithPeer(CONTACT_KEY, 1u, Priority.Background)
        sut.closeAndClear()
    }

    @Test
    fun `pruning rechecks contact snapshot after cold cache load`() = test {
        cacheData.update { PrivatePaykitCacheData(contacts = mapOf(CONTACT_KEY to cachedPublishedContact())) }
        val loadStarted = CompletableDeferred<Unit>()
        val resumeLoad = CompletableDeferred<Unit>()
        var holdLoad = true
        var isCurrent = true
        whenever(cacheStore.data).thenReturn(
            flow {
                if (holdLoad) {
                    loadStarted.complete(Unit)
                    resumeLoad.await()
                }
                emit(cacheData.value)
            },
        )
        sut = createSut()
        sut.setContactPreparationActive(false)
        val prune = async { sut.pruneUnsavedContactState(emptyList()) { isCurrent } }
        loadStarted.await()

        isCurrent = false
        holdLoad = false
        sut.refreshSavedContactEndpoints(CONTACT_KEY, listOf(CONTACT_KEY)).getOrThrow()
        resumeLoad.complete(Unit)
        prune.await().getOrThrow()

        assertTrue(cacheData.value.contacts.containsKey(CONTACT_KEY))
        verifyNoInteractions(addressReservationRepo)
        verify(paykitSdkService, never()).clearPrivatePaymentLists(any(), anyOrNull())
        sut.setContactPreparationActive(true)
        runCurrent()
        verify(paykitSdkService).ensureLinkWithPeer(CONTACT_KEY, 1u, Priority.Interactive)
        sut.closeAndClear()
    }

    @Test
    fun `contact readd during withdrawal preserves cache assignments and explicit retry`() = test {
        cacheData.update { PrivatePaykitCacheData(contacts = mapOf(CONTACT_KEY to cachedPublishedContact())) }
        sut = createSut()
        sut.setContactPreparationActive(false)
        val withdrawalStarted = CompletableDeferred<Unit>()
        val resumeWithdrawal = CompletableDeferred<Unit>()
        var isCurrent = true
        whenever(paykitSdkService.clearPrivatePaymentLists(any(), any())).doSuspendableAnswer {
            withdrawalStarted.complete(Unit)
            resumeWithdrawal.await()
            privateListDeliveryReport(clearedCounterparties = listOf(CONTACT_KEY))
        }
        val removal = async { sut.removeSavedContacts(listOf(CONTACT_KEY)) { isCurrent } }
        withdrawalStarted.await()

        isCurrent = false
        sut.refreshSavedContactEndpoints(CONTACT_KEY, listOf(CONTACT_KEY)).getOrThrow()
        resumeWithdrawal.complete(Unit)
        removal.await().getOrThrow()

        assertEquals(cachedPublishedContact(), cacheData.value.contacts[CONTACT_KEY])
        assertTrue(cacheData.value.deletedContactCleanupPendingPublicKeys.isEmpty())
        verifyNoInteractions(addressReservationRepo)
        sut.setContactPreparationActive(true)
        runCurrent()
        verify(paykitSdkService).ensureLinkWithPeer(CONTACT_KEY, 1u, Priority.Interactive)
        sut.closeAndClear()
    }

    @Test
    fun `enabling sharing rechecks sign in inside cleanup flag update`() = test {
        cacheData.update { it.copy(cleanupPending = true) }
        val updateStarted = CompletableDeferred<Unit>()
        val resumeUpdate = CompletableDeferred<Unit>()
        var isCurrent = true
        whenever(cacheStore.update(any())).doSuspendableAnswer {
            val transform = it.getArgument<(PrivatePaykitCacheData) -> PrivatePaykitCacheData>(0)
            updateStarted.complete(Unit)
            resumeUpdate.await()
            cacheData.update(transform)
        }
        val enabling = async { sut.enableSharingAndPrepareSavedContacts({ listOf(CONTACT_KEY) }) { isCurrent } }
        updateStarted.await()
        isCurrent = false
        resumeUpdate.complete(Unit)

        enabling.await().getOrThrow()

        assertTrue(cacheData.value.cleanupPending)
        verifyNoInteractions(paykitSdkService, addressReservationRepo)
    }

    @Test
    fun `enabling sharing reschedules current contacts after pending cleanup skipped preparation`() = test {
        settingsData.update { SettingsData(sharesPrivatePaykitEndpoints = true, publicPaykitLightningEnabled = false) }
        cacheData.update {
            it.copy(cleanupPending = true, contacts = mapOf(CONTACT_KEY to cachedPublishedContact()))
        }
        sut.scheduleSavedContactPreparation(listOf(CONTACT_KEY)).getOrThrow()
        runCurrent()
        verifyNoInteractions(paykitSdkService, addressReservationRepo)

        val updateStarted = CompletableDeferred<Unit>()
        val resumeUpdate = CompletableDeferred<Unit>()
        var holdUpdate = true
        var contacts = listOf(CONTACT_KEY)
        whenever(cacheStore.update(any())).doSuspendableAnswer {
            val transform = it.getArgument<(PrivatePaykitCacheData) -> PrivatePaykitCacheData>(0)
            cacheData.update(transform)
            if (holdUpdate) {
                holdUpdate = false
                updateStarted.complete(Unit)
                resumeUpdate.await()
            }
        }
        val enabling = async { sut.enableSharingAndPrepareSavedContacts({ contacts }) { true } }
        updateStarted.await()
        contacts = listOf(CONTACT_KEY, OTHER_CONTACT_KEY)
        resumeUpdate.complete(Unit)
        enabling.await().getOrThrow()
        sut.retryPendingEndpointRemoval(contacts).getOrThrow()
        runCurrent()
        sut.awaitContactPreparation()

        assertFalse(cacheData.value.cleanupPending)
        verify(paykitSdkService, never()).clearPrivatePaymentLists(any(), anyOrNull())
        verify(paykitSdkService).ensureLinkWithPeer(CONTACT_KEY)
        verify(paykitSdkService).ensureLinkWithPeer(OTHER_CONTACT_KEY)
        sut.closeAndClear()
    }

    @Test
    fun `handleOnchainActivity skips SDK access when publication is locally unnecessary`() = test {
        settingsData.value = SettingsData(sharesPrivatePaykitEndpoints = true, publicPaykitLightningEnabled = false)
        sut.prepareSavedContacts(listOf(CONTACT_KEY)).getOrThrow()
        clearInvocations(paykitSdkService, pubkyService)

        for (unavailable in listOf("unused address", "background", "wallet", "node")) {
            whenever(addressReservationRepo.contactsWithUsedReservedAddresses())
                .thenReturn(if (unavailable == "unused address") emptyList() else listOf(CONTACT_KEY))
            App.currentActivity = if (unavailable == "background") {
                null
            } else {
                CurrentActivity().also { it.onActivityStarted(mock<Activity>()) }
            }
            whenever(walletRepo.walletExists()).thenReturn(unavailable != "wallet")
            lightningState.value = LightningState(
                nodeLifecycleState = if (unavailable == "node") {
                    NodeLifecycleState.Stopped
                } else {
                    NodeLifecycleState.Running
                },
            )

            sut.handleOnchainActivity().getOrThrow()
        }

        verify(paykitSdkService, never()).hasPrivatePaymentAccess()
        verify(paykitSdkService, never()).identityStatus()
        verify(pubkyService, never()).currentPublicKey()
    }

    @Test
    fun `hasPrivatePaymentAccess uses one SDK identity check`() = test {
        assertTrue(sut.hasPrivatePaymentAccess())

        verify(paykitSdkService).hasPrivatePaymentAccess()
        verify(pubkyService, never()).currentPublicKey()
    }

    @Test
    fun `prepareSavedContacts publishes private reservations through SDK`() = test {
        settingsData.value = SettingsData(
            sharesPrivatePaykitEndpoints = true,
            publicPaykitLightningEnabled = false,
            publicPaykitOnchainEnabled = true,
        )
        whenever(paykitSdkService.linkedPeers()).thenReturn(listOf(linkedPeer(CONTACT_KEY, LinkedPeerState.LINKED)))

        sut.setContactPreparationActive(false)
        val result = sut.prepareSavedContacts(listOf(CONTACT_KEY), requireImmediatePublication = true)

        assertTrue(result.isSuccess, result.exceptionOrNull().toString())
        val captor = argumentCaptor<List<PrivatePaymentListReservationUpdateInput>>()
        verifyBlocking(paykitSdkService) { syncPrivatePaymentListsWithReservations(captor.capture(), eq(false)) }

        val update = captor.firstValue.single()
        val reservation = update.reservations.single()
        assertEquals(CONTACT_KEY, update.counterparty)
        assertEquals(MethodId.P2wpkh.rawValue, reservation.identifier)
        assertEquals(PublicPaykitRepo.serializePayload(PRIVATE_ADDRESS), reservation.payload)
        assertTrue(
            reservation.reservationId.startsWith(
                "$CONTACT_KEY:${MethodId.P2wpkh.rawValue}:",
            ),
        )
        assertTrue(reservation.reservationId.length <= 128)
        assertEquals("private_paykit", reservation.attribution["type"])
        assertEquals(CONTACT_KEY, reservation.attribution["counterparty"])
        assertEquals(
            true,
            cacheData.value.contacts.getValue(CONTACT_KEY).hasPublishedPrivatePaymentList,
        )
        verify(paykitSdkService, never()).ensureLinkWithPeer(CONTACT_KEY)
        verify(paykitSdkService, never()).processPendingPrivateMessages()
        verify(paykitSdkService, never()).receivePrivateMessagesFromLinkedPeers()
        verify(paykitSdkService).identityStatus()
        verify(paykitSdkService).hasPrivatePaymentAccess()
        verify(pubkyService, times(2)).currentPublicKey()

        clearInvocations(paykitSdkService, pubkyService)
        whenever(paykitSdkService.identityStatus()).thenReturn(null)

        sut.prepareSavedContacts(listOf(CONTACT_KEY), requireImmediatePublication = true).getOrThrow()

        verify(paykitSdkService).identityStatus()
        verify(paykitSdkService, times(2)).hasPrivatePaymentAccess()
        verify(pubkyService, times(3)).currentPublicKey()
        verify(paykitSdkService).syncPrivatePaymentListsWithReservations(any(), eq(false))
    }

    @Test
    fun `hasPrivatePaymentAccess propagates SDK errors and cancellation`() = test {
        val failures = listOf(AppError("Paykit unavailable"), CancellationException("Cancelled"))
        var failure: Throwable = failures.first()
        whenever(paykitSdkService.hasPrivatePaymentAccess()).thenAnswer { throw failure }
        for (nextFailure in failures) {
            failure = nextFailure
            assertEquals(failure, assertFailsWith<Throwable> { sut.hasPrivatePaymentAccess() })
        }
    }

    @Test
    fun `prepareSavedContacts propagates identity errors and cancellation`() = test {
        settingsData.value = SettingsData(sharesPrivatePaykitEndpoints = true, publicPaykitLightningEnabled = false)
        val failures = listOf(AppError("Identity unavailable"), CancellationException("Cancelled"))
        var failure: Throwable = failures.first()
        whenever(paykitSdkService.identityStatus()).thenAnswer { throw failure }
        for (nextFailure in failures) {
            failure = nextFailure

            if (failure is CancellationException) {
                assertFailsWith<CancellationException> { sut.prepareSavedContacts(listOf(CONTACT_KEY)) }
            } else {
                assertEquals(failure, sut.prepareSavedContacts(listOf(CONTACT_KEY)).exceptionOrNull())
            }
        }
        verify(addressReservationRepo, never()).currentOrRotatedAddress(any())
        verify(paykitSdkService, never()).syncPrivatePaymentListsWithReservations(any(), any())
    }

    @Test
    fun `publication checks identity capability and settings after identity lookup`() = test {
        for (unavailable in listOf("identity", "capability", "sharing", "cleanup")) {
            settingsData.value = SettingsData(sharesPrivatePaykitEndpoints = true, publicPaykitLightningEnabled = false)
            cacheData.value = PrivatePaykitCacheData()
            val status = CompletableDeferred<IdentityStatus>()
            whenever(paykitSdkService.identityStatus()) doSuspendableAnswer { status.await() }
            val publication = async {
                sut.prepareSavedContacts(listOf(CONTACT_KEY), requireImmediatePublication = true)
            }
            runCurrent()
            assertFalse(publication.isCompleted)
            if (unavailable == "sharing") {
                settingsData.value = settingsData.value.copy(sharesPrivatePaykitEndpoints = false)
            }
            if (unavailable == "cleanup") cacheData.value = cacheData.value.copy(cleanupPending = true)
            status.complete(
                IdentityStatus(
                    if (unavailable == "identity") OTHER_CONTACT_KEY else OWN_KEY,
                    if (unavailable == "capability") {
                        PubkyIdentityCapability.PUBLIC_ONLY
                    } else {
                        PubkyIdentityCapability.PRIVATE_LINK_CAPABLE
                    },
                ),
            )

            assertIs<PrivatePaykitError.PrivateUnavailable>(publication.await().exceptionOrNull())
        }
        verify(addressReservationRepo, never()).currentOrRotatedAddress(any())
        verify(paykitSdkService, never()).syncPrivatePaymentListsWithReservations(any(), any())
    }

    @Test
    fun `publication rechecks identity after wallet reservation`() = test {
        settingsData.value = SettingsData(sharesPrivatePaykitEndpoints = true, publicPaykitLightningEnabled = false)
        val address = CompletableDeferred<String>()
        whenever(addressReservationRepo.currentOrRotatedAddress(CONTACT_KEY)) doSuspendableAnswer {
            Result.success(address.await())
        }
        val publication = async {
            sut.prepareSavedContacts(listOf(CONTACT_KEY), requireImmediatePublication = true)
        }
        runCurrent()
        assertFalse(publication.isCompleted)
        verify(paykitSdkService).identityStatus()
        whenever(pubkyService.currentPublicKey()).thenReturn(OTHER_CONTACT_KEY)
        address.complete(PRIVATE_ADDRESS)

        assertIs<PrivatePaykitError.PrivateUnavailable>(publication.await().exceptionOrNull())
        verify(pubkyService, times(2)).currentPublicKey()
        verify(paykitSdkService, never()).syncPrivatePaymentListsWithReservations(any(), any())
    }

    @Test
    fun `prepareSavedContacts links contacts when endpoint sharing is disabled`() = test {
        settingsData.value = SettingsData(sharesPrivatePaykitEndpoints = false)
        whenever { paykitSdkService.contactRecord(CONTACT_KEY) }
            .thenReturn(contactRecord(CONTACT_KEY))
        val result = sut.prepareSavedContacts(listOf(CONTACT_KEY))

        assertTrue(result.isSuccess, result.exceptionOrNull().toString())
        verifyBlocking(paykitSdkService) { ensureLinkWithPeer(CONTACT_KEY) }
        verifyBlocking(paykitSdkService, never()) { syncPrivatePaymentListsWithReservations(any(), any()) }
        verify(addressReservationRepo, never()).currentOrRotatedAddress(any())
    }

    @Test
    fun `publication failure retains pending link retries`() = test {
        settingsData.value = SettingsData(sharesPrivatePaykitEndpoints = true)
        var linkPrepared = false
        whenever(paykitSdkService.linkedPeers()).thenReturn(listOf(linkedPeer(CONTACT_KEY, LinkedPeerState.LINKING)))
        whenever(paykitSdkService.ensureLinkWithPeer(CONTACT_KEY)).thenAnswer {
            linkPrepared = true
            LinkedPeerHandshakeReport(CONTACT_KEY, LinkedPeerState.LINKING, 1uL, null)
        }
        whenever(paykitSdkService.identityStatus()).doSuspendableAnswer {
            if (linkPrepared) throw PaykitException.Transport("offline", "Unavailable homeserver")
            IdentityStatus(OWN_KEY, PubkyIdentityCapability.PRIVATE_LINK_CAPABLE)
        }

        try {
            assertTrue(sut.prepareSavedContacts(listOf(CONTACT_KEY)).isFailure)
            advanceTimeBy(1_000)
            runCurrent()
            linkPrepared = false
            advanceTimeBy(3_000)
            runCurrent()
            verify(paykitSdkService, times(2)).ensureLinkWithPeer(CONTACT_KEY)
        } finally {
            sut.closeAndClear()
        }
    }

    @Test
    fun `explicit publication retries queue failures after its priority window expires`() = test {
        settingsData.value = SettingsData(sharesPrivatePaykitEndpoints = true, publicPaykitLightningEnabled = false)
        whenever(clock.now()).thenAnswer {
            Instant.fromEpochSeconds(NOW_SECONDS) + testDispatcher.scheduler.currentTime.milliseconds
        }
        whenever(paykitSdkService.linkedPeers()).thenReturn(listOf(linkedPeer(CONTACT_KEY, LinkedPeerState.LINKED)))
        val completion = CompletableDeferred<Unit>()
        var publications = 0
        var deliveryPending = false
        whenever(paykitSdkService.pendingOutboundPrivateCounterparties()).thenAnswer {
            if (deliveryPending) listOf(CONTACT_KEY) else emptyList()
        }
        whenever(paykitSdkService.processOutboundPrivateMessages(CONTACT_KEY)).thenAnswer {
            deliveryPending = false
            mock<OutboundPrivateSendReport>()
        }
        whenever(paykitSdkService.syncPrivatePaymentListsWithReservations(any(), any())).doSuspendableAnswer {
            when (++publications) {
                1 -> {
                    completion.await()
                    throw PaykitException.Transport("offline", "Unavailable homeserver")
                }
                2 -> privateListDeliveryReport(failedToQueue = listOf(privateListSyncChange(CONTACT_KEY)))
                else -> {
                    deliveryPending = true
                    privateListDeliveryReport(queuedCounterparties = listOf(CONTACT_KEY))
                }
            }
        }

        try {
            sut.refreshSavedContactEndpoints(CONTACT_KEY, listOf(CONTACT_KEY)).getOrThrow()
            runCurrent()
            assertEquals(1, publications)
            advanceTimeBy(30_000)
            completion.complete(Unit)
            runCurrent()
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(2, publications)
            advanceTimeBy(3_000)
            runCurrent()
            assertEquals(3, publications)
            advanceTimeBy(120_000)
            runCurrent()
            assertEquals(3, publications)
            assertFalse(deliveryPending)
            verify(paykitSdkService).processOutboundPrivateMessages(CONTACT_KEY)
        } finally {
            completion.complete(Unit)
            sut.closeAndClear()
        }
    }

    @Test
    fun `cached identity schedules without SDK reads and validates before linking`() = test {
        val identity = CompletableDeferred<IdentityStatus>()
        whenever(paykitSdkService.identityStatus()).doSuspendableAnswer { identity.await() }
        whenever(pubkyService.currentPublicKey()).doSuspendableAnswer { awaitCancellation() }

        try {
            val scheduled = async {
                sut.refreshSavedContactEndpoints(CONTACT_KEY, listOf(CONTACT_KEY), OWN_KEY) { true }
            }
            runCurrent()
            assertTrue(scheduled.isCompleted)
            scheduled.await().getOrThrow()
            verify(pubkyService, never()).currentPublicKey()
            verify(paykitSdkService, never()).ensureLinkWithPeer(any(), any(), any())

            identity.complete(IdentityStatus(OTHER_CONTACT_KEY, PubkyIdentityCapability.PRIVATE_LINK_CAPABLE))
            runCurrent()
            advanceTimeBy(120_000)
            runCurrent()
            verify(paykitSdkService, never()).ensureLinkWithPeer(any(), any(), any())
            verify(paykitSdkService, never()).syncPrivatePaymentListsWithReservations(any(), any())
        } finally {
            sut.closeAndClear()
        }
    }

    @Test
    fun `contact refresh checks sign in after loading cleanup state`() = test {
        val cleanup = CompletableDeferred<Unit>()
        var isCurrent = true
        whenever(cacheStore.data).thenReturn(
            flow {
                cleanup.await()
                emit(PrivatePaykitCacheData())
            },
        )

        try {
            val scheduled = async {
                sut.refreshSavedContactEndpoints(CONTACT_KEY, listOf(CONTACT_KEY), OWN_KEY) { isCurrent }
            }
            runCurrent()
            assertFalse(scheduled.isCompleted)
            isCurrent = false
            cleanup.complete(Unit)
            assertTrue(scheduled.await().isSuccess)
            advanceTimeBy(120_000)
            runCurrent()
            verifyNoInteractions(paykitSdkService, pubkyService)
        } finally {
            cleanup.complete(Unit)
            sut.closeAndClear()
        }
    }

    @Test
    fun `unavailable peer retires explicit publication until refreshed`() = test {
        whenever(clock.now()).thenAnswer {
            Instant.fromEpochSeconds(NOW_SECONDS) + testDispatcher.scheduler.currentTime.milliseconds
        }
        whenever(paykitSdkService.linkedPeers()).thenReturn(listOf(linkedPeer(CONTACT_KEY, LinkedPeerState.NOT_LINKED)))
        whenever(paykitSdkService.pendingOutboundPrivateCounterparties()).thenReturn(listOf(OTHER_CONTACT_KEY))
        whenever(paykitSdkService.ensureLinkWithPeer(CONTACT_KEY))
            .thenAnswer { throw PaykitException.NotFound("not_found", "No App Registry") }

        for (sharingEnabled in listOf(true, false)) {
            settingsData.value = SettingsData(
                sharesPrivatePaykitEndpoints = sharingEnabled,
                publicPaykitLightningEnabled = false,
            )
            clearInvocations(paykitSdkService)
            try {
                sut.refreshSavedContactEndpoints(CONTACT_KEY, listOf(CONTACT_KEY)).getOrThrow()
                runCurrent()
                advanceTimeBy(1_000)
                runCurrent()
                verify(paykitSdkService).ensureLinkWithPeer(CONTACT_KEY)
                verify(paykitSdkService, never()).syncPrivatePaymentListsWithReservations(any(), any())
                clearInvocations(paykitSdkService)

                advanceTimeBy(600_000)
                runCurrent()
                verifyNoInteractions(paykitSdkService)

                sut.refreshSavedContactEndpoints(CONTACT_KEY, listOf(CONTACT_KEY)).getOrThrow()
                runCurrent()
                verify(paykitSdkService).ensureLinkWithPeer(CONTACT_KEY)
            } finally {
                sut.closeAndClear()
            }
        }
    }

    @Test
    fun `unavailable peer retains queued delivery until drained`() = test {
        settingsData.value = SettingsData(sharesPrivatePaykitEndpoints = true, publicPaykitLightningEnabled = false)
        whenever(clock.now()).thenAnswer {
            Instant.fromEpochSeconds(NOW_SECONDS) + testDispatcher.scheduler.currentTime.milliseconds
        }
        whenever(paykitSdkService.linkedPeers()).thenReturn(listOf(linkedPeer(CONTACT_KEY, LinkedPeerState.NOT_LINKED)))
        whenever(paykitSdkService.ensureLinkWithPeer(CONTACT_KEY))
            .thenAnswer { throw PaykitException.NotFound("not_found", "No App Registry") }
        var deliveryPending = true
        var deliveries = 0
        whenever(paykitSdkService.pendingOutboundPrivateCounterparties()).thenAnswer {
            if (deliveryPending) listOf(CONTACT_KEY) else emptyList()
        }
        whenever(paykitSdkService.processOutboundPrivateMessages(CONTACT_KEY)).thenAnswer {
            if (++deliveries == 1) throw PaykitException.Transport("offline", "Unavailable homeserver")
            deliveryPending = false
            mock<OutboundPrivateSendReport>()
        }

        try {
            sut.refreshSavedContactEndpoints(CONTACT_KEY, listOf(CONTACT_KEY)).getOrThrow()
            runCurrent()
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(1, deliveries)
            assertTrue(deliveryPending)

            advanceTimeBy(3_000)
            runCurrent()
            assertEquals(2, deliveries)
            assertFalse(deliveryPending)
            clearInvocations(paykitSdkService)
            advanceTimeBy(600_000)
            runCurrent()
            verifyNoInteractions(paykitSdkService)
        } finally {
            sut.closeAndClear()
        }
    }

    @Test
    fun `unavailable peer that links during queued delivery retries failed intake`() = test {
        settingsData.value = SettingsData(sharesPrivatePaykitEndpoints = true, publicPaykitLightningEnabled = false)
        whenever(clock.now()).thenAnswer {
            Instant.fromEpochSeconds(NOW_SECONDS) + testDispatcher.scheduler.currentTime.milliseconds
        }
        var peerState = LinkedPeerState.NOT_LINKED
        whenever(paykitSdkService.linkedPeers()).thenAnswer { listOf(linkedPeer(CONTACT_KEY, peerState)) }
        whenever(paykitSdkService.ensureLinkWithPeer(CONTACT_KEY))
            .thenAnswer { throw PaykitException.NotFound("not_found", "No App Registry") }
        var deliveryPending = true
        whenever(paykitSdkService.pendingOutboundPrivateCounterparties()).thenAnswer {
            if (deliveryPending) listOf(CONTACT_KEY) else emptyList()
        }
        whenever(paykitSdkService.processOutboundPrivateMessages(CONTACT_KEY)).thenAnswer {
            if (peerState != LinkedPeerState.LINKED) {
                throw PaykitException.Transport("offline", "Unavailable homeserver")
            }
            deliveryPending = false
            mock<OutboundPrivateSendReport>()
        }
        whenever(paykitSdkService.receivePrivateMessages(CONTACT_KEY))
            .thenAnswer { throw PaykitException.Transport("offline", "Unavailable homeserver") }
            .thenReturn(mock())

        try {
            sut.refreshSavedContactEndpoints(CONTACT_KEY, listOf(CONTACT_KEY)).getOrThrow()
            runCurrent()
            advanceTimeBy(1_000)
            runCurrent()
            assertTrue(deliveryPending)
            verify(paykitSdkService).processOutboundPrivateMessages(CONTACT_KEY, Priority.Interactive)

            peerState = LinkedPeerState.LINKED
            advanceTimeBy(3_000)
            runCurrent()
            assertFalse(deliveryPending)
            verify(paykitSdkService).receivePrivateMessages(CONTACT_KEY, Priority.Interactive)
            verifyNoInteractions(paymentRequestRepo)

            advanceTimeBy(8_000)
            runCurrent()
            verify(paykitSdkService, times(2)).receivePrivateMessages(CONTACT_KEY, Priority.Interactive)
            verify(paymentRequestRepo).refreshEligibleTarget(CONTACT_KEY)
            clearInvocations(paykitSdkService, paymentRequestRepo)
            advanceTimeBy(600_000)
            runCurrent()
            verifyNoInteractions(paykitSdkService, paymentRequestRepo)
        } finally {
            sut.closeAndClear()
        }
    }

    @Test
    fun `unavailable peer that links after identity check failure retries failed intake`() = test {
        settingsData.value = SettingsData(sharesPrivatePaykitEndpoints = true, publicPaykitLightningEnabled = false)
        whenever(clock.now()).thenAnswer {
            Instant.fromEpochSeconds(NOW_SECONDS) + testDispatcher.scheduler.currentTime.milliseconds
        }
        var peerState = LinkedPeerState.NOT_LINKED
        var failIdentityCheck = false
        whenever(paykitSdkService.linkedPeers()).thenAnswer { listOf(linkedPeer(CONTACT_KEY, peerState)) }
        whenever(paykitSdkService.ensureLinkWithPeer(CONTACT_KEY)).thenAnswer {
            failIdentityCheck = true
            throw PaykitException.NotFound("not_found", "No App Registry")
        }
        whenever(paykitSdkService.identityStatus()).thenAnswer {
            if (failIdentityCheck) throw PaykitException.Transport("offline", "Unavailable identity")
            IdentityStatus(OWN_KEY, PubkyIdentityCapability.PRIVATE_LINK_CAPABLE)
        }
        whenever(paykitSdkService.receivePrivateMessages(CONTACT_KEY))
            .thenAnswer { throw PaykitException.Transport("offline", "Unavailable homeserver") }
            .thenReturn(mock())

        try {
            sut.refreshSavedContactEndpoints(CONTACT_KEY, listOf(CONTACT_KEY)).getOrThrow()
            runCurrent()
            assertTrue(failIdentityCheck)
            verifyNoInteractions(paymentRequestRepo)

            failIdentityCheck = false
            peerState = LinkedPeerState.LINKED
            advanceTimeBy(1_000)
            runCurrent()
            verify(paykitSdkService).receivePrivateMessages(CONTACT_KEY, Priority.Interactive)
            verifyNoInteractions(paymentRequestRepo)

            advanceTimeBy(3_000)
            runCurrent()
            verify(paykitSdkService, times(2)).receivePrivateMessages(CONTACT_KEY, Priority.Interactive)
            verify(paymentRequestRepo).refreshEligibleTarget(CONTACT_KEY)
            verify(paykitSdkService, never()).processOutboundPrivateMessages(any(), any())
            clearInvocations(paykitSdkService, paymentRequestRepo)
            advanceTimeBy(600_000)
            runCurrent()
            verifyNoInteractions(paykitSdkService, paymentRequestRepo)
        } finally {
            sut.closeAndClear()
        }
    }

    @Test
    fun `blocked peer retires an unpublished explicit retry`() = test {
        settingsData.value = SettingsData(sharesPrivatePaykitEndpoints = true, publicPaykitLightningEnabled = false)
        whenever(paykitSdkService.linkedPeers()).thenReturn(listOf(linkedPeer(CONTACT_KEY, LinkedPeerState.BLOCKED)))

        try {
            sut.refreshSavedContactEndpoints(CONTACT_KEY, listOf(CONTACT_KEY)).getOrThrow()
            runCurrent()
            advanceTimeBy(1_000)
            runCurrent()
            verify(paykitSdkService, never()).syncPrivatePaymentListsWithReservations(any(), any())
            clearInvocations(paykitSdkService)
            advanceTimeBy(120_000)
            runCurrent()
            verify(paykitSdkService, never()).identityStatus(any())
        } finally {
            sut.closeAndClear()
        }
    }

    @Test
    fun `repeated refreshes preserve pending link retry backoff`() = test {
        settingsData.value = SettingsData(sharesPrivatePaykitEndpoints = false)
        whenever(paykitSdkService.linkedPeers()).thenReturn(listOf(linkedPeer(CONTACT_KEY, LinkedPeerState.LINKING)))
        sut.prepareSavedContacts(listOf(CONTACT_KEY)).getOrThrow()
        advanceTimeBy(1_000)
        runCurrent()
        clearInvocations(paykitSdkService)

        sut.prepareSavedContacts(listOf(CONTACT_KEY)).getOrThrow()
        advanceTimeBy(2_000)
        runCurrent()
        verify(paykitSdkService, never()).ensureLinkWithPeer(CONTACT_KEY)
        advanceTimeBy(1_000)
        runCurrent()
        verify(paykitSdkService).ensureLinkWithPeer(CONTACT_KEY)
        sut.closeAndClear()
    }

    @Test
    fun `due peers share scheduling reads and recheck state after each advance`() = test {
        settingsData.value = SettingsData(sharesPrivatePaykitEndpoints = false)
        val keys = listOf(CONTACT_KEY, OTHER_CONTACT_KEY)
        val states = keys.associateWith { LinkedPeerState.LINKING }.toMutableMap()
        val pending = mutableSetOf<String>()
        whenever(paykitSdkService.linkedPeers()).thenAnswer { states.map { linkedPeer(it.key, it.value) } }
        whenever(paykitSdkService.pendingOutboundPrivateCounterparties()).thenAnswer { pending.toList() }
        sut.prepareSavedContacts(keys).getOrThrow()
        clearInvocations(paykitSdkService)
        whenever(paykitSdkService.ensureLinkWithPeer(CONTACT_KEY)).thenAnswer {
            states[CONTACT_KEY] = LinkedPeerState.LINKED
            pending += CONTACT_KEY
            LinkedPeerHandshakeReport(CONTACT_KEY, LinkedPeerState.LINKED, 1uL, null)
        }
        whenever(paykitSdkService.processOutboundPrivateMessages(CONTACT_KEY)).thenAnswer {
            pending -= CONTACT_KEY
            mock<OutboundPrivateSendReport>()
        }

        try {
            advanceTimeBy(1_000)
            runCurrent()
            verify(paykitSdkService, times(6)).linkedPeers(Priority.Background)
            verify(paykitSdkService, times(4)).pendingOutboundPrivateCounterparties(Priority.Background)
            verify(paykitSdkService).processOutboundPrivateMessages(CONTACT_KEY, Priority.Background)
            verify(paykitSdkService).receivePrivateMessages(CONTACT_KEY, Priority.Background)
            verify(paykitSdkService, never()).receivePrivateMessages(OTHER_CONTACT_KEY, Priority.Background)

            clearInvocations(paykitSdkService)
            advanceTimeBy(3_000)
            runCurrent()
            verify(paykitSdkService, never()).ensureLinkWithPeer(eq(CONTACT_KEY), any(), any())
            verify(paykitSdkService).ensureLinkWithPeer(OTHER_CONTACT_KEY, priority = Priority.Background)
            states[OTHER_CONTACT_KEY] = LinkedPeerState.BLOCKED
            advanceTimeBy(8_000)
            runCurrent()
            clearInvocations(paykitSdkService)
            advanceTimeBy(120_000)
            runCurrent()
            verifyNoInteractions(paykitSdkService)
        } finally {
            sut.closeAndClear()
        }
    }

    @Test
    fun `shared scheduling reads retain each due peers foreground priority`() = test {
        settingsData.value = SettingsData(sharesPrivatePaykitEndpoints = false)
        val keys = listOf(CONTACT_KEY, OTHER_CONTACT_KEY)
        whenever(paykitSdkService.linkedPeers()).thenReturn(keys.map { linkedPeer(it, LinkedPeerState.LINKING) })
        sut.prepareSavedContacts(listOf(CONTACT_KEY)).getOrThrow()
        sut.refreshSavedContactEndpoints(OTHER_CONTACT_KEY, keys).getOrThrow()
        runCurrent()
        clearInvocations(paykitSdkService)

        try {
            advanceTimeBy(1_000)
            runCurrent()
            verify(paykitSdkService).ensureLinkWithPeer(CONTACT_KEY, priority = Priority.Background)
            verify(paykitSdkService).ensureLinkWithPeer(OTHER_CONTACT_KEY, priority = Priority.Interactive)
            verify(paykitSdkService, times(3)).linkedPeers(Priority.Background)
            verify(paykitSdkService, times(3)).linkedPeers(Priority.Interactive)
            verify(paykitSdkService, never()).ensureLinkWithPeer(CONTACT_KEY, priority = Priority.Interactive)
        } finally {
            sut.closeAndClear()
        }
    }

    @Test
    fun `shared scheduling reads discard invalidated work and pause until foreground`() = test {
        val keys = listOf(CONTACT_KEY, OTHER_CONTACT_KEY)
        for (invalidation in listOf("background", "identity", "deletion")) {
            settingsData.value = SettingsData(sharesPrivatePaykitEndpoints = false)
            whenever(paykitSdkService.linkedPeers(any())).doSuspendableAnswer { paykitSdkService.linkedPeers() }
            whenever(paykitSdkService.linkedPeers()).thenReturn(keys.map { linkedPeer(it, LinkedPeerState.LINKING) })
            sut.prepareSavedContacts(keys).getOrThrow()
            val completion = CompletableDeferred<Unit>()
            whenever(paykitSdkService.linkedPeers(Priority.Background)).doSuspendableAnswer {
                withContext(NonCancellable) { completion.await() }
                keys.map { linkedPeer(it, LinkedPeerState.LINKING) }
            }
            clearInvocations(paykitSdkService)

            try {
                advanceTimeBy(1_000)
                runCurrent()
                verify(paykitSdkService).linkedPeers(Priority.Background)
                when (invalidation) {
                    "background" -> sut.setContactPreparationActive(false)
                    "identity" -> whenever(paykitSdkService.identityStatus()).thenReturn(
                        IdentityStatus(OTHER_CONTACT_KEY, PubkyIdentityCapability.PRIVATE_LINK_CAPABLE),
                    )
                    "deletion" -> sut.beginProfileDeletion()
                }
                completion.complete(Unit)
                runCurrent()
                verify(paykitSdkService, never()).ensureLinkWithPeer(any(), any(), any())
                verify(paykitSdkService, never()).processOutboundPrivateMessages(any(), any())
                verify(paykitSdkService, never()).receivePrivateMessages(any(), any())
                verifyNoInteractions(paymentRequestRepo)
                if (invalidation == "background") {
                    verify(paykitSdkService, never()).pendingOutboundPrivateCounterparties(any())
                    sut.setContactPreparationActive(true)
                    runCurrent()
                    keys.forEach { verify(paykitSdkService).ensureLinkWithPeer(it, priority = Priority.Background) }
                }
            } finally {
                completion.complete(Unit)
                sut.closeAndClear()
                sut.endProfileDeletion()
                whenever(paykitSdkService.identityStatus()).thenReturn(
                    IdentityStatus(OWN_KEY, PubkyIdentityCapability.PRIVATE_LINK_CAPABLE),
                )
            }
        }
    }

    @Test
    fun `removing one waiter preserves shared scheduling reads for other due peers`() = test {
        settingsData.value = SettingsData(sharesPrivatePaykitEndpoints = false)
        val keys = listOf(CONTACT_KEY, OTHER_CONTACT_KEY)
        whenever(paykitSdkService.linkedPeers()).thenReturn(keys.map { linkedPeer(it, LinkedPeerState.LINKING) })
        sut.prepareSavedContacts(keys).getOrThrow()
        whenever(paykitSdkService.clearPrivatePaymentLists(listOf(CONTACT_KEY))).thenReturn(privateListDeliveryReport())
        val completion = CompletableDeferred<Unit>()
        whenever(paykitSdkService.linkedPeers(Priority.Background)).doSuspendableAnswer {
            completion.await()
            keys.map { linkedPeer(it, LinkedPeerState.LINKING) }
        }
        try {
            advanceTimeBy(1_000)
            runCurrent()
            val removal = async { sut.removeSavedContact(CONTACT_KEY) }
            runCurrent()
            clearInvocations(paykitSdkService)
            completion.complete(Unit)
            removal.await().getOrThrow()
            runCurrent()
            verify(paykitSdkService, never()).ensureLinkWithPeer(eq(CONTACT_KEY), any(), any())
            verify(paykitSdkService).ensureLinkWithPeer(OTHER_CONTACT_KEY, priority = Priority.Background)
        } finally {
            completion.complete(Unit)
            sut.closeAndClear()
        }
    }

    @Test
    fun `later due peer starts fresh scheduling reads without joining an earlier snapshot`() = test {
        settingsData.value = SettingsData(sharesPrivatePaykitEndpoints = false)
        val states = mutableMapOf(CONTACT_KEY to LinkedPeerState.LINKING, OTHER_CONTACT_KEY to LinkedPeerState.LINKING)
        whenever(paykitSdkService.linkedPeers()).thenAnswer { states.map { linkedPeer(it.key, it.value) } }
        sut.prepareSavedContacts(listOf(CONTACT_KEY)).getOrThrow()
        advanceTimeBy(500)
        sut.prepareSavedContacts(states.keys).getOrThrow()
        states[OTHER_CONTACT_KEY] = LinkedPeerState.LINKED
        val completion = CompletableDeferred<Unit>()
        var reads = 0
        whenever(paykitSdkService.pendingOutboundPrivateCounterparties(Priority.Background)).doSuspendableAnswer {
            if (++reads == 1) completion.await()
            emptyList()
        }
        clearInvocations(paykitSdkService)

        try {
            advanceTimeBy(500)
            runCurrent()
            verify(paykitSdkService).linkedPeers(Priority.Background)
            states[OTHER_CONTACT_KEY] = LinkedPeerState.LINKING
            advanceTimeBy(500)
            runCurrent()
            verify(paykitSdkService).ensureLinkWithPeer(OTHER_CONTACT_KEY, priority = Priority.Background)
            verify(paykitSdkService, never()).ensureLinkWithPeer(CONTACT_KEY, priority = Priority.Background)
            completion.complete(Unit)
            runCurrent()
            verify(paykitSdkService).ensureLinkWithPeer(CONTACT_KEY, priority = Priority.Background)
        } finally {
            completion.complete(Unit)
            sut.closeAndClear()
        }
    }

    @Test
    fun `new peer retries independently of a peer at maximum backoff`() = test {
        whenever(paykitSdkService.linkedPeers()).thenReturn(
            listOf(CONTACT_KEY, OTHER_CONTACT_KEY).map { linkedPeer(it, LinkedPeerState.LINKING) },
        )
        sut.prepareSavedContacts(listOf(CONTACT_KEY)).getOrThrow()
        advanceTimeBy(167_000)
        runCurrent()
        clearInvocations(paykitSdkService)

        sut.prepareSavedContacts(listOf(CONTACT_KEY, OTHER_CONTACT_KEY)).getOrThrow()
        verify(paykitSdkService).ensureLinkWithPeer(OTHER_CONTACT_KEY)
        advanceTimeBy(1_000)
        runCurrent()
        verify(paykitSdkService).ensureLinkWithPeer(OTHER_CONTACT_KEY, priority = Priority.Background)
        verify(paykitSdkService, never()).ensureLinkWithPeer(eq(CONTACT_KEY), any(), any())

        advanceTimeBy(89_000)
        runCurrent()
        verify(paykitSdkService).ensureLinkWithPeer(CONTACT_KEY, priority = Priority.Background)
        sut.closeAndClear()
    }

    @Test
    fun `explicit peer wakes promptly and duplicate intent does not extend interactive retries`() = test {
        whenever(clock.now()).thenAnswer {
            Instant.fromEpochSeconds(NOW_SECONDS) + testDispatcher.scheduler.currentTime.milliseconds
        }
        whenever(paykitSdkService.linkedPeers()).thenReturn(
            listOf(CONTACT_KEY, OTHER_CONTACT_KEY).map { linkedPeer(it, LinkedPeerState.LINKING) },
        )
        sut.prepareSavedContacts(listOf(CONTACT_KEY)).getOrThrow()
        advanceTimeBy(167_000)
        runCurrent()
        clearInvocations(paykitSdkService)

        sut.refreshSavedContactEndpoints(OTHER_CONTACT_KEY, listOf(CONTACT_KEY, OTHER_CONTACT_KEY)).getOrThrow()
        runCurrent()
        advanceTimeBy(12_000)
        runCurrent()
        sut.refreshSavedContactEndpoints(OTHER_CONTACT_KEY, listOf(CONTACT_KEY, OTHER_CONTACT_KEY)).getOrThrow()
        runCurrent()
        verify(paykitSdkService, times(4)).ensureLinkWithPeer(OTHER_CONTACT_KEY, 1u, Priority.Interactive)
        verify(paykitSdkService, never()).ensureLinkWithPeer(eq(CONTACT_KEY), any(), any())

        advanceTimeBy(20_000)
        runCurrent()
        verify(paykitSdkService).ensureLinkWithPeer(OTHER_CONTACT_KEY, 1u, Priority.Background)
        verify(paykitSdkService, times(4)).ensureLinkWithPeer(OTHER_CONTACT_KEY, 1u, Priority.Interactive)
        verify(paykitSdkService, never()).ensureLinkWithPeer(eq(CONTACT_KEY), any(), any())
        sut.closeAndClear()
    }

    @Test
    fun `waking an explicit peer does not cancel another admitted advance`() = test {
        val completion = CompletableDeferred<Unit>()
        var finished = false
        whenever(paykitSdkService.linkedPeers()).thenReturn(
            listOf(CONTACT_KEY, OTHER_CONTACT_KEY).map { linkedPeer(it, LinkedPeerState.LINKING) },
        )
        sut.prepareSavedContacts(listOf(CONTACT_KEY)).getOrThrow()
        whenever(paykitSdkService.ensureLinkWithPeer(CONTACT_KEY)).doSuspendableAnswer {
            completion.await()
            finished = true
            LinkedPeerHandshakeReport(CONTACT_KEY, LinkedPeerState.LINKING, 1uL, null)
        }
        advanceTimeBy(1_000)
        runCurrent()

        sut.refreshSavedContactEndpoints(OTHER_CONTACT_KEY, listOf(CONTACT_KEY, OTHER_CONTACT_KEY)).getOrThrow()
        runCurrent()
        verify(paykitSdkService).ensureLinkWithPeer(OTHER_CONTACT_KEY, 1u, Priority.Interactive)
        assertFalse(finished)
        completion.complete(Unit)
        runCurrent()
        assertTrue(finished)
        sut.closeAndClear()
    }

    @Test
    fun `explicit refresh joins admitted maintenance and refreshes only that linked target`() = test {
        val completion = CompletableDeferred<Unit>()
        var peerState = LinkedPeerState.LINKING
        whenever(paykitSdkService.linkedPeers()).thenAnswer { listOf(linkedPeer(CONTACT_KEY, peerState)) }
        whenever(paykitSdkService.ensureLinkWithPeer(CONTACT_KEY)).doSuspendableAnswer {
            completion.await()
            peerState = LinkedPeerState.LINKED
            LinkedPeerHandshakeReport(CONTACT_KEY, peerState, 1uL, null)
        }
        sut.scheduleSavedContactPreparation(listOf(CONTACT_KEY)).getOrThrow()
        runCurrent()
        repeat(2) { sut.refreshSavedContactEndpoints(CONTACT_KEY, listOf(CONTACT_KEY)).getOrThrow() }
        runCurrent()
        verify(paykitSdkService).ensureLinkWithPeer(CONTACT_KEY)

        completion.complete(Unit)
        runCurrent()
        verify(paykitSdkService).ensureLinkWithPeer(CONTACT_KEY)
        verify(paymentRequestRepo).refreshEligibleTarget(CONTACT_KEY)
        verify(paymentRequestRepo, never()).refreshEligibleTargets(any(), any())
        verify(publicPaykitRepo, never()).beginPayment(any())
        advanceTimeBy(120_000)
        runCurrent()
        verify(paymentRequestRepo).refreshEligibleTarget(CONTACT_KEY)
        sut.closeAndClear()
    }

    @Test
    fun `explicit link retry resumes after invalid metadata is corrected`() = test {
        var peerState = LinkedPeerState.LINKING
        whenever(paykitSdkService.linkedPeers()).thenAnswer { listOf(linkedPeer(CONTACT_KEY, peerState)) }
        whenever(paykitSdkService.ensureLinkWithPeer(CONTACT_KEY))
            .thenAnswer { throw PaykitException.Protocol("link_observation_failed", "Invalid link metadata") }
            .thenAnswer {
                peerState = LinkedPeerState.LINKED
                LinkedPeerHandshakeReport(CONTACT_KEY, peerState, 1uL, null)
            }

        try {
            sut.refreshSavedContactEndpoints(CONTACT_KEY, listOf(CONTACT_KEY)).getOrThrow()
            runCurrent()
            verifyNoInteractions(paymentRequestRepo)
            verify(paykitSdkService, never()).receivePrivateMessages(any(), any())

            advanceTimeBy(1_000)
            runCurrent()

            verify(paykitSdkService, times(2)).ensureLinkWithPeer(CONTACT_KEY, priority = Priority.Interactive)
            verify(paykitSdkService).receivePrivateMessages(CONTACT_KEY, Priority.Interactive)
            verify(paymentRequestRepo).refreshEligibleTarget(CONTACT_KEY)
            verify(publicPaykitRepo, never()).beginPayment(any())
        } finally {
            sut.closeAndClear()
        }
    }

    @Test
    fun `explicit linked readiness retries transient intake failure without pending outbound`() = test {
        whenever(paykitSdkService.linkedPeers()).thenReturn(listOf(linkedPeer(CONTACT_KEY, LinkedPeerState.LINKED)))
        whenever(paykitSdkService.receivePrivateMessages(CONTACT_KEY))
            .thenAnswer { throw PaykitException.Transport("offline", "Unavailable homeserver") }
            .thenReturn(mock())

        try {
            sut.refreshSavedContactEndpoints(CONTACT_KEY, listOf(CONTACT_KEY)).getOrThrow()
            runCurrent()
            verifyNoInteractions(paymentRequestRepo)
            advanceTimeBy(1_000)
            runCurrent()

            verify(paykitSdkService, times(2)).receivePrivateMessages(CONTACT_KEY, Priority.Interactive)
            verify(paymentRequestRepo).refreshEligibleTarget(CONTACT_KEY)
            advanceTimeBy(120_000)
            runCurrent()
            verify(paymentRequestRepo).refreshEligibleTarget(CONTACT_KEY)
            verify(paykitSdkService, never()).ensureLinkWithPeer(any(), any(), any())
        } finally {
            sut.closeAndClear()
        }
    }

    @Test
    fun `explicit readiness retries failed or incomplete discovery only within active window`() = test {
        whenever(clock.now()).thenAnswer {
            Instant.fromEpochSeconds(NOW_SECONDS) + testDispatcher.scheduler.currentTime.milliseconds
        }
        whenever(paykitSdkService.linkedPeers()).thenReturn(listOf(linkedPeer(CONTACT_KEY, LinkedPeerState.LINKED)))
        whenever(paymentRequestRepo.refreshEligibleTarget(CONTACT_KEY)).thenReturn(
            Result.failure(AppError("Discovery unavailable")),
            Result.success(PaykitPaymentRequestTargetCheck(null, isComplete = false)),
            Result.success(PaykitPaymentRequestTargetCheck(null, isComplete = true)),
        )

        try {
            sut.refreshSavedContactEndpoints(CONTACT_KEY, listOf(CONTACT_KEY)).getOrThrow()
            runCurrent()
            advanceTimeBy(1_000)
            runCurrent()
            verify(paymentRequestRepo, times(2)).refreshEligibleTarget(CONTACT_KEY)
            advanceTimeBy(3_000)
            runCurrent()
            verify(paymentRequestRepo, times(3)).refreshEligibleTarget(CONTACT_KEY)
            advanceTimeBy(120_000)
            runCurrent()
            verify(paymentRequestRepo, times(3)).refreshEligibleTarget(CONTACT_KEY)

            clearInvocations(paymentRequestRepo)
            whenever(paymentRequestRepo.refreshEligibleTarget(CONTACT_KEY))
                .thenReturn(Result.failure(AppError("Discovery unavailable")))
            sut.refreshSavedContactEndpoints(CONTACT_KEY, listOf(CONTACT_KEY)).getOrThrow()
            runCurrent()
            advanceTimeBy(120_000)
            runCurrent()
            verify(paymentRequestRepo, times(4)).refreshEligibleTarget(CONTACT_KEY)
        } finally {
            sut.closeAndClear()
        }
    }

    @Test
    fun `explicit admitted write finishes in background and resumes at ordinary priority after expiry`() = test {
        whenever(clock.now()).thenAnswer {
            Instant.fromEpochSeconds(NOW_SECONDS) + testDispatcher.scheduler.currentTime.milliseconds
        }
        val completion = CompletableDeferred<Unit>()
        var peerState = LinkedPeerState.LINKING
        var finished = false
        whenever(paykitSdkService.linkedPeers()).thenAnswer { listOf(linkedPeer(CONTACT_KEY, peerState)) }
        whenever(paykitSdkService.ensureLinkWithPeer(CONTACT_KEY)).doSuspendableAnswer {
            withContext(NonCancellable) { completion.await() }
            finished = true
            peerState = LinkedPeerState.LINKED
            LinkedPeerHandshakeReport(CONTACT_KEY, peerState, 1uL, null)
        }
        sut.refreshSavedContactEndpoints(CONTACT_KEY, listOf(CONTACT_KEY)).getOrThrow()
        runCurrent()
        verify(paykitSdkService).ensureLinkWithPeer(CONTACT_KEY, 1u, Priority.Interactive)
        sut.setContactPreparationActive(false)
        clearInvocations(paykitSdkService)
        completion.complete(Unit)
        runCurrent()
        assertTrue(finished)
        advanceTimeBy(30_000)
        runCurrent()
        verify(paykitSdkService, never()).pendingOutboundPrivateCounterparties(any())
        verifyNoInteractions(paymentRequestRepo)

        sut.setContactPreparationActive(true)
        runCurrent()
        verify(paykitSdkService).receivePrivateMessages(CONTACT_KEY, Priority.Background)
        verify(paykitSdkService, never()).receivePrivateMessages(CONTACT_KEY, Priority.Interactive)
        verifyNoInteractions(paymentRequestRepo)
        sut.closeAndClear()
    }

    @Test
    fun `backgrounding queued retry publication does not block explicit publication`() = test {
        settingsData.value = SettingsData(sharesPrivatePaykitEndpoints = true, publicPaykitLightningEnabled = false)
        whenever(paykitSdkService.linkedPeers()).thenReturn(listOf(linkedPeer(CONTACT_KEY, LinkedPeerState.LINKED)))
        val completion = CompletableDeferred<Unit>()
        var firstLookup = true
        whenever(paykitSdkService.identityStatus()).doSuspendableAnswer {
            if (firstLookup) {
                firstLookup = false
                completion.await()
            }
            IdentityStatus(OWN_KEY, PubkyIdentityCapability.PRIVATE_LINK_CAPABLE)
        }
        val initialPublication = async { sut.prepareSavedContacts(listOf(CONTACT_KEY)) }
        runCurrent()
        assertFalse(initialPublication.isCompleted)
        sut.refreshSavedContactEndpoints(CONTACT_KEY, listOf(CONTACT_KEY)).getOrThrow()
        runCurrent()

        try {
            sut.setContactPreparationActive(false)
            completion.complete(Unit)
            runCurrent()
            initialPublication.await().getOrThrow()
            val explicitPublication = async {
                sut.prepareSavedContacts(listOf(CONTACT_KEY), requireImmediatePublication = true)
            }
            runCurrent()

            assertTrue(explicitPublication.isCompleted)
            explicitPublication.await().getOrThrow()
        } finally {
            sut.closeAndClear()
        }
    }

    @Test
    fun `explicit retries discard a changed identity before another advance`() = test {
        whenever(paykitSdkService.linkedPeers()).thenReturn(listOf(linkedPeer(CONTACT_KEY, LinkedPeerState.LINKING)))
        sut.refreshSavedContactEndpoints(CONTACT_KEY, listOf(CONTACT_KEY)).getOrThrow()
        runCurrent()
        clearInvocations(paykitSdkService)
        whenever(paykitSdkService.identityStatus())
            .thenReturn(IdentityStatus(OTHER_CONTACT_KEY, PubkyIdentityCapability.PRIVATE_LINK_CAPABLE))

        advanceTimeBy(120_000)
        runCurrent()
        verify(paykitSdkService, never()).ensureLinkWithPeer(any(), any(), any())
        verify(paykitSdkService, never()).receivePrivateMessages(any(), any())
        verifyNoInteractions(paymentRequestRepo)
        sut.closeAndClear()
    }

    @Test
    fun `profile deletion invalidates an explicit admitted advance before followup work`() = test {
        val completion = CompletableDeferred<Unit>()
        var finished = false
        whenever(paykitSdkService.linkedPeers()).thenReturn(listOf(linkedPeer(CONTACT_KEY, LinkedPeerState.LINKING)))
        whenever(paykitSdkService.ensureLinkWithPeer(CONTACT_KEY)).doSuspendableAnswer {
            withContext(NonCancellable) {
                completion.await()
                finished = true
            }
            LinkedPeerHandshakeReport(CONTACT_KEY, LinkedPeerState.LINKED, 1uL, null)
        }
        sut.refreshSavedContactEndpoints(CONTACT_KEY, listOf(CONTACT_KEY)).getOrThrow()
        runCurrent()
        sut.beginProfileDeletion()
        clearInvocations(paykitSdkService)
        completion.complete(Unit)
        advanceTimeBy(120_000)
        runCurrent()

        assertTrue(finished)
        verify(paykitSdkService, never()).ensureLinkWithPeer(any(), any(), any())
        verify(paykitSdkService, never()).receivePrivateMessages(any(), any())
        verify(paykitSdkService, never()).processOutboundPrivateMessages(any(), any())
        verifyNoInteractions(paymentRequestRepo)
        sut.closeAndClear()
    }

    @Test
    fun `scheduled preparation waits for foreground and retains selected contacts`() = test {
        sut.setContactPreparationActive(false)
        sut.scheduleSavedContactPreparation(listOf(CONTACT_KEY)).getOrThrow()
        runCurrent()
        verify(paykitSdkService, never()).linkedPeers(any())
        verify(paykitSdkService, never()).ensureLinkWithPeer(any(), any(), any())

        sut.setContactPreparationActive(true)
        runCurrent()
        verify(paykitSdkService).ensureLinkWithPeer(CONTACT_KEY, priority = Priority.Background)
        sut.closeAndClear()
    }

    @Test
    fun `backgrounding finishes active preparation before pausing the next peer`() = test {
        val completion = CompletableDeferred<Unit>()
        var finished = false
        whenever(paykitSdkService.ensureLinkWithPeer(CONTACT_KEY)).doSuspendableAnswer {
            completion.await()
            finished = true
            LinkedPeerHandshakeReport(CONTACT_KEY, LinkedPeerState.LINKED, 1uL, null)
        }
        sut.scheduleSavedContactPreparation(listOf(CONTACT_KEY, OTHER_CONTACT_KEY)).getOrThrow()
        runCurrent()
        verify(paykitSdkService).ensureLinkWithPeer(CONTACT_KEY, priority = Priority.Background)

        sut.setContactPreparationActive(false)
        completion.complete(Unit)
        runCurrent()
        assertTrue(finished)
        verify(paykitSdkService, never()).ensureLinkWithPeer(eq(OTHER_CONTACT_KEY), any(), any())

        sut.setContactPreparationActive(true)
        runCurrent()
        verify(paykitSdkService).ensureLinkWithPeer(OTHER_CONTACT_KEY, priority = Priority.Background)
        sut.closeAndClear()
    }

    @Test
    fun `transport recovery waits for foreground and preserves background priority`() = test {
        settingsData.value = SettingsData(sharesPrivatePaykitEndpoints = true, publicPaykitLightningEnabled = false)
        whenever(paykitSdkService.linkedPeers(any())).thenReturn(emptyList())
        val completion = CompletableDeferred<Unit>()
        whenever(paykitSdkService.ensureLinkWithPeer(CONTACT_KEY)).doSuspendableAnswer {
            completion.await()
            throw PaykitException.Transport("offline", "Unavailable homeserver")
        }
        sut.scheduleSavedContactPreparation(listOf(CONTACT_KEY)).getOrThrow()
        runCurrent()
        verify(paykitSdkService).ensureLinkWithPeer(CONTACT_KEY, priority = Priority.Background)
        clearInvocations(paykitSdkService)

        sut.setContactPreparationActive(false)
        completion.complete(Unit)
        runCurrent()
        verify(paykitSdkService, never()).linkedPeers()
        verify(paykitSdkService, never()).linkedPeers(any())

        sut.setContactPreparationActive(true)
        runCurrent()
        verify(paykitSdkService).linkedPeers(Priority.Background)
        verify(paykitSdkService, never()).linkedPeers(Priority.Ordered)
        sut.closeAndClear()
    }

    @Test
    fun `profile deletion invalidates paused transport recovery`() = test {
        settingsData.value = SettingsData(sharesPrivatePaykitEndpoints = true, publicPaykitLightningEnabled = false)
        whenever(paykitSdkService.linkedPeers(any())).thenReturn(emptyList())
        val completion = CompletableDeferred<Unit>()
        whenever(paykitSdkService.ensureLinkWithPeer(CONTACT_KEY)).doSuspendableAnswer {
            completion.await()
            throw PaykitException.Transport("offline", "Unavailable homeserver")
        }
        sut.scheduleSavedContactPreparation(listOf(CONTACT_KEY)).getOrThrow()
        runCurrent()
        sut.setContactPreparationActive(false)
        completion.complete(Unit)
        runCurrent()
        sut.beginProfileDeletion()
        clearInvocations(paykitSdkService)

        sut.setContactPreparationActive(true)
        runCurrent()
        verify(paykitSdkService, never()).linkedPeers()
        verify(paykitSdkService, never()).linkedPeers(any())
        sut.closeAndClear()
    }

    @Test
    fun `pending retries pause in background and resume with background transport priority`() = test {
        whenever(paykitSdkService.linkedPeers()).thenReturn(listOf(linkedPeer(CONTACT_KEY, LinkedPeerState.LINKING)))
        sut.prepareSavedContacts(listOf(CONTACT_KEY)).getOrThrow()
        sut.setContactPreparationActive(false)
        clearInvocations(paykitSdkService)
        advanceTimeBy(120_000)
        runCurrent()
        verify(paykitSdkService, never()).linkedPeers(any())

        whenever(paykitSdkService.linkedPeers()).thenReturn(listOf(linkedPeer(CONTACT_KEY, LinkedPeerState.LINKED)))
        whenever(paykitSdkService.pendingOutboundPrivateCounterparties()).thenReturn(listOf(CONTACT_KEY))
        sut.setContactPreparationActive(true)
        runCurrent()
        verify(paykitSdkService).processOutboundPrivateMessages(CONTACT_KEY, Priority.Background)
        verify(paykitSdkService).receivePrivateMessages(CONTACT_KEY, Priority.Background)
        sut.closeAndClear()
    }

    @Test
    fun `profile deletion invalidates paused preparation and retries`() = test {
        whenever(paykitSdkService.linkedPeers()).thenReturn(listOf(linkedPeer(CONTACT_KEY, LinkedPeerState.LINKING)))
        sut.prepareSavedContacts(listOf(CONTACT_KEY)).getOrThrow()
        sut.setContactPreparationActive(false)
        sut.scheduleSavedContactPreparation(listOf(OTHER_CONTACT_KEY)).getOrThrow()
        advanceTimeBy(2_000)
        runCurrent()
        sut.beginProfileDeletion()
        clearInvocations(paykitSdkService)

        sut.setContactPreparationActive(true)
        advanceTimeBy(120_000)
        runCurrent()
        verify(paykitSdkService, never()).linkedPeers(any())
        verify(paykitSdkService, never()).ensureLinkWithPeer(any(), any(), any())
        sut.closeAndClear()
    }

    @Test
    fun `private retries stop after linking and delivering pending messages`() = test {
        settingsData.value = SettingsData(sharesPrivatePaykitEndpoints = false)
        whenever(paykitSdkService.linkedPeers()).thenReturn(listOf(linkedPeer(CONTACT_KEY, LinkedPeerState.LINKING)))
        sut.prepareSavedContacts(listOf(CONTACT_KEY)).getOrThrow()
        runCurrent()

        whenever(paykitSdkService.linkedPeers()).thenReturn(listOf(linkedPeer(CONTACT_KEY, LinkedPeerState.LINKED)))
        whenever(paykitSdkService.pendingOutboundPrivateCounterparties()).thenReturn(listOf(CONTACT_KEY))
        clearInvocations(paykitSdkService)
        advanceTimeBy(2_000)
        runCurrent()
        verify(paykitSdkService, atLeast(1)).processOutboundPrivateMessages(CONTACT_KEY)

        whenever(paykitSdkService.pendingOutboundPrivateCounterparties()).thenReturn(emptyList())
        advanceTimeBy(2_000)
        runCurrent()
        clearInvocations(paykitSdkService)
        advanceTimeBy(120_000)
        runCurrent()
        verify(paykitSdkService, never()).ensureLinkWithPeer(any(), any(), any())
        verify(paykitSdkService, never()).receivePrivateMessages(any())
        verify(paykitSdkService, never()).receivePrivateMessagesFromLinkedPeers()
        sut.closeAndClear()
    }

    @Test
    fun `endpoint cleanup cancels pending link retries before local state is cleared`() = test {
        settingsData.value = SettingsData(sharesPrivatePaykitEndpoints = false)
        whenever(paykitSdkService.linkedPeers()).thenReturn(listOf(linkedPeer(CONTACT_KEY, LinkedPeerState.LINKING)))
        sut.prepareSavedContacts(listOf(CONTACT_KEY)).getOrThrow()
        runCurrent()

        whenever(paykitSdkService.linkedPeers()).thenReturn(listOf(linkedPeer(CONTACT_KEY, LinkedPeerState.LINKED)))
        assertTrue(sut.removePublishedEndpointsForCleanup("test").isSuccess)
        clearInvocations(pubkyService, paykitSdkService)
        advanceTimeBy(30_000)
        runCurrent()

        verify(paykitSdkService, never()).ensureLinkWithPeer(CONTACT_KEY)
        verifyBlocking(paykitSdkService, never()) { syncPrivatePaymentListsWithReservations(any(), any()) }
        sut.closeAndClear()
    }

    @Test
    fun `prepareSavedContacts defers reservations while link preparation is unavailable`() = test {
        settingsData.value = SettingsData(
            sharesPrivatePaykitEndpoints = true,
            publicPaykitLightningEnabled = false,
            publicPaykitOnchainEnabled = true,
        )
        whenever(paykitSdkService.ensureLinkWithPeer(CONTACT_KEY)).thenAnswer {
            throw PaykitException.Transport("offline", "Unavailable homeserver")
        }

        val result = sut.prepareSavedContacts(listOf(CONTACT_KEY), requireImmediatePublication = true)

        assertTrue(result.isSuccess, result.exceptionOrNull().toString())
        verify(paykitSdkService, never()).syncPrivatePaymentListsWithReservations(any(), any())
        verify(addressReservationRepo, never()).currentOrRotatedAddress(any())
    }

    @Test
    fun `private message drain keeps retrying while link is still pending`() = test {
        settingsData.value = SettingsData(
            sharesPrivatePaykitEndpoints = true,
            publicPaykitLightningEnabled = false,
            publicPaykitOnchainEnabled = true,
        )
        whenever { paykitSdkService.linkedPeers() }
            .thenReturn(listOf(linkedPeer(CONTACT_KEY, LinkedPeerState.LINKING)))

        val result = sut.prepareSavedContacts(listOf(CONTACT_KEY), requireImmediatePublication = true)

        assertTrue(result.isSuccess, result.exceptionOrNull().toString())
        advanceTimeBy(257_000)
        runCurrent()
        sut.closeAndClear()

        verifyBlocking(paykitSdkService, atLeast(8)) { ensureLinkWithPeer(CONTACT_KEY) }
        verify(paykitSdkService, atLeast(1)).linkedPeers(Priority.Background)
        verify(paykitSdkService, atLeast(1)).ensureLinkWithPeer(CONTACT_KEY, priority = Priority.Background)
        verify(paykitSdkService, atLeast(1)).pendingOutboundPrivateCounterparties(Priority.Background)
        verify(paykitSdkService, never()).processOutboundPrivateMessages(any())
        verify(paykitSdkService, never()).receivePrivateMessages(any())
        verify(paykitSdkService, never()).processPendingPrivateMessages()
        verify(paykitSdkService, never()).receivePrivateMessagesFromLinkedPeers()
    }

    @Test
    fun `private message drain normalizes targets and skips unrelated blocked peers`() = test {
        settingsData.value = SettingsData(sharesPrivatePaykitEndpoints = false)
        var state = LinkedPeerState.LINKING
        whenever(paykitSdkService.linkedPeers()).thenAnswer {
            listOf(
                linkedPeer(OTHER_CONTACT_KEY.removePrefix("pubky"), LinkedPeerState.LINKED),
                linkedPeer(CONTACT_KEY.removePrefix("pubky"), state),
                linkedPeer(CONTACT_KEY, state),
            )
        }
        whenever(paykitSdkService.pendingOutboundPrivateCounterparties()).thenReturn(
            listOf(OTHER_CONTACT_KEY.removePrefix("pubky"), CONTACT_KEY.removePrefix("pubky"), CONTACT_KEY),
        )
        whenever(paykitSdkService.processPendingPrivateMessages()).doSuspendableAnswer { awaitCancellation() }
        whenever(paykitSdkService.receivePrivateMessagesFromLinkedPeers()).doSuspendableAnswer { awaitCancellation() }
        whenever(paykitSdkService.processOutboundPrivateMessages(OTHER_CONTACT_KEY))
            .doSuspendableAnswer { awaitCancellation() }
        whenever(paykitSdkService.receivePrivateMessages(OTHER_CONTACT_KEY)).doSuspendableAnswer { awaitCancellation() }
        whenever(paykitSdkService.ensureLinkWithPeer(CONTACT_KEY)).thenAnswer {
            state = LinkedPeerState.LINKED
            LinkedPeerHandshakeReport(CONTACT_KEY, state, 1uL, null)
        }

        val preparation = async { sut.prepareSavedContacts(listOf(CONTACT_KEY.removePrefix("pubky"), CONTACT_KEY)) }
        runCurrent()
        try {
            assertTrue(preparation.isCompleted)
            preparation.await().getOrThrow()
            verify(paykitSdkService).processOutboundPrivateMessages(CONTACT_KEY)
            verify(paykitSdkService).receivePrivateMessages(CONTACT_KEY)
            verify(paykitSdkService, never()).processOutboundPrivateMessages(OTHER_CONTACT_KEY)
            verify(paykitSdkService, never()).receivePrivateMessages(OTHER_CONTACT_KEY)
            verify(paykitSdkService, never()).processPendingPrivateMessages()
            verify(paykitSdkService, never()).receivePrivateMessagesFromLinkedPeers()
        } finally {
            preparation.cancel()
            sut.closeAndClear()
        }
    }

    @Test
    fun `overlapping link preparation retries after failure or cancellation`() = test {
        for (cancel in listOf(false, true)) {
            val completion = CompletableDeferred<Unit>()
            var state = LinkedPeerState.LINKING
            clearInvocations(paykitSdkService)
            whenever(paykitSdkService.linkedPeers(any())).thenAnswer { listOf(linkedPeer(CONTACT_KEY, state)) }
            whenever(paykitSdkService.ensureLinkWithPeer(any(), any(), eq(Priority.Ordered))).doSuspendableAnswer {
                completion.await()
                throw PaykitException.Transport("offline", "Unavailable homeserver")
            }
            val first = async { sut.prepareSavedContacts(listOf(CONTACT_KEY)) }
            runCurrent()
            val second = async { sut.prepareSavedContacts(listOf(CONTACT_KEY)) }
            try {
                runCurrent()
                verify(paykitSdkService).ensureLinkWithPeer(CONTACT_KEY)
                assertTrue(second.isCompleted)
                second.await().getOrThrow()
                if (cancel) first.cancel() else completion.complete(Unit)
                runCurrent()
                if (!cancel) first.await().getOrThrow()
                whenever(paykitSdkService.ensureLinkWithPeer(any(), any(), eq(Priority.Ordered))).thenAnswer {
                    state = LinkedPeerState.LINKED
                    LinkedPeerHandshakeReport(CONTACT_KEY, state, 1uL, null)
                }
                advanceTimeBy(1_000)
                runCurrent()
                verify(paykitSdkService, times(2)).ensureLinkWithPeer(CONTACT_KEY)
            } finally {
                first.cancel()
                second.cancel()
                sut.closeAndClear()
            }
        }
    }

    @Test
    fun `invalidated link preparation cannot release new preparation`() = test {
        val oldCompletion = CompletableDeferred<Unit>()
        val newCompletion = CompletableDeferred<Unit>()
        var advances = 0
        whenever(paykitSdkService.ensureLinkWithPeer(any(), any(), eq(Priority.Ordered))).doSuspendableAnswer {
            advances += 1
            when (advances) {
                1 -> oldCompletion.await()
                2 -> newCompletion.await()
            }
            LinkedPeerHandshakeReport(CONTACT_KEY, LinkedPeerState.LINKED, 1uL, null)
        }
        val old = async { sut.prepareSavedContacts(listOf(CONTACT_KEY)) }
        runCurrent()
        assertEquals(1, advances)
        sut.beginProfileDeletion()
        sut.endProfileDeletion()
        val current = async { sut.prepareSavedContacts(listOf(CONTACT_KEY)) }
        try {
            runCurrent()
            assertEquals(2, advances)
            oldCompletion.complete(Unit)
            old.await().getOrThrow()
            sut.prepareSavedContacts(listOf(CONTACT_KEY)).getOrThrow()
            assertEquals(2, advances)
            newCompletion.complete(Unit)
            current.await().getOrThrow()
        } finally {
            old.cancel()
            current.cancel()
            sut.closeAndClear()
        }
    }

    @Test
    fun `private message drain and retries do not wait for linked peer advancement`() = test {
        val keys = listOf(CONTACT_KEY, OTHER_CONTACT_KEY)
        whenever(paykitSdkService.linkedPeers()).thenReturn(
            keys.map { linkedPeer(it.removePrefix("pubky"), LinkedPeerState.LINKED) },
        )
        whenever(paykitSdkService.pendingOutboundPrivateCounterparties()).thenReturn(keys)
        whenever(paykitSdkService.ensureLinkWithPeer(any(), any(), eq(Priority.Ordered))).doSuspendableAnswer {
            awaitCancellation()
        }

        val preparation = async { sut.prepareSavedContacts(keys) }
        try {
            runCurrent()
            assertTrue(preparation.isCompleted)
            preparation.await().getOrThrow()
            advanceTimeBy(1_000)
            runCurrent()

            verify(paykitSdkService, never()).ensureLinkWithPeer(any(), any(), any())
            keys.forEach {
                verify(paykitSdkService, times(2)).processOutboundPrivateMessages(it)
                verify(paykitSdkService, times(2)).receivePrivateMessages(it)
            }
        } finally {
            preparation.cancel()
            sut.closeAndClear()
        }
    }

    @Test
    fun `private message drain advances recovery detected during send on next retry`() = test {
        var state = LinkedPeerState.LINKED
        var sends = 0
        whenever(paykitSdkService.linkedPeers()).thenAnswer { listOf(linkedPeer(CONTACT_KEY, state)) }
        whenever(paykitSdkService.pendingOutboundPrivateCounterparties()).thenReturn(listOf(CONTACT_KEY))
        whenever(paykitSdkService.ensureLinkWithPeer(CONTACT_KEY)).thenAnswer {
            assertEquals(LinkedPeerState.RECOVERY_REQUIRED, state)
            state = LinkedPeerState.LINKED
            LinkedPeerHandshakeReport(CONTACT_KEY, state, 1uL, null)
        }
        whenever(paykitSdkService.processOutboundPrivateMessages(CONTACT_KEY)).thenAnswer {
            sends += 1
            if (sends == 1) {
                state = LinkedPeerState.RECOVERY_REQUIRED
                throw PaykitException.Transport("recovery", "Peer requires recovery")
            }
            mock()
        }

        try {
            sut.prepareSavedContacts(listOf(CONTACT_KEY)).getOrThrow()
            verify(paykitSdkService, never()).ensureLinkWithPeer(any(), any(), any())
            verify(paykitSdkService, never()).receivePrivateMessages(any())
            advanceTimeBy(1_000)
            runCurrent()

            verify(paykitSdkService).ensureLinkWithPeer(CONTACT_KEY)
            verify(paykitSdkService, times(2)).processOutboundPrivateMessages(CONTACT_KEY)
            verify(paykitSdkService).receivePrivateMessages(CONTACT_KEY)
        } finally {
            sut.closeAndClear()
        }
    }

    @Test
    fun `private message drain isolates send and receive failures per peer`() = test {
        val keys = listOf(CONTACT_KEY, OTHER_CONTACT_KEY)
        whenever(paykitSdkService.linkedPeers()).thenReturn(keys.map { linkedPeer(it, LinkedPeerState.LINKED) })
        whenever(paykitSdkService.pendingOutboundPrivateCounterparties()).thenReturn(keys)
        val failure = PaykitException.Transport("offline", "Unavailable homeserver")
        whenever(paykitSdkService.processOutboundPrivateMessages(CONTACT_KEY)).thenAnswer { throw failure }
        whenever(paykitSdkService.receivePrivateMessages(CONTACT_KEY)).thenAnswer { throw failure }

        sut.prepareSavedContacts(keys).getOrThrow()

        verify(paykitSdkService).processOutboundPrivateMessages(OTHER_CONTACT_KEY)
        verify(paykitSdkService).receivePrivateMessages(OTHER_CONTACT_KEY)
        verify(paykitSdkService, never()).processPendingPrivateMessages()
        verify(paykitSdkService, never()).receivePrivateMessagesFromLinkedPeers()
        sut.closeAndClear()
    }

    @Test
    fun `private message drain stops after cancellation or preparation invalidation`() = test {
        val keys = listOf(CONTACT_KEY, OTHER_CONTACT_KEY)
        whenever(paykitSdkService.linkedPeers()).thenReturn(keys.map { linkedPeer(it, LinkedPeerState.LINKED) })
        whenever(paykitSdkService.pendingOutboundPrivateCounterparties()).thenReturn(keys)
        for (cancel in listOf(false, true)) {
            val releaseSend = CompletableDeferred<Unit>()
            whenever(paykitSdkService.processOutboundPrivateMessages(CONTACT_KEY)).doSuspendableAnswer {
                releaseSend.await()
                mock()
            }
            val preparation = async { sut.prepareSavedContacts(keys) }
            runCurrent()
            verify(paykitSdkService).processOutboundPrivateMessages(CONTACT_KEY)
            if (cancel) preparation.cancel() else sut.closeAndClear().getOrThrow()
            releaseSend.complete(Unit)
            if (cancel) {
                assertFailsWith<CancellationException> { preparation.await() }
            } else {
                preparation.await().getOrThrow()
            }
            verify(paykitSdkService, never()).processOutboundPrivateMessages(OTHER_CONTACT_KEY)
            verify(paykitSdkService, never()).receivePrivateMessages(any())
            sut.closeAndClear().getOrThrow()
            clearInvocations(paykitSdkService)
        }
    }

    @Test
    fun `private message drain processes outbound queued during advancement without a linked peer`() = test {
        settingsData.value = SettingsData(sharesPrivatePaykitEndpoints = false)
        whenever(paykitSdkService.linkedPeers()).thenReturn(listOf(linkedPeer(CONTACT_KEY, LinkedPeerState.LINKING)))
        var pendingOutbound = emptyList<String>()
        whenever(paykitSdkService.pendingOutboundPrivateCounterparties()).thenAnswer { pendingOutbound }
        whenever(paykitSdkService.ensureLinkWithPeer(CONTACT_KEY)).thenAnswer {
            pendingOutbound = listOf(CONTACT_KEY)
            LinkedPeerHandshakeReport(CONTACT_KEY, LinkedPeerState.LINKING, 1uL, null)
        }

        sut.prepareSavedContacts(listOf(CONTACT_KEY)).getOrThrow()
        sut.closeAndClear()

        verify(paykitSdkService).processOutboundPrivateMessages(CONTACT_KEY)
        verify(paykitSdkService, never()).receivePrivateMessages(any())
        verify(paykitSdkService, never()).receivePrivateMessagesFromLinkedPeers()
    }

    @Test
    fun `prepareSavedContacts includes lightning payment hash in reservation attribution`() = test {
        settingsData.value = SettingsData(
            sharesPrivatePaykitEndpoints = true,
            publicPaykitLightningEnabled = true,
            publicPaykitOnchainEnabled = false,
        )
        whenever(lightningRepo.canReceive()).thenReturn(true)
        whenever {
            lightningRepo.createInvoice(
                amountSats = null,
                description = "",
                expirySeconds = PRIVATE_BOLT11_EXPIRY_SECONDS,
            )
        }.thenReturn(Result.success(PRIVATE_BOLT11))
        whenever(coreService.decode(PRIVATE_BOLT11))
            .thenReturn(Scanner.Lightning(lightningInvoice(PRIVATE_BOLT11, byteArrayOf(9, 9, 9))))

        val result = sut.prepareSavedContacts(listOf(CONTACT_KEY), requireImmediatePublication = true)

        assertTrue(result.isSuccess, result.exceptionOrNull().toString())
        val captor = argumentCaptor<List<PrivatePaymentListReservationUpdateInput>>()
        verifyBlocking(paykitSdkService) { syncPrivatePaymentListsWithReservations(captor.capture(), eq(false)) }

        val reservation = captor.firstValue.single().reservations.single()
        assertEquals(MethodId.Bolt11.rawValue, reservation.identifier)
        assertEquals(PublicPaykitRepo.serializePayload(PRIVATE_BOLT11), reservation.payload)
        assertEquals("090909", reservation.attribution["payment_hash"])
    }

    @Test
    fun `publication skips contacts without Paykit and cleanup has nothing to withdraw`() = test {
        settingsData.value = SettingsData(
            sharesPrivatePaykitEndpoints = true,
            publicPaykitLightningEnabled = false,
            publicPaykitOnchainEnabled = true,
        )
        whenever { paykitSdkService.ensureLinkWithPeer(CONTACT_KEY) }
            .thenAnswer { throw PaykitException.NotFound("not_found", "No App Registry") }

        val publication = sut.prepareSavedContacts(listOf(CONTACT_KEY), requireImmediatePublication = true)
        val cleanup = sut.disableSharingAndPruneUnsavedContactState(listOf(CONTACT_KEY))

        assertTrue(publication.isSuccess, publication.exceptionOrNull().toString())
        assertTrue(cleanup.isSuccess, cleanup.exceptionOrNull().toString())
        verifyBlocking(addressReservationRepo, never()) { currentOrRotatedAddress(any()) }
        verifyBlocking(paykitSdkService, never()) { syncPrivatePaymentListsWithReservations(any(), any()) }
        verifyBlocking(paykitSdkService, never()) { clearPrivatePaymentLists(any(), anyOrNull()) }
    }

    @Test
    fun `full cleanup discovers remote peers and retries failed discovery without local state`() = test {
        settingsData.value = SettingsData(sharesPrivatePaykitEndpoints = false)
        val recoveringPublicKey = "pubky6rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg"
        var failLookup = true
        var linked = false
        var canAdvance = false
        val pendingOutbound = mutableSetOf(OTHER_CONTACT_KEY, recoveringPublicKey)
        whenever(paykitSdkService.linkedPeers()).thenAnswer {
            if (failLookup) throw AppError("Peer lookup unavailable")
            listOf(
                linkedPeer(CONTACT_KEY, LinkedPeerState.LINKED),
                linkedPeer(OTHER_CONTACT_KEY, if (linked) LinkedPeerState.LINKED else LinkedPeerState.LINKING),
                linkedPeer(
                    recoveringPublicKey,
                    if (linked) LinkedPeerState.LINKED else LinkedPeerState.RECOVERY_REQUIRED,
                ),
            )
        }
        whenever(paykitSdkService.ensureLinkWithPeer(any(), any(), eq(Priority.Ordered))).thenAnswer {
            linked = canAdvance
            LinkedPeerHandshakeReport(
                it.getArgument(0),
                if (linked) LinkedPeerState.LINKED else LinkedPeerState.LINKING,
                1uL,
                null,
            )
        }
        whenever(paykitSdkService.pendingOutboundPrivateCounterparties()).thenAnswer {
            if (linked) pendingOutbound.toList() else emptyList()
        }
        whenever(paykitSdkService.processOutboundPrivateMessages(any())).thenAnswer {
            pendingOutbound.remove(it.getArgument<String>(0))
            OutboundPrivateSendReport(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        }

        assertTrue(sut.disableSharingAndPruneUnsavedContactState(emptyList()).isFailure)
        assertTrue(cacheData.value.contacts.isEmpty())
        assertTrue(cacheData.value.cleanupPending)
        verify(paykitSdkService, never()).clearPrivatePaymentLists(any(), anyOrNull())

        failLookup = false
        assertTrue(sut.retryPendingEndpointRemoval(emptyList()).isFailure)
        assertTrue(cacheData.value.cleanupPending)
        verify(publicPaykitRepo, never()).syncPaykitApp()

        canAdvance = true
        sut.retryPendingEndpointRemoval(emptyList()).getOrThrow()

        verify(paykitSdkService, times(2)).clearPrivatePaymentLists(
            listOf(CONTACT_KEY, OTHER_CONTACT_KEY, recoveringPublicKey),
        )
        verify(paykitSdkService, times(2)).ensureLinkWithPeer(OTHER_CONTACT_KEY)
        verify(paykitSdkService, times(2)).ensureLinkWithPeer(recoveringPublicKey)
        assertTrue(pendingOutbound.isEmpty())
        assertFalse(cacheData.value.cleanupPending)
        verify(publicPaykitRepo).syncPaykitApp()
    }

    @Test
    fun `full cleanup preserves registry failures and retries with or without contacts`() = test {
        val cleanups = listOf<suspend () -> Result<Unit>>(
            { sut.disableSharingAndPruneUnsavedContactState(emptyList()) },
            { sut.retryPendingEndpointRemoval(emptyList()) },
            { sut.removePublishedEndpointsForCleanup("test") },
        )
        val failure = AppError("Registry unavailable")
        for (cleanup in cleanups) {
            for (hasContacts in listOf(false, true)) {
                settingsData.value = SettingsData(sharesPrivatePaykitEndpoints = false)
                cacheData.value = PrivatePaykitCacheData(
                    contacts = if (hasContacts) mapOf(CONTACT_KEY to cachedPublishedContact()) else emptyMap(),
                    cleanupPending = true,
                )
                whenever(paykitSdkService.linkedPeers()).thenReturn(
                    if (hasContacts) listOf(linkedPeer(CONTACT_KEY, LinkedPeerState.LINKED)) else emptyList(),
                )
                whenever(publicPaykitRepo.syncPaykitApp()).thenReturn(Result.failure(failure), Result.success(Unit))
                clearInvocations(publicPaykitRepo)
                sut = createSut()

                assertEquals(failure, cleanup().exceptionOrNull())
                assertTrue(cacheData.value.cleanupPending)
                assertTrue(settingsData.value.publicPaykitCleanupPending)
                verify(publicPaykitRepo).syncPaykitApp()
                clearInvocations(publicPaykitRepo)

                cleanup().getOrThrow()

                assertFalse(cacheData.value.cleanupPending)
                assertTrue(cacheData.value.contacts.isEmpty())
                verify(publicPaykitRepo).syncPaykitApp()

                clearInvocations(paykitSdkService, publicPaykitRepo)
                sut.retryPendingEndpointRemoval(emptyList()).getOrThrow()
                verifyNoInteractions(paykitSdkService, publicPaykitRepo)
            }
        }
    }

    @Test
    fun `cancelled registry synchronization leaves private cleanup pending`() = test {
        cacheData.value = PrivatePaykitCacheData(contacts = mapOf(CONTACT_KEY to cachedPublishedContact()))
        sut = createSut()
        whenever(publicPaykitRepo.syncPaykitApp()).doSuspendableAnswer { awaitCancellation() }

        val cleanup = async { sut.disableSharingAndPruneUnsavedContactState(listOf(CONTACT_KEY)) }
        runCurrent()
        try {
            verify(publicPaykitRepo).syncPaykitApp()
            assertTrue(cacheData.value.cleanupPending)
        } finally {
            cleanup.cancel()
        }

        assertFailsWith<CancellationException> { cleanup.await() }
        assertTrue(cacheData.value.cleanupPending)
    }

    @Test
    fun `disabled cleanup uses existing capability and retries withdrawal and registry failures`() = test {
        val publicRepo = PublicPaykitRepo(
            ioDispatcher = testDispatcher,
            pubkyRepo = mock(),
            walletRepo = walletRepo,
            lightningRepo = lightningRepo,
            coreService = coreService,
            paykitSdkService = paykitSdkService,
            settingsStore = settingsStore,
            clock = clock,
        )
        for (failureStage in listOf("withdraw", "disable capability")) {
            cacheData.value = PrivatePaykitCacheData(
                contacts = mapOf(CONTACT_KEY to PrivatePaykitContactCacheData(hasPublishedPrivatePaymentList = true)),
            )
            settingsData.value = SettingsData(sharesPrivatePaykitEndpoints = false)
            sut = createSut(publicRepo)
            var capability = true
            var failed = false
            var registryCalls = 0
            doSuspendableAnswer {
                registryCalls++
                val enabled = it.getArgument<Boolean>(0)
                assertFalse(enabled, "Cleanup must not enable private payments")
                if (!failed && failureStage == "disable capability") {
                    failed = true
                    throw AppError("Registration unavailable")
                }
                capability = enabled
            }.whenever(paykitSdkService).syncPaykitApp(any(), eq(Priority.Ordered))
            doSuspendableAnswer {
                assertTrue(capability, "Withdrawal requires the private capability")
                if (!failed && failureStage == "withdraw") {
                    failed = true
                    throw AppError("Delivery unavailable")
                }
                privateListDeliveryReport()
            }.whenever(paykitSdkService).clearPrivatePaymentLists(listOf(CONTACT_KEY))

            val result = sut.disableSharingAndPruneUnsavedContactState(listOf(CONTACT_KEY))

            assertTrue(result.isFailure, failureStage)
            assertTrue(failed, failureStage)
            assertTrue(cacheData.value.cleanupPending, failureStage)
            assertTrue(capability, failureStage)
            assertTrue(settingsData.value.publicPaykitCleanupPending, failureStage)
            assertEquals(if (failureStage == "withdraw") 0 else 1, registryCalls)

            sut.retryPendingEndpointRemoval(listOf(CONTACT_KEY)).getOrThrow()

            assertFalse(capability, failureStage)
            assertFalse(cacheData.value.cleanupPending, failureStage)
            assertFalse(cacheData.value.contacts[CONTACT_KEY]?.hasPublishedPrivatePaymentList == true, failureStage)
            assertEquals(if (failureStage == "withdraw") 1 else 2, registryCalls)
        }
    }

    @Test
    fun `failed deleted contact cleanup does not enable private payments`() = test {
        settingsData.value = SettingsData(sharesPrivatePaykitEndpoints = false)
        val failure = AppError("Peer lookup unavailable")
        whenever(paykitSdkService.linkedPeers()).thenAnswer { throw failure }

        val result = sut.removeSavedContact(CONTACT_KEY)

        assertEquals(failure, result.exceptionOrNull())
        verifyBlocking(publicPaykitRepo, never()) { syncPaykitApp(anyOrNull()) }
        assertTrue(settingsData.value.publicPaykitCleanupPending)
        assertTrue(CONTACT_KEY in cacheData.value.deletedContactCleanupPendingPublicKeys)
    }

    @Test
    fun `deleted contact cleanup retries registry update after withdrawal`() = test {
        settingsData.value = SettingsData(sharesPrivatePaykitEndpoints = false)
        cacheData.value = PrivatePaykitCacheData(contacts = mapOf(CONTACT_KEY to cachedPublishedContact()))
        sut = createSut()
        whenever(publicPaykitRepo.syncPaykitApp()).thenReturn(
            Result.failure(AppError("Registry unavailable")),
            Result.success(Unit),
        )

        assertTrue(sut.removeSavedContact(CONTACT_KEY).isFailure)
        assertTrue(settingsData.value.publicPaykitCleanupPending)
        assertTrue(CONTACT_KEY in cacheData.value.deletedContactCleanupPendingPublicKeys)

        sut.retryPendingEndpointRemoval(emptyList()).getOrThrow()

        assertFalse(CONTACT_KEY in cacheData.value.deletedContactCleanupPendingPublicKeys)
        verifyBlocking(publicPaykitRepo, never()) { syncPaykitApp(true) }
        verifyBlocking(publicPaykitRepo, times(2)) { syncPaykitApp() }
    }

    @Test
    fun `disable sharing removes onchain-only private publications from cache`() = test {
        settingsData.value = SettingsData(
            sharesPrivatePaykitEndpoints = true,
            publicPaykitLightningEnabled = false,
            publicPaykitOnchainEnabled = true,
        )
        sut.prepareSavedContacts(listOf(CONTACT_KEY), requireImmediatePublication = true)

        val result = sut.disableSharingAndPruneUnsavedContactState(listOf(CONTACT_KEY))

        assertTrue(result.isSuccess)
        verifyBlocking(paykitSdkService) { clearPrivatePaymentLists(listOf(CONTACT_KEY)) }
        verify(publicPaykitRepo).syncPaykitApp()
        assertTrue(cacheData.value.contacts.isEmpty())
    }

    @Test
    fun `disable sharing reports unavailable endpoint cleanup and retains retry state`() = test {
        settingsData.value = SettingsData(
            sharesPrivatePaykitEndpoints = true,
            publicPaykitLightningEnabled = false,
            publicPaykitOnchainEnabled = true,
        )
        sut.prepareSavedContacts(listOf(CONTACT_KEY), requireImmediatePublication = true).getOrThrow()
        settingsData.value = settingsData.value.copy(sharesPrivatePaykitEndpoints = false)
        whenever { paykitSdkService.clearPrivatePaymentLists(listOf(CONTACT_KEY)) }.thenReturn(
            privateListDeliveryReport(
                failedToQueue = listOf(
                    PrivatePaymentListSyncChange(
                        counterparty = CONTACT_KEY,
                        outboundMessageId = null,
                        error = mock(),
                    ),
                ),
            ),
        )

        val result = sut.disableSharingAndPruneUnsavedContactState(listOf(CONTACT_KEY))

        assertEquals(PrivatePaykitError.PrivateUnavailable, result.exceptionOrNull())
        assertTrue(cacheData.value.cleanupPending)
        assertTrue(cacheData.value.contacts.getValue(CONTACT_KEY).hasPublishedPrivatePaymentList)
        verifyBlocking(addressReservationRepo, never()) { clearContactAssignments(any(), anyOrNull()) }
    }

    @Test
    fun `enabling sharing supersedes deferred cleanup`() = test {
        cacheData.value = cacheData.value.copy(cleanupPending = true)
        settingsData.value = SettingsData(
            sharesPrivatePaykitEndpoints = true,
            publicPaykitLightningEnabled = false,
            publicPaykitOnchainEnabled = true,
        )

        val result = sut.enableSharingAndPrepareSavedContacts({ listOf(CONTACT_KEY) })

        assertTrue(result.isSuccess, result.exceptionOrNull().toString())
        assertFalse(cacheData.value.cleanupPending)
    }

    @Test
    fun `disabled sharing retries cached publications without cleanup marker`() = test {
        settingsData.value = SettingsData(
            sharesPrivatePaykitEndpoints = true,
            publicPaykitLightningEnabled = false,
            publicPaykitOnchainEnabled = true,
        )
        sut.prepareSavedContacts(listOf(CONTACT_KEY), requireImmediatePublication = true).getOrThrow()
        cacheData.value = cacheData.value.copy(cleanupPending = false)
        settingsData.value = settingsData.value.copy(sharesPrivatePaykitEndpoints = false)

        sut.retryPendingEndpointRemoval(listOf(CONTACT_KEY)).getOrThrow()

        verifyBlocking(paykitSdkService) { clearPrivatePaymentLists(listOf(CONTACT_KEY)) }
        assertFalse(cacheData.value.cleanupPending)
    }

    @Test
    fun `disabled publication recovery remains pending when local cleanup fails`() = test {
        settingsData.value = SettingsData(
            sharesPrivatePaykitEndpoints = true,
            publicPaykitLightningEnabled = false,
            publicPaykitOnchainEnabled = true,
        )
        sut.prepareSavedContacts(listOf(CONTACT_KEY), requireImmediatePublication = true).getOrThrow()
        cacheData.value = cacheData.value.copy(cleanupPending = false)
        settingsData.value = settingsData.value.copy(sharesPrivatePaykitEndpoints = false)
        whenever { addressReservationRepo.clearContactAssignments(any(), anyOrNull()) }
            .thenThrow(IllegalStateException("storage unavailable"))

        val result = sut.retryPendingEndpointRemoval(listOf(CONTACT_KEY))

        assertTrue(result.isFailure)
        assertTrue(cacheData.value.cleanupPending)
        assertTrue(
            cacheData.value.contacts.values.all { !it.hasPublishedPrivatePaymentList },
        )
    }

    @Test
    fun `cleanup removal marks cleanup pending when private endpoint removal fails`() = test {
        settingsData.value = SettingsData(
            sharesPrivatePaykitEndpoints = true,
            publicPaykitLightningEnabled = false,
            publicPaykitOnchainEnabled = true,
        )
        sut.prepareSavedContacts(listOf(CONTACT_KEY), requireImmediatePublication = true)
        whenever { paykitSdkService.clearPrivatePaymentLists(listOf(CONTACT_KEY)) }.thenReturn(
            privateListDeliveryReport(
                failedToQueue = listOf(
                    PrivatePaymentListSyncChange(
                        counterparty = CONTACT_KEY,
                        outboundMessageId = null,
                        error = mock(),
                    ),
                ),
            ),
        )

        val result = sut.removePublishedEndpointsForCleanup("test")

        assertTrue(result.isFailure)
        assertEquals(true, cacheData.value.cleanupPending)
    }

    @Test
    fun `cleanup remains pending until queued clear is delivered`() = test {
        settingsData.value = SettingsData(
            sharesPrivatePaykitEndpoints = true,
            publicPaykitLightningEnabled = false,
            publicPaykitOnchainEnabled = true,
        )
        sut.prepareSavedContacts(listOf(CONTACT_KEY), requireImmediatePublication = true)
        whenever { paykitSdkService.clearPrivatePaymentLists(listOf(CONTACT_KEY)) }
            .thenReturn(privateListDeliveryReport(clearedCounterparties = listOf(CONTACT_KEY)))
        whenever { paykitSdkService.pendingOutboundPrivateCounterparties() }
            .thenReturn(listOf(CONTACT_KEY))

        val result = sut.removePublishedEndpointsForCleanup("test")

        assertTrue(result.isFailure)
        assertTrue(cacheData.value.cleanupPending)
        assertEquals(
            true,
            cacheData.value.contacts.getValue(CONTACT_KEY).hasPublishedPrivatePaymentList,
        )
        sut.closeAndClear()
    }

    @Test
    fun `invalid deleted contact key is dropped from cleanup state`() = test {
        val invalidPublicKey = "not-a-pubky"
        cacheData.value = PrivatePaykitCacheData(
            contacts = mapOf(invalidPublicKey to cachedPublishedContact()),
            deletedContactCleanupPendingPublicKeys = setOf(invalidPublicKey),
        )
        sut = createSut()

        val result = sut.retryPendingEndpointRemoval(emptyList())

        assertTrue(result.isSuccess, result.exceptionOrNull().toString())
        assertTrue(cacheData.value.contacts.isEmpty())
        assertTrue(cacheData.value.deletedContactCleanupPendingPublicKeys.isEmpty())
        verifyBlocking(paykitSdkService, never()) { clearPrivatePaymentLists(any(), anyOrNull()) }
    }

    @Test
    fun `cleanup skips the drain when cleared contacts have no pending work`() = test {
        cacheData.value = PrivatePaykitCacheData(contacts = mapOf(CONTACT_KEY to cachedPublishedContact()))
        sut = createSut()
        whenever(paykitSdkService.linkedPeers()).thenReturn(listOf(linkedPeer(CONTACT_KEY, LinkedPeerState.LINKED)))
        whenever(paykitSdkService.pendingOutboundPrivateCounterparties()).thenReturn(listOf(OTHER_CONTACT_KEY))

        val result = sut.removePublishedEndpointsForCleanup("test")

        assertTrue(result.isSuccess, result.exceptionOrNull().toString())
        verify(paykitSdkService).clearPrivatePaymentLists(listOf(CONTACT_KEY))
        verify(publicPaykitRepo).syncPaykitApp()
        verify(paykitSdkService, never()).ensureLinkWithPeer(any(), any(), any())
        verify(paykitSdkService, never()).processOutboundPrivateMessages(any())
        verify(paykitSdkService, never()).receivePrivateMessages(any())
        verify(paykitSdkService, never()).processPendingPrivateMessages()
        verify(paykitSdkService, never()).receivePrivateMessagesFromLinkedPeers()
        assertTrue(cacheData.value.contacts.isEmpty())
        assertFalse(cacheData.value.cleanupPending)
    }

    @Test
    fun `cleanup drains only pending peers using normalized SDK keys`() = test {
        cacheData.value = PrivatePaykitCacheData(
            contacts = mapOf(
                CONTACT_KEY to cachedPublishedContact(),
                OTHER_CONTACT_KEY to cachedPublishedContact(),
            ),
        )
        sut = createSut()
        whenever(paykitSdkService.linkedPeers()).thenReturn(
            listOf(
                linkedPeer(CONTACT_KEY.removePrefix("pubky"), LinkedPeerState.LINKED),
                linkedPeer(OTHER_CONTACT_KEY.removePrefix("pubky"), LinkedPeerState.LINKED),
            ),
        )
        sut.prepareSavedContacts(listOf(CONTACT_KEY, OTHER_CONTACT_KEY)).getOrThrow()
        clearInvocations(paykitSdkService)
        val pendingKeys = listOf(CONTACT_KEY.removePrefix("pubky"))
        whenever(paykitSdkService.pendingOutboundPrivateCounterparties())
            .thenReturn(pendingKeys, pendingKeys, emptyList())

        sut.setContactPreparationActive(false)
        val result = sut.removePublishedEndpointsForCleanup("test")

        assertTrue(result.isSuccess, result.exceptionOrNull().toString())
        verifyBlocking(paykitSdkService) { clearPrivatePaymentLists(listOf(CONTACT_KEY, OTHER_CONTACT_KEY)) }
        verify(paykitSdkService, never()).ensureLinkWithPeer(any(), any(), any())
        verifyBlocking(paykitSdkService, atLeast(1)) { linkedPeers() }
        verifyBlocking(paykitSdkService, times(3)) { pendingOutboundPrivateCounterparties() }
        verify(paykitSdkService, times(3)).pendingOutboundPrivateCounterparties(Priority.Ordered)
        verify(paykitSdkService, never()).pendingOutboundPrivateCounterparties(Priority.Background)
        verify(paykitSdkService, never()).linkedPeers(Priority.Background)
        verify(paykitSdkService).processOutboundPrivateMessages(CONTACT_KEY)
        verify(paykitSdkService).receivePrivateMessages(CONTACT_KEY)
        verify(paykitSdkService, never()).processOutboundPrivateMessages(OTHER_CONTACT_KEY)
        verify(paykitSdkService, never()).receivePrivateMessages(OTHER_CONTACT_KEY)
        verify(paykitSdkService, never()).processPendingPrivateMessages()
        verify(paykitSdkService, never()).receivePrivateMessagesFromLinkedPeers()
        assertTrue(cacheData.value.contacts.isEmpty())
    }

    @Test
    fun `deleted contact retry cleans all pending contacts in one batch`() = test {
        cacheData.value = PrivatePaykitCacheData(
            contacts = mapOf(
                CONTACT_KEY to cachedPublishedContact(),
                OTHER_CONTACT_KEY to cachedPublishedContact(),
            ),
            deletedContactCleanupPendingPublicKeys = setOf(CONTACT_KEY, OTHER_CONTACT_KEY),
        )
        sut = createSut()

        val result = sut.retryPendingEndpointRemoval(emptyList())

        assertTrue(result.isSuccess, result.exceptionOrNull().toString())
        verifyBlocking(paykitSdkService) { clearPrivatePaymentLists(listOf(CONTACT_KEY, OTHER_CONTACT_KEY)) }
        verifyBlocking(paykitSdkService, atLeast(1)) { linkedPeers() }
        assertTrue(cacheData.value.contacts.isEmpty())
        assertTrue(cacheData.value.deletedContactCleanupPendingPublicKeys.isEmpty())
    }

    @Test
    fun `cleanup retains the batch when drain inspection fails`() = test {
        cacheData.value = PrivatePaykitCacheData(
            contacts = mapOf(
                CONTACT_KEY to cachedPublishedContact(),
                OTHER_CONTACT_KEY to cachedPublishedContact(),
            ),
        )
        sut = createSut()
        whenever { paykitSdkService.linkedPeers() }
            .thenThrow(IllegalStateException("drain inspection failed"))

        val result = sut.removePublishedEndpointsForCleanup("test")

        assertTrue(result.isFailure)
        assertTrue(CONTACT_KEY in cacheData.value.contacts)
        assertTrue(OTHER_CONTACT_KEY in cacheData.value.contacts)
        assertTrue(cacheData.value.cleanupPending)
    }

    @Test
    fun `cleanup retains endpoint cache updated during remote removal`() = test {
        cacheData.value = PrivatePaykitCacheData(
            contacts = mapOf(CONTACT_KEY to cachedPublishedContact()),
        )
        sut = createSut()
        val cleanupStarted = CompletableDeferred<Unit>()
        val resumeCleanup = CompletableDeferred<Unit>()
        whenever { paykitSdkService.clearPrivatePaymentLists(listOf(CONTACT_KEY)) }
            .doSuspendableAnswer {
                cleanupStarted.complete(Unit)
                resumeCleanup.await()
                privateListDeliveryReport(clearedCounterparties = listOf(CONTACT_KEY))
            }
        whenever {
            paykitSdkService.prepareAndResolvePrivatePaymentRequest(
                eq(CONTACT_KEY),
                eq("request-id"),
                eq(null),
            )
        }.thenReturn(resolution(resolvedEndpoint(MethodId.P2wpkh, PRIVATE_ADDRESS), version = 7uL))
        whenever(coreService.isAddressUsed(PRIVATE_ADDRESS)).thenReturn(false)

        val cleanup = async { sut.removePublishedEndpointsForCleanup("test") }
        cleanupStarted.await()
        sut.beginPaymentRequest(
            paymentRequest(acceptedEndpointIdentifiers = listOf(MethodId.P2wpkh.rawValue)),
        ).getOrThrow()
        resumeCleanup.complete(Unit)

        assertTrue(cleanup.await().isFailure)
        assertTrue(cacheData.value.contacts.getValue(CONTACT_KEY).remoteEndpoints.isNotEmpty())
        assertTrue(cacheData.value.cleanupPending)
    }

    @Test
    fun `cleanup isolates a failed contact while clearing successful contacts`() = test {
        for (failQueue in listOf(true, false)) {
            cacheData.value = PrivatePaykitCacheData(
                contacts = mapOf(
                    CONTACT_KEY to cachedPublishedContact(),
                    OTHER_CONTACT_KEY to cachedPublishedContact(),
                ),
            )
            sut = createSut()
            whenever(paykitSdkService.clearPrivatePaymentLists(listOf(CONTACT_KEY, OTHER_CONTACT_KEY))).thenReturn(
                privateListDeliveryReport(
                    clearedCounterparties = listOf(OTHER_CONTACT_KEY),
                    failedToQueue = if (failQueue) listOf(privateListSyncChange(CONTACT_KEY)) else emptyList(),
                    failedToDeliver = if (failQueue) {
                        emptyList()
                    } else {
                        listOf(PrivatePaymentListDeliveryFailure(CONTACT_KEY.removePrefix("pubky"), null, null, mock()))
                    },
                ),
            )

            val result = sut.removePublishedEndpointsForCleanup("test")

            assertTrue(result.isFailure)
            assertTrue(CONTACT_KEY in cacheData.value.contacts)
            assertTrue(OTHER_CONTACT_KEY !in cacheData.value.contacts)
            assertTrue(cacheData.value.cleanupPending)
        }
    }

    @Test
    fun `prepareSavedContacts records queued contacts when another contact cannot publish`() = test {
        settingsData.value = SettingsData(
            sharesPrivatePaykitEndpoints = true,
            publicPaykitLightningEnabled = false,
            publicPaykitOnchainEnabled = true,
        )
        whenever { addressReservationRepo.currentOrRotatedAddress(CONTACT_KEY) }
            .thenReturn(Result.failure(PrivatePaykitTestAppError("address unavailable")))
        whenever { addressReservationRepo.currentOrRotatedAddress(OTHER_CONTACT_KEY) }
            .thenReturn(Result.success(OTHER_PRIVATE_ADDRESS))
        whenever { paykitSdkService.syncPrivatePaymentListsWithReservations(any(), any()) }.thenReturn(
            privateListDeliveryReport(queuedCounterparties = listOf(OTHER_CONTACT_KEY)),
        )

        val result = sut.prepareSavedContacts(listOf(CONTACT_KEY, OTHER_CONTACT_KEY))

        assertTrue(result.isSuccess)
        assertEquals(
            true,
            cacheData.value.contacts.getValue(OTHER_CONTACT_KEY).hasPublishedPrivatePaymentList,
        )
    }

    @Test
    fun `prepareSavedContacts does not require a cached contact record`() = test {
        settingsData.value = SettingsData(
            sharesPrivatePaykitEndpoints = true,
            publicPaykitLightningEnabled = false,
            publicPaykitOnchainEnabled = true,
        )
        whenever { paykitSdkService.contactRecord(CONTACT_KEY) }
            .thenThrow(IllegalStateException("contact unavailable"))
        whenever { addressReservationRepo.currentOrRotatedAddress(OTHER_CONTACT_KEY) }
            .thenReturn(Result.success(OTHER_PRIVATE_ADDRESS))
        whenever { paykitSdkService.syncPrivatePaymentListsWithReservations(any(), any()) }.thenAnswer {
            privateListDeliveryReportForUpdates(it.getArgument(0))
        }

        val result = sut.prepareSavedContacts(listOf(CONTACT_KEY, OTHER_CONTACT_KEY))

        assertTrue(result.isSuccess)
        val captor = argumentCaptor<List<PrivatePaymentListReservationUpdateInput>>()
        verifyBlocking(paykitSdkService) { syncPrivatePaymentListsWithReservations(captor.capture(), eq(false)) }
        assertEquals(listOf(CONTACT_KEY, OTHER_CONTACT_KEY), captor.firstValue.map { it.counterparty })
    }

    @Test
    fun `prepareSavedContacts does not publish while cleanup is pending`() = test {
        settingsData.value = SettingsData(
            sharesPrivatePaykitEndpoints = true,
            publicPaykitLightningEnabled = false,
            publicPaykitOnchainEnabled = true,
        )
        cacheData.value = cacheData.value.copy(
            cleanupPending = true,
            deletedContactCleanupPendingPublicKeys = setOf(OTHER_CONTACT_KEY),
        )

        val result = sut.prepareSavedContacts(listOf(CONTACT_KEY), requireImmediatePublication = true)

        assertTrue(result.isFailure)
        assertEquals(true, cacheData.value.cleanupPending)
        assertEquals(setOf(OTHER_CONTACT_KEY), cacheData.value.deletedContactCleanupPendingPublicKeys)
        verify(paykitSdkService, never()).syncPrivatePaymentListsWithReservations(any(), any())
    }

    @Test
    fun `enabling returns before contact linking and repeated preparation is coalesced`() = test {
        settingsData.value = SettingsData(sharesPrivatePaykitEndpoints = true, publicPaykitLightningEnabled = false)
        val linkStarted = CompletableDeferred<Unit>()
        val resumeLink = CompletableDeferred<Unit>()
        whenever(paykitSdkService.ensureLinkWithPeer(CONTACT_KEY)) doSuspendableAnswer {
            linkStarted.complete(Unit)
            resumeLink.await()
            LinkedPeerHandshakeReport(CONTACT_KEY, LinkedPeerState.LINKED, 1uL, null)
        }

        assertTrue(sut.enableSharingAndPrepareSavedContacts({ listOf(CONTACT_KEY) }).isSuccess)
        runCurrent()
        assertTrue(linkStarted.isCompleted)
        repeat(3) { sut.scheduleSavedContactPreparation(listOf(CONTACT_KEY)).getOrThrow() }
        val preparation = async { sut.awaitContactPreparation() }
        runCurrent()
        assertFalse(preparation.isCompleted)
        resumeLink.complete(Unit)
        runCurrent()
        preparation.await()

        verify(paykitSdkService).ensureLinkWithPeer(CONTACT_KEY)
        verify(paykitSdkService).syncPrivatePaymentListsWithReservations(any(), eq(false))
        sut.closeAndClear()
    }

    @Test
    fun `profile deletion stops preparation and avoids repeated withdrawal`() = test {
        settingsData.value = SettingsData(sharesPrivatePaykitEndpoints = true, publicPaykitLightningEnabled = false)
        sut.beginProfileDeletion()
        sut.scheduleSavedContactPreparation(listOf(CONTACT_KEY, OTHER_CONTACT_KEY)).getOrThrow()
        runCurrent()
        verify(paykitSdkService, never()).ensureLinkWithPeer(any(), any(), any())

        sut.removeSavedContacts(listOf(CONTACT_KEY, OTHER_CONTACT_KEY)).getOrThrow()
        verify(paykitSdkService, never()).clearPrivatePaymentLists(any(), anyOrNull())
        verify(addressReservationRepo).removeContactAssignments(setOf(CONTACT_KEY, OTHER_CONTACT_KEY))

        sut.endProfileDeletion()
        sut.scheduleSavedContactPreparation(listOf(CONTACT_KEY)).getOrThrow()
        runCurrent()
        verify(paykitSdkService).ensureLinkWithPeer(CONTACT_KEY)
        sut.closeAndClear()
    }

    @Test
    fun `cleanup stops a stalled preparation before later contacts are visited`() = test {
        settingsData.value = SettingsData(sharesPrivatePaykitEndpoints = true, publicPaykitLightningEnabled = false)
        val linkStarted = CompletableDeferred<Unit>()
        val resumeLink = CompletableDeferred<Unit>()
        whenever(paykitSdkService.ensureLinkWithPeer(CONTACT_KEY)) doSuspendableAnswer {
            linkStarted.complete(Unit)
            resumeLink.await()
            LinkedPeerHandshakeReport(CONTACT_KEY, LinkedPeerState.LINKED, 1uL, null)
        }
        val publication = async {
            sut.prepareSavedContacts(listOf(CONTACT_KEY, OTHER_CONTACT_KEY), requireImmediatePublication = true)
        }
        runCurrent()
        assertTrue(linkStarted.isCompleted)

        sut.removePublishedEndpointsForCleanup("test").getOrThrow()
        resumeLink.complete(Unit)
        runCurrent()

        assertTrue(publication.await().isFailure)
        verify(paykitSdkService, never()).ensureLinkWithPeer(OTHER_CONTACT_KEY)
        verify(paykitSdkService, never()).syncPrivatePaymentListsWithReservations(any(), any())
        sut.closeAndClear()
    }

    @Test
    fun `preparation drops contacts removed while linking`() = test {
        settingsData.value = SettingsData(sharesPrivatePaykitEndpoints = true, publicPaykitLightningEnabled = false)
        val resumeLink = CompletableDeferred<Unit>()
        whenever(paykitSdkService.ensureLinkWithPeer(CONTACT_KEY)) doSuspendableAnswer {
            resumeLink.await()
            LinkedPeerHandshakeReport(CONTACT_KEY, LinkedPeerState.LINKED, 1uL, null)
        }
        sut.scheduleSavedContactPreparation(listOf(CONTACT_KEY)).getOrThrow()
        runCurrent()
        sut.removeSavedContact(CONTACT_KEY).getOrThrow()
        resumeLink.complete(Unit)
        runCurrent()

        verify(addressReservationRepo, never()).currentOrRotatedAddress(CONTACT_KEY)
        verify(paykitSdkService, never()).syncPrivatePaymentListsWithReservations(any(), any())
        sut.closeAndClear()
    }

    @Test
    fun `unavailable contacts are retried after a cooldown`() = test {
        settingsData.value = SettingsData(sharesPrivatePaykitEndpoints = true, publicPaykitLightningEnabled = false)
        whenever(paykitSdkService.ensureLinkWithPeer(CONTACT_KEY))
            .thenAnswer { throw PaykitException.NotFound("not_found", "No App Registry") }
        repeat(3) { sut.prepareSavedContacts(listOf(CONTACT_KEY)).getOrThrow() }
        verify(paykitSdkService).ensureLinkWithPeer(CONTACT_KEY)

        whenever(clock.now()).thenReturn(Instant.fromEpochSeconds(NOW_SECONDS + 301))
        sut.prepareSavedContacts(listOf(CONTACT_KEY)).getOrThrow()
        verify(paykitSdkService, times(2)).ensureLinkWithPeer(CONTACT_KEY)
        sut.closeAndClear()
    }

    @Test
    fun `transport failures only defer contacts without a handshake after advancement`() = test {
        settingsData.value = SettingsData(sharesPrivatePaykitEndpoints = true, publicPaykitLightningEnabled = false)
        var state: LinkedPeerState? = null
        var nextState: LinkedPeerState? = null
        whenever(paykitSdkService.linkedPeers()).thenAnswer {
            state?.let { listOf(linkedPeer(CONTACT_KEY, it)) } ?: emptyList<LinkedPeerRecord>()
        }
        whenever(paykitSdkService.ensureLinkWithPeer(CONTACT_KEY)).thenAnswer {
            state = nextState
            throw PaykitException.Transport("offline", "Unavailable homeserver")
        }
        val states = listOf(
            null,
            LinkedPeerState.NOT_LINKED,
            LinkedPeerState.LINKING,
            LinkedPeerState.RECOVERY_REQUIRED,
        )
        for (stateAfterFailure in states) {
            sut = createSut()
            state = null
            nextState = null

            sut.prepareSavedContacts(listOf(CONTACT_KEY)).getOrThrow()
            nextState = stateAfterFailure
            sut.prepareSavedContacts(listOf(CONTACT_KEY), requireImmediatePublication = true).getOrThrow()
            advanceTimeBy(1_000)
            runCurrent()
            sut.closeAndClear()

            val hasNoHandshake = stateAfterFailure == null || stateAfterFailure == LinkedPeerState.NOT_LINKED
            val expectedAttempts = if (hasNoHandshake) 2 else 3
            verify(paykitSdkService, times(expectedAttempts)).ensureLinkWithPeer(CONTACT_KEY)
            clearInvocations(paykitSdkService)
        }
    }

    @Test
    fun `removed contacts do not retain unavailable link cooldown`() = test {
        settingsData.value = SettingsData(sharesPrivatePaykitEndpoints = true, publicPaykitLightningEnabled = false)
        whenever(paykitSdkService.ensureLinkWithPeer(CONTACT_KEY))
            .thenAnswer { throw PaykitException.NotFound("not_found", "No App Registry") }

        sut.prepareSavedContacts(listOf(CONTACT_KEY)).getOrThrow()
        sut.removeSavedContact(CONTACT_KEY).getOrThrow()
        sut.prepareSavedContacts(listOf(CONTACT_KEY)).getOrThrow()
        sut.prepareSavedContacts(emptyList()).getOrThrow()
        sut.prepareSavedContacts(listOf(CONTACT_KEY)).getOrThrow()
        sut.closeAndClear()

        verify(paykitSdkService, times(3)).ensureLinkWithPeer(CONTACT_KEY)
    }

    @Test
    fun `closeAndClear clears SDK state`() = test {
        val result = sut.closeAndClear()

        assertTrue(result.isSuccess)
        verifyBlocking(paykitSdkService) { clearState() }
        assertTrue(cacheData.value.contacts.isEmpty())
    }

    @Test
    fun `beginSavedContactPayment uses public resolution while Noise link is not established`() = test {
        sut.prepareSavedContacts(listOf(CONTACT_KEY))
        whenever {
            paykitSdkService.prepareAndResolvePrivateContactPayment(CONTACT_KEY, null)
        }.thenReturn(
            resolution(
                status = PrivatePaymentResolutionStatus.NO_ENDPOINT,
                state = PrivatePaymentResolutionState.NO_PRIVATE_ENDPOINT,
                linkState = LinkedPeerState.LINKING,
                version = null,
            ),
        )

        val result = sut.beginSavedContactPayment(CONTACT_KEY).getOrThrow()

        assertEquals(PublicPaykitPaymentResult.Opened("bitcoin:bcrt1qpublic"), result)
        verifyBlocking(publicPaykitRepo) { beginPayment(CONTACT_KEY) }
    }

    @Test
    fun `beginSavedContactPayment uses cached private resolution without live SDK session`() = test {
        sut.prepareSavedContacts(listOf(CONTACT_KEY))
        whenever(paykitSdkService.hasPrivatePaymentAccess()).thenReturn(false)
        whenever {
            paykitSdkService.prepareAndResolvePrivateContactPayment(CONTACT_KEY, null)
        }.thenReturn(resolution(resolvedEndpoint(MethodId.Bolt11, PRIVATE_BOLT11), version = 7uL))
        whenever(coreService.decode(PRIVATE_BOLT11))
            .thenReturn(Scanner.Lightning(lightningInvoice(PRIVATE_BOLT11, byteArrayOf(9, 9, 9))))

        val result = sut.beginSavedContactPayment(CONTACT_KEY).getOrThrow()

        assertEquals(
            PublicPaykitPaymentResult.Opened(
                paymentRequest = PRIVATE_BOLT11,
                privatePaymentContext = PrivatePaykitPaymentContext(mapOf(MethodId.Bolt11.rawValue to "bitkit"), 7uL),
            ),
            result,
        )
        verifyBlocking(publicPaykitRepo, never()) { beginPayment(any()) }
    }

    @Test
    fun `beginSavedContactPayment resolves before starting local endpoint publication`() = test {
        settingsData.value = SettingsData(
            sharesPrivatePaykitEndpoints = true,
            publicPaykitLightningEnabled = false,
            publicPaykitOnchainEnabled = true,
        )
        sut.prepareSavedContacts(listOf(CONTACT_KEY))
        clearInvocations(paykitSdkService)
        val releaseResolution = CompletableDeferred<Unit>()
        val publishStarted = CompletableDeferred<Unit>()
        val stalledPublish = CompletableDeferred<Unit>()
        whenever { paykitSdkService.syncPrivatePaymentListsWithReservations(any(), any()) }.doSuspendableAnswer {
            publishStarted.complete(Unit)
            stalledPublish.await()
            privateListDeliveryReport(queuedCounterparties = listOf(CONTACT_KEY))
        }
        whenever(paykitSdkService.prepareAndResolvePrivateContactPayment(CONTACT_KEY, null)).doSuspendableAnswer {
            releaseResolution.await()
            resolution(resolvedEndpoint(MethodId.Bolt11, PRIVATE_BOLT11), version = 7uL)
        }
        whenever(coreService.decode(PRIVATE_BOLT11))
            .thenReturn(Scanner.Lightning(lightningInvoice(PRIVATE_BOLT11, byteArrayOf(9, 9, 9))))

        val payment = async { sut.beginSavedContactPayment(CONTACT_KEY).getOrThrow() }
        runCurrent()
        verify(paykitSdkService, never()).syncPrivatePaymentListsWithReservations(any(), any())
        releaseResolution.complete(Unit)
        val result = payment.await()

        assertIs<PublicPaykitPaymentResult.Opened>(result)
        publishStarted.await()
        stalledPublish.complete(Unit)
    }

    @Test
    fun `beginSavedContactPayment runs one endpoint publish per contact at a time`() = test {
        settingsData.value = SettingsData(
            sharesPrivatePaykitEndpoints = true,
            publicPaykitLightningEnabled = false,
            publicPaykitOnchainEnabled = true,
        )
        sut.prepareSavedContacts(listOf(CONTACT_KEY))
        val publishStarted = CompletableDeferred<Unit>()
        val stalledPublish = CompletableDeferred<Unit>()
        var publishes = 0
        whenever { paykitSdkService.syncPrivatePaymentListsWithReservations(any(), any()) }.doSuspendableAnswer {
            publishes += 1
            publishStarted.complete(Unit)
            stalledPublish.await()
            privateListDeliveryReport(queuedCounterparties = listOf(CONTACT_KEY))
        }
        whenever {
            paykitSdkService.prepareAndResolvePrivateContactPayment(CONTACT_KEY, null)
        }.thenReturn(resolution(resolvedEndpoint(MethodId.Bolt11, PRIVATE_BOLT11), version = 7uL))
        whenever(coreService.decode(PRIVATE_BOLT11))
            .thenReturn(Scanner.Lightning(lightningInvoice(PRIVATE_BOLT11, byteArrayOf(9, 9, 9))))

        sut.beginSavedContactPayment(CONTACT_KEY).getOrThrow()
        publishStarted.await()
        sut.beginSavedContactPayment(CONTACT_KEY).getOrThrow()
        sut.beginSavedContactPayment(CONTACT_KEY).getOrThrow()
        stalledPublish.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, publishes)
    }

    @Test
    fun `beginSavedContactPayment opens private endpoint with its list version`() = test {
        sut.prepareSavedContacts(listOf(CONTACT_KEY))
        whenever {
            paykitSdkService.prepareAndResolvePrivateContactPayment(CONTACT_KEY, null)
        }.thenReturn(resolution(resolvedEndpoint(MethodId.Bolt11, PRIVATE_BOLT11), version = 7uL))
        whenever(coreService.decode(PRIVATE_BOLT11))
            .thenReturn(Scanner.Lightning(lightningInvoice(PRIVATE_BOLT11, byteArrayOf(9, 9, 9))))

        val result = sut.beginSavedContactPayment(CONTACT_KEY).getOrThrow()

        assertEquals(
            PublicPaykitPaymentResult.Opened(
                paymentRequest = PRIVATE_BOLT11,
                privatePaymentContext = PrivatePaykitPaymentContext(mapOf(MethodId.Bolt11.rawValue to "bitkit"), 7uL),
            ),
            result,
        )
        verifyBlocking(publicPaykitRepo, never()) { beginPayment(any()) }
    }

    @Test
    fun `beginSavedContactPayment never falls back while linked recovery is pending`() = test {
        sut.prepareSavedContacts(listOf(CONTACT_KEY))
        whenever {
            paykitSdkService.prepareAndResolvePrivateContactPayment(CONTACT_KEY, null)
        }.thenReturn(
            resolution(
                status = PrivatePaymentResolutionStatus.NO_ENDPOINT,
                state = PrivatePaymentResolutionState.RECOVERY_PENDING,
                linkState = LinkedPeerState.RECOVERY_REQUIRED,
                version = null,
            ),
        )

        val result = sut.beginSavedContactPayment(CONTACT_KEY).getOrThrow()

        assertEquals(PublicPaykitPaymentResult.PrivateLinkPending, result)
        verifyBlocking(publicPaykitRepo, never()) { beginPayment(any()) }
    }

    @Test
    fun `beginPaymentRequest waits for a linking peer before opening cached details`() = test {
        val request = paymentRequest()
        whenever(
            paykitSdkService.prepareAndResolvePrivatePaymentRequest(
                eq(CONTACT_KEY),
                eq("request-id"),
                eq(null),
            )
        ).thenReturn(
            resolution(
                resolvedEndpoint(MethodId.Bolt11, SERVER_PRIVATE_BOLT11),
                version = 7uL,
                linkState = LinkedPeerState.LINKING,
            ),
            resolution(
                resolvedEndpoint(MethodId.Bolt11, SERVER_PRIVATE_BOLT11),
                version = 7uL,
                linkState = LinkedPeerState.LINKED,
            ),
        )
        whenever(coreService.decode(SERVER_PRIVATE_BOLT11))
            .thenReturn(Scanner.Lightning(lightningInvoice(SERVER_PRIVATE_BOLT11, byteArrayOf(8, 8, 8))))

        val pending = sut.beginPaymentRequest(request).getOrThrow()
        val opened = sut.beginPaymentRequest(request).getOrThrow()

        assertEquals(PublicPaykitPaymentResult.PrivateLinkPending, pending)
        assertEquals(
            PublicPaykitPaymentResult.Opened(
                paymentRequest = SERVER_PRIVATE_BOLT11,
                privatePaymentContext = PrivatePaykitPaymentContext(mapOf(MethodId.Bolt11.rawValue to "bitkit"), 7uL),
            ),
            opened,
        )
        verifyBlocking(publicPaykitRepo, never()) { beginPayment(any()) }
    }

    @Test
    fun `beginPaymentRequest rejects cached details when the peer is not linked`() = test {
        val request = paymentRequest()
        whenever(
            paykitSdkService.prepareAndResolvePrivatePaymentRequest(
                eq(CONTACT_KEY),
                eq("request-id"),
                eq(null),
            )
        ).thenReturn(
            resolution(
                resolvedEndpoint(MethodId.Bolt11, SERVER_PRIVATE_BOLT11),
                version = 7uL,
                linkState = null,
            ),
        )

        val result = sut.beginPaymentRequest(request).getOrThrow()

        assertEquals(PublicPaykitPaymentResult.NoEndpoint, result)
        verify(coreService, never()).decode(any())
        verifyBlocking(publicPaykitRepo, never()) { beginPayment(any()) }
    }

    @Test
    fun `beginPaymentRequest keeps typed recovery failures pending`() = test {
        val request = paymentRequest()
        whenever(
            paykitSdkService.prepareAndResolvePrivatePaymentRequest(
                eq(CONTACT_KEY),
                eq("request-id"),
                eq(null),
            )
        ).doSuspendableAnswer {
            throw PaykitException.RecoveryRequired("recovery_required", "Handshake is in progress")
        }

        val result = sut.beginPaymentRequest(request).getOrThrow()

        assertEquals(PublicPaykitPaymentResult.PrivateLinkPending, result)
        verifyBlocking(publicPaykitRepo, never()) { beginPayment(any()) }
    }

    @Test
    fun `beginSavedContactPayment retries a newer private list without public fallback`() = test {
        sut.prepareSavedContacts(listOf(CONTACT_KEY))
        whenever {
            paykitSdkService.prepareAndResolvePrivateContactPayment(CONTACT_KEY, null)
        }.thenReturn(
            resolution(
                status = PrivatePaymentResolutionStatus.WAITING_FOR_UPDATED_PAYMENT_LIST,
                state = PrivatePaymentResolutionState.NO_PRIVATE_ENDPOINT,
                linkState = LinkedPeerState.LINKED,
                version = null,
            ),
            resolution(resolvedEndpoint(MethodId.Bolt11, PRIVATE_BOLT11), version = 7uL),
        )
        whenever(coreService.decode(PRIVATE_BOLT11))
            .thenReturn(Scanner.Lightning(lightningInvoice(PRIVATE_BOLT11, byteArrayOf(9, 9, 9))))

        val result = sut.beginSavedContactPayment(CONTACT_KEY).getOrThrow()

        assertEquals(
            PublicPaykitPaymentResult.Opened(
                paymentRequest = PRIVATE_BOLT11,
                privatePaymentContext = PrivatePaykitPaymentContext(mapOf(MethodId.Bolt11.rawValue to "bitkit"), 7uL),
            ),
            result,
        )
        verifyBlocking(paykitSdkService, times(2)) {
            prepareAndResolvePrivateContactPayment(CONTACT_KEY, null)
        }
        verifyBlocking(publicPaykitRepo, never()) { beginPayment(any()) }
    }

    @Test
    fun `beginSavedContactPayment only falls back after failure when Noise link is absent`() = test {
        sut.prepareSavedContacts(listOf(CONTACT_KEY))
        whenever {
            paykitSdkService.prepareAndResolvePrivateContactPayment(CONTACT_KEY, null)
        }.thenThrow(IllegalStateException("private unavailable"))

        val result = sut.beginSavedContactPayment(CONTACT_KEY).getOrThrow()

        assertEquals(PublicPaykitPaymentResult.Opened("bitcoin:bcrt1qpublic"), result)
        verifyBlocking(publicPaykitRepo) { beginPayment(CONTACT_KEY) }
    }

    @Test
    fun `beginSavedContactPayment propagates failure when Noise link exists`() = test {
        sut.prepareSavedContacts(listOf(CONTACT_KEY))
        whenever {
            paykitSdkService.prepareAndResolvePrivateContactPayment(CONTACT_KEY, null)
        }.thenThrow(IllegalStateException("private unavailable"))
        whenever(paykitSdkService.linkedPeers())
            .thenReturn(listOf(linkedPeer(CONTACT_KEY, LinkedPeerState.LINKED)))

        assertFailsWith<IllegalStateException> {
            sut.beginSavedContactPayment(CONTACT_KEY).getOrThrow()
        }
        verifyBlocking(publicPaykitRepo, never()) { beginPayment(any()) }
    }

    @Test
    fun `consumePrivatePaymentList persists version clears list and rejects reuse`() = test {
        val context = PrivatePaykitPaymentContext(mapOf(MethodId.Bolt11.rawValue to "bitkit"), 7uL)

        sut.consumePrivatePaymentList(CONTACT_KEY, context).getOrThrow()

        assertEquals(
            7uL,
            cacheData.value.contacts.getValue(CONTACT_KEY)
                .consumedPrivatePaymentListVersion,
        )
        assertFailsWith<PrivatePaykitError.PaymentListAlreadyConsumed> {
            sut.consumePrivatePaymentList(CONTACT_KEY, context).getOrThrow()
        }
    }

    @Test
    fun `unsent hardware release preserves previously consumed boundary`() = test {
        sut.consumePrivatePaymentList(CONTACT_KEY, PrivatePaykitPaymentContext(emptyMap(), 6uL)).getOrThrow()
        sut.consumePrivatePaymentList(CONTACT_KEY, PrivatePaykitPaymentContext(emptyMap(), 7uL)).getOrThrow()
        sut.releasePrivatePaymentListVersion(CONTACT_KEY, 7uL, 6uL).getOrThrow()
        assertEquals(6uL, cacheData.value.contacts.getValue(CONTACT_KEY).consumedPrivatePaymentListVersion)
    }

    @Test
    fun `releasePrivatePaymentList makes matching version reusable without clearing newer consumption`() = test {
        val releasedContext = PrivatePaykitPaymentContext(mapOf(MethodId.Bolt11.rawValue to "bitkit"), 7uL)
        val newerContext = PrivatePaykitPaymentContext(mapOf(MethodId.Bolt11.rawValue to "bitkit"), 8uL)

        sut.consumePrivatePaymentList(CONTACT_KEY, releasedContext).getOrThrow()
        sut.releasePrivatePaymentList(CONTACT_KEY, releasedContext).getOrThrow()

        assertNull(
            cacheData.value.contacts[CONTACT_KEY]
                ?.consumedPrivatePaymentListVersion,
        )
        sut.consumePrivatePaymentList(CONTACT_KEY, releasedContext).getOrThrow()
        sut.consumePrivatePaymentList(CONTACT_KEY, newerContext).getOrThrow()

        sut.releasePrivatePaymentList(CONTACT_KEY, releasedContext).getOrThrow()

        assertEquals(
            8uL,
            cacheData.value.contacts.getValue(CONTACT_KEY)
                .consumedPrivatePaymentListVersion,
        )
    }

    @Test
    fun `beginSavedContactPayment passes consumed list version to private resolver`() = test {
        sut.prepareSavedContacts(listOf(CONTACT_KEY))
        sut.consumePrivatePaymentList(
            CONTACT_KEY,
            PrivatePaykitPaymentContext(mapOf(MethodId.Bolt11.rawValue to "bitkit"), 7uL),
        ).getOrThrow()
        whenever {
            paykitSdkService.prepareAndResolvePrivateContactPayment(CONTACT_KEY, 7uL)
        }.thenReturn(
            resolution(
                status = PrivatePaymentResolutionStatus.WAITING_FOR_UPDATED_PAYMENT_LIST,
                state = PrivatePaymentResolutionState.NO_PRIVATE_ENDPOINT,
                linkState = LinkedPeerState.LINKED,
                version = null,
            ),
        )

        sut.beginSavedContactPayment(CONTACT_KEY).getOrThrow()

        verifyBlocking(paykitSdkService, times(4)) {
            prepareAndResolvePrivateContactPayment(CONTACT_KEY, 7uL)
        }
    }

    @Test
    fun `beginSavedContactPayment does not fall back when private resolution is cancelled`() = test {
        sut.prepareSavedContacts(listOf(CONTACT_KEY))
        whenever {
            paykitSdkService.prepareAndResolvePrivateContactPayment(CONTACT_KEY, null)
        }.thenThrow(CancellationException("cancelled"))

        assertFailsWith<CancellationException> {
            sut.beginSavedContactPayment(CONTACT_KEY)
        }
        verifyBlocking(publicPaykitRepo, never()) { beginPayment(any()) }
    }

    @Test
    fun `beginPaymentRequestWaitingForUpdatedList retries a newer private list`() = test {
        val request = paymentRequest()
        whenever {
            paykitSdkService.prepareAndResolvePrivatePaymentRequest(
                eq(CONTACT_KEY),
                eq("request-id"),
                eq(null),
            )
        }.thenReturn(
            resolution(
                status = PrivatePaymentResolutionStatus.WAITING_FOR_UPDATED_PAYMENT_LIST,
                state = PrivatePaymentResolutionState.NO_PRIVATE_ENDPOINT,
                linkState = LinkedPeerState.LINKED,
                version = null,
            ),
            resolution(resolvedEndpoint(MethodId.Bolt11, SERVER_PRIVATE_BOLT11), version = 7uL),
        )
        whenever(coreService.decode(SERVER_PRIVATE_BOLT11))
            .thenReturn(Scanner.Lightning(lightningInvoice(SERVER_PRIVATE_BOLT11, byteArrayOf(8, 8, 8))))

        val result = sut.beginPaymentRequestWaitingForUpdatedList(request).getOrThrow()

        assertEquals(
            PublicPaykitPaymentResult.Opened(
                paymentRequest = SERVER_PRIVATE_BOLT11,
                privatePaymentContext = PrivatePaykitPaymentContext(mapOf(MethodId.Bolt11.rawValue to "bitkit"), 7uL),
            ),
            result,
        )
        verifyBlocking(paykitSdkService, times(2)) {
            prepareAndResolvePrivatePaymentRequest(
                eq(CONTACT_KEY),
                eq("request-id"),
                eq(null),
            )
        }
    }

    @Test
    fun `beginPaymentRequest resolves only accepted private endpoints with the requested amount`() = test {
        val request = paymentRequest(acceptedEndpointIdentifiers = listOf(MethodId.Bolt11.rawValue))
        whenever {
            paykitSdkService.prepareAndResolvePrivatePaymentRequest(
                eq(CONTACT_KEY),
                eq("request-id"),
                eq(null),
            )
        }.thenReturn(
            resolution(
                resolvedEndpoint(MethodId.P2wpkh, PRIVATE_ADDRESS),
                resolvedEndpoint(MethodId.Bolt11, PRIVATE_BOLT11),
                version = 7uL,
            ),
        )
        whenever(coreService.decode(PRIVATE_BOLT11))
            .thenReturn(Scanner.Lightning(lightningInvoice(PRIVATE_BOLT11, byteArrayOf(9, 9, 9))))

        val result = sut.beginPaymentRequest(request).getOrThrow()

        assertEquals(
            PublicPaykitPaymentResult.Opened(
                paymentRequest = PRIVATE_BOLT11,
                privatePaymentContext = PrivatePaykitPaymentContext(mapOf(MethodId.Bolt11.rawValue to "bitkit"), 7uL),
            ),
            result,
        )
        verifyBlocking(paykitSdkService) {
            prepareAndResolvePrivatePaymentRequest(
                eq(CONTACT_KEY),
                eq("request-id"),
                eq(null),
            )
        }
        verifyBlocking(publicPaykitRepo, never()) { beginPayment(any()) }
    }

    @Test
    fun `beginPaymentRequest uses cached private resolution without live SDK session`() = test {
        val request = paymentRequest()
        whenever(paykitSdkService.hasPrivatePaymentAccess()).thenReturn(false)
        whenever {
            paykitSdkService.prepareAndResolvePrivatePaymentRequest(
                eq(CONTACT_KEY),
                eq("request-id"),
                eq(null),
            )
        }.thenReturn(
            resolution(
                resolvedEndpoint(MethodId.Bolt11, SERVER_PRIVATE_BOLT11),
                version = 7uL,
            ),
        )
        whenever(coreService.decode(SERVER_PRIVATE_BOLT11))
            .thenReturn(Scanner.Lightning(lightningInvoice(SERVER_PRIVATE_BOLT11, byteArrayOf(8, 8, 8))))

        val result = sut.beginPaymentRequest(request).getOrThrow()

        assertEquals(
            PublicPaykitPaymentResult.Opened(
                paymentRequest = SERVER_PRIVATE_BOLT11,
                privatePaymentContext = PrivatePaykitPaymentContext(mapOf(MethodId.Bolt11.rawValue to "bitkit"), 7uL),
            ),
            result,
        )
        verifyBlocking(publicPaykitRepo, never()) { beginPayment(any()) }
    }

    @Test
    fun `bound requests keep their own addresses after contact list updates without consuming the list`() = test {
        sut.prepareSavedContacts(listOf(CONTACT_KEY))
        sut.consumePrivatePaymentList(CONTACT_KEY, PrivatePaykitPaymentContext(emptyMap(), 7uL)).getOrThrow()
        whenever(
            paykitSdkService.prepareAndResolvePrivateContactPayment(CONTACT_KEY, 7uL),
        ).thenReturn(resolution(resolvedEndpoint(MethodId.P2wpkh, "latest-address"), version = 8uL))
        whenever(coreService.isAddressUsed(any())).thenReturn(false)
        sut.beginSavedContactPayment(CONTACT_KEY).getOrThrow()
        val cachedEndpoints = cacheData.value.contacts.getValue(CONTACT_KEY).remoteEndpoints
        val addresses = mapOf("invoice-a" to PRIVATE_ADDRESS, "invoice-b" to OTHER_PRIVATE_ADDRESS)
        whenever(
            paykitSdkService.prepareAndResolvePrivatePaymentRequest(eq(CONTACT_KEY), any(), eq(7uL)),
        ).thenAnswer {
            resolution(resolvedEndpoint(MethodId.P2wpkh, addresses.getValue(it.getArgument(1))), version = null)
        }

        for (id in listOf("invoice-a", "invoice-b", "invoice-a")) {
            val request = paymentRequest(listOf(MethodId.P2wpkh.rawValue)).copy(paymentRequestId = id)
            val result = sut.beginPaymentRequest(request).getOrThrow()
            val context = PrivatePaykitPaymentContext(mapOf(MethodId.P2wpkh.rawValue to "bitkit"), null)
            assertEquals(PublicPaykitPaymentResult.Opened(addresses.getValue(id), context), result)
            sut.consumePrivatePaymentList(CONTACT_KEY, context).getOrThrow()
            sut.releasePrivatePaymentList(CONTACT_KEY, context).getOrThrow()
            assertEquals(7uL, cacheData.value.contacts.getValue(CONTACT_KEY).consumedPrivatePaymentListVersion)
            assertEquals(cachedEndpoints, cacheData.value.contacts.getValue(CONTACT_KEY).remoteEndpoints)
        }
        verifyBlocking(paykitSdkService, times(1)) { prepareAndResolvePrivateContactPayment(CONTACT_KEY, 7uL) }
        verifyBlocking(publicPaykitRepo, never()) { beginPayment(any()) }
    }

    @Test
    fun `bound request with no payable candidate never falls back to contact or public endpoints`() = test {
        whenever {
            paykitSdkService.prepareAndResolvePrivatePaymentRequest(CONTACT_KEY, "request-id", null)
        }.thenReturn(resolution(version = null, linkState = LinkedPeerState.LINKED))

        assertEquals(PublicPaykitPaymentResult.NoEndpoint, sut.beginPaymentRequest(paymentRequest()).getOrThrow())
        verifyBlocking(paykitSdkService, never()) { prepareAndResolvePrivateContactPayment(any(), any(), any()) }
        verifyBlocking(publicPaykitRepo, never()) { beginPayment(any()) }
    }

    @Test
    fun `bound request keeps wallet address usage validation`() = test {
        whenever {
            paykitSdkService.prepareAndResolvePrivatePaymentRequest(CONTACT_KEY, "request-id", null)
        }.thenReturn(resolution(resolvedEndpoint(MethodId.P2wpkh, PRIVATE_ADDRESS), version = null))
        whenever(coreService.isAddressUsed(PRIVATE_ADDRESS)).thenReturn(true)

        val request = paymentRequest(listOf(MethodId.P2wpkh.rawValue))
        assertEquals(PublicPaykitPaymentResult.NotOpened, sut.beginPaymentRequest(request).getOrThrow())
        verifyBlocking(publicPaykitRepo, never()) { beginPayment(any()) }
    }

    @Test
    fun `recurring request can reuse its fixed onchain destination but not a consumed private list`() = test {
        val endpoint = resolvedEndpoint(MethodId.P2wpkh, PRIVATE_ADDRESS)
        whenever(
            paykitSdkService.prepareAndResolvePrivatePaymentRequest(CONTACT_KEY, "request-id", null),
        ).thenReturn(resolution(endpoint, version = null))
        whenever(coreService.isAddressUsed(PRIVATE_ADDRESS)).thenReturn(false)
        val request = paymentRequest(listOf(MethodId.P2wpkh.rawValue)).copy(
            lifecycleState = PaymentRequestLifecycleState.ACTIVE_RECURRING,
            billingPeriod = PaykitBillingPeriod(
                Instant.parse("2027-01-01T08:00:00Z"),
                Instant.parse("2027-02-01T08:00:00Z"),
            ),
        )
        val expected = PublicPaykitPaymentResult.Opened(
            PRIVATE_ADDRESS,
            PrivatePaykitPaymentContext(mapOf(MethodId.P2wpkh.rawValue to "bitkit"), null),
        )
        assertEquals(expected, sut.beginPaymentRequest(request).getOrThrow())

        whenever(coreService.isAddressUsed(PRIVATE_ADDRESS)).thenReturn(true)
        val nextPeriod = request.copy(
            billingPeriod = PaykitBillingPeriod(
                Instant.parse("2027-02-01T08:00:00Z"),
                Instant.parse("2027-03-01T08:00:00Z"),
            ),
        )
        assertEquals(expected, sut.beginPaymentRequest(nextPeriod).getOrThrow())

        whenever(
            paykitSdkService.prepareAndResolvePrivatePaymentRequest(CONTACT_KEY, "request-id", null),
        ).thenReturn(resolution(endpoint, version = 7uL))
        assertEquals(PublicPaykitPaymentResult.NotOpened, sut.beginPaymentRequest(nextPeriod).getOrThrow())
        verifyBlocking(publicPaykitRepo, never()) { beginPayment(any()) }
    }

    @Test
    fun `beginPaymentRequest rechecks proposal and payment deadlines after private resolution`() = test {
        whenever {
            paykitSdkService.prepareAndResolvePrivatePaymentRequest(
                eq(CONTACT_KEY),
                eq("request-id"),
                eq(null),
            )
        }.thenReturn(
            resolution(
                resolvedEndpoint(MethodId.Bolt11, SERVER_PRIVATE_BOLT11),
                version = 7uL,
            ),
        )
        whenever(coreService.decode(SERVER_PRIVATE_BOLT11))
            .thenReturn(Scanner.Lightning(lightningInvoice(SERVER_PRIVATE_BOLT11, byteArrayOf(8, 8, 8))))

        val requests = listOf(
            paymentRequest(),
            paymentRequest().copy(
                lifecycleState = PaymentRequestLifecycleState.ACCEPTED,
                expiresAt = Instant.fromEpochSeconds(NOW_SECONDS - 1),
                paymentDeadlineAt = Instant.fromEpochSeconds(NOW_SECONDS + 60),
            ),
        )
        requests.forEach { request ->
            whenever(clock.now()).thenReturn(
                Instant.fromEpochSeconds(NOW_SECONDS),
                Instant.fromEpochSeconds(NOW_SECONDS + 61),
            )
            assertFailsWith<PaykitPaymentRequestError.RequestExpired> {
                sut.beginPaymentRequest(request).getOrThrow()
            }
        }
        verifyBlocking(publicPaykitRepo, never()) { beginPayment(any()) }
    }

    @Test
    fun `backupSnapshot and restoreBackup use SDK backup state`() = test {
        val backup = "sdk-backup"
        whenever(paykitSdkService.exportBackupState()).thenReturn(backup)
        sut.consumePrivatePaymentList(
            CONTACT_KEY,
            PrivatePaykitPaymentContext(mapOf(MethodId.Bolt11.rawValue to "bitkit"), 7uL),
        ).getOrThrow()

        val snapshot = sut.backupSnapshot().getOrThrow()
        sut.restoreBackup(snapshot).getOrThrow()

        assertTrue(snapshot?.contains(backup) == true)
        assertEquals(
            7uL,
            cacheData.value.contacts.getValue(CONTACT_KEY)
                .consumedPrivatePaymentListVersion,
        )
        verifyBlocking(paykitSdkService) { retainRecoveryBackup(backup) }
    }

    private fun stubContactPreparationPriorities() {
        for (priority in listOf(Priority.Background, Priority.Interactive)) {
            whenever { paykitSdkService.ensureLinkWithPeer(any(), any(), eq(priority)) }
                .doSuspendableAnswer { paykitSdkService.ensureLinkWithPeer(it.getArgument(0)) }
        }
        whenever { paykitSdkService.identityStatus(any()) }.doSuspendableAnswer { paykitSdkService.identityStatus() }
    }

    private fun createSut(publicPaykitRepo: PublicPaykitRepo = this.publicPaykitRepo) = PrivatePaykitRepo(
        ioDispatcher = testDispatcher,
        paykitSdkService = paykitSdkService,
        pubkyService = pubkyService,
        cacheStore = cacheStore,
        settingsStore = settingsStore,
        addressReservationRepo = addressReservationRepo,
        lightningRepo = lightningRepo,
        walletRepo = walletRepo,
        publicPaykitRepo = publicPaykitRepo,
        paymentRequestRepo = paymentRequestRepo,
        coreService = coreService,
        clock = clock,
    ).also { it.setContactPreparationActive(true) }

    private fun resolution(
        vararg endpoints: PaykitResolvedPaymentEndpoint,
        status: PrivatePaymentResolutionStatus = if (endpoints.isEmpty()) {
            PrivatePaymentResolutionStatus.NO_ENDPOINT
        } else {
            PrivatePaymentResolutionStatus.PAYABLE
        },
        state: PrivatePaymentResolutionState = if (endpoints.isEmpty()) {
            PrivatePaymentResolutionState.NO_PRIVATE_ENDPOINT
        } else {
            PrivatePaymentResolutionState.AVAILABLE
        },
        version: ULong? = 1uL,
        linkState: LinkedPeerState? = LinkedPeerState.LINKED,
    ) = PaykitPreparedPrivateContactPayment(
        resolution = PaykitPrivateContactPaymentResolution(
            status = status,
            state = state,
            privatePaymentListVersion = version,
            payableEndpoints = endpoints.toList(),
        ),
        linkState = linkState,
    )

    private fun resolvedEndpoint(
        methodId: MethodId,
        value: String,
    ): PaykitResolvedPaymentEndpoint {
        return PaykitResolvedPaymentEndpoint(
            identifier = methodId.rawValue,
            payload = PublicPaykitRepo.serializePayload(value),
            appId = "bitkit",
        )
    }

    private fun paymentRequest(
        acceptedEndpointIdentifiers: List<String> = listOf(MethodId.Bolt11.rawValue),
    ) = PaykitPaymentRequest(
        paymentRequestId = "request-id",
        counterparty = CONTACT_KEY,
        amountValue = "0.000025",
        amountSats = 2_500uL,
        expiresAt = Instant.fromEpochSeconds(NOW_SECONDS + 60),
        acceptedPaymentEndpointIdentifiers = acceptedEndpointIdentifiers,
    )

    private fun privateListDeliveryReport(
        queuedCounterparties: List<String> = emptyList(),
        clearedCounterparties: List<String> = emptyList(),
        failedToQueue: List<PrivatePaymentListSyncChange> = emptyList(),
        failedToDeliver: List<PrivatePaymentListDeliveryFailure> = emptyList(),
    ) = PrivatePaymentListDeliveryReport(
        queued = queuedCounterparties.map {
            PrivatePaymentListSyncChange(
                counterparty = it,
                outboundMessageId = null,
                error = null,
            )
        },
        cleared = clearedCounterparties.map {
            PrivatePaymentListSyncChange(
                counterparty = it,
                outboundMessageId = null,
                error = null,
            )
        },
        failedToQueue = failedToQueue,
        failedToDeliver = failedToDeliver,
    )

    private fun cachedPublishedContact() = PrivatePaykitContactCacheData(
        hasPublishedPrivatePaymentList = true,
    )

    private fun privateListDeliveryReportForUpdates(
        updates: List<PrivatePaymentListReservationUpdateInput>,
    ) = PrivatePaymentListDeliveryReport(
        queued = updates
            .filter { it.reservations.isNotEmpty() }
            .map { privateListSyncChange(it.counterparty) },
        cleared = updates
            .filter { it.reservations.isEmpty() }
            .map { privateListSyncChange(it.counterparty) },
        failedToQueue = emptyList(),
        failedToDeliver = emptyList(),
    )

    private fun privateListSyncChange(
        counterparty: String,
    ) = PrivatePaymentListSyncChange(
        counterparty = counterparty,
        outboundMessageId = null,
        error = null,
    )

    private fun contactRecord(publicKey: String) = ContactRecord(
        publicKey = publicKey,
        label = null,
        profile = null,
        profileFetchedAt = null,
        createdAt = "2026-01-01T00:00:00Z",
        updatedAt = "2026-01-01T00:00:00Z",
        publicContactMarkerStatus = PublicationStatus.NOT_PUBLISHED,
        publicContactPublishedAt = null,
        publicContactRemovedAt = null,
        publicContactLastError = null,
    )

    private fun linkedPeer(
        publicKey: String,
        state: LinkedPeerState,
    ) = LinkedPeerRecord(
        counterparty = publicKey,
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

    private fun lightningInvoice(bolt11: String, paymentHash: ByteArray) = LightningInvoice(
        bolt11 = bolt11,
        paymentHash = paymentHash,
        amountSatoshis = 0uL,
        timestampSeconds = NOW_SECONDS.toULong(),
        expirySeconds = PRIVATE_BOLT11_EXPIRY_SECONDS.toULong(),
        isExpired = false,
        description = "",
        networkType = NetworkType.REGTEST,
        payeeNodeId = null,
    )
}

private class PrivatePaykitTestAppError(message: String) : AppError(message)
