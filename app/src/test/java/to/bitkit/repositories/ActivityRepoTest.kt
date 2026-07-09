package to.bitkit.repositories

import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.ActivityFilter
import com.synonym.bitkitcore.ActivityTags
import com.synonym.bitkitcore.IcJitEntry
import com.synonym.bitkitcore.LightningActivity
import com.synonym.bitkitcore.OnchainActivity
import com.synonym.bitkitcore.PaymentType
import com.synonym.bitkitcore.SortDirection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Before
import org.junit.Test
import org.lightningdevkit.ldknode.PaymentDetails
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argThat
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.kotlin.wheneverBlocking
import to.bitkit.data.AppCacheData
import to.bitkit.data.CacheStore
import to.bitkit.data.dto.PendingBoostActivity
import to.bitkit.ext.create
import to.bitkit.ext.createChannelDetails
import to.bitkit.ext.mock
import to.bitkit.models.ActivityWalletType
import to.bitkit.services.CoreService
import to.bitkit.test.BaseUnitTest
import to.bitkit.utils.AppError
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import com.synonym.bitkitcore.TransactionDetails as BitkitCoreTransactionDetails

@Suppress("LargeClass")
@OptIn(ExperimentalTime::class)
class ActivityRepoTest : BaseUnitTest() {

    private val coreService = mock<CoreService>()
    private val lightningRepo = mock<LightningRepo>()
    private val blocktankRepo = mock<BlocktankRepo>()
    private val transferRepo = mock<TransferRepo>()
    private val cacheStore = mock<CacheStore>()
    private val clock = mock<Clock>()

    private lateinit var sut: ActivityRepo

    private val testPaymentDetails = mock<PaymentDetails> {
        on { id } doReturn "payment1"
    }

    private val testActivityV1 = mock<LightningActivity> {
        on { id } doReturn "activity1"
    }

    private val testActivity = mock<Activity.Lightning> {
        on { v1 } doReturn testActivityV1
    }
    private val hardwareWalletId = ActivityWalletType.TREZOR.idPrefixed("dev1")

    private val baseOnchainActivity = OnchainActivity.create(
        id = "base_activity_id",
        txType = PaymentType.SENT,
        txId = "base_tx_id",
        value = 1000uL,
        fee = 100uL,
        address = "bc1test",
        timestamp = 1234567890uL,
        feeRate = 10uL,
    )

    @Suppress("LongParameterList")
    private fun createOnchainActivity(
        id: String = baseOnchainActivity.id,
        txType: PaymentType = baseOnchainActivity.txType,
        txId: String = baseOnchainActivity.txId,
        value: ULong = baseOnchainActivity.value,
        fee: ULong = baseOnchainActivity.fee,
        feeRate: ULong = baseOnchainActivity.feeRate,
        address: String = baseOnchainActivity.address,
        confirmed: Boolean = baseOnchainActivity.confirmed,
        timestamp: ULong = baseOnchainActivity.timestamp,
        isBoosted: Boolean = baseOnchainActivity.isBoosted,
        boostTxIds: List<String> = baseOnchainActivity.boostTxIds,
        isTransfer: Boolean = baseOnchainActivity.isTransfer,
        doesExist: Boolean = baseOnchainActivity.doesExist,
        confirmTimestamp: ULong? = baseOnchainActivity.confirmTimestamp,
        channelId: String? = baseOnchainActivity.channelId,
        transferTxId: String? = baseOnchainActivity.transferTxId,
        contact: String? = baseOnchainActivity.contact,
        createdAt: ULong? = baseOnchainActivity.createdAt,
        updatedAt: ULong? = baseOnchainActivity.updatedAt,
        walletId: String = baseOnchainActivity.walletId,
    ): Activity.Onchain {
        return Activity.Onchain(
            v1 = baseOnchainActivity.copy(
                id = id,
                txType = txType,
                txId = txId,
                value = value,
                fee = fee,
                feeRate = feeRate,
                address = address,
                confirmed = confirmed,
                timestamp = timestamp,
                isBoosted = isBoosted,
                boostTxIds = boostTxIds,
                isTransfer = isTransfer,
                doesExist = doesExist,
                confirmTimestamp = confirmTimestamp,
                channelId = channelId,
                transferTxId = transferTxId,
                contact = contact,
                createdAt = createdAt,
                updatedAt = updatedAt,
                walletId = walletId,
            )
        )
    }

    private fun transactionDetails(txId: String, amountSats: Long) = BitkitCoreTransactionDetails(
        walletId = hardwareWalletId,
        txId = txId,
        amountSats = amountSats,
        inputs = emptyList(),
        outputs = emptyList(),
    )

