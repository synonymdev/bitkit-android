package to.bitkit.services

import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.AddressType
import com.synonym.bitkitcore.LightningActivity
import com.synonym.bitkitcore.OnchainActivity
import com.synonym.bitkitcore.PaymentState
import com.synonym.bitkitcore.PaymentType
import com.synonym.bitkitcore.TransactionDetails
import com.synonym.bitkitcore.TxOutput
import com.synonym.bitkitcore.getActivities
import com.synonym.bitkitcore.getActivityById
import com.synonym.bitkitcore.getActivityByTxId
import com.synonym.bitkitcore.getTransactionDetails
import com.synonym.bitkitcore.updateActivity
import com.synonym.bitkitcore.upsertActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Test
import org.lightningdevkit.ldknode.ConfirmationStatus
import org.lightningdevkit.ldknode.OnchainWalletAccount
import org.lightningdevkit.ldknode.PaymentDetails
import org.lightningdevkit.ldknode.PaymentDirection
import org.lightningdevkit.ldknode.PaymentKind
import org.lightningdevkit.ldknode.PaymentStatus
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.async.ServiceQueue
import to.bitkit.data.AppCacheData
import to.bitkit.data.CacheStore
import to.bitkit.data.PrivatePaykitCacheData
import to.bitkit.data.PrivatePaykitCacheStore
import to.bitkit.data.PrivatePaykitContactCacheData
import to.bitkit.data.PrivatePaykitReservationData
import to.bitkit.data.PrivatePaykitReservationStore
import to.bitkit.data.PrivatePaykitStoredAssignmentData
import to.bitkit.data.ProfileRemovalStore
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.ext.create
import to.bitkit.ext.scopedActivityId
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.PaykitPaymentRequestRepo
import to.bitkit.repositories.PaykitReceivedPaymentContacts
import to.bitkit.repositories.PaykitReceivedPaymentContactsTest.Companion.BUYER
import to.bitkit.repositories.PaykitReceivedPaymentContactsTest.Companion.OTHER_BUYER
import to.bitkit.repositories.PrivatePaykitAddressReservationRepo
import to.bitkit.repositories.PrivatePaykitAddressReservationRepo.ReceiptAssignments
import to.bitkit.repositories.PrivatePaykitContactResolver
import to.bitkit.test.BaseUnitTest
import to.bitkit.test.connectionRecoveryFixture
import to.bitkit.utils.AppError
import java.io.IOException
import java.util.UUID
import javax.inject.Provider
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.lightningdevkit.ldknode.AddressType as LdkAddressType
import org.lightningdevkit.ldknode.TransactionDetails as LdkTransactionDetails
import org.lightningdevkit.ldknode.TxOutput as LdkTxOutput

class ActivityServicePaykitContactsTest : BaseUnitTest() {
    private val contacts = mock<PaykitReceivedPaymentContacts>()
    private val requestRepo = mock<PaykitPaymentRequestRepo>()
    private val reservations = mock<PrivatePaykitAddressReservationRepo>()
    private var receiptReservations = reservations
    private val privateCacheStore = mock<PrivatePaykitCacheStore>()
    private val profileRemovalStore = ProfileRemovalStore.inMemoryForTesting()
    private var walletBinding = "original-wallet"
    private val connectionRecovery = connectionRecoveryFixture(mock(), currentBinding = { walletBinding })
    private val cacheStore = mock<CacheStore>()
    private val cacheData = MutableStateFlow(AppCacheData(onchainAddress = "wallet-address"))
    private val settingsStore = mock<SettingsStore>()
    private val lightningService = mock<LightningService>()
    private val coreService = mock<CoreService>()
    private val resolver by lazy {
        PrivatePaykitContactResolver(
            ioDispatcher = testDispatcher,
            cacheStore = privateCacheStore,
            addressReservationRepo = Provider { receiptReservations },
            paymentRequestRepo = Provider { requestRepo },
            profileRemovalStore = profileRemovalStore,
            connectionRecovery = Provider { connectionRecovery },
        )
    }
    private val sut by lazy {
        ActivityService(coreService, cacheStore, lightningService, settingsStore, Provider { resolver })
    }
    private var rows = listOf<Activity>()
    private var details: TransactionDetails? = null
    private val updates = mutableListOf<Activity>()
    private val reservationVersion = MutableStateFlow(0L)
    private var activityReads = 0
    private var detailReads = 0

    @Test
    fun `backfill retains its captured contact across completed profile cleanup`() = coreTest {
        rows = listOf(Activity.Onchain(onchain("request-address")))
        val outputs = listOf(output("request-address"))
        details = mock { on { this.outputs }.thenReturn(outputs) }
        sharedAddresses("request-address" to BUYER)
        val id = UUID.randomUUID().toString()
        whenever(reservations.contactPublicKeyForReservedAddress(any())).doSuspendableAnswer {
            profileRemovalStore.withAccess(id) {
                profileRemovalStore.begin(id)
                profileRemovalStore.complete(id)
            }
            null
        }

        assertTrue(sut.backfillPaykitContacts())
        assertEquals(BUYER, (updates.single() as Activity.Onchain).v1.contact)
        rows = listOf(updates.single())
        whenever(reservations.contactPublicKeyForReservedAddress(any())).thenReturn(null)
        assertFalse(sut.backfillPaykitContacts())
        assertEquals(1, activityReads)
    }

