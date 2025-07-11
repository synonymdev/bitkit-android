package to.bitkit.repositories

import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.ActivityFilter
import com.synonym.bitkitcore.LightningActivity
import com.synonym.bitkitcore.OnchainActivity
import com.synonym.bitkitcore.PaymentType
import com.synonym.bitkitcore.SortDirection
import kotlinx.coroutines.flow.flowOf
import org.junit.Before
import org.junit.Test
import org.lightningdevkit.ldknode.PaymentDetails
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.kotlin.wheneverBlocking
import to.bitkit.data.AppCacheData
import to.bitkit.data.CacheStore
import to.bitkit.data.dto.PendingBoostActivity
import to.bitkit.ext.matchesPaymentId
import to.bitkit.services.CoreService
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ActivityRepoTest : BaseUnitTest() {

    private val coreService: CoreService = mock()
    private val lightningRepo: LightningRepo = mock()
    private val cacheStore: CacheStore = mock()

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

    private val testOnChainActivityV1 = mock<OnchainActivity> {
        on { id } doReturn "onchain1"
        on { updatedAt } doReturn 1000u
        on { isBoosted } doReturn false
        on { feeRate } doReturn 10u
        on { fee } doReturn 1000u
    }

    private val testOnChainActivity = mock<Activity.Onchain> {
        on { v1 } doReturn testOnChainActivityV1
    }

    @Before
    fun setUp() {
        whenever(cacheStore.data).thenReturn(flowOf(AppCacheData()))
        whenever(coreService.activity).thenReturn(mock())

        sut = ActivityRepo(
            bgDispatcher = testDispatcher,
            coreService = coreService,
            lightningRepo = lightningRepo,
            cacheStore = cacheStore,
        )
    }

    @Test
    fun `syncActivities success flow`() = test {
        val payments = listOf(testPaymentDetails)
        wheneverBlocking { lightningRepo.getPayments() }.thenReturn(Result.success(payments))
        wheneverBlocking { coreService.activity.getActivity(any()) }.thenReturn(null)
        wheneverBlocking { coreService.activity.syncLdkNodePayments(any(), forceUpdate = eq(false)) }.thenReturn(Unit)

        val result = sut.syncActivities()

        assertTrue(result.isSuccess)
        verify(lightningRepo).getPayments()
        verify(coreService.activity).syncLdkNodePayments(payments)
        assertFalse(sut.isSyncingLdkNodePayments)
    }

    @Test
    fun `syncActivities skips when already syncing`() = test {
        sut.isSyncingLdkNodePayments = true

        val result = sut.syncActivities()

        assertTrue(result.isFailure)
        verify(lightningRepo, never()).getPayments()
    }

    @Test
    fun `syncActivities handles lightningRepo failure`() = test {
        val exception = Exception("Lightning repo failed")
        wheneverBlocking { lightningRepo.getPayments() }.thenReturn(Result.failure(exception))

        val result = sut.syncActivities()

        assertTrue(result.isFailure)
        assertEquals(exception, result.exceptionOrNull())
        assertFalse(sut.isSyncingLdkNodePayments)
    }

    @Test
    fun `syncActivities counts added and updated activities correctly`() = test {
        val payments = listOf(testPaymentDetails, mock<PaymentDetails> { on { id } doReturn "payment2" })
        wheneverBlocking { lightningRepo.getPayments() }.thenReturn(Result.success(payments))
        wheneverBlocking { coreService.activity.getActivity("payment1") }.thenReturn(testActivity) // existing
        wheneverBlocking { coreService.activity.getActivity("payment2") }.thenReturn(null) // new
        wheneverBlocking { coreService.activity.syncLdkNodePayments(any()) }.thenReturn(Unit)

        val result = sut.syncActivities()

        assertTrue(result.isSuccess)
        verify(coreService.activity, times(2)).syncLdkNodePayments(any())
    }

    @Test
    fun `findActivityByPaymentId returns activity when found immediately`() = test {
        val paymentId = "payment123"
        wheneverBlocking {
            coreService.activity.get(
                filter = ActivityFilter.LIGHTNING,
                txType = PaymentType.RECEIVED,
                tags = null,
                search = null,
                minDate = null,
                maxDate = null,
                limit = 10u,
                sortDirection = null
            )
        }.thenReturn(listOf(testActivity))

        whenever(testActivity.matchesPaymentId(paymentId)).thenReturn(true)

        val result = sut.findActivityByPaymentId(
            paymentHashOrTxId = paymentId,
            type = ActivityFilter.LIGHTNING,
            txType = PaymentType.RECEIVED
        )

        assertTrue(result.isSuccess)
        assertEquals(testActivity, result.getOrThrow())
    }

    @Test
    fun `findActivityByPaymentId syncs and retries when not found initially`() = test {
        val paymentId = "payment123"

        // First call returns empty, second call after sync returns activity
        wheneverBlocking {
            coreService.activity.get(
                filter = ActivityFilter.LIGHTNING,
                txType = PaymentType.RECEIVED,
                tags = null,
                search = null,
                minDate = null,
                maxDate = null,
                limit = 10u,
                sortDirection = null
            )
        }.thenReturn(emptyList()).thenReturn(listOf(testActivity))

        whenever(testActivity.matchesPaymentId(paymentId)).thenReturn(true)
        wheneverBlocking { lightningRepo.sync() }.thenReturn(Result.success(Unit))
        wheneverBlocking { lightningRepo.getPayments() }.thenReturn(Result.success(emptyList()))

        val result = sut.findActivityByPaymentId(
            paymentHashOrTxId = paymentId,
            type = ActivityFilter.LIGHTNING,
            txType = PaymentType.RECEIVED
        )

        assertTrue(result.isSuccess)
        assertEquals(testActivity, result.getOrThrow())
        verify(lightningRepo).sync()
    }

    @Test
    fun `findActivityByPaymentId returns failure when activity not found after sync`() = test {
        val paymentId = "payment123"

        wheneverBlocking {
            coreService.activity.get(any(), any(), any(), any(), any(), any(), any(), any())
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
    fun `getActivities handles service failure`() = test {
        val exception = Exception("Service failed")
        wheneverBlocking { coreService.activity.get(any(), any(), any(), any(), any(), any(), any(), any()) }
            .thenThrow(exception)

        val result = sut.getActivities()

        assertTrue(result.isFailure)
        assertEquals(exception, result.exceptionOrNull())
    }

    @Test
    fun `getActivity returns activity when found`() = test {
        val activityId = "activity123"
        wheneverBlocking { coreService.activity.getActivity(activityId) }.thenReturn(testActivity)

        val result = sut.getActivity(activityId)

        assertTrue(result.isSuccess)
        assertEquals(testActivity, result.getOrThrow())
    }

    @Test
    fun `getActivity returns null when not found`() = test {
        val activityId = "activity123"
        wheneverBlocking { coreService.activity.getActivity(activityId) }.thenReturn(null)

        val result = sut.getActivity(activityId)

        assertTrue(result.isSuccess)
        assertNull(result.getOrThrow())
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
    fun `replaceActivity updates and deletes successfully`() = test {
        val activityId = "activity123"
        val activityToDeleteId = "activity456"
        val cacheData = AppCacheData(deletedActivities = emptyList())
        whenever(cacheStore.data).thenReturn(flowOf(cacheData))

        wheneverBlocking { coreService.activity.update(activityId, testActivity) }.thenReturn(Unit)
        wheneverBlocking { coreService.activity.delete(activityToDeleteId) }.thenReturn(true)
        wheneverBlocking { cacheStore.addActivityToDeletedList(activityToDeleteId) }.thenReturn(Unit)

        val result = sut.replaceActivity(activityId, activityToDeleteId, testActivity)

        assertTrue(result.isSuccess)
        verify(coreService.activity).update(activityId, testActivity)
        verify(coreService.activity).delete(activityToDeleteId)
    }

    @Test
    fun `replaceActivity caches deletion when delete fails`() = test {
        val activityId = "activity123"
        val activityToDeleteId = "activity456"
        val cacheData = AppCacheData(deletedActivities = emptyList())
        whenever(cacheStore.data).thenReturn(flowOf(cacheData))

        wheneverBlocking { coreService.activity.update(activityId, testActivity) }.thenReturn(Unit)
        wheneverBlocking { coreService.activity.delete(activityToDeleteId) }.thenThrow(Exception("Delete failed"))

        val result = sut.replaceActivity(activityId, activityToDeleteId, testActivity)

        assertTrue(result.isSuccess)
        verify(cacheStore).addActivityToPendingDelete(activityToDeleteId)
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
    fun `addTagsToActivity adds new tags successfully`() = test {
        val activityId = "activity123"
        val existingTags = listOf("tag1", "tag2")
        val newTags = listOf("tag2", "tag3", "tag4", "") // tag2 exists, empty string should be filtered
        val expectedNewTags = listOf("tag3", "tag4")

        wheneverBlocking { coreService.activity.getActivity(activityId) }.thenReturn(testActivity)
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
    fun `addTagsToActivity fails when activity not found`() = test {
        val activityId = "activity123"
        wheneverBlocking { coreService.activity.getActivity(activityId) }.thenReturn(null)

        val result = sut.addTagsToActivity(activityId, listOf("tag1"))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `addTagsToActivity does nothing when no new tags`() = test {
        val activityId = "activity123"
        val existingTags = listOf("tag1", "tag2")
        val duplicateTags = listOf("tag1", "tag2", "")

        wheneverBlocking { coreService.activity.getActivity(activityId) }.thenReturn(testActivity)
        wheneverBlocking { coreService.activity.tags(activityId) }.thenReturn(existingTags)

        val result = sut.addTagsToActivity(activityId, duplicateTags)

        assertTrue(result.isSuccess)
        verify(coreService.activity, never()).appendTags(any(), any())
    }

    @Test
    fun `removeTagsFromActivity removes tags successfully`() = test {
        val activityId = "activity123"
        val tagsToRemove = listOf("tag1", "tag2")

        wheneverBlocking { coreService.activity.getActivity(activityId) }.thenReturn(testActivity)
        wheneverBlocking { coreService.activity.dropTags(activityId, tagsToRemove) }.thenReturn(Unit)

        val result = sut.removeTagsFromActivity(activityId, tagsToRemove)

        assertTrue(result.isSuccess)
        verify(coreService.activity).dropTags(activityId, tagsToRemove)
    }

    @Test
    fun `removeTagsFromActivity fails when activity not found`() = test {
        val activityId = "activity123"
        wheneverBlocking { coreService.activity.getActivity(activityId) }.thenReturn(null)

        val result = sut.removeTagsFromActivity(activityId, listOf("tag1"))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
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
    fun `boostPendingActivities processes pending boosts correctly`() = test {
        val pendingBoost = PendingBoostActivity(
            txId = "tx123",
            feeRate = 20u,
            fee = 2000u,
            updatedAt = 2000u,
            activityToDelete = null
        )
        val cacheData = AppCacheData(pendingBoostActivities = listOf(pendingBoost))
        whenever(cacheStore.data).thenReturn(flowOf(cacheData))

        wheneverBlocking {
            coreService.activity.get(
                filter = ActivityFilter.ONCHAIN,
                txType = PaymentType.SENT,
                tags = null,
                search = null,
                minDate = null,
                maxDate = null,
                limit = 10u,
                sortDirection = null
            )
        }.thenReturn(listOf(testOnChainActivity))

        whenever(testOnChainActivity.matchesPaymentId("tx123")).thenReturn(true)
        wheneverBlocking { coreService.activity.update(any(), any()) }.thenReturn(Unit)
        wheneverBlocking { cacheStore.removeActivityFromPendingBoost(pendingBoost) }.thenReturn(Unit)

        sut.syncActivities()

        verify(cacheStore).removeActivityFromPendingBoost(pendingBoost)
    }

    @Test
    fun `addActivityToPendingBoost adds to cache`() = test {
        val pendingBoost = PendingBoostActivity(
            txId = "tx123",
            feeRate = 20u,
            fee = 2000u,
            updatedAt = 2000u,
            activityToDelete = null
        )
        wheneverBlocking { cacheStore.addActivityToPendingBoost(pendingBoost) }.thenReturn(Unit)

        sut.addActivityToPendingBoost(pendingBoost)

        verify(cacheStore).addActivityToPendingBoost(pendingBoost)
    }
}
