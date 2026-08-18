package to.bitkit.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import to.bitkit.ext.scopedActivityId
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Config(application = Application::class, sdk = [34])
@RunWith(RobolectricTestRunner::class)
class CacheStoreTest : BaseUnitTest() {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val sut = CacheStore(context)

    @Before
    fun setUp() = runBlocking { sut.reset() }

    @After
    fun tearDown() = runBlocking { sut.reset() }

    @Test
    fun `invalidateReceiveLightningInvoice preserves a replacement invoice`() = test {
        val replacement = AppCacheData(
            onchainAddress = "bc1qtest",
            bolt11 = "replacementInvoice",
            bolt11PaymentHash = "replacementHash",
            bip21 = "bitcoin:bc1qtest?lightning=replacementInvoice",
        )
        sut.update { replacement }

        val invalidated = sut.invalidateReceiveLightningInvoice(expectedBolt11 = "settledInvoice")

        assertFalse(invalidated)
        assertEquals(replacement, sut.data.first())
    }

    @Test
    fun `invalidateReceiveOnchainAddress clears the matching address`() = test {
        sut.update {
            AppCacheData(
                onchainAddress = "settledAddress",
                bolt11 = "activeInvoice",
                bolt11PaymentHash = "activeHash",
                bip21 = "bitcoin:settledAddress?lightning=activeInvoice",
            )
        }

        val invalidated = sut.invalidateReceiveOnchainAddress(expectedAddress = "settledAddress")
        val data = sut.data.first()

        assertTrue(invalidated)
        assertEquals("", data.onchainAddress)
        assertEquals("", data.bip21)
        assertEquals("activeInvoice", data.bolt11)
        assertEquals("activeHash", data.bolt11PaymentHash)
    }

    @Test
    fun `invalidateReceiveOnchainAddress preserves a replacement address`() = test {
        val replacement = AppCacheData(
            onchainAddress = "replacementAddress",
            bolt11 = "activeInvoice",
            bolt11PaymentHash = "activeHash",
            bip21 = "bitcoin:replacementAddress?lightning=activeInvoice",
        )
        sut.update { replacement }

        val invalidated = sut.invalidateReceiveOnchainAddress(expectedAddress = "settledAddress")

        assertFalse(invalidated)
        assertEquals(replacement, sut.data.first())
    }

    @Test
    fun `addActivityToDeletedList persists a wallet scoped id`() = test {
        sut.addActivityToDeletedList("shared-id", "hardware-wallet")

        assertEquals(
            listOf(scopedActivityId("hardware-wallet", "shared-id")),
            sut.data.first().deletedActivities,
        )
    }

    @Test
    fun `quickPaySpentSatsForDay returns spend for matching day key`() = test {
        sut.recordQuickPaySpendSats(amountSats = 3500L, dayKey = "2026-08-15")

        assertEquals(3500L, sut.quickPaySpentSatsForDay("2026-08-15"))
    }

    @Test
    fun `quickPaySpentSatsForDay returns zero for a later day key`() = test {
        sut.recordQuickPaySpendSats(amountSats = 12_000L, dayKey = "2026-08-14")

        assertEquals(0L, sut.quickPaySpentSatsForDay("2026-08-15"))
    }

    @Test
    fun `quickPaySpentSatsForDay keeps spend on clock rollback`() = test {
        sut.recordQuickPaySpendSats(amountSats = 12_000L, dayKey = "2026-08-15")

        assertEquals(12_000L, sut.quickPaySpentSatsForDay("2026-08-14"))
        assertTrue(sut.tryReserveQuickPaySpendSats(amountSats = 1_000L, dayKey = "2026-08-14", dailyCapSats = 20_000L))
        assertEquals(13_000L, sut.quickPaySpentSatsForDay("2026-08-14"))
        assertEquals(13_000L, sut.quickPaySpentSatsForDay("2026-08-15"))
    }

    @Test
    fun `recordQuickPaySpendSats accumulates on the same day and resets on a new day`() = test {
        sut.recordQuickPaySpendSats(amountSats = 2_000L, dayKey = "2026-08-15")
        sut.recordQuickPaySpendSats(amountSats = 1_500L, dayKey = "2026-08-15")
        assertEquals(3_500L, sut.quickPaySpentSatsForDay("2026-08-15"))

        sut.recordQuickPaySpendSats(amountSats = 4_000L, dayKey = "2026-08-16")
        assertEquals(4_000L, sut.quickPaySpentSatsForDay("2026-08-16"))
        assertEquals(4_000L, sut.quickPaySpentSatsForDay("2026-08-15"))
    }

    @Test
    fun `tryReserveQuickPaySpendSats reserves under the cap and rejects over it`() = test {
        assertTrue(sut.tryReserveQuickPaySpendSats(amountSats = 10_000L, dayKey = "2026-08-15", dailyCapSats = 25_000L))
        assertTrue(sut.tryReserveQuickPaySpendSats(amountSats = 10_000L, dayKey = "2026-08-15", dailyCapSats = 25_000L))
        assertFalse(sut.tryReserveQuickPaySpendSats(amountSats = 10_000L, dayKey = "2026-08-15", dailyCapSats = 25_000L))
        assertEquals(20_000L, sut.quickPaySpentSatsForDay("2026-08-15"))
    }

    @Test
    fun `releaseQuickPaySpendSats rolls back a reservation`() = test {
        assertTrue(sut.tryReserveQuickPaySpendSats(amountSats = 5_000L, dayKey = "2026-08-15", dailyCapSats = 25_000L))
        sut.releaseQuickPaySpendSats(amountSats = 5_000L, dayKey = "2026-08-15")
        assertEquals(0L, sut.quickPaySpentSatsForDay("2026-08-15"))
    }

    @Test
    fun `releaseQuickPayReservation frees pending spend by payment hash`() = test {
        assertTrue(sut.tryReserveQuickPaySpendSats(amountSats = 5_000L, dayKey = "2026-08-15", dailyCapSats = 25_000L))
        sut.rememberQuickPayReservation(paymentHash = "abc", amountSats = 5_000L, dayKey = "2026-08-15")

        sut.releaseQuickPayReservation("abc")

        assertEquals(0L, sut.quickPaySpentSatsForDay("2026-08-15"))
    }

    @Test
    fun `clearQuickPayReservation keeps spend after success`() = test {
        assertTrue(sut.tryReserveQuickPaySpendSats(amountSats = 5_000L, dayKey = "2026-08-15", dailyCapSats = 25_000L))
        sut.rememberQuickPayReservation(paymentHash = "abc", amountSats = 5_000L, dayKey = "2026-08-15")

        sut.clearQuickPayReservation("abc")

        assertEquals(5_000L, sut.quickPaySpentSatsForDay("2026-08-15"))
    }
}
