package to.bitkit.repositories

import com.synonym.bitkitcore.LightningInvoice
import com.synonym.bitkitcore.NetworkType
import com.synonym.bitkitcore.Scanner
import com.synonym.paykit.LinkedPeerState
import com.synonym.paykit.PaymentAmountContext
import com.synonym.paykit.PrivatePaymentResolutionState
import com.synonym.paykit.PrivatePaymentResolutionStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.data.PrivatePaykitCacheData
import to.bitkit.data.PrivatePaykitCacheStore
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.ext.toHex
import to.bitkit.models.NodeLifecycleState
import to.bitkit.services.CoreService
import to.bitkit.services.PaykitPreparedPrivateContactPayment
import to.bitkit.services.PaykitPrivateContactPaymentResolution
import to.bitkit.services.PaykitReceiverPaths
import to.bitkit.services.PaykitResolvedPaymentEndpoint
import to.bitkit.services.PaykitSdkService
import to.bitkit.services.PubkyService
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Instant

class PrivatePaykitAllowancePaymentTest : BaseUnitTest(StandardTestDispatcher()) {
    companion object {
        private const val CONTACT_KEY = "pubky3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg"
        private const val PRIVATE_ADDRESS = "bcrt1qs04g2ka4pr9s3mv73nu32tvfy7r3cxd27wkyu8"
        private const val PRIVATE_BOLT11 = "lnbcrt1private"
        private const val PRIVATE_LNURL = "lnurl1private"
        private const val NOW_SECONDS = 1_700_000_000L
        private val PAYMENT_HASH = byteArrayOf(9, 9, 9)
    }

    private val paykitSdkService = mock<PaykitSdkService>()
    private val cacheStore = mock<PrivatePaykitCacheStore>()
    private val settingsStore = mock<SettingsStore>()
    private val lightningRepo = mock<LightningRepo>()
    private val publicPaykitRepo = mock<PublicPaykitRepo>()
    private val coreService = mock<CoreService>()
    private val clock = mock<Clock>()
    private lateinit var sut: PrivatePaykitRepo

    @Before
    fun setUp() = test {
        whenever(cacheStore.data).thenReturn(MutableStateFlow(PrivatePaykitCacheData()))
        whenever(settingsStore.data).thenReturn(MutableStateFlow(SettingsData()))
        whenever(lightningRepo.lightningState)
            .thenReturn(MutableStateFlow(LightningState(nodeLifecycleState = NodeLifecycleState.Running)))
        whenever(lightningRepo.getPayments()).thenReturn(Result.success(emptyList()))
        whenever(clock.now()).thenReturn(Instant.fromEpochSeconds(NOW_SECONDS))
        whenever(publicPaykitRepo.payableEndpoints(any())).thenAnswer { it.getArgument<List<Endpoint>>(0) }
        whenever(coreService.isAddressUsed(any())).thenReturn(false)
        PublicPaykitRepo.lightningRouteHintsValidator = { true }

        sut = PrivatePaykitRepo(
            ioDispatcher = testDispatcher,
            paykitSdkService = paykitSdkService,
            pubkyService = mock<PubkyService>(),
            cacheStore = cacheStore,
            settingsStore = settingsStore,
            addressReservationRepo = mock<PrivatePaykitAddressReservationRepo>(),
            lightningRepo = lightningRepo,
            walletRepo = mock<WalletRepo>(),
            publicPaykitRepo = publicPaykitRepo,
            coreService = coreService,
            clock = clock,
        )
    }

    @After
    fun tearDown() {
        PublicPaykitRepo.lightningRouteHintsValidator = null
    }

    @Test
    fun `allowance payment prefers an exact bolt11 invoice among accepted private endpoints`() = test {
        stubResolution(
            resolvedEndpoint(MethodId.P2wpkh, PRIVATE_ADDRESS),
            resolvedEndpoint(MethodId.Bolt11, PRIVATE_BOLT11),
        )
        stubInvoice(amountSats = 2_500uL)
        val request = paymentRequest()

        val payment = sut.resolveAllowancePayment(request, eligible(MethodId.Bolt11, MethodId.P2wpkh)).getOrThrow()

        val invoiceEndpoint = PublicPaykitRepo.parseEndpoint(MethodId.Bolt11.rawValue, payload(PRIVATE_BOLT11))
        assertEquals(
            PrivatePaykitAllowancePayment(
                endpoint = checkNotNull(invoiceEndpoint),
                context = PrivatePaykitPaymentContext(PaykitReceiverPaths.SERVER, 7uL),
                lightningPaymentHash = PAYMENT_HASH.toHex(),
                lightningInvoiceHasAmount = true,
            ),
            payment,
        )
        val amountCaptor = argumentCaptor<PaymentAmountContext>()
        verify(paykitSdkService).prepareAndResolvePrivateContactPayment(
            eq(CONTACT_KEY),
            eq(PaykitReceiverPaths.SERVER),
            eq(null),
            amountCaptor.capture(),
        )
        assertEquals(PaymentAmountContext("0.000025", "btc"), amountCaptor.firstValue)
    }

    @Test
    fun `allowance payment skips an invoice for another amount and falls back to on-chain`() = test {
        stubResolution(
            resolvedEndpoint(MethodId.Bolt11, PRIVATE_BOLT11),
            resolvedEndpoint(MethodId.P2wpkh, PRIVATE_ADDRESS),
        )
        stubInvoice(amountSats = 1_000uL)

        val payment = sut.resolveAllowancePayment(paymentRequest(), eligible(MethodId.Bolt11, MethodId.P2wpkh))
            .getOrThrow()

        assertEquals(MethodId.P2wpkh, payment?.endpoint?.methodId)
        assertEquals(PRIVATE_ADDRESS, payment?.endpoint?.value)
        assertNull(payment?.lightningPaymentHash)
    }

