package to.bitkit.repositories

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import to.bitkit.data.PrivatePaykitCacheData
import to.bitkit.data.PrivatePaykitCacheStore
import to.bitkit.data.PrivatePaykitContactCacheData
import to.bitkit.data.PrivatePaykitStoredInvoiceData
import to.bitkit.models.PubkyPublicKeyFormat
import to.bitkit.test.BaseUnitTest
import javax.inject.Provider
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PrivatePaykitContactResolverTest : BaseUnitTest() {
    companion object {
        private const val CONTACT_KEY = "pubky3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg"
        private const val PAYMENT_HASH = "010203"
        private const val PRIVATE_ADDRESS = "bcrt1qterdweva9vextackckt6pjy0mmuc54g87g6lsq"
    }

    private val cacheStore = mock<PrivatePaykitCacheStore>()
    private val addressReservationRepo = mock<PrivatePaykitAddressReservationRepo>()
    private val paymentRequestRepo = mock<PaykitPaymentRequestRepo>()
    private val cacheData = MutableStateFlow(PrivatePaykitCacheData())

    private lateinit var sut: PrivatePaykitContactResolver

    @Before
    fun setUp() {
        whenever(cacheStore.data).thenReturn(cacheData)
        whenever(paymentRequestRepo.receivedPaymentContacts).thenReturn(PaykitReceivedPaymentContacts.Empty)
        sut = PrivatePaykitContactResolver(
            ioDispatcher = testDispatcher,
            cacheStore = cacheStore,
            addressReservationRepo = Provider { addressReservationRepo },
            paymentRequestRepo = Provider { paymentRequestRepo },
        )
    }

    @Test
    fun `shared request invoice hash resolves without local invoice reservations`() = test {
        val shared = mock<PaykitReceivedPaymentContacts>()
        whenever(shared.contactsForPaymentHash(PAYMENT_HASH)).thenReturn(setOf(CONTACT_KEY))
        whenever(paymentRequestRepo.receivedPaymentContacts).thenReturn(shared)

        assertEquals(
            PubkyPublicKeyFormat.normalized(CONTACT_KEY),
            sut.contactPublicKeyForPrivateInvoicePaymentHash(PAYMENT_HASH)
        )
    }

    @Test
    fun `conflicting local reservation and shared request stay unattributed`() = test {
        val shared = mock<PaykitReceivedPaymentContacts>()
        whenever(shared.contactsForAddresses(listOf(PRIVATE_ADDRESS)))
            .thenReturn(setOf(PaykitReceivedPaymentContactsTest.BUYER))
        whenever(paymentRequestRepo.receivedPaymentContacts).thenReturn(shared)
        whenever(addressReservationRepo.contactPublicKeyForReservedAddress(PRIVATE_ADDRESS)).thenReturn(CONTACT_KEY)

        assertNull(sut.contactPublicKeyForPrivateOnchainAddresses(PRIVATE_ADDRESS, listOf(PRIVATE_ADDRESS)))
    }

    @Test
    fun `identity changes while resolving discard old shared request matches`() = test {
        val shared = mock<PaykitReceivedPaymentContacts>()
        whenever(shared.contactsForAddresses(listOf(PRIVATE_ADDRESS))).thenReturn(setOf(CONTACT_KEY))
        whenever(paymentRequestRepo.receivedPaymentContacts).thenReturn(shared)
        whenever(addressReservationRepo.contactPublicKeyForReservedAddress(PRIVATE_ADDRESS)).thenAnswer {
            whenever(paymentRequestRepo.receivedPaymentContacts).thenReturn(PaykitReceivedPaymentContacts.Empty)
            null
        }

        assertNull(sut.contactPublicKeyForPrivateOnchainAddresses(PRIVATE_ADDRESS, listOf(PRIVATE_ADDRESS)))
    }

    @Test
    fun `contactPublicKeyForPrivateInvoicePaymentHash resolves current local invoice`() = test {
        cacheData.value = PrivatePaykitCacheData(
            contacts = mapOf(
                CONTACT_KEY to PrivatePaykitContactCacheData(
                    localInvoice = PrivatePaykitStoredInvoiceData(
                        bolt11 = "lnbcrt1private",
                        paymentHash = PAYMENT_HASH,
                        expiresAt = 1_700_000_000L,
                    ),
                ),
            ),
        )

        val result = sut.contactPublicKeyForPrivateInvoicePaymentHash(PAYMENT_HASH)

        assertEquals(PubkyPublicKeyFormat.normalized(CONTACT_KEY), result)
    }

    @Test
    fun `contactPublicKeyForPrivateInvoicePaymentHash resolves remembered received invoice`() = test {
        cacheData.value = PrivatePaykitCacheData(
            contacts = mapOf(
                CONTACT_KEY to PrivatePaykitContactCacheData(
                    receivedInvoicePaymentHashes = listOf(PAYMENT_HASH),
                ),
            ),
        )

        val result = sut.contactPublicKeyForPrivateInvoicePaymentHash(PAYMENT_HASH)

        assertEquals(PubkyPublicKeyFormat.normalized(CONTACT_KEY), result)
    }

    @Test
    fun `contactPublicKeyForPrivateInvoicePaymentHash ignores blank hash`() = test {
        val result = sut.contactPublicKeyForPrivateInvoicePaymentHash("")

        assertNull(result)
    }

    @Test
    fun `contactPublicKeyForPrivateOnchainAddresses resolves reserved address`() = test {
        whenever(addressReservationRepo.contactPublicKeyForReservedAddress(PRIVATE_ADDRESS))
            .thenReturn(CONTACT_KEY)

        val result = sut.contactPublicKeyForPrivateOnchainAddresses(PRIVATE_ADDRESS, listOf(PRIVATE_ADDRESS))

        assertEquals(PubkyPublicKeyFormat.normalized(CONTACT_KEY), result)
    }

    @Test
    fun `shared request is not a local address reservation`() = test {
        val shared = mock<PaykitReceivedPaymentContacts>()
        whenever(shared.contactsForAddresses(listOf(PRIVATE_ADDRESS))).thenReturn(setOf(CONTACT_KEY))
        whenever(paymentRequestRepo.receivedPaymentContacts).thenReturn(shared)

        assertNull(sut.contactPublicKeyForReservedAddress(PRIVATE_ADDRESS))
        whenever(addressReservationRepo.contactPublicKeyForReservedAddress(PRIVATE_ADDRESS)).thenReturn(CONTACT_KEY)
        assertEquals(CONTACT_KEY, sut.contactPublicKeyForReservedAddress(PRIVATE_ADDRESS))
    }

    @Test
    fun `unrelated output cannot supply a contact for the receiving address`() = test {
        val outputs = listOf("wallet-address", PRIVATE_ADDRESS)
        whenever(addressReservationRepo.contactPublicKeyForReservedAddress(PRIVATE_ADDRESS)).thenReturn(CONTACT_KEY)

        assertNull(sut.contactPublicKeyForPrivateOnchainAddresses("wallet-address", outputs))
        assertNull(sut.contactPublicKeyForPrivateOnchainAddresses(null, outputs))
        assertNull(sut.contactPublicKeyForPrivateOnchainAddresses(PRIVATE_ADDRESS, listOf("wallet-address")))
    }
}
