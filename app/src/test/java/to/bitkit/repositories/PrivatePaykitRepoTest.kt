package to.bitkit.repositories

import android.app.Activity
import com.synonym.bitkitcore.LightningInvoice
import com.synonym.bitkitcore.NetworkType
import com.synonym.bitkitcore.Scanner
import com.synonym.paykit.ContactPaymentResolutionPrivateState
import com.synonym.paykit.IdentityStatus
import com.synonym.paykit.LinkedPeerRecord
import com.synonym.paykit.LinkedPeerState
import com.synonym.paykit.PaymentEndpointSource
import com.synonym.paykit.PrivatePaymentListDeliveryReport
import com.synonym.paykit.PrivatePaymentListReservationUpdateInput
import com.synonym.paykit.PrivatePaymentListSyncChange
import com.synonym.paykit.PubkyIdentityCapability
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeast
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import to.bitkit.App
import to.bitkit.CurrentActivity
import to.bitkit.data.PrivatePaykitCacheData
import to.bitkit.data.PrivatePaykitCacheStore
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.models.NodeLifecycleState
import to.bitkit.services.CoreService
import to.bitkit.services.PaykitContactPaymentResolution
import to.bitkit.services.PaykitResolvedPaymentEndpoint
import to.bitkit.services.PaykitSdkService
import to.bitkit.services.PubkyService
import to.bitkit.test.BaseUnitTest
import to.bitkit.utils.AppError
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Clock
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
        whenever(lightningRepo.lightningState).thenReturn(lightningState)
        whenever(clock.now()).thenReturn(Instant.fromEpochSeconds(NOW_SECONDS))
        whenever(pubkyService.currentPublicKey()).thenReturn(OWN_KEY)
        whenever(paykitSdkService.identityStatus()).thenReturn(
            IdentityStatus(
                publicKey = OWN_KEY,
                capability = PubkyIdentityCapability.PRIVATE_LINK_CAPABLE,
                liveSessionAvailable = true,
                privateLinkCapable = true,
            ),
        )
        whenever(walletRepo.walletExists()).thenReturn(true)
        whenever { walletRepo.refreshReusableReceiveAddressIfReserved() }.thenReturn(Result.success(Unit))
        whenever { addressReservationRepo.reconcileReservedIndexesWithLdk() }.thenReturn(Result.success(Unit))
        whenever { addressReservationRepo.currentOrRotatedAddress(CONTACT_KEY) }
            .thenReturn(Result.success(PRIVATE_ADDRESS))
        whenever { paykitSdkService.syncPrivatePaymentListsWithReservations(any(), any()) }
            .thenReturn(privateListDeliveryReport(queuedCounterparties = listOf(CONTACT_KEY)))
        whenever { paykitSdkService.linkedPeers() }.thenReturn(emptyList())
        whenever { paykitSdkService.pendingOutboundPrivateCounterparties() }.thenReturn(emptyList())
        whenever { paykitSdkService.clearPrivatePaymentList(any()) }.thenReturn(privateListDeliveryReport())
        whenever { publicPaykitRepo.beginPayment(any()) }
            .thenReturn(Result.success(PublicPaykitPaymentResult.Opened("bitcoin:bcrt1qpublic")))
        whenever { publicPaykitRepo.payableEndpoints(any()) }.thenAnswer { it.getArgument<List<Endpoint>>(0) }
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
    fun `prepareSavedContacts publishes private reservations through SDK`() = test {
        settingsData.value = SettingsData(
            sharesPrivatePaykitEndpoints = true,
            publicPaykitLightningEnabled = false,
            publicPaykitOnchainEnabled = true,
        )

        val result = sut.prepareSavedContacts(listOf(CONTACT_KEY), requireImmediatePublication = true)

        assertTrue(result.isSuccess, result.exceptionOrNull().toString())
        val captor = argumentCaptor<List<PrivatePaymentListReservationUpdateInput>>()
        verifyBlocking(paykitSdkService) { syncPrivatePaymentListsWithReservations(captor.capture(), eq(false)) }

        val update = captor.firstValue.single()
        val reservation = update.reservations.single()
        assertEquals(CONTACT_KEY, update.counterparty)
        assertEquals(MethodId.P2wpkh.rawValue, reservation.identifier)
        assertEquals(PublicPaykitRepo.serializePayload(PRIVATE_ADDRESS), reservation.payload)
        assertTrue(reservation.reservationId.startsWith("$CONTACT_KEY:${MethodId.P2wpkh.rawValue}:"))
        assertTrue(reservation.reservationId.length <= 128)
        assertEquals("private_paykit", reservation.attribution["type"])
        assertEquals(CONTACT_KEY, reservation.attribution["counterparty"])
        assertEquals(true, cacheData.value.contacts.getValue(CONTACT_KEY).hasPublishedPrivatePaymentList)
        assertEquals(setOf(CONTACT_KEY), cacheData.value.contactSharingPublicKeys)
    }

    @Test
    fun `prepare persists cleanup targets before SDK publication completes`() = test {
        settingsData.value = SettingsData(
            sharesPrivatePaykitEndpoints = true,
            publicPaykitLightningEnabled = false,
            publicPaykitOnchainEnabled = true,
        )
        whenever(paykitSdkService.syncPrivatePaymentListsWithReservations(any(), any()))
            .thenAnswer { throw PrivatePaykitTestAppError("publish interrupted") }

        val result = sut.prepareSavedContacts(listOf(CONTACT_KEY), requireImmediatePublication = true)

        assertTrue(result.isFailure)
        assertEquals(setOf(CONTACT_KEY), cacheData.value.contactSharingPublicKeys)
    }

    @Test
    fun `prepareSavedContacts succeeds when link preparation fails but SDK queues reservations`() = test {
        settingsData.value = SettingsData(
            sharesPrivatePaykitEndpoints = true,
            publicPaykitLightningEnabled = false,
            publicPaykitOnchainEnabled = true,
        )
        whenever(paykitSdkService.ensureLinkWithPeer(CONTACT_KEY)).thenAnswer {
            throw PrivatePaykitTestAppError("still linking")
        }

        val result = sut.prepareSavedContacts(listOf(CONTACT_KEY), requireImmediatePublication = true)

        assertTrue(result.isSuccess, result.exceptionOrNull().toString())
        verifyBlocking(paykitSdkService) { syncPrivatePaymentListsWithReservations(any(), eq(false)) }
        assertEquals(true, cacheData.value.contacts.getValue(CONTACT_KEY).hasPublishedPrivatePaymentList)
    }

    @Test
    fun `private message drain keeps retrying while link is still pending`() = test {
        settingsData.value = SettingsData(
            sharesPrivatePaykitEndpoints = true,
            publicPaykitLightningEnabled = false,
            publicPaykitOnchainEnabled = true,
        )
        whenever(paykitSdkService.linkedPeers())
            .thenReturn(listOf(linkedPeer(CONTACT_KEY, LinkedPeerState.LINKING)))

        val result = sut.prepareSavedContacts(listOf(CONTACT_KEY), requireImmediatePublication = true)

        assertTrue(result.isSuccess, result.exceptionOrNull().toString())
        advanceTimeBy(257_000)
        runCurrent()

        verifyBlocking(paykitSdkService, atLeast(8)) { ensureLinkWithPeer(CONTACT_KEY) }
        sut.closeAndClear()
    }

    @Test
    fun `prepareSavedContacts includes lightning payment hash in reservation attribution`() = test {
        settingsData.value = SettingsData(
            sharesPrivatePaykitEndpoints = true,
            publicPaykitLightningEnabled = true,
            publicPaykitOnchainEnabled = false,
        )
        whenever(lightningRepo.canReceive()).thenReturn(true)
        whenever(
            lightningRepo.createInvoice(
                amountSats = null,
                description = "",
                expirySeconds = PRIVATE_BOLT11_EXPIRY_SECONDS,
            ),
        ).thenReturn(Result.success(PRIVATE_BOLT11))
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
    fun `disable sharing removes onchain-only private publications from cache`() = test {
        settingsData.value = SettingsData(
            sharesPrivatePaykitEndpoints = true,
            publicPaykitLightningEnabled = false,
            publicPaykitOnchainEnabled = true,
        )
        sut.prepareSavedContacts(listOf(CONTACT_KEY), requireImmediatePublication = true)

        val result = sut.disableSharingAndPruneUnsavedContactState(listOf(CONTACT_KEY))

        assertTrue(result.isSuccess)
        verifyBlocking(paykitSdkService) { clearPrivatePaymentList(CONTACT_KEY) }
        assertTrue(cacheData.value.contacts.isEmpty())
    }

    @Test
    fun `disable removes supplied contacts when publication cache was not persisted`() = test {
        settingsData.value = SettingsData(pendingContactPaymentsEnabled = false)

        val result = sut.disableSharingAndPruneUnsavedContactState(listOf(CONTACT_KEY))

        assertTrue(result.isSuccess)
        verifyBlocking(paykitSdkService) { clearPrivatePaymentList(CONTACT_KEY) }
    }

    @Test
    fun `pending cleanup uses durable publication targets before contacts load`() = test {
        cacheData.value = cacheData.value.copy(
            cleanupPending = true,
            contactSharingPublicKeys = setOf(CONTACT_KEY),
        )

        val result = sut.retryPendingEndpointRemoval(emptyList())

        assertTrue(result.isSuccess)
        assertEquals(false, cacheData.value.cleanupPending)
        assertTrue(cacheData.value.contactSharingPublicKeys.isEmpty())
        verifyBlocking(paykitSdkService) { clearPrivatePaymentList(CONTACT_KEY) }
    }

    @Test
    fun `pending cleanup includes supplied contacts when cache is empty`() = test {
        cacheData.value = cacheData.value.copy(cleanupPending = true)

        val result = sut.retryPendingEndpointRemoval(listOf(CONTACT_KEY))

        assertTrue(result.isSuccess)
        verifyBlocking(paykitSdkService) { clearPrivatePaymentList(CONTACT_KEY) }
    }

    @Test
    fun `pending cleanup preserves secondary removal failures`() = test {
        cacheData.value = cacheData.value.copy(
            cleanupPending = true,
            contactSharingPublicKeys = setOf(CONTACT_KEY, OTHER_CONTACT_KEY),
        )
        val failureReport = privateListDeliveryReport(
            failedToQueue = listOf(
                PrivatePaymentListSyncChange(
                    counterparty = CONTACT_KEY,
                    outboundMessageId = null,
                    error = "failed",
                ),
            ),
        )
        whenever(paykitSdkService.clearPrivatePaymentList(CONTACT_KEY)).thenReturn(failureReport)
        whenever(paykitSdkService.clearPrivatePaymentList(OTHER_CONTACT_KEY)).thenReturn(failureReport)

        val error = sut.retryPendingEndpointRemoval(emptyList()).exceptionOrNull()

        assertTrue(error is PrivatePaykitError.EndpointCleanupFailed)
        assertTrue(error.cause is PrivatePaykitError.PrivateUnavailable)
        assertEquals(1, error.suppressed.size)
        assertEquals(true, cacheData.value.cleanupPending)
    }

    @Test
    fun `prepareSavedContacts records queued contacts when another contact cannot publish`() = test {
        settingsData.value = SettingsData(
            sharesPrivatePaykitEndpoints = true,
            publicPaykitLightningEnabled = false,
            publicPaykitOnchainEnabled = true,
        )
        whenever(addressReservationRepo.currentOrRotatedAddress(CONTACT_KEY))
            .thenReturn(Result.failure(PrivatePaykitTestAppError("address unavailable")))
        whenever(addressReservationRepo.currentOrRotatedAddress(OTHER_CONTACT_KEY))
            .thenReturn(Result.success(OTHER_PRIVATE_ADDRESS))
        whenever(paykitSdkService.syncPrivatePaymentListsWithReservations(any(), any())).thenReturn(
            privateListDeliveryReport(queuedCounterparties = listOf(OTHER_CONTACT_KEY)),
        )

        val result = sut.prepareSavedContacts(listOf(CONTACT_KEY, OTHER_CONTACT_KEY))

        assertTrue(result.isSuccess)
        assertEquals(true, cacheData.value.contacts.getValue(OTHER_CONTACT_KEY).hasPublishedPrivatePaymentList)
    }

    @Test
    fun `prepareSavedContacts preserves cleanup markers and blocks publication`() = test {
        settingsData.value = SettingsData(
            sharesPrivatePaykitEndpoints = true,
            publicPaykitLightningEnabled = false,
            publicPaykitOnchainEnabled = true,
        )
        cacheData.value = cacheData.value.copy(
            cleanupPending = true,
            deletedContactCleanupPendingPublicKeys = setOf(OTHER_CONTACT_KEY),
        )

        val result = sut.prepareSavedContacts(listOf(CONTACT_KEY))

        assertTrue(result.isSuccess)
        assertEquals(true, cacheData.value.cleanupPending)
        assertEquals(setOf(OTHER_CONTACT_KEY), cacheData.value.deletedContactCleanupPendingPublicKeys)
        verifyBlocking(paykitSdkService, never()) { syncPrivatePaymentListsWithReservations(any(), any()) }
    }

    @Test
    fun `ordinary preparation does not publish during a pending enable`() = test {
        settingsData.value = SettingsData(
            pendingContactPaymentsEnabled = true,
            sharesPrivatePaykitEndpoints = true,
            publicPaykitLightningEnabled = false,
        )

        val result = sut.prepareSavedContacts(listOf(CONTACT_KEY))

        assertTrue(result.isSuccess)
        verifyBlocking(paykitSdkService, never()) { syncPrivatePaymentListsWithReservations(any(), any()) }
    }

    @Test
    fun `active enable preparation can publish during its pending transition`() = test {
        settingsData.value = SettingsData(
            pendingContactPaymentsEnabled = true,
            sharesPrivatePaykitEndpoints = true,
            publicPaykitLightningEnabled = false,
        )

        val result = sut.prepareSavedContacts(listOf(CONTACT_KEY), requireImmediatePublication = true)

        assertTrue(result.isSuccess)
        verifyBlocking(paykitSdkService) { syncPrivatePaymentListsWithReservations(any(), any()) }
    }

    @Test
    fun `prepareSavedContacts does not publish after sharing is disabled during preparation`() = test {
        settingsData.value = SettingsData(
            sharesPrivatePaykitEndpoints = true,
            publicPaykitLightningEnabled = false,
            publicPaykitOnchainEnabled = true,
        )
        val preparationStarted = CompletableDeferred<Unit>()
        val releasePreparation = CompletableDeferred<Unit>()
        whenever(walletRepo.refreshReusableReceiveAddressIfReserved()).doSuspendableAnswer {
            preparationStarted.complete(Unit)
            releasePreparation.await()
            Result.success(Unit)
        }

        val preparation = async { sut.prepareSavedContacts(listOf(CONTACT_KEY)) }
        preparationStarted.await()
        settingsData.update { it.copy(sharesPrivatePaykitEndpoints = false) }
        releasePreparation.complete(Unit)

        assertTrue(preparation.await().isSuccess)
        verifyBlocking(paykitSdkService, never()) { syncPrivatePaymentListsWithReservations(any(), any()) }
    }

    @Test
    fun `closeAndClear clears SDK state`() = test {
        val result = sut.closeAndClear()

        assertTrue(result.isSuccess)
        verifyBlocking(paykitSdkService) { clearState() }
        assertTrue(cacheData.value.contacts.isEmpty())
    }

    @Test
    fun `beginSavedContactPayment uses public SDK endpoint when private capability is unavailable`() = test {
        settingsData.value = SettingsData(sharesPrivatePaykitEndpoints = true)
        whenever(paykitSdkService.identityStatus()).thenReturn(
            IdentityStatus(
                publicKey = OWN_KEY,
                capability = PubkyIdentityCapability.PUBLIC_ONLY,
                liveSessionAvailable = true,
                privateLinkCapable = false,
            ),
        )
        sut.prepareSavedContacts(listOf(CONTACT_KEY))
        whenever(
            paykitSdkService.prepareAndResolveContactPayment(CONTACT_KEY, includePublicEndpoints = true),
        ).thenReturn(
            resolution(
                resolvedEndpoint(
                    methodId = MethodId.P2wpkh,
                    value = "bcrt1qpublic",
                    source = PaymentEndpointSource.PUBLIC_PAYMENT_ENDPOINT,
                ),
            ),
        )

        val result = sut.beginSavedContactPayment(CONTACT_KEY).getOrThrow()

        assertEquals(PublicPaykitPaymentResult.Opened("bcrt1qpublic"), result)
        verifyBlocking(paykitSdkService) { prepareAndResolveContactPayment(CONTACT_KEY, includePublicEndpoints = true) }
        verifyBlocking(publicPaykitRepo, never()) { beginPayment(any()) }
    }

    @Test
    fun `beginSavedContactPayment opens SDK resolved private endpoint`() = test {
        settingsData.value = SettingsData(sharesPrivatePaykitEndpoints = true)
        sut.prepareSavedContacts(listOf(CONTACT_KEY))
        whenever(
            paykitSdkService.prepareAndResolveContactPayment(CONTACT_KEY, includePublicEndpoints = true),
        ).thenReturn(
            resolution(
                resolvedEndpoint(
                    methodId = MethodId.Bolt11,
                    value = PRIVATE_BOLT11,
                ),
            ),
        )
        whenever(coreService.decode(PRIVATE_BOLT11))
            .thenReturn(Scanner.Lightning(lightningInvoice(PRIVATE_BOLT11, byteArrayOf(9, 9, 9))))

        val result = sut.beginSavedContactPayment(CONTACT_KEY).getOrThrow()

        assertEquals(PublicPaykitPaymentResult.Opened(PRIVATE_BOLT11), result)
        verifyBlocking(paykitSdkService) { prepareAndResolveContactPayment(CONTACT_KEY, includePublicEndpoints = true) }
        verifyBlocking(publicPaykitRepo, never()) { beginPayment(any()) }
    }

    @Test
    fun `beginSavedContactPayment refreshes private endpoints before unified resolution`() = test {
        settingsData.value = SettingsData(sharesPrivatePaykitEndpoints = true)
        sut.prepareSavedContacts(listOf(CONTACT_KEY))
        clearInvocations(paykitSdkService)
        whenever(
            paykitSdkService.prepareAndResolveContactPayment(CONTACT_KEY, includePublicEndpoints = true),
        ).thenReturn(
            resolution(
                resolvedEndpoint(
                    methodId = MethodId.P2wpkh,
                    value = "bcrt1qpublic",
                    source = PaymentEndpointSource.PUBLIC_PAYMENT_ENDPOINT,
                ),
            ),
        )

        val result = sut.beginSavedContactPayment(CONTACT_KEY).getOrThrow()

        assertEquals(PublicPaykitPaymentResult.Opened("bcrt1qpublic"), result)
        val captor = argumentCaptor<List<PrivatePaymentListReservationUpdateInput>>()
        verifyBlocking(paykitSdkService) { syncPrivatePaymentListsWithReservations(captor.capture(), eq(false)) }
        assertEquals(CONTACT_KEY, captor.firstValue.single().counterparty)
        verifyBlocking(paykitSdkService) { prepareAndResolveContactPayment(CONTACT_KEY, includePublicEndpoints = true) }
    }

    @Test
    fun `beginSavedContactPayment does not fall back to public when unified resolution is cancelled`() = test {
        settingsData.value = SettingsData(sharesPrivatePaykitEndpoints = true)
        sut.prepareSavedContacts(listOf(CONTACT_KEY))
        whenever(
            paykitSdkService.prepareAndResolveContactPayment(CONTACT_KEY, includePublicEndpoints = true),
        ).thenThrow(CancellationException("cancelled"))

        assertFailsWith<CancellationException> {
            sut.beginSavedContactPayment(CONTACT_KEY)
        }

        verifyBlocking(publicPaykitRepo, never()) { beginPayment(any()) }
    }

    @Test
    fun `beginSavedContactPayment does not fall back to public when private refresh is cancelled`() = test {
        settingsData.value = SettingsData(sharesPrivatePaykitEndpoints = true)
        sut.prepareSavedContacts(listOf(CONTACT_KEY))
        clearInvocations(paykitSdkService, publicPaykitRepo)
        whenever(paykitSdkService.syncPrivatePaymentListsWithReservations(any(), any()))
            .thenThrow(CancellationException("cancelled"))

        assertFailsWith<CancellationException> {
            sut.beginSavedContactPayment(CONTACT_KEY)
        }

        verifyBlocking(paykitSdkService, never()) { prepareAndResolveContactPayment(any(), any()) }
        verifyBlocking(publicPaykitRepo, never()) { beginPayment(any()) }
    }

    @Test
    fun `beginSavedContactPayment does not fall back to public when endpoint build is cancelled`() = test {
        settingsData.value = SettingsData(sharesPrivatePaykitEndpoints = true)
        sut.prepareSavedContacts(listOf(CONTACT_KEY))
        clearInvocations(paykitSdkService, publicPaykitRepo)
        whenever(walletRepo.refreshReusableReceiveAddressIfReserved())
            .thenThrow(CancellationException("cancelled"))

        assertFailsWith<CancellationException> {
            sut.beginSavedContactPayment(CONTACT_KEY)
        }

        verifyBlocking(paykitSdkService, never()) { prepareAndResolveContactPayment(any(), any()) }
        verifyBlocking(publicPaykitRepo, never()) { beginPayment(any()) }
    }

    @Test
    fun `beginSavedContactPayment does not fall back to public when publish gate is cancelled`() = test {
        settingsData.value = SettingsData(sharesPrivatePaykitEndpoints = true)
        sut.prepareSavedContacts(listOf(CONTACT_KEY))
        clearInvocations(paykitSdkService, publicPaykitRepo)
        whenever(pubkyService.currentPublicKey()).thenThrow(CancellationException("cancelled"))

        assertFailsWith<CancellationException> {
            sut.beginSavedContactPayment(CONTACT_KEY)
        }

        verifyBlocking(paykitSdkService, never()) { prepareAndResolveContactPayment(any(), any()) }
        verifyBlocking(publicPaykitRepo, never()) { beginPayment(any()) }
    }

    @Test
    fun `beginSavedContactPayment falls back to public when unified resolution fails`() = test {
        settingsData.value = SettingsData(sharesPrivatePaykitEndpoints = true)
        sut.prepareSavedContacts(listOf(CONTACT_KEY))
        whenever(
            paykitSdkService.prepareAndResolveContactPayment(CONTACT_KEY, includePublicEndpoints = true),
        ).thenThrow(IllegalStateException("private unavailable"))
        whenever(publicPaykitRepo.beginPayment(CONTACT_KEY)).thenReturn(
            Result.success(PublicPaykitPaymentResult.Opened("public-fallback")),
        )

        val result = sut.beginSavedContactPayment(CONTACT_KEY).getOrThrow()

        assertEquals(PublicPaykitPaymentResult.Opened("public-fallback"), result)
        verifyBlocking(publicPaykitRepo) { beginPayment(CONTACT_KEY) }
    }

    @Test
    fun `beginSavedContactPayment uses public endpoint from unified resolution when private has no endpoints`() = test {
        settingsData.value = SettingsData(sharesPrivatePaykitEndpoints = true)
        sut.prepareSavedContacts(listOf(CONTACT_KEY))
        whenever(
            paykitSdkService.prepareAndResolveContactPayment(CONTACT_KEY, includePublicEndpoints = true),
        ).thenReturn(
            resolution(
                resolvedEndpoint(
                    methodId = MethodId.P2wpkh,
                    value = "bcrt1qpublic",
                    source = PaymentEndpointSource.PUBLIC_PAYMENT_ENDPOINT,
                ),
            ),
        )

        val result = sut.beginSavedContactPayment(CONTACT_KEY).getOrThrow()

        assertEquals(PublicPaykitPaymentResult.Opened("bcrt1qpublic"), result)
        verifyBlocking(publicPaykitRepo, never()) { beginPayment(any()) }
    }

    @Test
    fun `beginSavedContactPayment uses public endpoint while private recovery is pending`() = test {
        settingsData.value = SettingsData(sharesPrivatePaykitEndpoints = true)
        sut.prepareSavedContacts(listOf(CONTACT_KEY))
        whenever(
            paykitSdkService.prepareAndResolveContactPayment(CONTACT_KEY, includePublicEndpoints = true),
        ).thenReturn(
            resolution(
                resolvedEndpoint(
                    methodId = MethodId.P2wpkh,
                    value = "bcrt1qpublic",
                    source = PaymentEndpointSource.PUBLIC_PAYMENT_ENDPOINT,
                ),
                privateState = ContactPaymentResolutionPrivateState.RECOVERY_PENDING,
            ),
        )

        val result = sut.beginSavedContactPayment(CONTACT_KEY).getOrThrow()

        assertEquals(PublicPaykitPaymentResult.Opened("bcrt1qpublic"), result)
        verifyBlocking(publicPaykitRepo, never()) { beginPayment(any()) }
    }

    @Test
    fun `beginSavedContactPayment returns no endpoint when recovery pending has no public endpoint`() = test {
        settingsData.value = SettingsData(sharesPrivatePaykitEndpoints = true)
        sut.prepareSavedContacts(listOf(CONTACT_KEY))
        whenever(
            paykitSdkService.prepareAndResolveContactPayment(CONTACT_KEY, includePublicEndpoints = true),
        ).thenReturn(
            resolution(privateState = ContactPaymentResolutionPrivateState.RECOVERY_PENDING),
        )

        val result = sut.beginSavedContactPayment(CONTACT_KEY).getOrThrow()

        assertEquals(PublicPaykitPaymentResult.NoEndpoint, result)
        verifyBlocking(publicPaykitRepo, never()) { beginPayment(any()) }
    }

    @Test
    fun `beginSavedContactPayment falls back to public when private endpoints are not locally payable`() = test {
        settingsData.value = SettingsData(sharesPrivatePaykitEndpoints = true)
        sut.prepareSavedContacts(listOf(CONTACT_KEY))
        whenever(
            paykitSdkService.prepareAndResolveContactPayment(CONTACT_KEY, includePublicEndpoints = true),
        ).thenReturn(
            resolution(
                resolvedEndpoint(
                    methodId = MethodId.Bolt11,
                    value = PRIVATE_BOLT11,
                ),
                resolvedEndpoint(
                    methodId = MethodId.P2wpkh,
                    value = "bcrt1qpublic",
                    source = PaymentEndpointSource.PUBLIC_PAYMENT_ENDPOINT,
                ),
            ),
        )
        whenever(publicPaykitRepo.payableEndpoints(any())).thenAnswer {
            val endpoints = it.getArgument<List<Endpoint>>(0)
            val hasLightningEndpoint = endpoints.any { endpoint -> endpoint.methodId == MethodId.Bolt11 }
            endpoints.takeUnless { hasLightningEndpoint }.orEmpty()
        }

        val result = sut.beginSavedContactPayment(CONTACT_KEY).getOrThrow()

        assertEquals(PublicPaykitPaymentResult.Opened("bcrt1qpublic"), result)
        verifyBlocking(publicPaykitRepo, never()) { beginPayment(any()) }
    }

    @Test
    fun `beginSavedContactPayment does not fall back to public when private payable check is cancelled`() = test {
        settingsData.value = SettingsData(sharesPrivatePaykitEndpoints = true)
        sut.prepareSavedContacts(listOf(CONTACT_KEY))
        whenever(
            paykitSdkService.prepareAndResolveContactPayment(CONTACT_KEY, includePublicEndpoints = true),
        ).thenReturn(
            resolution(
                resolvedEndpoint(
                    methodId = MethodId.P2wpkh,
                    value = PRIVATE_ADDRESS,
                ),
                resolvedEndpoint(
                    methodId = MethodId.P2wpkh,
                    value = "bcrt1qpublic",
                    source = PaymentEndpointSource.PUBLIC_PAYMENT_ENDPOINT,
                ),
            ),
        )
        whenever(coreService.isAddressUsed(PRIVATE_ADDRESS))
            .thenThrow(CancellationException("cancelled"))

        assertFailsWith<CancellationException> {
            sut.beginSavedContactPayment(CONTACT_KEY)
        }

        verifyBlocking(publicPaykitRepo, never()) { beginPayment(any()) }
    }

    @Test
    fun `beginSavedContactPayment does not fall back to public when private invoice decode is cancelled`() = test {
        settingsData.value = SettingsData(sharesPrivatePaykitEndpoints = true)
        sut.prepareSavedContacts(listOf(CONTACT_KEY))
        whenever(
            paykitSdkService.prepareAndResolveContactPayment(CONTACT_KEY, includePublicEndpoints = true),
        ).thenReturn(
            resolution(
                resolvedEndpoint(
                    methodId = MethodId.Bolt11,
                    value = PRIVATE_BOLT11,
                ),
                resolvedEndpoint(
                    methodId = MethodId.P2wpkh,
                    value = "bcrt1qpublic",
                    source = PaymentEndpointSource.PUBLIC_PAYMENT_ENDPOINT,
                ),
            ),
        )
        whenever(coreService.decode(PRIVATE_BOLT11))
            .thenThrow(CancellationException("cancelled"))

        assertFailsWith<CancellationException> {
            sut.beginSavedContactPayment(CONTACT_KEY)
        }

        verifyBlocking(publicPaykitRepo, never()) { beginPayment(any()) }
    }

    @Test
    fun `backupSnapshot and restoreBackup use SDK backup state`() = test {
        val backup = "sdk-backup"
        whenever(paykitSdkService.exportBackupState()).thenReturn(backup)

        val snapshot = sut.backupSnapshot().getOrThrow()
        sut.restoreBackup(snapshot).getOrThrow()

        assertEquals(backup, snapshot)
        verifyBlocking(paykitSdkService) { restoreBackupState(backup) }
    }

    private fun createSut() = PrivatePaykitRepo(
        ioDispatcher = testDispatcher,
        paykitSdkService = paykitSdkService,
        pubkyService = pubkyService,
        cacheStore = cacheStore,
        settingsStore = settingsStore,
        addressReservationRepo = addressReservationRepo,
        lightningRepo = lightningRepo,
        walletRepo = walletRepo,
        publicPaykitRepo = publicPaykitRepo,
        coreService = coreService,
        clock = clock,
    )

    private fun resolution(
        vararg endpoints: PaykitResolvedPaymentEndpoint,
        privateState: ContactPaymentResolutionPrivateState = ContactPaymentResolutionPrivateState.AVAILABLE,
    ) = PaykitContactPaymentResolution(
        privateState = privateState,
        payableEndpoints = endpoints.toList(),
    )

    private fun resolvedEndpoint(
        methodId: MethodId,
        value: String,
        source: PaymentEndpointSource = PaymentEndpointSource.PRIVATE_PAYMENT_LIST,
    ): PaykitResolvedPaymentEndpoint {
        return PaykitResolvedPaymentEndpoint(
            counterparty = CONTACT_KEY,
            source = source,
            identifier = methodId.rawValue,
            payload = PublicPaykitRepo.serializePayload(value),
        )
    }

    private fun privateListDeliveryReport(
        queuedCounterparties: List<String> = emptyList(),
        clearedCounterparties: List<String> = emptyList(),
        failedToQueue: List<PrivatePaymentListSyncChange> = emptyList(),
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
        failedToDeliver = emptyList(),
    )

    private fun linkedPeer(publicKey: String, state: LinkedPeerState) = LinkedPeerRecord(
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
