package to.bitkit.repositories

import androidx.datastore.core.DataStoreFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import to.bitkit.data.PrivatePaykitCacheData
import to.bitkit.data.PrivatePaykitCacheSerializer
import to.bitkit.data.PrivatePaykitCacheStore
import to.bitkit.data.PrivatePaykitContactCacheData
import to.bitkit.data.PrivatePaykitReservationData
import to.bitkit.data.PrivatePaykitReservationSerializer
import to.bitkit.data.PrivatePaykitReservationStore
import to.bitkit.data.PrivatePaykitStoredAssignmentData
import to.bitkit.data.PrivatePaykitStoredInvoiceData
import to.bitkit.data.ProfileRemovalStore
import to.bitkit.models.PubkyPublicKeyFormat
import to.bitkit.test.BaseUnitTest
import to.bitkit.test.connectionRecoveryFixture
import java.util.UUID
import javax.inject.Provider
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PrivatePaykitContactResolverTest : BaseUnitTest() {
    @get:Rule
    val folder = TemporaryFolder()
    companion object {
        private const val CONTACT_KEY = "pubky3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg"
        private const val PAYMENT_HASH = "010203"
        private const val PRIVATE_ADDRESS = "bcrt1qterdweva9vextackckt6pjy0mmuc54g87g6lsq"
    }

    private val profileRemovalStore = ProfileRemovalStore.inMemoryForTesting()
    private val connectionRecovery = connectionRecoveryFixture(mock())
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
            profileRemovalStore = profileRemovalStore,
            connectionRecovery = Provider { connectionRecovery },
        )
    }

    @Test
    fun `first receipt captures a cold real reservation ledger without a false concurrent change`() = test {
        for (ledger in listOf(
            PrivatePaykitReservationData(),
            PrivatePaykitReservationData(
                contactAssignments = mapOf(
                    CONTACT_KEY to PrivatePaykitStoredAssignmentData("p2wpkh", 3, PRIVATE_ADDRESS)
                )
            ),
        )) {
            val store = mock<PrivatePaykitReservationStore>()
            whenever(store.data).thenReturn(MutableStateFlow(ledger))
            val reservations = PrivatePaykitAddressReservationRepo(testDispatcher, store, mock(), mock(), mock())
            val resolver = PrivatePaykitContactResolver(
                testDispatcher,
                cacheStore,
                Provider { reservations },
                Provider { paymentRequestRepo },
                profileRemovalStore,
                Provider { connectionRecovery },
            )
            resolver.withReceiptOperation { receipt ->
                assertEquals(
                    ledger.contactAssignments.map { it.key to it.value },
                    requireNotNull(receipt.snapshot).assignments
                )
                receipt.requireCurrent()
            }
        }
    }

    @Test
    fun `malformed real reservation storage cannot turn a partial request into a positive receipt`() = test {
        val bytes = "{broken reservation evidence".toByteArray()
        val file = folder.newFile("reservations").also { it.writeBytes(bytes) }
        val storageScope = CoroutineScope(SupervisorJob() + testDispatcher)
        val storage = DataStoreFactory.create(PrivatePaykitReservationSerializer, scope = storageScope) { file }
        val store = mock<PrivatePaykitReservationStore>()
        whenever(store.data).thenReturn(storage.data)
        val reservations = PrivatePaykitAddressReservationRepo(testDispatcher, store, mock(), mock(), mock())
        val resolver = PrivatePaykitContactResolver(
            testDispatcher,
            cacheStore,
            Provider { reservations },
            Provider { paymentRequestRepo },
            profileRemovalStore,
            Provider { connectionRecovery },
        )
        val request = mock<PaykitReceivedPaymentContacts>()
        whenever(request.contactsForPaymentHash(PAYMENT_HASH)).thenReturn(setOf(CONTACT_KEY))
        whenever(paymentRequestRepo.receivedPaymentContacts).thenReturn(request)
        resolver.withReceiptOperation {
            assertNull(it.snapshot)
            assertNull(it.contactForInvoice(PAYMENT_HASH))
        }
        assertFailsWith<IllegalStateException> { storage.updateData { it.copy(version = it.version + 1) } }
        requireNotNull(storageScope.coroutineContext[Job]).cancelAndJoin()
        assertTrue(file.readBytes().contentEquals(bytes))
        val reopened = DataStoreFactory.create(PrivatePaykitReservationSerializer, scope = backgroundScope) { file }
        assertFalse(reopened.data.first().receiptSourceReadable)
    }

    @Test
    fun `malformed real invoice storage remains unavailable to complete receipt capture`() = test {
        val bytes = "{broken invoice evidence".toByteArray()
        val file = folder.newFile("invoices").also { it.writeBytes(bytes) }
        val storageScope = CoroutineScope(SupervisorJob() + testDispatcher)
        val storage = DataStoreFactory.create(PrivatePaykitCacheSerializer, scope = storageScope) { file }
        whenever(cacheStore.data).thenReturn(storage.data)
        whenever(addressReservationRepo.isUnavailableForReusableReceive(PRIVATE_ADDRESS)).thenReturn(true)
        sut.withReceiptOperation {
            assertNull(it.snapshot)
            assertNull(it.contactForInvoice(PAYMENT_HASH))
            assertTrue(it.isReservedReceiveAddress(PRIVATE_ADDRESS))
        }
        assertFailsWith<IllegalStateException> { storage.updateData { it.copy(cleanupPending = true) } }
        requireNotNull(storageScope.coroutineContext[Job]).cancelAndJoin()
        assertTrue(file.readBytes().contentEquals(bytes))
        val reopened = DataStoreFactory.create(PrivatePaykitCacheSerializer, scope = backgroundScope) { file }
        assertFalse(reopened.data.first().receiptSourceReadable)
    }

    @Test
    fun `recovery generation discards a cold invoice lookup across completed local cleanup`() = test {
        val old = PrivatePaykitCacheData(
            contacts = mapOf(
                CONTACT_KEY to PrivatePaykitContactCacheData(receivedInvoicePaymentHashes = listOf(PAYMENT_HASH))
            )
        )
        val ready = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        whenever(cacheStore.data).thenReturn(
            flow {
                ready.complete(Unit)
                release.await()
                emit(old)
            }
        )
        val lookup = async { sut.contactPublicKeyForPrivateInvoicePaymentHash(PAYMENT_HASH) }
        ready.await()
        val id = UUID.randomUUID().toString()
        profileRemovalStore.withAccess(id) {
            profileRemovalStore.begin(id)
            cacheData.value = PrivatePaykitCacheData()
            profileRemovalStore.complete(id)
        }
        release.complete(Unit)
        assertNull(lookup.await())
    }

    @Test
    fun `recovery generation discards a reserved contact lookup across completed local cleanup`() = test {
        val ready = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        whenever(addressReservationRepo.contactPublicKeyForReservedAddress(PRIVATE_ADDRESS)).doSuspendableAnswer {
            ready.complete(Unit)
            release.await()
            CONTACT_KEY
        }
        val lookup = async { sut.contactPublicKeyForReservedAddress(PRIVATE_ADDRESS) }
        ready.await()
        val id = UUID.randomUUID().toString()
        profileRemovalStore.withAccess(id) {
            profileRemovalStore.begin(id)
            profileRemovalStore.complete(id)
        }
        release.complete(Unit)
        assertNull(lookup.await())
    }

    @Test
    fun `pending removal hides invoice and address contacts without erasing reservation state`() = test {
        val shared = mock<PaykitReceivedPaymentContacts>()
        whenever(shared.contactsForPaymentHash(PAYMENT_HASH)).thenReturn(setOf(CONTACT_KEY))
        whenever(shared.contactsForAddresses(listOf(PRIVATE_ADDRESS))).thenReturn(setOf(CONTACT_KEY))
        whenever(paymentRequestRepo.receivedPaymentContacts).thenReturn(shared)
        whenever(addressReservationRepo.contactPublicKeyForReservedAddress(PRIVATE_ADDRESS)).thenReturn(CONTACT_KEY)
        val id = UUID.randomUUID().toString()
        profileRemovalStore.withAccess(id) { profileRemovalStore.begin(id) }
        assertNull(sut.contactPublicKeyForPrivateInvoicePaymentHash(PAYMENT_HASH))
        assertNull(sut.contactPublicKeyForReservedAddress(PRIVATE_ADDRESS))
        assertNull(sut.contactPublicKeyForPrivateOnchainAddresses(PRIVATE_ADDRESS, listOf(PRIVATE_ADDRESS)))
        assertTrue(sut.isReservedReceiveAddress(PRIVATE_ADDRESS))
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
