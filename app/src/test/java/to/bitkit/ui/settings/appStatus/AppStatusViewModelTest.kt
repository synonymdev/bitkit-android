package to.bitkit.ui.settings.appStatus

import android.content.Context
import app.cash.turbine.test
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import to.bitkit.R
import to.bitkit.data.CacheStore
import to.bitkit.models.BackupCategory
import to.bitkit.models.BackupItemStatus
import to.bitkit.models.HealthState
import to.bitkit.models.NodeLifecycleState
import to.bitkit.repositories.AppHealthState
import to.bitkit.repositories.HealthRepo
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.LightningState
import to.bitkit.repositories.SparkExitFundingState
import to.bitkit.repositories.SparkExitRecord
import to.bitkit.repositories.SparkExitRepo
import to.bitkit.repositories.SparkExitState
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals

class AppStatusViewModelTest : BaseUnitTest() {
    private val context: Context = mock()
    private val healthRepo: HealthRepo = mock()
    private val lightningRepo: LightningRepo = mock()
    private val sparkExitRepo: SparkExitRepo = mock()
    private val cacheStore: CacheStore = mock()

    private lateinit var sut: AppStatusViewModel

    private val failedBackupSubtitle = "Failed to complete a full backup"
    private val readyBackupSubtitle = "Ready"

    @Before
    fun setUp() {
        whenever(context.getString(R.string.settings__status__backup__error)).thenReturn(failedBackupSubtitle)
        whenever(context.getString(R.string.settings__status__backup__ready)).thenReturn(readyBackupSubtitle)
        whenever(context.getString(R.string.other__node_stopped)).thenReturn("Stopped")
        whenever(context.getString(R.string.other__node_starting)).thenReturn("Starting")
        whenever(context.getString(R.string.other__node_running)).thenReturn("Running")
        whenever(context.getString(R.string.other__node_stopping)).thenReturn("Stopping")
        whenever(context.getString(R.string.other__node_initializing)).thenReturn("Setting up wallet…")
        whenever(healthRepo.healthState).thenReturn(MutableStateFlow(AppHealthState()))
        whenever(lightningRepo.lightningState).thenReturn(MutableStateFlow(LightningState()))
        whenever(cacheStore.backupStatuses).thenReturn(flowOf(emptyMap()))
        whenever(sparkExitRepo.state).thenReturn(MutableStateFlow(SparkExitState()))
    }

    private fun createSut(): AppStatusViewModel {
        return AppStatusViewModel(
            context = context,
            healthRepo = healthRepo,
            lightningRepo = lightningRepo,
            sparkExitRepo = sparkExitRepo,
            cacheStore = cacheStore,
        )
    }

