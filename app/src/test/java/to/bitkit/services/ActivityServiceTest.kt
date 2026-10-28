package to.bitkit.services

import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.LightningActivity
import com.synonym.bitkitcore.OnchainActivity
import com.synonym.bitkitcore.PaymentState
import com.synonym.bitkitcore.PaymentType
import com.synonym.bitkitcore.PreActivityMetadata
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Test
import org.lightningdevkit.ldknode.ConfirmationStatus
import org.lightningdevkit.ldknode.LspFeeLimits
import org.lightningdevkit.ldknode.PaymentDetails
import org.lightningdevkit.ldknode.PaymentDirection
import org.lightningdevkit.ldknode.PaymentKind
import org.lightningdevkit.ldknode.PaymentStatus
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.never
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import to.bitkit.async.ServiceQueue
import to.bitkit.data.AppCacheData
import to.bitkit.data.CacheStore
import to.bitkit.ext.create
import to.bitkit.ext.scopedActivityId
import to.bitkit.ext.totalValue
import to.bitkit.models.WalletScope
import to.bitkit.repositories.PrivatePaykitContactResolver
import to.bitkit.test.BaseUnitTest
import javax.inject.Provider
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ActivityServiceTest : BaseUnitTest() {
    companion object {
        private const val WALLET_ID = "bitkit"
        private const val ACTIVITY_ID = "activity"
        private const val SEEN_AT = 1_790_000_000uL
    }

    private val binding = Class.forName("com.synonym.bitkitcore.Bitkitcore_androidKt")
    private val getActivityById = binding.getMethod("getActivityById", String::class.java, String::class.java)
    private val updateActivity = binding.getMethod("updateActivity", String::class.java, Activity::class.java)
    private val markActivityAsSeen = binding.methods.single { it.name.startsWith("markActivityAsSeen") }

    private val cacheStore = mock<CacheStore>()
    private val privateContactResolver = mock<PrivatePaykitContactResolver>()

    private val sut by lazy {
        ActivityService(
            coreService = mock(),
            cacheStore = cacheStore,
            lightningService = mock(),
            settingsStore = mock(),
            privatePaykitContactResolver = Provider { privateContactResolver },
        )
    }

    @Test
    fun `markActivityAsSeen persists the timestamp through the core seen call`() = test {
        ServiceQueue.CORE.background {
            mockStatic(binding).use { native ->
                native.`when`<Any?> { getActivityById.invoke(null, WALLET_ID, ACTIVITY_ID) }.thenReturn(activity())

                sut.markActivityAsSeen(ACTIVITY_ID, walletId = WALLET_ID, seenAt = SEEN_AT)

                native.verify { markActivityAsSeen.invoke(null, WALLET_ID, ACTIVITY_ID, SEEN_AT.toLong()) }
                native.verify({ updateActivity.invoke(null, anyString(), any(Activity::class.java)) }, never())
            }
        }
    }

    @Test
    fun `markActivityAsSeen skips the core seen call when the activity is missing`() = test {
        ServiceQueue.CORE.background {
            mockStatic(binding).use { native ->
                native.`when`<Any?> { getActivityById.invoke(null, WALLET_ID, ACTIVITY_ID) }.thenReturn(null)

                sut.markActivityAsSeen(ACTIVITY_ID, walletId = WALLET_ID, seenAt = SEEN_AT)

                native.verify({ markActivityAsSeen.invoke(null, anyString(), anyString(), anyLong()) }, never())
            }
        }
    }

    @Test
    fun `verified external confirmation preserves payment fields and can apply an authoritative reorg`() = test {
        ServiceQueue.CORE.background {
            val getByTx = binding.getMethod("getActivityByTxId", String::class.java, String::class.java)
            val upsert = binding.getMethod("upsertActivity", Activity::class.java)
            val original = activity().v1.copy(
                txType = PaymentType.SENT,
                timestamp = 200uL,
                value = 5_000uL,
                fee = 2_344uL,
                contact = "contact",
            )
            var saved = original
            mockStatic(binding).use { native ->
                native.`when`<Any?> { getByTx.invoke(null, WALLET_ID, ACTIVITY_ID) }.thenAnswer { saved }
                native.`when`<Any?> { upsert.invoke(null, any(Activity::class.java)) }.thenAnswer {
                    saved = (it.getArgument<Activity>(0) as Activity.Onchain).v1
                    Unit
                }

                sut.applyVerifiedSentOnchainConfirmation(original, ConfirmationStatus.Confirmed("block", 6u, 150uL))

                assertTrue(saved.confirmed)
                assertEquals(150uL, saved.confirmTimestamp)
                assertEquals(150uL, saved.timestamp)
                assertEquals(original.value, saved.value)
                assertEquals(original.fee, saved.fee)
                assertEquals(original.contact, saved.contact)
                sut.applyVerifiedSentOnchainConfirmation(original, ConfirmationStatus.Unconfirmed)
                kotlin.test.assertFalse(saved.confirmed)
                kotlin.test.assertNull(saved.confirmTimestamp)

                val changedAmount = saved.copy(value = 6_000uL)
                saved = changedAmount
                sut.applyVerifiedSentOnchainConfirmation(original, ConfirmationStatus.Confirmed("block", 6u, 150uL))
                assertEquals(changedAmount, saved)
            }
        }
    }

    @Test
    fun `restored contact fills missing attribution and preserves a later edit`() = test {
        whenever(cacheStore.data).thenReturn(flowOf(AppCacheData()))
        ServiceQueue.CORE.background {
            val getByTx = binding.getMethod("getActivityByTxId", String::class.java, String::class.java)
            val upsert = binding.getMethod("upsertActivity", Activity::class.java)
            for (existingContact in listOf(null, "later-contact")) {
                var saved = activity().v1.copy(txType = PaymentType.SENT, contact = existingContact)
                mockStatic(binding).use { native ->
                    native.`when`<Any?> { getByTx.invoke(null, WALLET_ID, ACTIVITY_ID) }.thenAnswer { saved }
                    native.`when`<Any?> { upsert.invoke(null, any(Activity::class.java)) }.thenAnswer {
                        saved = (it.getArgument<Activity>(0) as Activity.Onchain).v1
                        Unit
                    }
                    sut.restoreSentOnchainContact(ACTIVITY_ID, WALLET_ID, "original-contact")
                    kotlin.test.assertEquals(existingContact ?: "original-contact", saved.contact)
                    if (existingContact != null) {
                        native.verify({ upsert.invoke(null, any(Activity::class.java)) }, never())
                    }
                }
            }
        }
    }

    @Test
    fun `restored contact preserves durable manual detachment without blocking completion`() = test {
        whenever(cacheStore.data).thenReturn(
            flowOf(AppCacheData(detachedActivityContacts = setOf(scopedActivityId(WALLET_ID, ACTIVITY_ID))))
        )
        ServiceQueue.CORE.background {
            val getByTx = binding.getMethod("getActivityByTxId", String::class.java, String::class.java)
            val upsert = binding.getMethod("upsertActivity", Activity::class.java)
            var saved = activity().v1.copy(txType = PaymentType.SENT, contact = null)
            mockStatic(binding).use { native ->
                native.`when`<Any?> { getByTx.invoke(null, WALLET_ID, ACTIVITY_ID) }.thenAnswer { saved }
                native.`when`<Any?> { upsert.invoke(null, any(Activity::class.java)) }.thenAnswer {
                    saved = (it.getArgument<Activity>(0) as Activity.Onchain).v1
                    Unit
                }
                sut.restoreSentOnchainContact(ACTIVITY_ID, WALLET_ID, "original-contact")
                kotlin.test.assertNull(saved.contact)
                native.verify({ upsert.invoke(null, any(Activity::class.java)) }, never())
            }
        }
    }

    @Test
    fun `observed hardware tags use original wallet once and preserve later edits`() = test {
        ServiceQueue.CORE.background {
            val getByTx = binding.getMethod("getActivityByTxId", String::class.java, String::class.java)
            val getMetadata = binding.getMethod("getAllPreActivityMetadata")
            val getTags = binding.getMethod("getTags", String::class.java, String::class.java)
            val addTags = binding.getMethod("addTags", String::class.java, String::class.java, List::class.java)
            val upsertMetadata = binding.getMethod("upsertPreActivityMetadata", List::class.java)
            val original = tagMetadata(WALLET_ID)
            val foreign = tagMetadata("another-wallet").copy(tags = listOf("foreign"))
            var metadata = listOf(original, foreign)
            var tags = listOf("existing")
            mockStatic(binding).use { native ->
                native.`when`<Any?> { getByTx.invoke(null, WALLET_ID, ACTIVITY_ID) }
                    .thenReturn(activity().v1.copy(txType = PaymentType.SENT))
                native.`when`<Any?> { getMetadata.invoke(null) }.thenAnswer { metadata }
                native.`when`<Any?> { getTags.invoke(null, WALLET_ID, ACTIVITY_ID) }.thenAnswer { tags }
                native.`when`<Any?> {
                    addTags.invoke(null, WALLET_ID, ACTIVITY_ID, listOf("original"))
                }.thenAnswer {
                    tags = tags + "original"
                    Unit
                }
                native.`when`<Any?> { upsertMetadata.invoke(null, any(List::class.java)) }.thenAnswer {
                    val updated = it.getArgument<List<PreActivityMetadata>>(0)
                    metadata = metadata.map { row ->
                        updated.firstOrNull { it.walletId == row.walletId && it.paymentId == row.paymentId } ?: row
                    }
                    Unit
                }

                kotlin.test.assertTrue(sut.restoreSentOnchainTags(ACTIVITY_ID, WALLET_ID))
                kotlin.test.assertEquals(listOf("existing", "original"), tags)
                kotlin.test.assertEquals(original.copy(tags = emptyList()), metadata.first())
                kotlin.test.assertEquals(foreign, metadata.last())
                tags = listOf("later-edit")
                kotlin.test.assertFalse(sut.restoreSentOnchainTags(ACTIVITY_ID, WALLET_ID))
                kotlin.test.assertEquals(listOf("later-edit"), tags)
            }
        }
    }

    @Test
    fun `failed hardware tag write retains saved tags for recovery`() = test {
        ServiceQueue.CORE.background {
            val getByTx = binding.getMethod("getActivityByTxId", String::class.java, String::class.java)
            val getMetadata = binding.getMethod("getAllPreActivityMetadata")
            val getTags = binding.getMethod("getTags", String::class.java, String::class.java)
            val addTags = binding.getMethod("addTags", String::class.java, String::class.java, List::class.java)
            val upsertMetadata = binding.getMethod("upsertPreActivityMetadata", List::class.java)
            mockStatic(binding).use { native ->
                native.`when`<Any?> { getByTx.invoke(null, WALLET_ID, ACTIVITY_ID) }
                    .thenReturn(activity().v1.copy(txType = PaymentType.SENT))
                native.`when`<Any?> { getMetadata.invoke(null) }.thenReturn(listOf(tagMetadata(WALLET_ID)))
                native.`when`<Any?> { getTags.invoke(null, WALLET_ID, ACTIVITY_ID) }.thenReturn(emptyList<String>())
                native.`when`<Any?> { addTags.invoke(null, WALLET_ID, ACTIVITY_ID, listOf("original")) }
                    .thenThrow(IllegalStateException("tag write failed"))
                kotlin.test.assertFails { sut.restoreSentOnchainTags(ACTIVITY_ID, WALLET_ID) }
                native.verify({ upsertMetadata.invoke(null, any(List::class.java)) }, never())
            }
        }
    }

    @Test
    fun `sync overlapping send completion keeps one attributed activity through confirmation`() = test {
        val walletId = WalletScope.default
        val transactionId = "transaction"
        val syncPaused = CompletableDeferred<Unit>()
        val resumeSync = CompletableDeferred<Unit>()
        var pauseOnce = true
        whenever(cacheStore.data).thenReturn(
            flow {
                if (pauseOnce) {
                    pauseOnce = false
                    syncPaused.complete(Unit)
                    resumeSync.await()
                }
                emit(AppCacheData())
            },
        )
        val payment = PaymentDetails(
            id = "ldk-payment-id",
            kind = PaymentKind.Onchain(transactionId, ConfirmationStatus.Unconfirmed),
            amountMsat = 5_000_000uL,
            feePaidMsat = 209_000uL,
            direction = PaymentDirection.OUTBOUND,
            status = PaymentStatus.SUCCEEDED,
            latestUpdateTimestamp = 100uL,
        )
        withOnchainActivities(transactionId) { saved ->
            val syncing = async { sut.syncLdkNodePaymentsToActivities(listOf(payment)) }
            syncPaused.await()
            sut.createSentOnchainActivityFromSendResult(
                txid = transactionId,
                address = "recipient",
                amount = 5_000uL,
                fee = 209uL,
                feeRate = 1uL,
                isTransfer = false,
                channelId = null,
                walletId = walletId,
            )
            sut.restoreSentOnchainContact(transactionId, walletId, "recipient-contact")
            resumeSync.complete(Unit)
            syncing.await()

            assertEquals(listOf(transactionId), saved.keys.toList())
            val confirmed = mock<ConfirmationStatus.Confirmed> { on { timestamp }.thenReturn(200uL) }
            sut.syncLdkNodePaymentsToActivities(
                listOf(
                    payment.copy(
                        kind = PaymentKind.Onchain(transactionId, confirmed),
                        latestUpdateTimestamp = 200uL,
                    ),
                ),
            )
            val activity = saved.values.single().v1
            assertEquals(transactionId, activity.id)
            assertEquals("recipient-contact", activity.contact)
            assertEquals("recipient", activity.address)
            assertEquals(209uL, activity.fee)
            assertTrue(activity.confirmed)
        }
    }

    @Test
    fun `primary JIT receipt is created once with net value and actual skim after settlement`() = test {
        whenever(cacheStore.data).thenReturn(flowOf(AppCacheData()))
        val settled = jitPayment()
        withLightningActivities { saved ->
            sut.syncLdkNodePaymentsToActivities(listOf(settled.copy(status = PaymentStatus.PENDING)))
            assertTrue(saved.isEmpty())

            sut.syncLdkNodePaymentsToActivities(listOf(settled))
            sut.syncLdkNodePaymentsToActivities(listOf(settled))

            val receipt = saved.values.single()
            assertEquals(settled.id, receipt.v1.id)
            assertEquals(PaymentType.RECEIVED, receipt.v1.txType)
            assertEquals(PaymentState.SUCCEEDED, receipt.v1.status)
            assertEquals(19_880uL, receipt.v1.value)
            assertEquals(120uL, receipt.v1.fee)
            assertEquals(19_880uL, receipt.totalValue())
            assertEquals("primary-invoice", receipt.v1.invoice)
            assertEquals("receive note", receipt.v1.message)
            assertEquals("preimage", receipt.v1.preimage)
        }
    }

    @Test
    fun `JIT receipt updates existing row without losing metadata or duplicating legacy history`() = test {
        whenever(cacheStore.data).thenReturn(flowOf(AppCacheData()))
        val payment = jitPayment()
        val existing = LightningActivity.create(
            id = payment.id,
            txType = PaymentType.RECEIVED,
            status = PaymentState.PENDING,
            value = 20_000uL,
            invoice = "primary-invoice",
            timestamp = 50uL,
            message = "saved note",
            contact = "saved contact",
            seenAt = 55uL,
        )
        val legacy = existing.copy(id = "legacy-funding-tx", value = 25_000uL, fee = 0uL, invoice = "legacy-invoice")
        withLightningActivities { saved ->
            saved[payment.id] = Activity.Lightning(existing)
            saved[legacy.id] = Activity.Lightning(legacy)

            sut.syncLdkNodePaymentsToActivities(listOf(payment))

            assertEquals(2, saved.size)
            assertEquals(Activity.Lightning(legacy), saved[legacy.id])
            val updated = requireNotNull(saved[payment.id]).v1
            assertEquals(19_880uL, updated.value)
            assertEquals(120uL, updated.fee)
            assertEquals(existing.message, updated.message)
            assertEquals(existing.contact, updated.contact)
            assertEquals(existing.seenAt, updated.seenAt)
            assertEquals(20_000uL, Activity.Lightning(updated.copy(txType = PaymentType.SENT)).totalValue())
        }
    }

    private fun jitPayment() = PaymentDetails(
        id = "primary-payment",
        kind = PaymentKind.Bolt11Jit(
            hash = "primary-payment",
            preimage = "preimage",
            secret = null,
            counterpartySkimmedFeeMsat = 120_000uL,
            lspFeeLimits = LspFeeLimits(120_000uL, null),
            description = "receive note",
            bolt11 = "primary-invoice",
        ),
        amountMsat = 19_880_000uL,
        feePaidMsat = null,
        direction = PaymentDirection.INBOUND,
        status = PaymentStatus.SUCCEEDED,
        latestUpdateTimestamp = 100uL,
    )

    private suspend fun withLightningActivities(block: suspend (MutableMap<String, Activity.Lightning>) -> Unit) =
        ServiceQueue.CORE.background {
            val saved = mutableMapOf<String, Activity.Lightning>()
            val upsert = binding.getMethod("upsertActivity", Activity::class.java)
            mockStatic(binding).use { native ->
                native.`when`<Any?> { getActivityById.invoke(null, anyString(), anyString()) }.thenAnswer {
                    saved[it.getArgument<String>(1)]
                }
                native.`when`<Any?> { upsert.invoke(null, any(Activity::class.java)) }.thenAnswer {
                    val activity = it.getArgument<Activity.Lightning>(0)
                    saved[activity.v1.id] = activity
                    Unit
                }
                native.`when`<Any?> { updateActivity.invoke(null, anyString(), any(Activity::class.java)) }.thenAnswer {
                    val activity = it.getArgument<Activity.Lightning>(1)
                    saved[activity.v1.id] = activity
                    Unit
                }
                block(saved)
            }
        }

    private suspend fun withOnchainActivities(
        transactionId: String,
        block: suspend CoroutineScope.(MutableMap<String, Activity.Onchain>) -> Unit,
    ) = ServiceQueue.CORE.background {
        val walletId = WalletScope.default
        val saved = mutableMapOf<String, Activity.Onchain>()
        val getByTx = binding.getMethod("getActivityByTxId", String::class.java, String::class.java)
        val upsert = binding.getMethod("upsertActivity", Activity::class.java)
        mockStatic(binding).use { native ->
            native.`when`<Any?> { getActivityById.invoke(null, anyString(), anyString()) }.thenAnswer {
                saved[it.getArgument<String>(1)]
            }
            native.`when`<Any?> { getByTx.invoke(null, walletId, transactionId) }.thenAnswer {
                saved.values.firstOrNull { it.v1.txId == transactionId }?.v1
            }
            native.`when`<Any?> { upsert.invoke(null, any(Activity::class.java)) }.thenAnswer {
                val activity = it.getArgument<Activity>(0) as Activity.Onchain
                saved[activity.v1.id] = activity
                Unit
            }
            native.`when`<Any?> { updateActivity.invoke(null, anyString(), any(Activity::class.java)) }.thenAnswer {
                val activity = it.getArgument<Activity>(1) as Activity.Onchain
                saved[activity.v1.id] = activity
                Unit
            }
            block(saved)
        }
    }

    private fun tagMetadata(walletId: String) = PreActivityMetadata(
        walletId = walletId, paymentId = ACTIVITY_ID, tags = listOf("original"),
        paymentHash = null, txId = ACTIVITY_ID, address = "address", isReceive = false,
        feeRate = 0uL, isTransfer = false, channelId = null, createdAt = 1uL,
    )

    private fun activity() = Activity.Onchain(
        OnchainActivity.create(
            walletId = WALLET_ID,
            id = ACTIVITY_ID,
            txType = PaymentType.RECEIVED,
            txId = ACTIVITY_ID,
            value = 1uL,
            fee = 0uL,
            address = "address",
            timestamp = 1uL,
        )
    )
}
