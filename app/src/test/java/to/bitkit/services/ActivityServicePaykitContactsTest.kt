package to.bitkit.services

import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.LightningActivity
import com.synonym.bitkitcore.OnchainActivity
import com.synonym.bitkitcore.PaymentState
import com.synonym.bitkitcore.PaymentType
import com.synonym.bitkitcore.TransactionDetails
import com.synonym.bitkitcore.TxOutput
import com.synonym.bitkitcore.getActivities
import com.synonym.bitkitcore.getTransactionDetails
import com.synonym.bitkitcore.updateActivity
import org.junit.Test
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.async.ServiceQueue
import to.bitkit.ext.create
import to.bitkit.repositories.PaykitReceivedPaymentContacts
import to.bitkit.repositories.PrivatePaykitContactResolver
import to.bitkit.test.BaseUnitTest
import javax.inject.Provider
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ActivityServicePaykitContactsTest : BaseUnitTest() {
    private val contacts = mock<PaykitReceivedPaymentContacts>()
    private val resolver = mock<PrivatePaykitContactResolver>()
    private val sut by lazy { ActivityService(mock(), mock(), mock(), mock(), Provider { resolver }) }
    private var rows = listOf<Activity>()
    private var details: TransactionDetails? = null
    private val updates = mutableListOf<Activity>()

    @Test
    fun `backfill matches cached outputs and preserves all other onchain metadata`() = coreTest {
        val original = onchain(address = "unknown")
        rows = listOf(Activity.Onchain(original))
        val outputs = listOf(output("request-address"), output("change-address"))
        details = mock { on { this.outputs }.thenReturn(outputs) }
        whenever(contacts.contactsForAddresses(listOf("unknown", "request-address", "change-address")))
            .thenReturn(setOf("buyer"))

        assertTrue(sut.backfillPaykitContacts())

        assertEquals(listOf<Activity>(Activity.Onchain(original.copy(contact = "buyer"))), updates)
        verify(contacts).contactsForAddresses(listOf("unknown", "request-address", "change-address"))
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
    fun `backfill refuses ambiguity across stored receiving address and other outputs`() = coreTest {
        rows = listOf(Activity.Onchain(onchain(address = "request-address")))
        val outputs = listOf(output("other-request-address"))
        details = mock { on { this.outputs }.thenReturn(outputs) }
        whenever(contacts.contactsForAddresses(listOf("request-address", "other-request-address")))
            .thenReturn(setOf("buyer", "other-buyer"))

        assertFalse(sut.backfillPaykitContacts())
        assertTrue(updates.isEmpty())
    }

    @Test
    fun `backfill waits for complete cached outputs even when stored address matches`() = coreTest {
        rows = listOf(Activity.Onchain(onchain(address = "request-address")))
        whenever(contacts.contactsForAddresses(listOf("request-address"))).thenReturn(setOf("buyer"))

        assertFalse(sut.backfillPaykitContacts())
        details = mock { on { outputs }.thenReturn(emptyList()) }
        assertFalse(sut.backfillPaykitContacts())

        val outputs = listOf(output("other-request-address"))
        details = mock { on { this.outputs }.thenReturn(outputs) }
        whenever(contacts.contactsForAddresses(listOf("request-address", "other-request-address")))
            .thenReturn(setOf("buyer", "other-buyer"))
        assertFalse(sut.backfillPaykitContacts())
        assertTrue(updates.isEmpty())
    }

    @Test
    fun `backfill refuses stale identity snapshot before write`() = coreTest {
        rows = listOf(Activity.Lightning(lightning()))
        whenever(contacts.contactsForPaymentHash(any())).thenAnswer {
            whenever(resolver.receivedPaymentContacts).thenReturn(PaykitReceivedPaymentContacts.Empty)
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

    private fun coreTest(block: suspend () -> Unit) = test {
        whenever(resolver.receivedPaymentContacts).thenReturn(contacts)
        ServiceQueue.CORE.background {
            mockStatic(Class.forName("com.synonym.bitkitcore.Bitkitcore_androidKt")).use { native ->
                native.`when`<List<Activity>> {
                    getActivities(
                        anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(),
                        anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull()
                    )
                }.thenAnswer { rows }
                native.`when`<TransactionDetails?> { getTransactionDetails(any(), any()) }.thenAnswer { details }
                native.`when`<Unit> { updateActivity(any(), any()) }.thenAnswer {
                    updates.add(it.arguments[1] as Activity)
                    null
                }
                block()
            }
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