    @Test
    fun `backfill matches receiving address in outputs and preserves all other onchain metadata`() = coreTest {
        val original = onchain(address = "request-address")
        rows = listOf(Activity.Onchain(original))
        val outputs = listOf(output("request-address"), output("change-address"))
        details = mock { on { this.outputs }.thenReturn(outputs) }
        sharedAddresses("request-address" to BUYER)

        assertTrue(sut.backfillPaykitContacts())

        assertEquals(listOf<Activity>(Activity.Onchain(original.copy(contact = BUYER))), updates)
        verify(contacts).contactsForAddresses(listOf("request-address", "change-address"))
    }

    @Test
    fun `backfill matches lightning hash when invoice text is missing and preserves metadata`() = coreTest {
        val original = lightning()
        rows = listOf(Activity.Lightning(original))
        whenever(contacts.contactsForPaymentHash(original.id)).thenReturn(setOf(BUYER))

        assertTrue(sut.backfillPaykitContacts())

        assertEquals(listOf<Activity>(Activity.Lightning(original.copy(contact = BUYER))), updates)
    }

    @Test
    fun `backfill skips explicit contacts outgoing and failed received activities`() = coreTest {
        rows = listOf(
            Activity.Onchain(onchain().copy(contact = "explicit")),
            Activity.Onchain(onchain().copy(txType = PaymentType.SENT)),
            Activity.Lightning(lightning().copy(contact = "explicit")),
            Activity.Lightning(lightning().copy(txType = PaymentType.SENT)),
            Activity.Lightning(lightning().copy(status = PaymentState.FAILED)),
        )
        whenever(contacts.contactsForPaymentHash(any())).thenReturn(setOf(BUYER))
        whenever(contacts.contactsForAddresses(any())).thenReturn(setOf(BUYER))

        assertFalse(sut.backfillPaykitContacts())
        assertTrue(updates.isEmpty())
    }

    @Test
    fun `backfill respects a manually detached contact`() = coreTest {
        val original = lightning()
        rows = listOf(Activity.Lightning(original))
        whenever(contacts.contactsForPaymentHash(original.id)).thenReturn(setOf(BUYER))
        cacheData.value = cacheData.value.copy(
            detachedActivityContacts = setOf(scopedActivityId(original.walletId, original.id)),
        )

        assertFalse(sut.backfillPaykitContacts())
        assertTrue(updates.isEmpty())
    }

    @Test
    fun `backfill preserves a manual edit during the final cache read`() = coreTest {
        val original = lightning()
        val edited = Activity.Lightning(original.copy(contact = OTHER_BUYER, message = "manual note"))
        rows = listOf(Activity.Lightning(original))
        whenever(contacts.contactsForPaymentHash(original.id)).thenReturn(setOf(BUYER))
        var cacheReads = 0
        whenever(cacheStore.data).thenReturn(
            flow {
                if (++cacheReads == 1) {
                    sut.update(original.id, edited)
                    rows = listOf(edited)
                }
                emit(cacheData.value)
            },
        )

        assertFalse(sut.backfillPaykitContacts())
        assertEquals(listOf<Activity>(edited), updates)
        assertFalse(sut.backfillPaykitContacts())
        assertEquals(listOf<Activity>(edited), updates)
    }

    @Test
    fun `backfill refuses ambiguity across stored receiving address and other outputs`() = coreTest {
        rows = listOf(Activity.Onchain(onchain(address = "request-address")))
        val outputs = listOf(output("request-address"), output("other-request-address"))
        details = mock { on { this.outputs }.thenReturn(outputs) }
        sharedAddresses("request-address" to BUYER, "other-request-address" to OTHER_BUYER)

        assertFalse(sut.backfillPaykitContacts())
        assertTrue(updates.isEmpty())
    }

    @Test
    fun `backfill waits for complete cached outputs even when stored address matches`() = coreTest {
        rows = listOf(Activity.Onchain(onchain(address = "request-address")))
        sharedAddresses("request-address" to BUYER)

        assertFalse(sut.backfillPaykitContacts())
        details = mock { on { outputs }.thenReturn(emptyList()) }
        assertFalse(sut.backfillPaykitContacts())

        val outputs = listOf(output("other-request-address"))
        details = mock { on { this.outputs }.thenReturn(outputs) }
        assertFalse(sut.backfillPaykitContacts())
        assertTrue(updates.isEmpty())
    }

