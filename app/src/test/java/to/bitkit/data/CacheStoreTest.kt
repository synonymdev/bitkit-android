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
    fun `a pending node send behind the cap of paid ones stays kept`() = test {
        val paid = settledHashes()
        sut.seedLdkSendHashes(listOf("pending") + paid)

        sut.trimLdkSendHashes(paid.toSet())

        val kept = sut.data.first().ldkSendHashes
        assertTrue("pending" in kept)
        assertEquals(MAX_SPARK_SEND_HASHES, kept.size)
        assertFalse(paid.first() in kept, "The oldest paid send was kept over the cap")
    }

    @Test
    fun `a node send still pending behind the cap of later sends stays kept`() = test {
        sut.addLdkSendHash("pending")
        settledHashes().forEach { sut.addLdkSendHash(it) }

        assertTrue("pending" in sut.data.first().ldkSendHashes)
    }

    @Test
    fun `a spark send still pending behind the cap of later sends stays kept`() = test {
        sut.addSparkSendHash("pending")
        val paid = settledHashes()
        paid.forEach { sut.addSparkSendHash(it) }

        sut.trimSparkSendHashes(paid.toSet())

        val kept = sut.data.first().sparkSendHashes
        assertTrue("pending" in kept)
        assertEquals(MAX_SPARK_SEND_HASHES, kept.size)
    }

    @Test
    fun `node sends listed as failed are trimmed past the cap, a pending one stays`() = test {
        sut.addLdkSendHash("pending")
        val failed = settledHashes()
        failed.forEach { sut.addLdkSendHash(it) }

        sut.trimLdkSendHashes(failed.toSet())

        val kept = sut.data.first().ldkSendHashes
        assertTrue("pending" in kept)
        assertEquals(MAX_SPARK_SEND_HASHES, kept.size)
        assertFalse(failed.first() in kept, "The oldest failed send was kept over the cap")
    }

    @Test
    fun `a retried node send survives a trim from a listing that still shows it failed`() = test {
        sut.addLdkSendHash("retried")
        val failed = settledHashes()
        failed.forEach { sut.addLdkSendHash(it) }
        sut.addLdkSendHash("retried")

        sut.trimLdkSendHashes(failed.toSet() + "retried")

        val kept = sut.data.first().ldkSendHashes
        assertEquals("retried", kept.last())
        assertEquals(MAX_SPARK_SEND_HASHES, kept.size)
    }

    private fun settledHashes() = (1..MAX_SPARK_SEND_HASHES).map { "paid$it" }
}
