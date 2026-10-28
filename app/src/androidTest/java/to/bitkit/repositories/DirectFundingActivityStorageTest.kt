package to.bitkit.repositories

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.ActivityFilter
import com.synonym.bitkitcore.getActivities
import com.synonym.bitkitcore.getActivityById
import com.synonym.bitkitcore.initDb
import com.synonym.bitkitcore.upsertActivity
import org.junit.Test
import org.junit.runner.RunWith
import org.lightningdevkit.ldknode.DirectFundingPaymentDetails
import org.lightningdevkit.ldknode.DirectFundingPaymentStatus
import to.bitkit.test.annotations.CoreServiceIntegration
import to.bitkit.test.annotations.DeviceStorageIntegration
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@CoreServiceIntegration
@DeviceStorageIntegration
class DirectFundingActivityStorageTest {
    @Test
    fun directFundingReceiptsPersistInRealCoreDatabaseAcrossReplayAndReopen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Files.createTempDirectory(context.cacheDir.toPath(), "direct-funding-db-")
        initDb(database.toString())
        val payment = DirectFundingPaymentDetails(
            requestId = "12".repeat(32),
            paymentHash = "34".repeat(32),
            amountSats = 20_000uL,
            feeSats = null,
            fundingTxid = null,
            status = DirectFundingPaymentStatus.SIGNED_PENDING,
            isOutbound = true,
            createdAt = 1_700_000_000_000uL,
        )
        val pending = directFundingActivity(payment, null, 1_700_000_001uL)
        upsertActivity(Activity.Onchain(pending))
        val storedPending = assertNotNull(getActivityById(pending.walletId, pending.id)) as Activity.Onchain
        assertEquals(pending.id, storedPending.v1.id)
        assertEquals(pending.address, storedPending.v1.address)
        assertFalse(storedPending.v1.confirmed)
        assertTrue(pending.address.startsWith("direct-funding:"))

        val confirmedPayment = payment.copy(
            fundingTxid = "56".repeat(32),
            feeSats = 155uL,
            status = DirectFundingPaymentStatus.CONFIRMED,
        )
        val confirmed = directFundingActivity(confirmedPayment, pending, 1_700_000_002uL)
        repeat(2) { upsertActivity(Activity.Onchain(confirmed)) }
        initDb(database.toString())
        val restored = assertNotNull(getActivityById(confirmed.walletId, confirmed.id)) as Activity.Onchain
        assertEquals(20_000uL, restored.v1.value)
        assertEquals(155uL, restored.v1.fee)
        assertEquals(confirmed.address, restored.v1.address)
        assertTrue(restored.v1.confirmed)
        assertFalse(restored.v1.isTransfer)

        val incoming = directFundingActivity(
            confirmedPayment.copy(requestId = "78".repeat(32), isOutbound = false),
            null,
            1_700_000_002uL,
        )
        upsertActivity(Activity.Onchain(incoming))
        assertEquals(0uL, incoming.fee)
        val receipts = getActivities(
            walletId = pending.walletId,
            filter = ActivityFilter.ONCHAIN,
            txType = null,
            tags = null,
            search = null,
            minDate = null,
            maxDate = null,
            limit = null,
            sortDirection = null,
        )
        assertEquals(setOf(pending.id, incoming.id), receipts.map { (it as Activity.Onchain).v1.id }.toSet())
    }
}