    @Test
    fun `backfill keeps its immutable received contact after the live profile cache is cleared`() = coreTest {
        rows = listOf(Activity.Lightning(lightning()))
        whenever(contacts.contactsForPaymentHash(any())).thenAnswer {
            whenever(requestRepo.receivedPaymentContacts).thenReturn(PaykitReceivedPaymentContacts.Empty)
            setOf(BUYER)
        }

        assertTrue(sut.backfillPaykitContacts())
        assertEquals(BUYER, (updates.single() as Activity.Lightning).v1.contact)
    }

    @Test
    fun `backfill refuses a replaced wallet before its final write`() = coreTest {
        rows = listOf(Activity.Lightning(lightning()))
        whenever(contacts.contactsForPaymentHash(any())).thenAnswer {
            walletBinding = "replacement-wallet"
            setOf(BUYER)
        }

        assertFailsWith<AppError> { sut.backfillPaykitContacts() }
        assertTrue(updates.isEmpty())
    }

    @Test
    fun `backfill remains idempotent after persisted activity is reopened`() = coreTest {
        rows = listOf(Activity.Lightning(lightning()))
        whenever(contacts.contactsForPaymentHash(any())).thenReturn(setOf(BUYER))
        assertTrue(sut.backfillPaykitContacts())
        rows = updates.toList()
        updates.clear()

        assertFalse(sut.backfillPaykitContacts())
        assertTrue(updates.isEmpty())
    }

    @Test
    fun `backfill rejects a request that only matches another output`() = coreTest {
        for (address in listOf("wallet-address", "unknown")) {
            rows = listOf(Activity.Onchain(onchain(address)))
            val outputs = listOf(output("wallet-address"), output("request-address"))
            details = mock { on { this.outputs }.thenReturn(outputs) }
            sharedAddresses("request-address" to BUYER)

            assertFalse(sut.backfillPaykitContacts())
        }
        assertTrue(updates.isEmpty())
    }

    @Test
    fun `backfill includes local reservations in ambiguity checks`() = coreTest {
        rows = listOf(Activity.Onchain(onchain("request-address")))
        val outputs = listOf(output("request-address"), output("reserved-address"))
        details = mock { on { this.outputs }.thenReturn(outputs) }
        sharedAddresses("request-address" to BUYER)
        whenever(reservations.contactPublicKeyForReservedAddress("reserved-address")).thenReturn(OTHER_BUYER)

        assertFalse(sut.backfillPaykitContacts())
        assertTrue(updates.isEmpty())
    }

    @Test
    fun `completed backfill skips unchanged inputs until new activity arrives`() = coreTest {
        rows = listOf(Activity.Onchain(onchain("wallet-address")))
        val outputs = listOf(output("wallet-address"))
        details = mock { on { this.outputs }.thenReturn(outputs) }

        assertFalse(sut.backfillPaykitContacts())
        assertFalse(sut.backfillPaykitContacts())
        assertEquals(1, activityReads)
        assertEquals(1, detailReads)

        val received = Activity.Lightning(lightning())
        sut.upsert(received)
        rows = listOf(received)
        updates.clear()
        whenever(contacts.contactsForPaymentHash(any())).thenReturn(setOf(BUYER))

        assertTrue(sut.backfillPaykitContacts())
        assertEquals(BUYER, (updates.single() as Activity.Lightning).v1.contact)
        assertEquals(2, activityReads)
    }

    @Test
    fun `reservation changes invalidate an ambiguous cached result`() = coreTest {
        rows = listOf(Activity.Onchain(onchain("request-address")))
        val outputs = listOf(output("request-address"), output("reserved-address"))
        details = mock { on { this.outputs }.thenReturn(outputs) }
        sharedAddresses("request-address" to BUYER)
        whenever(reservations.contactPublicKeyForReservedAddress("reserved-address")).thenReturn(OTHER_BUYER)
        assertFalse(sut.backfillPaykitContacts())
        assertFalse(sut.backfillPaykitContacts())
        assertEquals(1, activityReads)

        whenever(reservations.contactPublicKeyForReservedAddress("reserved-address")).thenReturn(null)
        reservationVersion.value++
        assertTrue(sut.backfillPaykitContacts())
        assertEquals(BUYER, (updates.single() as Activity.Onchain).v1.contact)
        assertEquals(2, activityReads)
    }

    @Test
    fun `incomplete details and failed reservation lookups retry until a scan completes`() = coreTest {
        rows = listOf(Activity.Onchain(onchain("request-address")), Activity.Lightning(lightning()))
        sharedAddresses("request-address" to BUYER)
        assertFalse(sut.backfillPaykitContacts())
        assertFalse(sut.backfillPaykitContacts())
        val outputs = listOf(output("request-address"))
        details = mock { on { this.outputs }.thenReturn(outputs) }
        whenever(reservations.contactPublicKeyForReservedAddress(any()))
            .thenThrow(IllegalStateException("unavailable")).thenReturn(null)
        assertFalse(sut.backfillPaykitContacts())

        assertTrue(sut.backfillPaykitContacts())
        assertFalse(sut.backfillPaykitContacts())
        assertEquals(4, activityReads)
        assertEquals(4, detailReads)
        assertEquals(BUYER, (updates.single() as Activity.Onchain).v1.contact)
    }

