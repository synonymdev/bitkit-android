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
    private val cacheData = MutableStateFlow(PrivatePaykitCacheData())

    private lateinit var sut: PrivatePaykitContactResolver

    @Before
    fun setUp() {
        whenever(cacheStore.data).thenReturn(cacheData)
        sut = PrivatePaykitContactResolver(
            ioDispatcher = testDispatcher,
            cacheStore = cacheStore,
            addressReservationRepo = Provider { addressReservationRepo },
        )
    }

    @Test
    fun `contactPublicKeyForPrivateInvoicePaymentHash resolves current local invoice`() = test {
        cacheData.value = PrivatePaykitCacheData(
            contacts = mapOf(
                CONTACT_KEY to PrivatePaykitContactCacheData(
                    localInvoicesByReceiverPath = mapOf(
                        "bitkit/wallet" to PrivatePaykitStoredInvoiceData(
                            bolt11 = "lnbcrt1private",
                            paymentHash = PAYMENT_HASH,
                            expiresAt = 1_700_000_000L,
                        ),
                    ),
                ),
            ),
        )

        val result = sut.contactPublicKeyForPrivateInvoicePaymentHash(PAYMENT_HASH)

        assertEquals(CONTACT_KEY, result)
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

        assertEquals(CONTACT_KEY, result)
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

        val result = sut.contactPublicKeyForPrivateOnchainAddresses(listOf(PRIVATE_ADDRESS))

        assertEquals(CONTACT_KEY, result)
    }
}
