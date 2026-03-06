package to.bitkit.ui.screens.wallets.send

import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.LightningActivity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import to.bitkit.models.NewTransactionSheetDirection
import to.bitkit.models.NewTransactionSheetType
import to.bitkit.repositories.ActivityRepo
import to.bitkit.repositories.PendingPaymentRepo
import to.bitkit.repositories.PendingPaymentResolution
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class SendPendingViewModelTest : BaseUnitTest() {

    private val pendingPaymentRepo = PendingPaymentRepo()
    private val activityRepo: ActivityRepo = mock()

    private val hash = "test_payment_hash"
    private val amount = 5000L

    private lateinit var sut: SendPendingViewModel

    @Before
    fun setUp() {
        whenever { activityRepo.findActivityByPaymentId(any(), any(), any(), any()) }.thenReturn(
            Result.failure(Exception("not found"))
        )
    }

    @Test
    fun `init sets amount in uiState`() = test {
        sut = createViewModel()
        sut.init(hash, amount)
        advanceUntilIdle()

        assertEquals(amount, sut.uiState.value.amount)
    }

    @Test
    fun `init sets activeHash on repo`() = test {
        sut = createViewModel()
        sut.init(hash, amount)
        advanceUntilIdle()

        assertEquals(true, pendingPaymentRepo.isActive(hash))
    }

    @Test
    fun `init is idempotent`() = test {
        sut = createViewModel()
        sut.init(hash, amount)
        sut.init(hash, 9999L)
        advanceUntilIdle()

        assertEquals(amount, sut.uiState.value.amount)
    }

    @Test
    fun `findActivity sets activityId`() = test {
        val activityV1 = mock<LightningActivity> {
            on { id } doReturn "activity_id_123"
        }
        val activity = mock<Activity.Lightning> {
            on { v1 } doReturn activityV1
        }
        whenever { activityRepo.findActivityByPaymentId(any(), any(), any(), any()) }
            .thenReturn(Result.success(activity))

        sut = createViewModel()
        sut.init(hash, amount)
        advanceUntilIdle()

        assertEquals("activity_id_123", sut.uiState.value.activityId)
    }

    @Test
    fun `findActivity failure leaves activityId null`() = test {
        sut = createViewModel()
        sut.init(hash, amount)
        advanceUntilIdle()

        assertNull(sut.uiState.value.activityId)
    }

    @Test
    fun `observeResolution Success updates uiState`() = test {
        sut = createViewModel()
        sut.init(hash, amount)
        advanceUntilIdle()

        pendingPaymentRepo.track(hash)
        pendingPaymentRepo.resolve(PendingPaymentResolution.Success(hash))
        advanceUntilIdle()

        val resolution = sut.uiState.value.resolution
        assertIs<SendPendingUiState.Resolution.Success>(resolution)
        assertEquals(hash, resolution.details.paymentHashOrTxId)
        assertEquals(amount, resolution.details.sats)
        assertEquals(NewTransactionSheetType.LIGHTNING, resolution.details.type)
        assertEquals(NewTransactionSheetDirection.SENT, resolution.details.direction)
    }

    @Test
    fun `observeResolution Failure updates uiState`() = test {
        sut = createViewModel()
        sut.init(hash, amount)
        advanceUntilIdle()

        pendingPaymentRepo.track(hash)
        pendingPaymentRepo.resolve(PendingPaymentResolution.Failure(hash))
        advanceUntilIdle()

        val resolution = sut.uiState.value.resolution
        assertIs<SendPendingUiState.Resolution.Error>(resolution)
    }

    @Test
    fun `observeResolution ignores other hashes`() = test {
        sut = createViewModel()
        sut.init(hash, amount)
        advanceUntilIdle()

        pendingPaymentRepo.track("other_hash")
        pendingPaymentRepo.resolve(PendingPaymentResolution.Success("other_hash"))
        advanceUntilIdle()

        assertNull(sut.uiState.value.resolution)
    }

    @Test
    fun `onResolutionHandled clears resolution`() = test {
        sut = createViewModel()
        sut.init(hash, amount)
        advanceUntilIdle()

        pendingPaymentRepo.track(hash)
        pendingPaymentRepo.resolve(PendingPaymentResolution.Success(hash))
        advanceUntilIdle()

        sut.onResolutionHandled()

        assertNull(sut.uiState.value.resolution)
    }

    private fun createViewModel() = SendPendingViewModel(
        pendingPaymentRepo = pendingPaymentRepo,
        activityRepo = activityRepo,
    )
}