    @Test
    fun `updated transaction details invalidate a completed cached scan`() = coreTest {
        rows = listOf(Activity.Onchain(onchain("request-address")))
        val ambiguousOutputs = listOf(output("request-address"), output("other-request-address"))
        details = mock { on { outputs }.thenReturn(ambiguousOutputs) }
        sharedAddresses("request-address" to BUYER, "other-request-address" to OTHER_BUYER)
        assertFalse(sut.backfillPaykitContacts())

        val resolvedOutputs = listOf(output("request-address"))
        details = mock { on { outputs }.thenReturn(resolvedOutputs) }
        sut.handleOnchainTransactionConfirmed("transaction", mock())
        assertTrue(sut.backfillPaykitContacts())
        assertEquals(2, activityReads)
    }

    @Test
    fun `identity generation invalidates equal contact snapshots`() = coreTest {
        rows = listOf(Activity.Lightning(lightning()))
        assertFalse(sut.backfillPaykitContacts())
        whenever(requestRepo.receivedPaymentContactsGeneration).thenReturn(1L)
        whenever(contacts.contactsForPaymentHash(any())).thenReturn(setOf(BUYER))

        assertTrue(sut.backfillPaykitContacts())
        assertEquals(2, activityReads)
    }

    @Test
    fun `reservation mutation during snapshot capture cannot cache or write partial attribution`() = coreTest {
        rows = listOf(Activity.Onchain(onchain("request-address")))
        val outputs = listOf(output("request-address"))
        details = mock { on { this.outputs }.thenReturn(outputs) }
        sharedAddresses("request-address" to BUYER)
        whenever(reservations.receiptAssignments()).doSuspendableAnswer {
            val captured = reservationVersion.value
            reservationVersion.value++
            ReceiptAssignments(emptyList(), captured)
        }

        assertFalse(sut.backfillPaykitContacts())
        assertTrue(updates.isEmpty())
        whenever(
            reservations.receiptAssignments()
        ).thenAnswer { ReceiptAssignments(emptyList(), reservationVersion.value) }
        assertTrue(sut.backfillPaykitContacts())
        assertEquals(1, activityReads)
    }

    @Test
    fun `live received payment rejects a request matching an unrelated output`() = coreTest {
        sharedAddresses("request-address" to BUYER)

        receive("wallet-address", "request-address")

        val row = (updates.single() as Activity.Onchain).v1
        assertEquals("wallet-address", row.address)
        assertNull(row.contact)
    }

    @Test
    fun `live received payment attributes a positively matched wallet destination`() = coreTest {
        sharedAddresses("wallet-address" to BUYER)

        receive("wallet-address", "unrelated-address")

        assertEquals(BUYER, (updates.single() as Activity.Onchain).v1.contact)
    }

    @Test
    fun `live received payment rejects conflicting request outputs`() = coreTest {
        sharedAddresses("wallet-address" to BUYER, "request-address" to OTHER_BUYER)

        receive("wallet-address", "request-address")

        assertNull((updates.single() as Activity.Onchain).v1.contact)
    }

    @Test
    fun `shared request destination alone cannot resolve the receiving address`() = coreTest {
        sharedAddresses("request-address" to BUYER)

        receive("request-address")

        val row = (updates.single() as Activity.Onchain).v1
        assertEquals("Loading...", row.address)
        assertNull(row.contact)
    }

    @Test
    fun `local reservation can resolve and attribute a received destination`() = coreTest {
        whenever(reservations.contactPublicKeyForReservedAddress("reserved-address")).thenReturn(BUYER)

        receive("reserved-address")

        val row = (updates.single() as Activity.Onchain).v1
        assertEquals("reserved-address", row.address)
        assertEquals(BUYER, row.contact)
    }

