package to.bitkit.repositories

import app.cash.turbine.test
import com.synonym.bitkitcore.PreActivityMetadata
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.kotlin.wheneverBlocking
import to.bitkit.services.ActivityService
import to.bitkit.services.CoreService
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class PreActivityMetadataRepoTest : BaseUnitTest() {

    private val coreService = mock<CoreService>()
    private val activityService = mock<ActivityService>()
    private val clock = mock<Clock>()

    private lateinit var sut: PreActivityMetadataRepo

    private val testTimestamp = Instant.parse("2025-01-01T00:00:00Z")
    private var timestampCounter = 0L

    private val testMetadata = PreActivityMetadata(
        paymentId = "payment-123",
        createdAt = 1234567890uL,
        tags = listOf("tag1", "tag2"),
        paymentHash = "hash-123",
        txId = "tx-123",
        address = "bc1qtest",
        isReceive = false,
        feeRate = 10u,
        isTransfer = false,
        channelId = "channel-123"
    )

    @Before
    fun setUp() {
        timestampCounter = 0L
        whenever(coreService.activity).thenReturn(activityService)
        // Return incrementing timestamps to ensure StateFlow emits new values
        whenever(clock.now()).thenAnswer {
            Instant.fromEpochMilliseconds(testTimestamp.toEpochMilliseconds() + (++timestampCounter))
        }

        sut = PreActivityMetadataRepo(
            ioDispatcher = testDispatcher,
            coreService = coreService,
            clock = clock,
        )
    }

    // region getAllPreActivityMetadata

    @Test
    fun `getAllPreActivityMetadata returns success with metadata list`() = test {
        val metadataList = listOf(testMetadata)
        wheneverBlocking { activityService.getAllPreActivityMetadata() }.thenReturn(metadataList)

        val result = sut.getAllPreActivityMetadata()

        assertTrue(result.isSuccess)
        assertEquals(metadataList, result.getOrNull())
        verify(activityService).getAllPreActivityMetadata()
    }

    @Test
    fun `getAllPreActivityMetadata returns empty list when no metadata exists`() = test {
        wheneverBlocking { activityService.getAllPreActivityMetadata() }.thenReturn(emptyList())

        val result = sut.getAllPreActivityMetadata()

        assertTrue(result.isSuccess)
        assertEquals(emptyList(), result.getOrNull())
    }

    @Test
    fun `getAllPreActivityMetadata returns failure on exception`() = test {
        val exception = RuntimeException("Database error")
        wheneverBlocking { activityService.getAllPreActivityMetadata() } doThrow exception

        val result = sut.getAllPreActivityMetadata()

        assertTrue(result.isFailure)
        assertEquals(exception, result.exceptionOrNull())
    }

    // endregion

    // region upsertPreActivityMetadata

    @Test
    fun `upsertPreActivityMetadata succeeds and notifies changed`() = test {
        val metadataList = listOf(testMetadata)
        wheneverBlocking { activityService.upsertPreActivityMetadata(metadataList) }.thenReturn(Unit)

        sut.preActivityMetadataChanged.test {
            val initialValue = awaitItem()

            val result = sut.upsertPreActivityMetadata(metadataList)

            assertTrue(result.isSuccess)
            verify(activityService).upsertPreActivityMetadata(metadataList)

            val updatedValue = awaitItem()
            assertTrue(updatedValue > initialValue, "Changed timestamp should be updated")
        }
    }

    @Test
    fun `upsertPreActivityMetadata with multiple items succeeds`() = test {
        val metadata1 = testMetadata.copy(paymentId = "payment-1")
        val metadata2 = testMetadata.copy(paymentId = "payment-2")
        val metadataList = listOf(metadata1, metadata2)
        wheneverBlocking { activityService.upsertPreActivityMetadata(metadataList) }.thenReturn(Unit)

        val result = sut.upsertPreActivityMetadata(metadataList)

        assertTrue(result.isSuccess)
        verify(activityService).upsertPreActivityMetadata(metadataList)
    }

    @Test
    fun `upsertPreActivityMetadata returns failure on exception`() = test {
        val metadataList = listOf(testMetadata)
        val exception = RuntimeException("Upsert failed")
        wheneverBlocking { activityService.upsertPreActivityMetadata(metadataList) } doThrow exception

        val result = sut.upsertPreActivityMetadata(metadataList)

        assertTrue(result.isFailure)
        assertEquals(exception, result.exceptionOrNull())
    }

    // endregion

    // region addPreActivityMetadataTags

    @Test
    fun `addPreActivityMetadataTags succeeds and notifies changed`() = test {
        val paymentId = "payment-123"
        val tags = listOf("shopping", "groceries")
        wheneverBlocking { activityService.addPreActivityMetadataTags(paymentId, tags) }.thenReturn(Unit)

        sut.preActivityMetadataChanged.test {
            val initialValue = awaitItem()

            val result = sut.addPreActivityMetadataTags(paymentId, tags)

            assertTrue(result.isSuccess)
            verify(activityService).addPreActivityMetadataTags(paymentId, tags)

            val updatedValue = awaitItem()
            assertTrue(updatedValue > initialValue, "Changed timestamp should be updated")
        }
    }

    @Test
    fun `addPreActivityMetadataTags with single tag succeeds`() = test {
        val paymentId = "payment-123"
        val tags = listOf("important")
        wheneverBlocking { activityService.addPreActivityMetadataTags(paymentId, tags) }.thenReturn(Unit)

        val result = sut.addPreActivityMetadataTags(paymentId, tags)

        assertTrue(result.isSuccess)
    }

    @Test
    fun `addPreActivityMetadataTags returns failure on exception`() = test {
        val paymentId = "payment-123"
        val tags = listOf("tag1")
        val exception = RuntimeException("Add tags failed")
        wheneverBlocking { activityService.addPreActivityMetadataTags(paymentId, tags) } doThrow exception

        val result = sut.addPreActivityMetadataTags(paymentId, tags)

        assertTrue(result.isFailure)
        assertEquals(exception, result.exceptionOrNull())
    }

    // endregion

    // region removePreActivityMetadataTags

    @Test
    fun `removePreActivityMetadataTags succeeds and notifies changed`() = test {
        val paymentId = "payment-123"
        val tags = listOf("tag1")
        wheneverBlocking { activityService.removePreActivityMetadataTags(paymentId, tags) }.thenReturn(Unit)

        sut.preActivityMetadataChanged.test {
            val initialValue = awaitItem()

            val result = sut.removePreActivityMetadataTags(paymentId, tags)

            assertTrue(result.isSuccess)
            verify(activityService).removePreActivityMetadataTags(paymentId, tags)

            val updatedValue = awaitItem()
            assertTrue(updatedValue > initialValue, "Changed timestamp should be updated")
        }
    }

    @Test
    fun `removePreActivityMetadataTags with multiple tags succeeds`() = test {
        val paymentId = "payment-123"
        val tags = listOf("tag1", "tag2", "tag3")
        wheneverBlocking { activityService.removePreActivityMetadataTags(paymentId, tags) }.thenReturn(Unit)

        val result = sut.removePreActivityMetadataTags(paymentId, tags)

        assertTrue(result.isSuccess)
        verify(activityService).removePreActivityMetadataTags(paymentId, tags)
    }

    @Test
    fun `removePreActivityMetadataTags returns failure on exception`() = test {
        val paymentId = "payment-123"
        val tags = listOf("tag1")
        val exception = RuntimeException("Remove tags failed")
        wheneverBlocking { activityService.removePreActivityMetadataTags(paymentId, tags) } doThrow exception

        val result = sut.removePreActivityMetadataTags(paymentId, tags)

        assertTrue(result.isFailure)
        assertEquals(exception, result.exceptionOrNull())
    }

    // endregion

    // region resetPreActivityMetadataTags

    @Test
    fun `resetPreActivityMetadataTags succeeds and notifies changed`() = test {
        val paymentId = "payment-123"
        wheneverBlocking { activityService.resetPreActivityMetadataTags(paymentId) }.thenReturn(Unit)

        sut.preActivityMetadataChanged.test {
            val initialValue = awaitItem()

            val result = sut.resetPreActivityMetadataTags(paymentId)

            assertTrue(result.isSuccess)
            verify(activityService).resetPreActivityMetadataTags(paymentId)

            val updatedValue = awaitItem()
            assertTrue(updatedValue > initialValue, "Changed timestamp should be updated")
        }
    }

    @Test
    fun `resetPreActivityMetadataTags returns failure on exception`() = test {
        val paymentId = "payment-123"
        val exception = RuntimeException("Reset failed")
        wheneverBlocking { activityService.resetPreActivityMetadataTags(paymentId) } doThrow exception

        val result = sut.resetPreActivityMetadataTags(paymentId)

        assertTrue(result.isFailure)
        assertEquals(exception, result.exceptionOrNull())
    }

    // endregion

    // region getPreActivityMetadata

    @Test
    fun `getPreActivityMetadata by payment id returns metadata`() = test {
        val searchKey = "payment-123"
        wheneverBlocking { activityService.getPreActivityMetadata(searchKey, false) }.thenReturn(testMetadata)

        val result = sut.getPreActivityMetadata(searchKey, searchByAddress = false)

        assertTrue(result.isSuccess)
        assertEquals(testMetadata, result.getOrNull())
        verify(activityService).getPreActivityMetadata(searchKey, false)
    }

    @Test
    fun `getPreActivityMetadata by address returns metadata`() = test {
        val address = "bc1qtest"
        wheneverBlocking { activityService.getPreActivityMetadata(address, true) }.thenReturn(testMetadata)

        val result = sut.getPreActivityMetadata(address, searchByAddress = true)

        assertTrue(result.isSuccess)
        assertEquals(testMetadata, result.getOrNull())
        verify(activityService).getPreActivityMetadata(address, true)
    }

    @Test
    fun `getPreActivityMetadata returns null when not found`() = test {
        val searchKey = "non-existent"
        wheneverBlocking { activityService.getPreActivityMetadata(searchKey, false) }.thenReturn(null)

        val result = sut.getPreActivityMetadata(searchKey, searchByAddress = false)

        assertTrue(result.isSuccess)
        assertNull(result.getOrNull())
    }

    @Test
    fun `getPreActivityMetadata returns failure on exception`() = test {
        val searchKey = "payment-123"
        val exception = RuntimeException("Query failed")
        wheneverBlocking { activityService.getPreActivityMetadata(searchKey, false) } doThrow exception

        val result = sut.getPreActivityMetadata(searchKey, searchByAddress = false)

        assertTrue(result.isFailure)
        assertEquals(exception, result.exceptionOrNull())
    }

    // endregion

    // region deletePreActivityMetadata

    @Test
    fun `deletePreActivityMetadata succeeds and notifies changed`() = test {
        val paymentId = "payment-123"
        wheneverBlocking { activityService.deletePreActivityMetadata(paymentId) }.thenReturn(Unit)

        sut.preActivityMetadataChanged.test {
            val initialValue = awaitItem()

            val result = sut.deletePreActivityMetadata(paymentId)

            assertTrue(result.isSuccess)
            verify(activityService).deletePreActivityMetadata(paymentId)

            val updatedValue = awaitItem()
            assertTrue(updatedValue > initialValue, "Changed timestamp should be updated")
        }
    }

    @Test
    fun `deletePreActivityMetadata returns failure on exception`() = test {
        val paymentId = "payment-123"
        val exception = RuntimeException("Delete failed")
        wheneverBlocking { activityService.deletePreActivityMetadata(paymentId) } doThrow exception

        val result = sut.deletePreActivityMetadata(paymentId)

        assertTrue(result.isFailure)
        assertEquals(exception, result.exceptionOrNull())
    }

    // endregion

    // region savePreActivityMetadata

    @Test
    fun `savePreActivityMetadata with tags succeeds`() = test {
        val id = "payment-123"
        val address = "bc1qtest"
        val tags = listOf("tag1", "tag2")
        wheneverBlocking { activityService.upsertPreActivityMetadata(any()) }.thenReturn(Unit)

        val result = sut.savePreActivityMetadata(
            id = id,
            address = address,
            isReceive = true,
            tags = tags
        )

        assertTrue(result.isSuccess)
        verify(activityService).upsertPreActivityMetadata(any())
    }

    @Test
    fun `savePreActivityMetadata with transfer flag succeeds even without tags`() = test {
        val id = "payment-123"
        val address = "bc1qtest"
        wheneverBlocking { activityService.upsertPreActivityMetadata(any()) }.thenReturn(Unit)

        val result = sut.savePreActivityMetadata(
            id = id,
            address = address,
            isReceive = false,
            tags = emptyList(),
            isTransfer = true
        )

        assertTrue(result.isSuccess)
    }

    @Test
    fun `savePreActivityMetadata with all optional parameters succeeds`() = test {
        val id = "payment-123"
        wheneverBlocking { activityService.upsertPreActivityMetadata(any()) }.thenReturn(Unit)

        val result = sut.savePreActivityMetadata(
            id = id,
            paymentHash = "hash-123",
            txId = "tx-123",
            address = "bc1qtest",
            isReceive = false,
            tags = listOf("important"),
            feeRate = 10u,
            isTransfer = true,
            channelId = "channel-123"
        )

        assertTrue(result.isSuccess)
        verify(activityService).upsertPreActivityMetadata(any())
    }

    @Test
    fun `savePreActivityMetadata fails when no tags and not transfer`() = test {
        val id = "payment-123"
        val address = "bc1qtest"

        val result = sut.savePreActivityMetadata(
            id = id,
            address = address,
            isReceive = true,
            tags = emptyList(),
            isTransfer = false
        )

        assertTrue(result.isFailure)
        assertNotNull(result.exceptionOrNull())
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `savePreActivityMetadata uses default feeRate when not provided`() = test {
        val id = "payment-123"
        val address = "bc1qtest"
        val tags = listOf("tag1")

        wheneverBlocking { activityService.upsertPreActivityMetadata(any()) }.thenAnswer { invocation ->
            val metadataList = invocation.getArgument<List<PreActivityMetadata>>(0)
            assertEquals(0u, metadataList.first().feeRate, "Default feeRate should be 0")
            Unit
        }

        val result = sut.savePreActivityMetadata(
            id = id,
            address = address,
            isReceive = true,
            tags = tags,
            feeRate = null
        )

        assertTrue(result.isSuccess)
    }

    @Test
    fun `savePreActivityMetadata uses empty string for null channelId`() = test {
        val id = "payment-123"
        val address = "bc1qtest"
        val tags = listOf("tag1")

        wheneverBlocking { activityService.upsertPreActivityMetadata(any()) }.thenAnswer { invocation ->
            val metadataList = invocation.getArgument<List<PreActivityMetadata>>(0)
            assertEquals("", metadataList.first().channelId, "Null channelId should be empty string")
            Unit
        }

        val result = sut.savePreActivityMetadata(
            id = id,
            address = address,
            isReceive = true,
            tags = tags,
            channelId = null
        )

        assertTrue(result.isSuccess)
    }

    @Test
    fun `savePreActivityMetadata returns failure on exception`() = test {
        val id = "payment-123"
        val address = "bc1qtest"
        val tags = listOf("tag1")
        val exception = RuntimeException("Save failed")
        wheneverBlocking { activityService.upsertPreActivityMetadata(any()) } doThrow exception

        val result = sut.savePreActivityMetadata(
            id = id,
            address = address,
            isReceive = true,
            tags = tags
        )

        assertTrue(result.isFailure)
        assertEquals(exception, result.exceptionOrNull())
    }

    // endregion

    // region Change Notification Tests

    @Test
    fun `multiple operations trigger change notifications`() = test {
        val paymentId = "payment-123"
        val tags = listOf("tag1")

        wheneverBlocking { activityService.addPreActivityMetadataTags(any(), any()) }.thenReturn(Unit)
        wheneverBlocking { activityService.removePreActivityMetadataTags(any(), any()) }.thenReturn(Unit)

        // Test that add operation triggers notification
        val initialValue = sut.preActivityMetadataChanged.value

        sut.addPreActivityMetadataTags(paymentId, tags)
        val afterAdd = sut.preActivityMetadataChanged.value
        assertTrue(afterAdd > initialValue, "After add should update timestamp")

        // Test that remove operation also triggers notification
        sut.removePreActivityMetadataTags(paymentId, tags)
        val afterRemove = sut.preActivityMetadataChanged.value
        assertTrue(afterRemove > afterAdd, "After remove should update timestamp")
    }

    @Test
    fun `failed operations do not trigger change notifications`() = test {
        val paymentId = "payment-123"
        val exception = RuntimeException("Operation failed")

        wheneverBlocking { activityService.deletePreActivityMetadata(paymentId) } doThrow exception

        sut.preActivityMetadataChanged.test {
            awaitItem()

            sut.deletePreActivityMetadata(paymentId)
            advanceUntilIdle()

            // Should not emit a new value on failure
            expectNoEvents()
        }
    }

    @Test
    fun `getAllPreActivityMetadata does not trigger change notification`() = test {
        wheneverBlocking { activityService.getAllPreActivityMetadata() }.thenReturn(emptyList())

        sut.preActivityMetadataChanged.test {
            awaitItem()

            sut.getAllPreActivityMetadata()
            advanceUntilIdle()

            // Read operations should not trigger change notifications
            expectNoEvents()
        }
    }

    @Test
    fun `getPreActivityMetadata does not trigger change notification`() = test {
        val searchKey = "payment-123"
        wheneverBlocking { activityService.getPreActivityMetadata(searchKey, false) }.thenReturn(null)

        sut.preActivityMetadataChanged.test {
            awaitItem()

            sut.getPreActivityMetadata(searchKey, searchByAddress = false)
            advanceUntilIdle()

            // Read operations should not trigger change notifications
            expectNoEvents()
        }
    }

    // endregion

    // region Edge Cases

    @Test
    fun `savePreActivityMetadata handles empty strings correctly`() = test {
        val id = ""
        val address = ""
        val tags = listOf("tag1")
        wheneverBlocking { activityService.upsertPreActivityMetadata(any()) }.thenReturn(Unit)

        val result = sut.savePreActivityMetadata(
            id = id,
            address = address,
            isReceive = true,
            tags = tags
        )

        assertTrue(result.isSuccess)
    }

    @Test
    fun `addPreActivityMetadataTags with empty tag list succeeds`() = test {
        val paymentId = "payment-123"
        val tags = emptyList<String>()
        wheneverBlocking { activityService.addPreActivityMetadataTags(paymentId, tags) }.thenReturn(Unit)

        val result = sut.addPreActivityMetadataTags(paymentId, tags)

        assertTrue(result.isSuccess)
        verify(activityService).addPreActivityMetadataTags(paymentId, tags)
    }

    @Test
    fun `upsertPreActivityMetadata with empty list succeeds`() = test {
        val emptyList = emptyList<PreActivityMetadata>()
        wheneverBlocking { activityService.upsertPreActivityMetadata(emptyList) }.thenReturn(Unit)

        val result = sut.upsertPreActivityMetadata(emptyList)

        assertTrue(result.isSuccess)
    }

    // endregion
}