    @Before
    fun setUp() {
        whenever(cacheStore.data).thenReturn(flowOf(AppCacheData()))
        whenever(coreService.activity).thenReturn(mock())
        whenever(clock.now()).thenReturn(Clock.System.now())
        whenever(lightningRepo.lightningState).thenReturn(MutableStateFlow(LightningState()))
        whenever(blocktankRepo.blocktankState).thenReturn(MutableStateFlow(BlocktankState()))

        sut = ActivityRepo(
            bgDispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
            coreService = coreService,
            lightningRepo = lightningRepo,
            blocktankRepo = blocktankRepo,
            cacheStore = cacheStore,
            transferRepo = transferRepo,
            clock = clock,
        )
    }

    private fun setupSyncActivitiesMocks(
        cacheData: AppCacheData,
    ) {
        whenever(cacheStore.data).thenReturn(flowOf(cacheData))
        wheneverBlocking { lightningRepo.getPayments() }.thenReturn(Result.success(emptyList()))
        wheneverBlocking {
            coreService.activity.syncLdkNodePaymentsToActivities(
                any(),
                eq(false),
                any()
            )
        }.thenReturn(Unit)
        wheneverBlocking { transferRepo.syncTransferStates() }.thenReturn(Result.success(Unit))
        wheneverBlocking { coreService.activity.allPossibleTags() }.thenReturn(emptyList())
    }

    @Test
    fun `syncActivities success flow`() = test {
        val payments = listOf(testPaymentDetails)
        wheneverBlocking { lightningRepo.getPayments() }.thenReturn(Result.success(payments))
        whenever(coreService.activity.getActivity(any<String>(), anyOrNull())).thenReturn(null)
        wheneverBlocking {
            coreService.activity.syncLdkNodePaymentsToActivities(
                any<List<PaymentDetails>>(),
                any<Boolean>(),
                any<Map<String, String>>()
            )
        }.thenReturn(Unit)
        wheneverBlocking { transferRepo.syncTransferStates() }.thenReturn(Result.success(Unit))
        wheneverBlocking { coreService.activity.allPossibleTags() }.thenReturn(emptyList())

        val result = sut.syncActivities()

        assertTrue(result.isSuccess)
        verify(lightningRepo).getPayments()
        verify(coreService.activity).syncLdkNodePaymentsToActivities(any(), any(), any())
        assertFalse(sut.isSyncingLdkNodePayments.value)
    }

    @Test
    fun `syncActivities handles lightningRepo failure`() = test {
        val exception = Exception("Lightning repo failed")
        wheneverBlocking { lightningRepo.getPayments() }.thenReturn(Result.failure(exception))

        val result = sut.syncActivities()

        assertTrue(result.isFailure)
        assertEquals(exception, result.exceptionOrNull())
        assertFalse(sut.isSyncingLdkNodePayments.value)
    }

