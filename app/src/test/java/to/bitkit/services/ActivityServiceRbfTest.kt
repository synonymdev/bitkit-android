package to.bitkit.services

import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.OnchainActivity
import com.synonym.bitkitcore.PaymentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.any
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.async.ServiceQueue
import to.bitkit.data.AppCacheData
import to.bitkit.data.CacheStore
import to.bitkit.ext.create
import to.bitkit.ext.scopedActivityId
import to.bitkit.models.WalletScope
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ActivityServiceRbfTest : BaseUnitTest() {
    private val binding = Class.forName("com.synonym.bitkitcore.Bitkitcore_androidKt")
    private val cache = mock<CacheStore>()
    private val lightning = mock<LightningService>()
    private val sut by lazy { ActivityService(mock(), cache, lightning, mock(), mock()) }

    @Test
    fun `publication rereads fresh metadata after suspension and preserves real transaction fees`() = test {
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        whenever(cache.data).thenReturn(
            flow {
                entered.complete(Unit)
                resume.await()
                emit(AppCacheData())
            }
        )
        withActivities { saved, tags ->
            val publishing = async { sut.publishRbfBoost("a", "b") }
            entered.await()
            assertFalse(saved.getValue("b").isBoosted)
            assertTrue(saved.getValue("a").doesExist)
            saved["a"] = saved.getValue("a").copy(contact = "fresh-original")
            saved["b"] = saved.getValue("b").copy(contact = "fresh-recipient")
            val result = publishing.also { resume.complete(Unit) }.await().v1
            assertEquals("payment-b", result.id)
            assertEquals("b", result.txId)
            assertEquals(422uL, result.fee)
            assertEquals("fresh-recipient", result.contact)
            assertEquals(141uL, saved.getValue("a").fee)
            assertFalse(saved.getValue("a").doesExist)
            assertTrue(result.isBoosted)
            assertEquals(listOf("a"), result.boostTxIds)
            assertEquals(setOf("original-tag", "replacement-tag"), tags.getValue("payment-b"))
            verify(lightning, never()).sync()
        }
    }

    @Test
    fun `publication waits for wallet sync to discover the broadcast replacement`() = test {
        whenever(cache.data).thenReturn(flowOf(AppCacheData()))
        whenever(lightning.listPayments()).thenReturn(emptyList())
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        withActivities { saved, tags ->
            val replacement = saved.remove("b")!!
            whenever(lightning.sync()).doSuspendableAnswer {
                entered.complete(Unit)
                resume.await()
                saved["b"] = replacement
            }
            val publishing = async { sut.publishRbfBoost("a", "b") }
            entered.await()
            assertFalse(publishing.isCompleted)
            assertTrue(saved.getValue("a").doesExist)
            assertFalse("b" in saved)
            saved["a"] = saved.getValue("a").copy(contact = "edited-during-sync")
            val result = publishing.also { resume.complete(Unit) }.await().v1
            assertEquals("b", result.txId)
            assertEquals(422uL, result.fee)
            assertEquals("edited-during-sync", result.contact)
            assertEquals(listOf("a"), result.boostTxIds)
            assertTrue(result.isBoosted)
            assertFalse(saved.getValue("a").doesExist)
            assertEquals(141uL, saved.getValue("a").fee)
            assertEquals(setOf("original-tag", "replacement-tag"), tags.getValue("payment-b"))
            verify(lightning).sync()
        }
    }

    @Test
    fun `wallet sync failure preserves original and can retry publication without a broadcast`() = test {
        whenever(cache.data).thenReturn(flowOf(AppCacheData()))
        whenever(lightning.listPayments()).thenReturn(emptyList())
        withActivities { saved, _ ->
            val replacement = saved.remove("b")!!
            val original = saved.getValue("a")
            whenever(lightning.sync()).thenThrow(IllegalStateException("sync unavailable"))
            assertFailsWith<Exception> { sut.publishRbfBoost("a", "b") }
            assertEquals(original, saved.getValue("a"))
            saved["b"] = replacement
            assertEquals("b", sut.publishRbfBoost("a", "b").v1.txId)
            verify(lightning).sync()
        }
    }

    @Test
    fun `cancellation while discovering the replacement does not publish it`() = test {
        whenever(cache.data).thenReturn(flowOf(AppCacheData()))
        whenever(lightning.listPayments()).thenReturn(emptyList())
        whenever(lightning.sync()).thenThrow(CancellationException("cancelled sync"))
        withActivities { saved, _ ->
            saved.remove("b")
            val original = saved.getValue("a")
            assertFailsWith<CancellationException> { sut.publishRbfBoost("a", "b") }
            assertEquals(original, saved.getValue("a"))
            assertFalse("b" in saved)
        }
    }

    @Test
    fun `duplicate and delayed events preserve retired successors and repair partial tag writes`() = test {
        whenever(cache.data).thenReturn(flowOf(AppCacheData()))
        withActivities(failFirstTagWrite = true) { saved, tags ->
            assertFailsWith<Exception> { sut.publishRbfBoost("a", "b") }
            assertFalse(saved.getValue("b").isBoosted)
            sut.handleOnchainTransactionReplaced("a", listOf("b"))
            assertEquals(setOf("original-tag", "replacement-tag"), tags.getValue("payment-b"))
            sut.publishRbfBoost("b", "c")
            saved["b"] = saved.getValue("b").copy(contact = "later-contact")
            sut.handleOnchainTransactionReplaced("a", listOf("b"))
            assertFalse(saved.getValue("a").doesExist)
            assertFalse(saved.getValue("b").doesExist)
            assertEquals("later-contact", saved.getValue("b").contact)
            assertEquals(listOf("a"), saved.getValue("b").boostTxIds)
            assertEquals(listOf("b"), saved.getValue("c").boostTxIds)
            assertEquals(setOf("original-tag", "replacement-tag"), tags.getValue("payment-c"))
        }
    }

    @Test
    fun `duplicate replacement event preserves edits made after completed publication`() = test {
        whenever(cache.data).thenReturn(flowOf(AppCacheData()))
        withActivities { saved, tags ->
            sut.publishRbfBoost("a", "b")
            tags["payment-b"] = setOf("replacement-tag")
            saved["b"] = saved.getValue("b").copy(contact = null)
            sut.handleOnchainTransactionReplaced("a", listOf("b"))
            assertEquals(setOf("replacement-tag"), tags.getValue("payment-b"))
            assertEquals(null, saved.getValue("b").contact)
        }
    }

    @Test
    fun `external replacement retires original even if metadata fails and repairs on retry`() = test {
        whenever(cache.data).thenReturn(flowOf(AppCacheData()))
        withActivities(failFirstTagWrite = true) { saved, tags ->
            saved["a"] = saved.getValue("a").copy(txType = PaymentType.RECEIVED)
            saved["b"] = saved.getValue("b").copy(txType = PaymentType.RECEIVED)
            sut.handleOnchainTransactionReplaced("a", listOf("b"))
            assertFalse(saved.getValue("a").doesExist)
            assertFalse(saved.getValue("b").isBoosted)
            sut.handleOnchainTransactionReplaced("a", listOf("b"))
            assertTrue(saved.getValue("b").isBoosted)
            assertEquals(PaymentType.RECEIVED, saved.getValue("b").txType)
            assertEquals(setOf("original-tag", "replacement-tag"), tags.getValue("payment-b"))
        }
    }

    @Test
    fun `manual contact detachment and existing replacement metadata survive publication`() = test {
        whenever(cache.data).thenReturn(
            flowOf(
                AppCacheData(
                    detachedActivityContacts = setOf(scopedActivityId(WalletScope.default, "payment-b")),
                )
            )
        )
        withActivities { saved, _ ->
            saved["b"] = saved.getValue("b").copy(boostTxIds = listOf("earlier"))
            val result = sut.publishRbfBoost("a", "b").v1
            assertEquals(null, result.contact)
            assertEquals(listOf("earlier", "a"), result.boostTxIds)
        }
    }

    @Test
    fun `missing replacement fails without fabricating original state`() = test {
        whenever(cache.data).thenReturn(flowOf(AppCacheData()))
        whenever(lightning.listPayments()).thenReturn(emptyList())
        withActivities { saved, _ ->
            saved.remove("b")
            val original = saved.getValue("a")
            assertFailsWith<Exception> { sut.publishRbfBoost("a", "b") }
            assertEquals(original, saved.getValue("a"))
            assertFalse("b" in saved)
            sut.handleOnchainTransactionReplaced("a", listOf("b"))
            assertFalse(saved.getValue("a").doesExist)
        }
    }

    @Test
    fun `cancelled publication does not retire the original`() = test {
        whenever(cache.data).thenReturn(flow { throw CancellationException("cancelled") })
        withActivities { saved, _ ->
            assertFailsWith<CancellationException> { sut.publishRbfBoost("a", "b") }
            assertTrue(saved.getValue("a").doesExist)
            assertFalse(saved.getValue("b").isBoosted)
        }
    }

    private suspend fun withActivities(
        failFirstTagWrite: Boolean = false,
        block: suspend CoroutineScope.(MutableMap<String, OnchainActivity>, MutableMap<String, Set<String>>) -> Unit,
    ) = ServiceQueue.CORE.background {
        val wallet = WalletScope.default
        val saved = listOf("a", "b", "c").associateWith { txid ->
            OnchainActivity.create(
                walletId = wallet, id = "payment-$txid", txId = txid, txType = PaymentType.SENT,
                value = 10_000uL, fee = if (txid == "a") 141uL else 422uL,
                address = "recipient", timestamp = 1uL, contact = if (txid == "a") "original" else null,
            )
        }.toMutableMap()
        val tags = mutableMapOf("payment-a" to setOf("original-tag"), "payment-b" to setOf("replacement-tag"))
        var failTags = failFirstTagWrite
        val getByTx = binding.getMethod("getActivityByTxId", String::class.java, String::class.java)
        val update = binding.getMethod("updateActivity", String::class.java, Activity::class.java)
        val getTags = binding.getMethod("getTags", String::class.java, String::class.java)
        val addTags = binding.getMethod("addTags", String::class.java, String::class.java, List::class.java)
        mockStatic(binding).use { native ->
            native.`when`<Any?> { getByTx.invoke(null, anyString(), anyString()) }.thenAnswer {
                saved[it.getArgument<String>(1)].takeIf { activity -> activity?.walletId == it.getArgument<String>(0) }
            }
            native.`when`<Any?> { update.invoke(null, anyString(), any<Activity>()) }.thenAnswer {
                val activity = it.getArgument<Activity.Onchain>(1).v1
                saved[activity.txId] = activity
                Unit
            }
            native.`when`<Any?> { getTags.invoke(null, anyString(), anyString()) }.thenAnswer {
                tags[it.getArgument<String>(1)].orEmpty().toList()
            }
            native.`when`<Any?> { addTags.invoke(null, anyString(), anyString(), any<List<String>>()) }.thenAnswer {
                if (failTags) {
                    failTags = false
                    error("tag write failed")
                }
                val id = it.getArgument<String>(1)
                tags[id] = tags[id].orEmpty() + it.getArgument<List<String>>(2)
                Unit
            }
            block(saved, tags)
        }
    }
}