    @Test
    fun `reservation derivation failure preserves receipt accounting and retries contact backfill`() = coreTest {
        val address = "bcrt1qterdweva9vextackckt6pjy0mmuc54g87g6lsq"
        cacheData.value = cacheData.value.copy(onchainAddress = address)
        val ledger = flowOf(
            PrivatePaykitReservationData(
                contactAssignments = mapOf(BUYER to PrivatePaykitStoredAssignmentData("nativeSegwit", 9000, "")),
            )
        )
        val store = mock<PrivatePaykitReservationStore>()
        whenever(store.data).thenReturn(ledger)
        val lightning = mock<LightningRepo>()
        receiptReservations = PrivatePaykitAddressReservationRepo(
            testDispatcher, store, settingsStore, coreService, lightning,
        )
        whenever(
            lightning.addressInfoForType(any(), any())
        ).thenReturn(Result.failure(IOException("derivation unavailable")))
        val outputs = listOf(output(address))
        details = mock { on { this.outputs }.thenReturn(outputs) }

        sut.syncLdkNodePaymentsToActivities({ listOf(payment()) })

        val stored = (updates.single() as Activity.Onchain).v1
        assertEquals(15_000uL, stored.value)
        assertFalse(stored.confirmed)
        assertEquals(address, stored.address)
        assertNull(stored.contact)
        verify(lightning).addressInfoForType(AddressType.P2WPKH, 9000)
        rows = updates.toList()
        updates.clear()
        assertFalse(sut.backfillPaykitContacts())
        assertTrue(updates.isEmpty())
        whenever(lightning.addressInfoForType(any(), any()))
            .thenReturn(Result.success(AddressDerivationInfo(address, 9000)))
        assertTrue(sut.backfillPaykitContacts())
        assertEquals(BUYER, (updates.single() as Activity.Onchain).v1.contact)
        assertEquals(2, activityReads)
    }

    @Test
    fun `unavailable invoice attribution still records a high reserved receiving address`() = coreTest {
        val address = "bcrt1qterdweva9vextackckt6pjy0mmuc54g87g6lsq"
        val store = mock<PrivatePaykitReservationStore>()
        whenever(store.data).thenReturn(
            flowOf(
                PrivatePaykitReservationData(reservedReceiveIndexesByAddressType = mapOf("nativeSegwit" to setOf(9000)))
            ),
        )
        val derivation = mock<LightningRepo>()
        whenever(derivation.addressInfoForType(addressType = AddressType.P2WPKH, receiveIndex = 9000)).thenReturn(
            Result.success(AddressDerivationInfo(address, 9000)),
        )
        receiptReservations = PrivatePaykitAddressReservationRepo(
            testDispatcher, store, settingsStore, coreService, derivation,
        )
        whenever(privateCacheStore.data).thenReturn(flowOf(PrivatePaykitCacheData(receiptSourceReadable = false)))
        whenever(lightningService.addressInfosForType(any(), any(), any(), any(), any())).thenReturn(emptyList())

        receive(address)

        val row = (updates.single() as Activity.Onchain).v1
        assertEquals(address, row.address)
        assertEquals(15_000uL, row.value)
        assertNull(row.contact)
        assertFalse(sut.backfillPaykitContacts())
    }

    @Test
    fun `held incoming search uses its captured high index reservation after profile cleanup`() = coreTest {
        val address = "bcrt1qterdweva9vextackckt6pjy0mmuc54g87g6lsq"
        val ledger = MutableStateFlow(
            PrivatePaykitReservationData(
                contactAssignments = mapOf(BUYER to PrivatePaykitStoredAssignmentData("nativeSegwit", 9000, address)),
            )
        )
        val store = mock<PrivatePaykitReservationStore>()
        whenever(store.data).thenReturn(ledger)
        whenever(store.update(any())).thenAnswer {
            val transform: (PrivatePaykitReservationData) -> PrivatePaykitReservationData = it.getArgument(0)
            ledger.value = transform(ledger.value)
        }
        receiptReservations = PrivatePaykitAddressReservationRepo(
            testDispatcher, store, settingsStore, coreService, mock(),
        )
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        whenever(lightningService.addressInfosForType(any(), any(), any(), any(), any())).doSuspendableAnswer {
            entered.complete(Unit)
            release.await()
            emptyList()
        }
        coroutineScope {
            val incoming = async { receive(address) }
            entered.await()
            val id = UUID.randomUUID().toString()
            profileRemovalStore.withAccess(id) {
                profileRemovalStore.begin(id)
                profileRemovalStore.withReceiptCleanup { receiptReservations.clearContactAssignments(emptySet()) }
                profileRemovalStore.complete(id)
            }
            assertTrue(ledger.value.contactAssignments.isEmpty())
            release.complete(Unit)
            incoming.await()
        }
        val row = (updates.single() as Activity.Onchain).v1
        assertEquals(address, row.address)
        assertEquals(BUYER, row.contact)
        assertEquals(15_000uL, row.value)
    }

    @Test
    fun `registered companion account resolves request destination with scoped search indexes`() = coreTest {
        sharedAddresses("server-address" to BUYER)
        cacheData.value = cacheData.value.copy(
            addressSearchLastUsedReceiveIndexes = mapOf("nativeSegwit" to 600),
        )
        whenever(lightningService.listOnchainWalletAccounts()).thenReturn(
            listOf(OnchainWalletAccount(LdkAddressType.NATIVE_SEGWIT, 5u)),
        )
        whenever(lightningService.addressInfosForType(AddressType.P2WPKH, false, 200, 200, 5u))
            .thenReturn(listOf(AddressDerivationInfo("server-address", 203)))

        receive("change-address", "server-address")

        val row = (updates.single() as Activity.Onchain).v1
        assertEquals("server-address", row.address)
        assertEquals(BUYER, row.contact)
        assertEquals(
            mapOf("nativeSegwit" to 600, "nativeSegwit:account:5" to 203),
            cacheData.value.addressSearchLastUsedReceiveIndexes,
        )
    }

