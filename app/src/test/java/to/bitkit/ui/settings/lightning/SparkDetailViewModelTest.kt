package to.bitkit.ui.settings.lightning

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import to.bitkit.models.HealthState
import to.bitkit.repositories.AppHealthState
import to.bitkit.repositories.HealthRepo
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.SparkExitRepo
import to.bitkit.repositories.SparkExitState
import to.bitkit.repositories.SparkRepo
import to.bitkit.repositories.SparkState
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals

class SparkDetailViewModelTest : BaseUnitTest() {
    private val healthState = MutableStateFlow(AppHealthState())
    private val lightningRepo = mock<LightningRepo>()
    private val sparkRepo = mock<SparkRepo>()
    private val sparkExitRepo = mock<SparkExitRepo>()
    private val healthRepo = mock<HealthRepo>()

    @Before
    fun setUp() {
        whenever(sparkRepo.state).thenReturn(MutableStateFlow(SparkState()))
        whenever(sparkExitRepo.state).thenReturn(MutableStateFlow(SparkExitState()))
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
    fun `status is offline while spark's health is unknown`() = test {
        whenever(sparkRepo.depositAddress()).thenReturn(Result.success("bcrt1qaddress"))
        healthState.value = AppHealthState(spark = null)

        val sut = createSut()

        assertEquals(HealthState.ERROR, sut.uiState.value.status)
    }
}