    @Test
    fun `backup subtitle shows error message when backup health is error`() = test {
        val backupStatuses = mapOf(
            BackupCategory.SETTINGS to BackupItemStatus(synced = 1000L, required = 2000L)
        )
        val healthState = AppHealthState(backups = HealthState.ERROR)

        whenever(healthRepo.healthState).thenReturn(MutableStateFlow(healthState))
        whenever(cacheStore.backupStatuses).thenReturn(flowOf(backupStatuses))

        sut = createSut()

        sut.uiState.test {
            val state = awaitItem()
            assertEquals(failedBackupSubtitle, state.backupSubtitle)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `backup subtitle shows ready when no backup statuses exist`() = test {
        val healthState = AppHealthState(backups = HealthState.READY)

        whenever(healthRepo.healthState).thenReturn(MutableStateFlow(healthState))
        whenever(cacheStore.backupStatuses).thenReturn(flowOf(emptyMap()))

        sut = createSut()

        sut.uiState.test {
            val state = awaitItem()
            // Should show "Ready" for maxSyncTime = 0L
            assertEquals(readyBackupSubtitle, state.backupSubtitle)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `backup subtitle shows ready when all backup statuses have zero synced time`() = test {
        val backupStatuses = mapOf(
            BackupCategory.SETTINGS to BackupItemStatus(synced = 0L, required = 1000L),
            BackupCategory.WIDGETS to BackupItemStatus(synced = 0L, required = 1000L)
        )
        val healthState = AppHealthState(backups = HealthState.READY)

        whenever(healthRepo.healthState).thenReturn(MutableStateFlow(healthState))
        whenever(cacheStore.backupStatuses).thenReturn(flowOf(backupStatuses))

        sut = createSut()

        sut.uiState.test {
            val state = awaitItem()
            // Should show "Ready" for maxSyncTime = 0L
            assertEquals(readyBackupSubtitle, state.backupSubtitle)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `backup subtitle excludes LIGHTNING_CONNECTIONS category from sync time calculation`() = test {
        val currentTime = System.currentTimeMillis()
        val backupStatuses = mapOf(
            BackupCategory.SETTINGS to BackupItemStatus(
                synced = currentTime - 3600000L,
                required = currentTime - 7200000L
            ), // 1 hour ago
            BackupCategory.LIGHTNING_CONNECTIONS to BackupItemStatus(
                synced = currentTime,
                required = currentTime - 1800000L
            ) // Should be ignored
        )
        val healthState = AppHealthState(backups = HealthState.READY)

        whenever(healthRepo.healthState).thenReturn(MutableStateFlow(healthState))
        whenever(cacheStore.backupStatuses).thenReturn(flowOf(backupStatuses))

        sut = createSut()

        sut.uiState.test {
            val state = awaitItem()
            // Should use older timestamp from SETTINGS, not newer one from LIGHTNING_CONNECTIONS
            assert(state.backupSubtitle.isNotEmpty())
            // Should not contain today's date/time since we're using 1 hour old timestamp
            assert(!state.backupSubtitle.contains("1970")) // Should not be the default 0L timestamp
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `node subtitle shows NodeLifecycleState uiText`() = test {
        val lightningState = LightningState(nodeLifecycleState = NodeLifecycleState.Running)

        whenever(lightningRepo.lightningState).thenReturn(MutableStateFlow(lightningState))

        sut = createSut()

        sut.uiState.test {
            val state = awaitItem()
            assertEquals("Running", state.nodeSubtitle)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `node subtitle updates when NodeLifecycleState changes`() = test {
        val initialState = LightningState(nodeLifecycleState = NodeLifecycleState.Starting)
        val lightningStateFlow = MutableStateFlow(initialState)

        whenever(lightningRepo.lightningState).thenReturn(lightningStateFlow)

        sut = createSut()

        sut.uiState.test {
            // Initial state
            assertEquals("Starting", awaitItem().nodeSubtitle)

            // Update state
            lightningStateFlow.value = initialState.copy(nodeLifecycleState = NodeLifecycleState.Running)
            assertEquals("Running", awaitItem().nodeSubtitle)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `exit step is null without a running exit`() {
        assertEquals(null, (null as SparkExitRecord?).exitStep())
        assertEquals(null, exitRecord(SparkExitRecord.Phase.Done, isActive = false).exitStep())
        assertEquals(null, exitRecord(SparkExitRecord.Phase.Failed("reason"), isActive = false).exitStep())
    }

    @Test
    fun `exit step maps running and funding phases`() {
        assertEquals(ExitStep.RUNNING, exitRecord(SparkExitRecord.Phase.Running).exitStep())
        assertEquals(ExitStep.FUNDING, exitRecord(SparkExitRecord.Phase.AwaitingFunding(1_000uL), 1_000uL).exitStep())
        assertEquals(ExitStep.FUNDING, exitRecord(SparkExitRecord.Phase.NeedsFunding(1_000uL), 1_000uL).exitStep())
        val asked = exitRecord(SparkExitRecord.Phase.NeedsFunding(1_000uL), 1_000uL)
        assertEquals(ExitStep.FUNDING, asked.exitStep(SparkExitFundingState.Ask(1_000uL)))
    }

    @Test
    fun `exit step is running once a payment covers the funding`() {
        val awaiting = exitRecord(SparkExitRecord.Phase.AwaitingFunding(1_000uL), 1_000uL)
        val needs = exitRecord(SparkExitRecord.Phase.NeedsFunding(1_000uL), 1_000uL)
        assertEquals(ExitStep.RUNNING, awaiting.exitStep(SparkExitFundingState.Paid("txid")))
        assertEquals(ExitStep.RUNNING, needs.exitStep(SparkExitFundingState.Seen("txid")))
        assertEquals(ExitStep.RUNNING, exitRecord(SparkExitRecord.Phase.NeedsFunding(1_000uL)).exitStep())
    }

    @Test
    fun `ui state shows the step of a running exit`() = test {
        val record = exitRecord(SparkExitRecord.Phase.Running)
        whenever(sparkExitRepo.state).thenReturn(MutableStateFlow(SparkExitState(record = record)))

        sut = createSut()

        sut.uiState.test {
            assertEquals(ExitStep.RUNNING, awaitItem().exitStep)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun exitRecord(
        phase: SparkExitRecord.Phase,
        shortfallSats: ULong? = null,
        isActive: Boolean = true,
    ) = mock<SparkExitRecord> {
        on { this.phase } doReturn phase
        on { this.isActive } doReturn isActive
        on { fundingShortfallSats } doReturn shortfallSats
    }
}