    @Test
    fun `companion account search advances from the matched index through the next window`() = coreTest {
        whenever(lightningService.listOnchainWalletAccounts()).thenReturn(
            listOf(OnchainWalletAccount(LdkAddressType.NATIVE_SEGWIT, 5u)),
        )
        for (index in listOf(999, 1999)) {
            val address = "server-address-$index"
            sharedAddresses(address to BUYER)
            whenever(lightningService.addressInfosForType(AddressType.P2WPKH, false, index - 199, 200, 5u))
                .thenReturn(listOf(AddressDerivationInfo(address, index)))

            receive("change-address", address)

            val row = (updates.last() as Activity.Onchain).v1
            assertEquals(address, row.address)
            assertEquals(BUYER, row.contact)
            assertEquals(index, cacheData.value.addressSearchLastUsedReceiveIndexes["nativeSegwit:account:5"])
        }
    }

    @Test
    fun `companion account search keeps its own bounded receive and change windows`() = coreTest {
        cacheData.value = cacheData.value.copy(
            addressSearchLastUsedReceiveIndexes = mapOf("nativeSegwit" to 600),
            addressSearchLastUsedChangeIndexes = mapOf("nativeSegwit:account:5" to 200),
        )
        whenever(lightningService.listOnchainWalletAccounts()).thenReturn(
            listOf(OnchainWalletAccount(LdkAddressType.NATIVE_SEGWIT, 5u)),
        )

        receive("unowned-address")

        verify(lightningService).addressInfosForType(AddressType.P2WPKH, false, 800, 200, 5u)
        verify(lightningService, never()).addressInfosForType(AddressType.P2WPKH, false, 1000, 200, 5u)
        verify(lightningService).addressInfosForType(AddressType.P2WPKH, true, 1000, 200, 5u)
        verify(lightningService).addressInfosForType(AddressType.P2WPKH, true, 1200, 1, 5u)
        verify(lightningService, never()).addressInfosForType(AddressType.P2WPKH, true, 1400, 200, 5u)
    }

    @Test
    fun `received private invoice keeps its historical contact during pending profile cleanup`() = coreTest {
        val cached = MutableStateFlow(
            PrivatePaykitCacheData(
                contacts = mapOf(
                    BUYER to PrivatePaykitContactCacheData(receivedInvoicePaymentHashes = listOf("received"))
                )
            )
        )
        whenever(privateCacheStore.data).thenReturn(cached)
        val id = UUID.randomUUID().toString()
        profileRemovalStore.withAccess(id) { profileRemovalStore.begin(id) }
        val received = payment().copy(
            kind = PaymentKind.Bolt11(
                hash = "received",
                preimage = "preimage",
                secret = null,
                bolt11 = null,
                description = "receipt",
            )
        )

        sut.syncLdkNodePaymentsToActivities({ listOf(received) })

        val first = updates.single() as Activity.Lightning
        assertEquals(15_000uL, first.v1.value)
        assertEquals(BUYER, first.v1.contact)
        rows = listOf(first)
        cached.value = PrivatePaykitCacheData()
        profileRemovalStore.withAccess(id) { profileRemovalStore.complete(id) }
        sut.syncLdkNodePaymentsToActivities({ listOf(received) })
        assertEquals(BUYER, (updates.last() as Activity.Lightning).v1.contact)
    }

    @Test
    fun `profile cleanup preserves accounting without claiming complete contacts`() = coreTest {
        verifyAccountingWithUnavailableAssociations(cleanupPending = true)
    }

    @Test
    fun `unreadable invoice cache preserves accounting and a later valid source can backfill`() = coreTest {
        verifyAccountingWithUnavailableAssociations(cleanupPending = false)
    }

