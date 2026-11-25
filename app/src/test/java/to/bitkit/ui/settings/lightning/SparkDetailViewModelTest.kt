package to.bitkit.ui.settings.lightning

import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import to.bitkit.models.HealthState
import to.bitkit.repositories.AppHealthState
import to.bitkit.repositories.HealthRepo
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.SparkExitRecord
import to.bitkit.repositories.SparkExitRepo
import to.bitkit.repositories.SparkExitState
import to.bitkit.repositories.SparkRepo
import to.bitkit.repositories.SparkState
import to.bitkit.repositories.SparkWithdrawal
import to.bitkit.test.BaseUnitTest
import to.bitkit.ui.settings.appStatus.ExitStep
import kotlin.test.assertEquals

class SparkDetailViewModelTest : BaseUnitTest() {
    private val healthState = MutableStateFlow(AppHealthState())
    private val exitState = MutableStateFlow(SparkExitState())
    private val sparkState = MutableStateFlow(SparkState())
    private val lightningRepo = mock<LightningRepo>()
    private val sparkRepo = mock<SparkRepo>()
    private val sparkExitRepo = mock<SparkExitRepo>()
    private val healthRepo = mock<HealthRepo>()

    @Before
    fun setUp() {
        whenever(sparkRepo.state).thenReturn(sparkState)
        whenever(sparkExitRepo.state).thenReturn(exitState)
        whenever(healthRepo.healthState).thenReturn(healthState)
    }

    private fun createSut() = SparkDetailViewModel(
        bgDispatcher = testDispatcher,
        lightningRepo = lightningRepo,
        sparkRepo = sparkRepo,
        sparkExitRepo = sparkExitRepo,
        healthRepo = healthRepo,
    )

    @Test
    fun `status follows spark's health as app status shows it`() = test {
        whenever(sparkRepo.depositAddress()).thenReturn(Result.success("bcrt1qaddress"))
        healthState.value = AppHealthState(spark = HealthState.READY)
        val sut = createSut()

        assertEquals(HealthState.READY, sut.uiState.value.status)
        healthState.value = AppHealthState(spark = HealthState.PENDING)
        assertEquals(HealthState.PENDING, sut.uiState.value.status)
        healthState.value = AppHealthState(spark = HealthState.ERROR)
        assertEquals(HealthState.ERROR, sut.uiState.value.status)
    }

    @Test
    fun `status is not open while a force transfer runs with spark connected`() = test {
        whenever(sparkRepo.depositAddress()).thenReturn(Result.success("bcrt1qaddress"))
        healthState.value = AppHealthState(spark = HealthState.READY, isSparkExiting = true)

        val sut = createSut()

        assertEquals(HealthState.PENDING, sut.uiState.value.status)
    }

    @Test
    fun `status reads as app status's spark row while a force transfer runs`() = test {
        whenever(sparkRepo.depositAddress()).thenReturn(Result.success("bcrt1qaddress"))
        val record = mock<SparkExitRecord> {
            on { isActive } doReturn true
            on { phase } doReturn SparkExitRecord.Phase.Running
        }
        exitState.value = SparkExitState(record = record)

        val sut = createSut()

        assertEquals(ExitStep.RUNNING, sut.uiState.value.exitStep)
        exitState.value = SparkExitState()
        assertEquals(null, sut.uiState.value.exitStep)
    }

    @Test
    fun `an empty connected spark still shows it receives`() = test {
        whenever(sparkRepo.depositAddress()).thenReturn(Result.success("bcrt1qaddress"))
        sparkState.value = SparkState(isReady = true)
        healthState.value = AppHealthState(internet = HealthState.READY, spark = HealthState.READY)

        val sut = createSut()

        assertEquals(true, sut.uiState.value.spark?.isReceiving)
        sut.onPullToRefresh()
        assertEquals(true, sut.uiState.value.spark?.isReceiving)
    }

    @Test
    fun `the deposit address loads once a spark opened offline connects`() = test {
        whenever(sparkRepo.depositAddress())
            .thenReturn(Result.failure(IllegalStateException("not connected")), Result.success("bcrt1qaddress"))
        sparkState.value = SparkState(isReady = false, hasReadState = true)

        val sut = createSut()

        assertEquals(null, sut.uiState.value.depositAddress)
        sparkState.value = SparkState(isReady = true, hasReadState = true)
        assertEquals("bcrt1qaddress", sut.uiState.value.depositAddress)
    }

    @Test
    fun `status is offline while spark's health is unknown`() = test {
        whenever(sparkRepo.depositAddress()).thenReturn(Result.success("bcrt1qaddress"))
        healthState.value = AppHealthState(spark = null)

        val sut = createSut()

        assertEquals(HealthState.ERROR, sut.uiState.value.status)
    }

    @Test
    fun `the detail keeps spark's withdrawals when the connections list leaves spark out`() = test {
        val withdrawal = SparkWithdrawal("4f1d0c55e2a9b713", 4_917uL, feeSats = 1_470uL)
        sparkState.value = SparkState(isReady = false, withdrawals = persistentListOf(withdrawal))

        val sut = createSut()

        assertEquals<List<SparkWithdrawal>?>(listOf(withdrawal), sut.uiState.value.spark?.withdrawals)
    }
}
