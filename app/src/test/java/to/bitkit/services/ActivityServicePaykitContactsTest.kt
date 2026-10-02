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
import com.synonym.bitkitcore.getTransactionDetails
import com.synonym.bitkitcore.updateActivity
import com.synonym.bitkitcore.upsertActivity
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
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.async.ServiceQueue
import to.bitkit.data.AppCacheData
import to.bitkit.data.CacheStore
import to.bitkit.data.PrivatePaykitCacheData
import to.bitkit.data.PrivatePaykitCacheStore
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.ext.create
import to.bitkit.ext.scopedActivityId
import to.bitkit.repositories.PaykitPaymentRequestRepo
import to.bitkit.repositories.PaykitReceivedPaymentContacts
import to.bitkit.repositories.PaykitReceivedPaymentContactsTest.Companion.BUYER
import to.bitkit.repositories.PaykitReceivedPaymentContactsTest.Companion.OTHER_BUYER
import to.bitkit.repositories.PrivatePaykitAddressReservationRepo
import to.bitkit.repositories.PrivatePaykitContactResolver
import to.bitkit.test.BaseUnitTest
import to.bitkit.utils.AppError
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
    private val privateCacheStore = mock<PrivatePaykitCacheStore>()
    private val cacheStore = mock<CacheStore>()
    private val cacheData = MutableStateFlow(AppCacheData(onchainAddress = "wallet-address"))
    private val settingsStore = mock<SettingsStore>()
    private val lightningService = mock<LightningService>()
    private val coreService = mock<CoreService>()
    private val resolver by lazy {
        PrivatePaykitContactResolver(
            ioDispatcher = testDispatcher,
            cacheStore = privateCacheStore,
            addressReservationRepo = Provider { reservations },
            paymentRequestRepo = Provider { requestRepo },
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
        whenever(contacts.contactsForPaymentHash(original.id)).thenReturn(setOf("buyer"))

        assertTrue(sut.backfillPaykitContacts())

        assertEquals(listOf<Activity>(Activity.Lightning(original.copy(contact = "buyer"))), updates)
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
        whenever(contacts.contactsForPaymentHash(any())).thenReturn(setOf("buyer"))
        whenever(contacts.contactsForAddresses(any())).thenReturn(setOf("buyer"))

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
                if (++cacheReads == 2) {
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
    fun `backfill refuses stale identity snapshot before write`() = coreTest {
        rows = listOf(Activity.Lightning(lightning()))
        whenever(contacts.contactsForPaymentHash(any())).thenAnswer {
            whenever(requestRepo.receivedPaymentContacts).thenReturn(PaykitReceivedPaymentContacts.Empty)
            setOf("buyer")
        }

        assertFalse(sut.backfillPaykitContacts())
        assertTrue(updates.isEmpty())
    }

    @Test
    fun `backfill remains idempotent after persisted activity is reopened`() = coreTest {
        rows = listOf(Activity.Lightning(lightning()))
        whenever(contacts.contactsForPaymentHash(any())).thenReturn(setOf("buyer"))
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
        assertFailsWith<AppError> { sut.backfillPaykitContacts() }

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
    fun `reservation mutation during backfill cannot cache or write stale attribution`() = coreTest {
        rows = listOf(Activity.Onchain(onchain("request-address")))
        val outputs = listOf(output("request-address"))
        details = mock { on { this.outputs }.thenReturn(outputs) }
        sharedAddresses("request-address" to BUYER)
        whenever(reservations.contactPublicKeyForReservedAddress(any())).thenAnswer {
            reservationVersion.value++
            null
        }

        assertFalse(sut.backfillPaykitContacts())
        assertTrue(updates.isEmpty())
        whenever(reservations.contactPublicKeyForReservedAddress(any())).thenReturn(null)
        assertTrue(sut.backfillPaykitContacts())
        assertEquals(2, activityReads)
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
    fun `payment sync uses stored outputs for receiving address attribution`() = coreTest {
        val outputs = listOf(output("wallet-address"), output("unrelated-address"))
        details = mock { on { this.outputs }.thenReturn(outputs) }
        sharedAddresses("wallet-address" to BUYER)

        sut.syncLdkNodePaymentsToActivities(listOf(payment()))

        assertEquals(BUYER, (updates.single() as Activity.Onchain).v1.contact)
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