    @Test
    fun `findActivityByPaymentId returns failure when activity not found after sync`() = test {
        val paymentId = "payment123"

        wheneverBlocking {
            coreService.activity.get(
                walletId = anyOrNull(),
                filter = any(),
                txType = any(),
                tags = any(),
                search = any(),
                minDate = any(),
                maxDate = any(),
                limit = any(),
                sortDirection = any()
            )
        }.thenReturn(emptyList())

        wheneverBlocking { lightningRepo.sync() }.thenReturn(Result.success(Unit))
        wheneverBlocking { lightningRepo.getPayments() }.thenReturn(Result.success(emptyList()))

        val result = sut.findActivityByPaymentId(
            paymentHashOrTxId = paymentId,
            type = ActivityFilter.LIGHTNING,
            txType = PaymentType.RECEIVED
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun `getActivities returns activities successfully`() = test {
        val activities = listOf(testActivity)
        wheneverBlocking {
            coreService.activity.get(
                walletId = ActivityWalletType.BITKIT.id(),
                filter = ActivityFilter.ALL,
                txType = PaymentType.RECEIVED,
                tags = listOf("tag1"),
                search = "search",
                minDate = 1000u,
                maxDate = 2000u,
                limit = 50u,
                sortDirection = SortDirection.DESC
            )
        }.thenReturn(activities)

        val result = sut.getActivities(
            filter = ActivityFilter.ALL,
            txType = PaymentType.RECEIVED,
            tags = listOf("tag1"),
            search = "search",
            minDate = 1000u,
            maxDate = 2000u,
            limit = 50u,
            sortDirection = SortDirection.DESC
        )

        assertTrue(result.isSuccess)
        assertEquals(activities, result.getOrThrow())
    }

    @Test
    fun `getAllActivitiesTags defaults to Bitkit wallet`() = test {
        val bitkitTags = ActivityTags(
            walletId = ActivityWalletType.BITKIT.id(),
            activityId = "bitkit-activity",
            tags = listOf("bitkit"),
        )
        val hardwareTags = ActivityTags(
            walletId = hardwareWalletId,
            activityId = "hardware-activity",
            tags = listOf("hardware"),
        )
        whenever(coreService.activity.getAllActivitiesTags())
            .thenReturn(listOf(bitkitTags, hardwareTags))

        val result = sut.getAllActivitiesTags()

        assertEquals(listOf(bitkitTags), result.getOrThrow())
    }

    @Test
    fun `getActivity returns activity when found`() = test {
        val activityId = "activity123"
        whenever(coreService.activity.getActivity(activityId, null)).thenReturn(testActivity)

        val result = sut.getActivity(activityId)

        assertTrue(result.isSuccess)
        assertEquals(testActivity, result.getOrThrow())
    }

    @Test
    fun `getActivity passes wallet id to core lookup`() = test {
        val activityId = "activity123"
        whenever(coreService.activity.getActivity(activityId, hardwareWalletId)).thenReturn(testActivity)

        val result = sut.getActivity(activityId, hardwareWalletId)

        assertTrue(result.isSuccess)
        assertEquals(testActivity, result.getOrThrow())
        verify(coreService.activity).getActivity(activityId, hardwareWalletId)
    }

    @Test
    fun `persistHardware upserts multiple activities and transaction details`() = test {
        val activities = listOf(
            createOnchainActivity(
                id = "hw-received-id",
                txId = "hw-received-txid",
                value = 10_000uL,
                fee = 0uL,
                timestamp = 2_000uL,
                confirmed = true,
                walletId = hardwareWalletId,
            ),
            createOnchainActivity(
                id = "hw-sent-id",
                txId = "hw-sent-txid",
                value = 4_200uL,
                fee = 321uL,
                timestamp = 3_000uL,
                confirmed = false,
                walletId = hardwareWalletId,
                txType = PaymentType.SENT,
            ),
            createOnchainActivity(
                id = "hw-transfer-id",
                txId = "hw-transfer-txid",
                value = 7_000uL,
                fee = 222uL,
                timestamp = 4_000uL,
                confirmed = true,
                isTransfer = true,
                walletId = hardwareWalletId,
                txType = PaymentType.SENT,
            ),
        )
        val details = listOf(
            transactionDetails("hw-received-txid", 10_000L),
            transactionDetails("hw-sent-txid", -4_521L),
            transactionDetails("hw-transfer-txid", -7_222L),
        )
        whenever(coreService.activity.upsertList(activities)).thenReturn(Unit)
        whenever(coreService.activity.upsertTransactionDetailsList(details)).thenReturn(Unit)

        val result = sut.persistHardware(activities, details)

        assertTrue(result.isSuccess)
        verify(coreService.activity).upsertList(activities)
        verify(coreService.activity).upsertTransactionDetailsList(details)
    }

    @Test
    fun `persistHardware preserves transfer metadata for existing hardware activity`() = test {
        val incoming = createOnchainActivity(
            id = "hw-transfer-txid",
            txId = "hw-transfer-txid",
            value = 7_000uL,
            fee = 222uL,
            timestamp = 4_000uL,
            confirmed = true,
            isTransfer = false,
            walletId = hardwareWalletId,
            txType = PaymentType.SENT,
        )
        val existing = createOnchainActivity(
            id = "hw-transfer-txid",
            txId = "hw-transfer-txid",
            value = 7_000uL,
            fee = 222uL,
            timestamp = 3_900uL,
            confirmed = false,
            isTransfer = true,
            channelId = "channel-id",
            walletId = hardwareWalletId,
            txType = PaymentType.SENT,
        )
        val expected = Activity.Onchain(
            incoming.v1.copy(
                isTransfer = true,
                channelId = "channel-id",
            )
        )
        whenever {
            coreService.activity.getOnchainActivityByTxId("hw-transfer-txid", hardwareWalletId)
        }.thenReturn(existing.v1)
        whenever { coreService.activity.upsertList(listOf(expected)) }.thenReturn(Unit)

        val result = sut.persistHardware(listOf(incoming), emptyList())

        assertTrue(result.isSuccess)
        verify(coreService.activity).upsertList(listOf(expected))
    }

    @Test
    fun `persistHardware does nothing when both lists are empty`() = test {
        val result = sut.persistHardware(emptyList(), emptyList())

        assertTrue(result.isSuccess)
        verify(coreService.activity, never()).upsertList(any())
        verify(coreService.activity, never()).upsertTransactionDetailsList(any())
    }

    @Test
    fun `deleteForWallet delegates to core delete by wallet id`() = test {
        whenever(coreService.activity.deleteByWalletId(hardwareWalletId)).thenReturn(3u)

        val result = sut.deleteForWallet(hardwareWalletId)

        assertTrue(result.isSuccess)
        verify(coreService.activity).deleteByWalletId(hardwareWalletId)
    }

    @Test
    fun `getActivity returns null when not found`() = test {
        val activityId = "activity123"
        whenever(coreService.activity.getActivity(activityId, null)).thenReturn(null)

        val result = sut.getActivity(activityId)

        assertTrue(result.isSuccess)
        assertNull(result.getOrThrow())
        verify(coreService.activity, never()).get(walletId = null)
    }

    @Test
    fun `contactActivities filters replaced sent transaction`() = test {
        val contactPublicKey = "pubky3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg"
        val replacedTxId = "replaced_tx_id"
        val replacedActivity = createOnchainActivity(
            id = "replaced_activity_id",
            txId = replacedTxId,
            doesExist = false,
            contact = contactPublicKey,
        )
        val replacementActivity = createOnchainActivity(
            id = "replacement_activity_id",
            txId = "replacement_tx_id",
            boostTxIds = listOf(replacedTxId),
            contact = contactPublicKey,
        )
        whenever(coreService.activity.getTxIdsInBoostTxIds()).thenReturn(setOf(replacedTxId))
        whenever(
            coreService.activity.get(
                walletId = ActivityWalletType.BITKIT.id(),
                filter = ActivityFilter.ALL,
                txType = null,
                tags = null,
                search = null,
                minDate = null,
                maxDate = null,
                limit = null,
                sortDirection = SortDirection.DESC,
            )
        ).thenReturn(listOf(replacedActivity, replacementActivity))

        val result = sut.contactActivities(contactPublicKey)

        assertEquals(listOf(replacementActivity), result.getOrThrow())
    }

    @Test
    fun `setContact propagates contact to replacement transaction`() = test {
        val contactPublicKey = "pubky3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg"
        val replacedTxId = "replaced_tx_id"
        val replacedActivity = createOnchainActivity(
            id = "replaced_activity_id",
            txId = replacedTxId,
            doesExist = false,
        )
        val replacementActivity = createOnchainActivity(
            id = "replacement_activity_id",
            txId = "replacement_tx_id",
            boostTxIds = listOf(replacedTxId),
        )
        whenever(coreService.activity.getActivity(replacedTxId)).thenReturn(null)
        whenever(coreService.activity.getOnchainActivityByTxId(replacedTxId)).thenReturn(replacedActivity.v1)
        whenever(
            coreService.activity.get(
                walletId = ActivityWalletType.BITKIT.id(),
                filter = ActivityFilter.ONCHAIN,
                txType = null,
                tags = null,
                search = null,
                minDate = null,
                maxDate = null,
                limit = null,
                sortDirection = null,
            )
        ).thenReturn(listOf(replacedActivity, replacementActivity))

        val result = sut.setContact(
            contactPublicKey = contactPublicKey,
            forPaymentId = replacedTxId,
            syncLdkPayments = false,
        )

        assertTrue(result.isSuccess)
        verify(coreService.activity).update(
            eq(replacedActivity.v1.id),
            argThat {
                this is Activity.Onchain && v1.contact == contactPublicKey
            },
        )
        verify(coreService.activity).update(
            eq(replacementActivity.v1.id),
            argThat {
                this is Activity.Onchain && v1.contact == contactPublicKey
            },
        )
    }

    @Test
    fun `updateActivity updates successfully when not deleted`() = test {
        val activityId = "activity123"
        val cacheData = AppCacheData(deletedActivities = emptyList())
        whenever(cacheStore.data).thenReturn(flowOf(cacheData))
        wheneverBlocking { coreService.activity.update(activityId, testActivity) }.thenReturn(Unit)

        val result = sut.updateActivity(activityId, testActivity)

        assertTrue(result.isSuccess)
        verify(coreService.activity).update(activityId, testActivity)
    }

    @Test
    fun `updateActivity fails when activity is deleted and forceUpdate is false`() = test {
        val activityId = "activity123"
        val cacheData = AppCacheData(deletedActivities = listOf(activityId))
        whenever(cacheStore.data).thenReturn(flowOf(cacheData))

        val result = sut.updateActivity(activityId, testActivity, forceUpdate = false)

        assertTrue(result.isFailure)
        verify(coreService.activity, never()).update(any(), any())
    }

    @Test
    fun `updateActivity succeeds when activity is deleted but forceUpdate is true`() = test {
        val activityId = "activity123"
        val cacheData = AppCacheData(deletedActivities = listOf(activityId))
        whenever(cacheStore.data).thenReturn(flowOf(cacheData))
        wheneverBlocking { coreService.activity.update(activityId, testActivity) }.thenReturn(Unit)

        val result = sut.updateActivity(activityId, testActivity, forceUpdate = true)

        assertTrue(result.isSuccess)
        verify(coreService.activity).update(activityId, testActivity)
    }

    @Test
    fun `replaceActivity updates activity and copies tags`() = test {
        val activityId = "activity123"
        val activityToDeleteId = "activity456"
        val tagsMock = listOf("tag1", "tag2")
        val cacheData = AppCacheData(deletedActivities = emptyList())
        whenever(cacheStore.data).thenReturn(flowOf(cacheData))

        // Mock update for the new activity
        wheneverBlocking { coreService.activity.update(activityId, testActivity) }.thenReturn(Unit)
        // Mock tags retrieval from the old activity
        wheneverBlocking { coreService.activity.tags(activityToDeleteId) }.thenReturn(tagsMock)
        // Mock tags retrieval from the new activity (should be empty so all tags are considered new)
        wheneverBlocking { coreService.activity.tags(activityId) }.thenReturn(emptyList())
        // Mock appendTags to add tags to the new activity
        wheneverBlocking { coreService.activity.appendTags(activityId, tagsMock) }.thenReturn(Result.success(Unit))

        val result = sut.replaceActivity(activityId, activityToDeleteId, testActivity)

        assertTrue(result.isSuccess)
        // Verify the new activity is updated
        verify(coreService.activity).update(activityId, testActivity)
        // Verify tags are retrieved from the old activity
        verify(coreService.activity).tags(activityToDeleteId)
        // Verify tags are added to the new activity
        verify(coreService.activity).appendTags(activityId, tagsMock)
        // Verify delete is NOT called
        verify(coreService.activity, never()).delete(any(), anyOrNull())
        // Verify addActivityToDeletedList is NOT called
        verify(cacheStore, never()).addActivityToDeletedList(any())
    }

    @Test
    fun `deleteActivity deletes successfully`() = test {
        val activityId = "activity123"
        wheneverBlocking { coreService.activity.delete(activityId) }.thenReturn(true)
        wheneverBlocking { cacheStore.addActivityToDeletedList(activityId) }.thenReturn(Unit)

        val result = sut.deleteActivity(activityId)

        assertTrue(result.isSuccess)
        verify(coreService.activity).delete(activityId)
        verify(cacheStore).addActivityToDeletedList(activityId)
    }

    @Test
    fun `deleteActivity fails when service returns false`() = test {
        val activityId = "activity123"
        wheneverBlocking { coreService.activity.delete(activityId) }.thenReturn(false)

        val result = sut.deleteActivity(activityId)

        assertTrue(result.isFailure)
        verify(cacheStore, never()).addActivityToDeletedList(any())
    }

    @Test
    fun `insertActivity inserts successfully when not deleted`() = test {
        val cacheData = AppCacheData(deletedActivities = emptyList())
        whenever(cacheStore.data).thenReturn(flowOf(cacheData))
        wheneverBlocking { coreService.activity.insert(testActivity) }.thenReturn(Unit)

        val result = sut.insertActivity(testActivity)

        assertTrue(result.isSuccess)
        verify(coreService.activity).insert(testActivity)
    }

    @Test
    fun `insertActivity fails when activity is deleted`() = test {
        val cacheData = AppCacheData(deletedActivities = listOf("activity1"))
        whenever(cacheStore.data).thenReturn(flowOf(cacheData))

        val result = sut.insertActivity(testActivity)

        assertTrue(result.isFailure)
        verify(coreService.activity, never()).insert(any())
    }

    @Test
    fun `insertActivityFromCjit returns true when newly inserted`() = test {
        val channel = createChannelDetails()
        val id = channel.fundingTxo?.txid.orEmpty()
        wheneverBlocking { coreService.activity.getActivity(id) }.thenReturn(null)
        wheneverBlocking { coreService.activity.insert(any()) }.thenReturn(Unit)

        val result = sut.insertActivityFromCjit(cjitEntry = IcJitEntry.mock(), channel = channel)

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow())
        verify(coreService.activity).insert(any())
    }

    @Test
    fun `insertActivityFromCjit returns false when activity already exists`() = test {
        val channel = createChannelDetails()
        val id = channel.fundingTxo?.txid.orEmpty()
        wheneverBlocking { coreService.activity.getActivity(id) }.thenReturn(testActivity)

        val result = sut.insertActivityFromCjit(cjitEntry = IcJitEntry.mock(), channel = channel)

        assertTrue(result.isSuccess)
        assertFalse(result.getOrThrow())
        verify(coreService.activity, never()).insert(any())
    }

    @Test
    fun `insertActivityFromCjit fails when cjitEntry is null`() = test {
        val channel = createChannelDetails()

        val result = sut.insertActivityFromCjit(cjitEntry = null, channel = channel)

        assertTrue(result.isFailure)
        verify(coreService.activity, never()).insert(any())
    }

    @Test
    fun `addTagsToActivity adds new tags successfully`() = test {
        val activityId = "activity123"
        val existingTags = listOf("tag1", "tag2")
        val newTags = listOf("tag2", "tag3", "tag4", "") // tag2 exists, empty string should be filtered
        val expectedNewTags = listOf("tag3", "tag4")

        wheneverBlocking { coreService.activity.tags(activityId) }.thenReturn(existingTags)
        wheneverBlocking {
            coreService.activity.appendTags(
                activityId,
                expectedNewTags
            )
        }.thenReturn(Result.success(Unit))

        val result = sut.addTagsToActivity(activityId, newTags)

        assertTrue(result.isSuccess)
        verify(coreService.activity).appendTags(activityId, expectedNewTags)
    }

    @Test
    fun `addTagsToActivity fails when tags lookup fails`() = test {
        val activityId = "activity123"
        whenever(coreService.activity.tags(activityId)) doThrow AppError("tags failed")

        val result = sut.addTagsToActivity(activityId, listOf("tag1"))

        assertTrue(result.isFailure)
        verify(coreService.activity, never()).appendTags(any(), any(), anyOrNull())
    }

    @Test
    fun `addTagsToActivity does nothing when no new tags`() = test {
        val activityId = "activity123"
        val existingTags = listOf("tag1", "tag2")
        val duplicateTags = listOf("tag1", "tag2", "")

        wheneverBlocking { coreService.activity.tags(activityId) }.thenReturn(existingTags)

        val result = sut.addTagsToActivity(activityId, duplicateTags)

        assertTrue(result.isSuccess)
        verify(coreService.activity, never()).appendTags(any(), any(), anyOrNull())
    }

    @Test
    fun `attachTagsToActivity should fail with empty tags`() = test {
        val result = sut.addTagsToTransaction(
            paymentHashOrTxId = "txId",
            type = ActivityFilter.ALL,
            txType = PaymentType.SENT,
            tags = emptyList()
        )

        assertTrue(result.isFailure)
    }

    @Test
    fun `attachTagsToActivity should fail with empty paymentHashOrTxId`() = test {
        val result = sut.addTagsToTransaction(
            paymentHashOrTxId = "",
            type = ActivityFilter.ALL,
            txType = PaymentType.SENT,
            tags = listOf("tag1")
        )

        assertTrue(result.isFailure)
    }

    @Test
    fun `removeTagsFromActivity removes tags successfully`() = test {
        val activityId = "activity123"
        val tagsToRemove = listOf("tag1", "tag2")

        wheneverBlocking { coreService.activity.dropTags(activityId, tagsToRemove) }.thenReturn(Unit)

        val result = sut.removeTagsFromActivity(activityId, tagsToRemove)

        assertTrue(result.isSuccess)
        verify(coreService.activity).dropTags(activityId, tagsToRemove)
    }

    @Test
    fun `removeTagsFromActivity fails when dropTags fails`() = test {
        val activityId = "activity123"
        val tags = listOf("tag1")
        whenever(coreService.activity.dropTags(activityId, tags)) doThrow AppError("drop failed")

        val result = sut.removeTagsFromActivity(activityId, tags)

        assertTrue(result.isFailure)
    }

    @Test
    fun `getActivityTags returns tags successfully`() = test {
        val activityId = "activity123"
        val tags = listOf("tag1", "tag2", "tag3")
        wheneverBlocking { coreService.activity.tags(activityId) }.thenReturn(tags)

        val result = sut.getActivityTags(activityId)

        assertTrue(result.isSuccess)
        assertEquals(tags, result.getOrThrow())
    }

    @Test
    fun `getAllAvailableTags returns all tags successfully`() = test {
        val allTags = listOf("tag1", "tag2", "tag3", "tag4")
        wheneverBlocking { coreService.activity.allPossibleTags() }.thenReturn(allTags)

        val result = sut.getAllAvailableTags()

        assertTrue(result.isSuccess)
        assertEquals(allTags, result.getOrThrow())
    }

    @Test
    fun `removeAllActivities removes all activities successfully`() = test {
        wheneverBlocking { coreService.activity.removeAll() }.thenReturn(Unit)

        val result = sut.removeAllActivities()

        assertTrue(result.isSuccess)
        verify(coreService.activity).removeAll()
    }

    @Test
    fun `generateTestData generates with validated count`() = test {
        wheneverBlocking { coreService.activity.generateRandomTestData(any()) }.thenReturn(Unit)

        val result = sut.generateTestData(50)

        assertTrue(result.isSuccess)
        verify(coreService.activity).generateRandomTestData(50)
    }

    @Test
    fun `generateTestData coerces count to valid range`() = test {
        wheneverBlocking { coreService.activity.generateRandomTestData(any()) }.thenReturn(Unit)

        val result = sut.generateTestData(1500) // Over limit

        assertTrue(result.isSuccess)
        verify(coreService.activity).generateRandomTestData(1000) // Should be coerced to max
    }

    @Test
    fun `addActivityToPendingBoost adds to cache`() = test {
        val pendingBoost = PendingBoostActivity(
            txId = "tx123",
            updatedAt = 2000u,
            activityToDelete = null
        )
        wheneverBlocking { cacheStore.addActivityToPendingBoost(pendingBoost) }.thenReturn(Unit)

        sut.addActivityToPendingBoost(pendingBoost)

        verify(cacheStore).addActivityToPendingBoost(pendingBoost)
    }

    @Test
    fun `boostPendingActivities adds parentTxId to boostTxIds when parentTxId is provided`() = test {
        val txId = "tx123"
        val parentTxId = "parentTx456"
        val activityId = "activity123"
        val updatedAt = 2000uL

        val existingActivity = createOnchainActivity(
            id = activityId,
            txId = txId,
            updatedAt = 1000uL
        )

        val pendingBoost = PendingBoostActivity(
            txId = txId,
            updatedAt = updatedAt,
            activityToDelete = null,
            parentTxId = parentTxId
        )

        val cacheData = AppCacheData(pendingBoostActivities = listOf(pendingBoost))
        setupSyncActivitiesMocks(cacheData)
        wheneverBlocking {
            coreService.activity.get(
                walletId = anyOrNull(),
                filter = eq(ActivityFilter.ONCHAIN),
                txType = eq(PaymentType.SENT),
                tags = anyOrNull(),
                search = anyOrNull(),
                minDate = anyOrNull(),
                maxDate = anyOrNull(),
                limit = eq(10u),
                sortDirection = anyOrNull()
            )
        }.thenReturn(listOf(existingActivity))
        wheneverBlocking { coreService.activity.update(eq(activityId), any()) }.thenReturn(Unit)
        wheneverBlocking { cacheStore.removeActivityFromPendingBoost(pendingBoost) }.thenReturn(Unit)

        val result = sut.syncActivities()

        assertTrue(result.isSuccess)
        // Verify update was called with parentTxId added to empty boostTxIds
        verify(coreService.activity).update(
            eq(activityId),
            argThat { activity ->
                activity is Activity.Onchain && activity.v1.boostTxIds == listOf(parentTxId)
            }
        )
        verify(cacheStore).removeActivityFromPendingBoost(pendingBoost)
    }

    @Test
    fun `boostPendingActivities preserves existing boostTxIds when adding parentTxId`() = test {
        val txId = "tx123"
        val parentTxId = "parentTx456"
        val existingBoostTxId = "existingBoost123"
        val activityId = "activity123"
        val updatedAt = 2000uL

        val existingActivity = createOnchainActivity(
            id = activityId,
            txId = txId,
            boostTxIds = listOf(existingBoostTxId),
            updatedAt = 1000uL
        )

        val pendingBoost = PendingBoostActivity(
            txId = txId,
            updatedAt = updatedAt,
            activityToDelete = null,
            parentTxId = parentTxId
        )

        val cacheData = AppCacheData(pendingBoostActivities = listOf(pendingBoost))
        setupSyncActivitiesMocks(cacheData)
        wheneverBlocking {
            coreService.activity.get(
                walletId = anyOrNull(),
                filter = eq(ActivityFilter.ONCHAIN),
                txType = eq(PaymentType.SENT),
                tags = anyOrNull(),
                search = anyOrNull(),
                minDate = anyOrNull(),
                maxDate = anyOrNull(),
                limit = eq(10u),
                sortDirection = anyOrNull()
            )
        }.thenReturn(listOf(existingActivity))
        wheneverBlocking { coreService.activity.update(eq(activityId), any()) }.thenReturn(Unit)
        wheneverBlocking { cacheStore.removeActivityFromPendingBoost(pendingBoost) }.thenReturn(Unit)

        val result = sut.syncActivities()

        assertTrue(result.isSuccess)
        // Verify update was called with both existing and new parentTxId in boostTxIds
        verify(coreService.activity).update(
            eq(activityId),
            argThat { activity ->
                activity is Activity.Onchain &&
                    activity.v1.boostTxIds.contains(existingBoostTxId) &&
                    activity.v1.boostTxIds.contains(parentTxId)
            }
        )
    }

    @Test
    fun `boostPendingActivities does not add parentTxId when parentTxId is null`() = test {
        val txId = "tx123"
        val existingBoostTxId = "existingBoost123"
        val activityId = "activity123"
        val updatedAt = 2000uL

        val existingActivity = createOnchainActivity(
            id = activityId,
            txId = txId,
            boostTxIds = listOf(existingBoostTxId),
            updatedAt = 1000uL
        )

        val pendingBoost = PendingBoostActivity(
            txId = txId,
            updatedAt = updatedAt,
            activityToDelete = null,
            parentTxId = null
        )

        val cacheData = AppCacheData(pendingBoostActivities = listOf(pendingBoost))
        setupSyncActivitiesMocks(cacheData)
        wheneverBlocking {
            coreService.activity.get(
                walletId = anyOrNull(),
                filter = eq(ActivityFilter.ONCHAIN),
                txType = eq(PaymentType.SENT),
                tags = anyOrNull(),
                search = anyOrNull(),
                minDate = anyOrNull(),
                maxDate = anyOrNull(),
                limit = eq(10u),
                sortDirection = anyOrNull()
            )
        }.thenReturn(listOf(existingActivity))
        wheneverBlocking { coreService.activity.update(eq(activityId), any()) }.thenReturn(Unit)
        wheneverBlocking { cacheStore.removeActivityFromPendingBoost(pendingBoost) }.thenReturn(Unit)

        val result = sut.syncActivities()

        assertTrue(result.isSuccess)
        // Verify update was called with only existing boostTxIds (no new parentTxId added)
        verify(coreService.activity).update(
            eq(activityId),
            argThat { activity ->
                activity is Activity.Onchain &&
                    activity.v1.boostTxIds == listOf(existingBoostTxId)
            }
        )
    }

    @Test
    fun `boostPendingActivities calls replaceActivity when activityToDelete is provided`() = test {
        val txId = "tx123"
        val parentTxId = "parentTx456"
        val activityId = "activity123"
        val activityToDeleteId = "activity456"
        val updatedAt = 2000uL

        val existingActivity = createOnchainActivity(
            id = activityId,
            txId = txId,
            updatedAt = 1000uL
        )

        val pendingBoost = PendingBoostActivity(
            txId = txId,
            updatedAt = updatedAt,
            activityToDelete = activityToDeleteId,
            parentTxId = parentTxId
        )

        val cacheData = AppCacheData(pendingBoostActivities = listOf(pendingBoost))
        setupSyncActivitiesMocks(cacheData)
        wheneverBlocking {
            coreService.activity.get(
                walletId = anyOrNull(),
                filter = eq(ActivityFilter.ONCHAIN),
                txType = eq(PaymentType.SENT),
                tags = anyOrNull(),
                search = anyOrNull(),
                minDate = anyOrNull(),
                maxDate = anyOrNull(),
                limit = eq(10u),
                sortDirection = anyOrNull()
            )
        }.thenReturn(listOf(existingActivity))
        val tagsToCopy = listOf("tag1", "tag2")
        wheneverBlocking { coreService.activity.update(eq(activityId), any()) }.thenReturn(Unit)
        wheneverBlocking { coreService.activity.getActivity(activityId) }.thenReturn(existingActivity)
        wheneverBlocking { coreService.activity.tags(activityToDeleteId) }.thenReturn(tagsToCopy)
        wheneverBlocking { coreService.activity.tags(activityId) }.thenReturn(emptyList())
        wheneverBlocking { coreService.activity.appendTags(activityId, tagsToCopy) }.thenReturn(Result.success(Unit))
        wheneverBlocking { cacheStore.removeActivityFromPendingBoost(pendingBoost) }.thenReturn(Unit)

        val result = sut.syncActivities()

        assertTrue(result.isSuccess)
        // Verify replaceActivity was called (indirectly by checking the new activity was updated)
        verify(coreService.activity).update(eq(activityId), any())
        // Verify tags were copied from old activity to new activity
        verify(coreService.activity).tags(activityToDeleteId)
        verify(coreService.activity).appendTags(activityId, tagsToCopy)
        verify(cacheStore).removeActivityFromPendingBoost(pendingBoost)
    }

    @Test
    fun `boostPendingActivities skips when activity updatedAt is newer than pendingBoost updatedAt`() = test {
        val txId = "tx123"
        val activityId = "activity123"
        val updatedAt = 2000uL

        val existingActivity = createOnchainActivity(
            id = activityId,
            txId = txId,
            updatedAt = 3000uL // Newer than pendingBoost.updatedAt
        )

        val pendingBoost = PendingBoostActivity(
            txId = txId,
            updatedAt = updatedAt,
            activityToDelete = null,
            parentTxId = null
        )

        val cacheData = AppCacheData(pendingBoostActivities = listOf(pendingBoost))
        setupSyncActivitiesMocks(cacheData)
        wheneverBlocking {
            coreService.activity.get(
                walletId = anyOrNull(),
                filter = eq(ActivityFilter.ONCHAIN),
                txType = eq(PaymentType.SENT),
                tags = anyOrNull(),
                search = anyOrNull(),
                minDate = anyOrNull(),
                maxDate = anyOrNull(),
                limit = eq(10u),
                sortDirection = anyOrNull()
            )
        }.thenReturn(listOf(existingActivity))
        wheneverBlocking { cacheStore.removeActivityFromPendingBoost(pendingBoost) }.thenReturn(Unit)

        val result = sut.syncActivities()

        assertTrue(result.isSuccess)
        // Verify update was NOT called (activity is newer)
        verify(coreService.activity, never()).update(eq(activityId), any())
        // Verify pending boost was removed (skipped)
        verify(cacheStore).removeActivityFromPendingBoost(pendingBoost)
    }
}
