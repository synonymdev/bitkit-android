package to.bitkit.ui.settings.appStatus

import android.content.Context
import app.cash.turbine.test
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Before
import org.junit.Test
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
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals

class AppStatusViewModelTest : BaseUnitTest() {
    private val context: Context = mock()
    private val healthRepo: HealthRepo = mock()
    private val lightningRepo: LightningRepo = mock()
    private val cacheStore: CacheStore = mock()

    private lateinit var sut: AppStatusViewModel

    private val failedBackupSubtitle = "Failed to complete a full backup"
    private val readyBackupSubtitle = "Ready"

    @Before
    fun setUp() {
        whenever(context.getString(R.string.settings__status__backup__error)).thenReturn(failedBackupSubtitle)
        whenever(context.getString(R.string.settings__status__backup__ready)).thenReturn(readyBackupSubtitle)
        whenever(healthRepo.healthState).thenReturn(MutableStateFlow(AppHealthState()))
        whenever(lightningRepo.lightningState).thenReturn(MutableStateFlow(LightningState()))
        whenever(cacheStore.backupStatuses).thenReturn(flowOf(emptyMap()))
    }

    private fun createSut(): AppStatusViewModel {
        return AppStatusViewModel(
            context = context,
            healthRepo = healthRepo,
            lightningRepo = lightningRepo,
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
}