    private suspend fun verifyAccountingWithUnavailableAssociations(cleanupPending: Boolean) {
        whenever(contacts.contactsForPaymentHash(any())).thenReturn(setOf(BUYER))
        val id = UUID.randomUUID().toString()
        if (cleanupPending) {
            profileRemovalStore.withAccess(id) {
                profileRemovalStore.begin(id)
                profileRemovalStore.beginLocalCleanup()
            }
        } else {
            whenever(privateCacheStore.data).thenReturn(flowOf(PrivatePaykitCacheData(receiptSourceReadable = false)))
        }
        val inbound = payment().copy(kind = PaymentKind.Bolt11("received", "preimage", null, null, "receipt"))
        val outbound = inbound.copy(id = "sent", direction = PaymentDirection.OUTBOUND)
        sut.syncLdkNodePaymentsToActivities({ listOf(inbound, outbound) })
        assertEquals(2, updates.size)
        val stored = updates.filterIsInstance<Activity.Lightning>()
        assertTrue(
            stored.all { it.v1.value == 15_000uL && it.v1.status == PaymentState.SUCCEEDED && it.v1.contact == null }
        )
        assertEquals(setOf(PaymentType.RECEIVED, PaymentType.SENT), stored.map { it.v1.txType }.toSet())
        rows = updates.toList()
        updates.clear()
        assertFalse(sut.backfillPaykitContacts())
        assertTrue(updates.isEmpty())
        assertEquals(0, activityReads)
        if (cleanupPending) profileRemovalStore.withAccess(id) { profileRemovalStore.complete(id) }
        whenever(privateCacheStore.data).thenReturn(flowOf(PrivatePaykitCacheData()))
        assertTrue(sut.backfillPaykitContacts())
        assertEquals(BUYER, (updates.single() as Activity.Lightning).v1.contact)
        assertEquals(1, activityReads)
    }

    @Test
    fun `received reserved address keeps its historical contact during pending profile cleanup`() = coreTest {
        val outputs = listOf(output("wallet-address"))
        details = mock { on { this.outputs }.thenReturn(outputs) }
        whenever(reservations.contactPublicKeyForReservedAddress("wallet-address")).thenReturn(BUYER)
        val id = UUID.randomUUID().toString()
        profileRemovalStore.withAccess(id) { profileRemovalStore.begin(id) }

        sut.syncLdkNodePaymentsToActivities({ listOf(payment()) })

        val first = updates.single() as Activity.Onchain
        assertEquals(15_000uL, first.v1.value)
        assertEquals(BUYER, first.v1.contact)
        rows = listOf(first)
        whenever(reservations.contactPublicKeyForReservedAddress("wallet-address")).thenReturn(null)
        profileRemovalStore.withAccess(id) { profileRemovalStore.complete(id) }
        sut.syncLdkNodePaymentsToActivities({ listOf(payment()) })
        assertEquals(BUYER, (updates.last() as Activity.Onchain).v1.contact)
    }

    @Test
    fun `payment sync uses stored outputs for receiving address attribution`() = coreTest {
        val outputs = listOf(output("wallet-address"), output("unrelated-address"))
        details = mock { on { this.outputs }.thenReturn(outputs) }
        sharedAddresses("wallet-address" to BUYER)

        sut.syncLdkNodePaymentsToActivities({ listOf(payment()) })

        assertEquals(BUYER, (updates.single() as Activity.Onchain).v1.contact)
    }

    @Test
    fun `payment sync skips contact discovery for an attributed activity`() = coreTest {
        val original = onchain("wallet-address").copy(id = "stored-id", contact = BUYER)
        rows = listOf(Activity.Onchain(original))
        val outputs = listOf(output("wallet-address"))
        details = mock { on { this.outputs }.thenReturn(outputs) }

        repeat(2) { sut.syncLdkNodePaymentsToActivities({ listOf(payment()) }) }

        assertEquals(2, updates.size)
        assertTrue(updates.all { (it as Activity.Onchain).v1.contact == BUYER })
        verify(reservations, never()).contactPublicKeyForReservedAddress(any())
        verify(contacts, never()).contactsForAddresses(any())
    }

    @Test
    fun `payment sync skips contact discovery for a manually detached activity`() = coreTest {
        val original = onchain("wallet-address").copy(id = "stored-id")
        rows = listOf(Activity.Onchain(original))
        cacheData.value = cacheData.value.copy(
            detachedActivityContacts = setOf(scopedActivityId(original.walletId, original.id)),
        )
        val outputs = listOf(output("wallet-address"))
        details = mock { on { this.outputs }.thenReturn(outputs) }
        sharedAddresses("wallet-address" to BUYER)

        repeat(2) { sut.syncLdkNodePaymentsToActivities({ listOf(payment()) }) }

        assertEquals(2, updates.size)
        assertTrue(updates.all { (it as Activity.Onchain).v1.contact == null })
        verify(reservations, never()).contactPublicKeyForReservedAddress(any())
        verify(contacts, never()).contactsForAddresses(any())
    }

    @Test
    fun `repeated incoming payment sync does not fetch missing node transaction details`() = coreTest {
        val txid = "ab".repeat(32)
        val incoming = payment().copy(kind = PaymentKind.Onchain(txid, ConfirmationStatus.Unconfirmed))
        whenever(lightningService.getTransactionDetails(txid, 0)).thenReturn(
            LdkTransactionDetails(15_000, emptyList(), listOf(LdkTxOutput("", "", "request-address", 15_000, 0u))),
        )

        repeat(2) {
            sut.syncLdkNodePaymentsToActivities({ listOf(incoming) })
            rows = listOf(updates.last())
        }

        assertEquals(txid, (rows.single() as Activity.Onchain).v1.txId)
        verify(lightningService, never()).getTransactionDetails(any(), any())
        verify(lightningService, never()).addressInfosForType(any(), any(), any(), any(), any())
    }