    @Test
    fun `amount-less invoice qualifies for the requested amount`() = test {
        stubResolution(resolvedEndpoint(MethodId.Bolt11, PRIVATE_BOLT11))
        stubInvoice(amountSats = 0uL)

        val payment = sut.resolveAllowancePayment(paymentRequest(), eligible(MethodId.Bolt11)).getOrThrow()

        assertEquals(PRIVATE_BOLT11, payment?.endpoint?.value)
        assertEquals(false, payment?.lightningInvoiceHasAmount)
    }

    @Test
    fun `allowance payment uses only endpoints the allowance and the request both accept`() = test {
        stubResolution(
            resolvedEndpoint(MethodId.Bolt11, PRIVATE_BOLT11),
            resolvedEndpoint(MethodId.P2wpkh, PRIVATE_ADDRESS),
        )
        stubInvoice(amountSats = 2_500uL)
        val request = paymentRequest(accepted = eligible(MethodId.P2wpkh))

        val payment = sut.resolveAllowancePayment(request, eligible(MethodId.Bolt11, MethodId.P2wpkh)).getOrThrow()
        val none = sut.resolveAllowancePayment(request, eligible(MethodId.Bolt11)).getOrThrow()

        assertEquals(MethodId.P2wpkh, payment?.endpoint?.methodId)
        assertNull(none)
    }

    @Test
    fun `lnurl and used addresses never qualify`() = test {
        stubResolution(
            resolvedEndpoint(MethodId.Lnurl, PRIVATE_LNURL),
            resolvedEndpoint(MethodId.P2wpkh, PRIVATE_ADDRESS),
        )
        whenever(coreService.isAddressUsed(PRIVATE_ADDRESS)).thenReturn(true)
        val request = paymentRequest(accepted = eligible(MethodId.Lnurl, MethodId.P2wpkh))

        assertNull(sut.resolveAllowancePayment(request, eligible(MethodId.Lnurl, MethodId.P2wpkh)).getOrThrow())
    }

    @Test
    fun `allowance payment needs a private payment list`() = test {
        stubResolution(resolvedEndpoint(MethodId.P2wpkh, PRIVATE_ADDRESS), version = null)

        assertNull(sut.resolveAllowancePayment(paymentRequest(), eligible(MethodId.P2wpkh)).getOrThrow())
    }

    @Test
    fun `allowance payment waits for a payment list newer than the one already paid`() = test {
        stubResolution(
            resolvedEndpoint(MethodId.Bolt11, PRIVATE_BOLT11),
            version = null,
            status = PrivatePaymentResolutionStatus.WAITING_FOR_UPDATED_PAYMENT_LIST,
        )

        val result = sut.resolveAllowancePayment(paymentRequest(), eligible(MethodId.Bolt11))

        assertEquals(PaykitAllowanceError.PaymentListPending, result.exceptionOrNull())
    }

    @Test
    fun `expired request is never resolved`() = test {
        val expired = paymentRequest().copy(expiresAt = Instant.fromEpochSeconds(NOW_SECONDS - 1))

        assertNull(sut.resolveAllowancePayment(expired, eligible(MethodId.Bolt11)).getOrThrow())
        verify(paykitSdkService, never()).prepareAndResolvePrivateContactPayment(any(), any(), anyOrNull(), anyOrNull())
    }

    private suspend fun stubResolution(
        vararg endpoints: PaykitResolvedPaymentEndpoint,
        version: ULong? = 7uL,
        status: PrivatePaymentResolutionStatus = PrivatePaymentResolutionStatus.PAYABLE,
    ) {
        whenever(paykitSdkService.prepareAndResolvePrivateContactPayment(any(), any(), anyOrNull(), anyOrNull()))
            .thenReturn(
                PaykitPreparedPrivateContactPayment(
                    resolution = PaykitPrivateContactPaymentResolution(
                        status = status,
                        state = PrivatePaymentResolutionState.AVAILABLE,
                        privatePaymentListVersion = version,
                        payableEndpoints = endpoints.toList(),
                    ),
                    linkState = LinkedPeerState.LINKED,
                ),
            )
    }

    private suspend fun stubInvoice(amountSats: ULong) {
        whenever(coreService.decode(PRIVATE_BOLT11)).thenReturn(
            Scanner.Lightning(
                LightningInvoice(
                    bolt11 = PRIVATE_BOLT11,
                    paymentHash = PAYMENT_HASH,
                    amountSatoshis = amountSats,
                    timestampSeconds = NOW_SECONDS.toULong(),
                    expirySeconds = 86_400uL,
                    isExpired = false,
                    description = "",
                    networkType = NetworkType.REGTEST,
                    payeeNodeId = null,
                ),
            ),
        )
    }

    private fun eligible(vararg methods: MethodId) = methods.map { it.rawValue }

    private fun payload(value: String) = PublicPaykitRepo.serializePayload(value)

    private fun resolvedEndpoint(methodId: MethodId, value: String) = PaykitResolvedPaymentEndpoint(
        identifier = methodId.rawValue,
        payload = payload(value),
    )

    private fun paymentRequest(accepted: List<String> = eligible(MethodId.Bolt11, MethodId.P2wpkh)) =
        PaykitPaymentRequest(
            paymentRequestId = "request-id",
            counterparty = CONTACT_KEY,
            counterpartyReceiverPath = PaykitReceiverPaths.SERVER,
            amountValue = "0.000025",
            amountSats = 2_500uL,
            expiresAt = Instant.fromEpochSeconds(NOW_SECONDS + 60),
            acceptedPaymentEndpointIdentifiers = accepted,
        )
}
