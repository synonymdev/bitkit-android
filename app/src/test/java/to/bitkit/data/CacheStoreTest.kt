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
}