    private suspend fun receive(vararg addresses: String) {
        whenever(lightningService.listPayments()).thenReturn(listOf(payment()))
        sut.handleOnchainTransactionReceived(
            "transaction",
            LdkTransactionDetails(
                amountSats = 15_000,
                inputs = emptyList(),
                outputs = addresses.mapIndexed { index, address ->
                    LdkTxOutput("", "", address, 15_000, index.toUInt())
                },
            ),
        )
    }

    private fun payment() = PaymentDetails(
        id = "received",
        kind = PaymentKind.Onchain("transaction", ConfirmationStatus.Unconfirmed),
        amountMsat = 15_000_000uL,
        feePaidMsat = 0uL,
        direction = PaymentDirection.INBOUND,
        status = PaymentStatus.SUCCEEDED,
        latestUpdateTimestamp = 100uL,
    )

    private fun coreTest(block: suspend () -> Unit) = test {
        whenever(requestRepo.receivedPaymentContacts).thenReturn(contacts)
        whenever(reservations.attributionVersion).thenAnswer { reservationVersion.value }
        whenever(
            reservations.receiptAssignments()
        ).thenAnswer { ReceiptAssignments(emptyList(), reservationVersion.value) }
        whenever(reservations.contactsForReceipt(any(), any())).doSuspendableAnswer {
            listOfNotNull(reservations.contactPublicKeyForReservedAddress(it.getArgument(0)))
        }
        whenever(privateCacheStore.data).thenReturn(flowOf(PrivatePaykitCacheData()))
        whenever(cacheStore.data).thenReturn(cacheData)
        whenever(cacheStore.update(any())).thenAnswer {
            cacheData.value = it.getArgument<(AppCacheData) -> AppCacheData>(0)(cacheData.value)
        }
        whenever(settingsStore.data).thenReturn(flowOf(SettingsData()))
        whenever(coreService.activity).thenReturn(mock())
        whenever(lightningService.listOnchainWalletAccounts()).thenReturn(emptyList())
        whenever(lightningService.addressInfosForType(any(), any(), any(), any(), any())).thenReturn(emptyList())
        ServiceQueue.CORE.background {
            mockStatic(Class.forName("com.synonym.bitkitcore.Bitkitcore_androidKt")).use { native ->
                native.`when`<List<Activity>> {
                    getActivities(
                        anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(),
                        anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull()
                    )
                }.thenAnswer {
                    activityReads++
                    rows
                }
                native.`when`<Activity?> { getActivityById(any(), any()) }.thenAnswer { invocation ->
                    rows.firstOrNull {
                        when (it) {
                            is Activity.Onchain -> it.v1.walletId == invocation.arguments[0] &&
                                it.v1.id == invocation.arguments[1]
                            is Activity.Lightning -> it.v1.walletId == invocation.arguments[0] &&
                                it.v1.id == invocation.arguments[1]
                        }
                    }
                }
                native.`when`<OnchainActivity?> { getActivityByTxId(any(), any()) }.thenAnswer { invocation ->
                    rows.filterIsInstance<Activity.Onchain>().firstOrNull {
                        it.v1.walletId == invocation.arguments[0] && it.v1.txId == invocation.arguments[1]
                    }?.v1
                }
                native.`when`<TransactionDetails?> { getTransactionDetails(any(), any()) }.thenAnswer {
                    detailReads++
                    details
                }
                native.`when`<Unit> { updateActivity(any(), any()) }.thenAnswer {
                    updates.add(it.arguments[1] as Activity)
                    null
                }
                native.`when`<Unit> { upsertActivity(any()) }.thenAnswer {
                    updates.add(it.arguments[0] as Activity)
                    null
                }
                block()
            }
        }
    }

    private fun sharedAddresses(vararg values: Pair<String, String>) {
        val byAddress = values.toMap()
        whenever(contacts.contactsForAddresses(any())).thenAnswer {
            it.getArgument<Collection<String>>(0).mapNotNull(byAddress::get).toSet()
        }
    }

    private fun output(address: String): TxOutput = mock { on { scriptpubkeyAddress }.thenReturn(address) }

    private fun onchain(address: String = "address") = OnchainActivity.create(
        id = "received", txId = "transaction", txType = PaymentType.RECEIVED, address = address,
        value = 15_000uL, fee = 1uL, timestamp = 100uL, confirmed = true, seenAt = 101uL,
    )

    private fun lightning() = LightningActivity.create(
        id = "payment-hash", txType = PaymentType.RECEIVED, status = PaymentState.SUCCEEDED,
        value = 15_000uL, invoice = "No invoice", timestamp = 100uL, fee = 1uL, message = "existing note",
        preimage = "preimage", seenAt = 101uL,
    )
}
