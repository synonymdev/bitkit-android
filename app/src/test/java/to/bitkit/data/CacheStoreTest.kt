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
    fun `quickPaySpentUsdForDay returns spend for matching day key`() = test {
        sut.recordQuickPaySpendUsd(amountUsd = 3.5, dayKey = "2026-08-15")

        assertEquals(3.5, sut.quickPaySpentUsdForDay("2026-08-15"))
    }

    @Test
    fun `quickPaySpentUsdForDay returns zero for a different day key`() = test {
        sut.recordQuickPaySpendUsd(amountUsd = 12.0, dayKey = "2026-08-14")

        assertEquals(0.0, sut.quickPaySpentUsdForDay("2026-08-15"))
    }

    @Test
    fun `recordQuickPaySpendUsd accumulates on the same day and resets on a new day`() = test {
        sut.recordQuickPaySpendUsd(amountUsd = 2.0, dayKey = "2026-08-15")
        sut.recordQuickPaySpendUsd(amountUsd = 1.5, dayKey = "2026-08-15")
        assertEquals(3.5, sut.quickPaySpentUsdForDay("2026-08-15"))

        sut.recordQuickPaySpendUsd(amountUsd = 4.0, dayKey = "2026-08-16")
        assertEquals(4.0, sut.quickPaySpentUsdForDay("2026-08-16"))
        assertEquals(0.0, sut.quickPaySpentUsdForDay("2026-08-15"))
    }

    @Test
    fun `tryReserveQuickPaySpendUsd reserves under the cap and rejects over it`() = test {
        assertTrue(sut.tryReserveQuickPaySpendUsd(amountUsd = 10.0, dayKey = "2026-08-15", dailyCapUsd = 25.0))
        assertTrue(sut.tryReserveQuickPaySpendUsd(amountUsd = 10.0, dayKey = "2026-08-15", dailyCapUsd = 25.0))
        assertFalse(sut.tryReserveQuickPaySpendUsd(amountUsd = 10.0, dayKey = "2026-08-15", dailyCapUsd = 25.0))
        assertEquals(20.0, sut.quickPaySpentUsdForDay("2026-08-15"))
    }

    @Test
    fun `releaseQuickPaySpendUsd rolls back a reservation`() = test {
        assertTrue(sut.tryReserveQuickPaySpendUsd(amountUsd = 5.0, dayKey = "2026-08-15", dailyCapUsd = 25.0))
        sut.releaseQuickPaySpendUsd(amountUsd = 5.0, dayKey = "2026-08-15")
        assertEquals(0.0, sut.quickPaySpentUsdForDay("2026-08-15"))
    }
}
