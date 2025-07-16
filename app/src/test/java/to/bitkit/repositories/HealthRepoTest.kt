package to.bitkit.repositories

import app.cash.turbine.test
import com.synonym.bitkitcore.IBtOrder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.junit.Before
import org.junit.Test
import org.lightningdevkit.ldknode.ChannelDetails
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import to.bitkit.data.CacheStore
import to.bitkit.models.BackupCategory
import to.bitkit.models.BackupItemStatus
import to.bitkit.models.HealthState
import to.bitkit.models.NodeLifecycleState
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

class HealthRepoTest : BaseUnitTest() {
    private val connectivityRepo: ConnectivityRepo = mock()
    private val lightningRepo: LightningRepo = mock()
    private val blocktankRepo: BlocktankRepo = mock()
    private val cacheStore: CacheStore = mock()
    private val clock: Clock = mock()

    private lateinit var sut: HealthRepo

    private val fixedTime = Instant.fromEpochMilliseconds(1000000000L)

    @Before
    fun setUp() {
        whenever(connectivityRepo.isOnline).thenReturn(flowOf(ConnectivityState.CONNECTED))
        whenever(lightningRepo.lightningState).thenReturn(MutableStateFlow(LightningState()))
        whenever(blocktankRepo.blocktankState).thenReturn(MutableStateFlow(BlocktankState()))
        whenever(cacheStore.backupStatuses).thenReturn(flowOf(emptyMap()))
        whenever(clock.now()).thenReturn(fixedTime)
    }

    private fun createSut(): HealthRepo {
        return HealthRepo(
            bgDispatcher = testDispatcher,
            connectivityRepo = connectivityRepo,
            lightningRepo = lightningRepo,
            blocktankRepo = blocktankRepo,
            cacheStore = cacheStore,
            clock = clock,
        )
    }

    @Test
    fun `backup status is ready when all backups are synced`() = test {
        val now = fixedTime.toEpochMilliseconds()
        val backupStatuses = mapOf(
            BackupCategory.WALLET to BackupItemStatus(synced = now, required = now - 1000),
            BackupCategory.SETTINGS to BackupItemStatus(synced = now, required = now - 1000),
        )
        whenever(cacheStore.backupStatuses).thenReturn(flowOf(backupStatuses))

        sut = createSut()

        sut.healthState.test {
            val state = awaitItem()
            assertEquals(HealthState.READY, state.backups)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `backup status is ready when within grace period`() = test {
        val now = fixedTime.toEpochMilliseconds()
        val backupStatuses = mapOf(
            BackupCategory.WALLET to BackupItemStatus(
                synced = now - 2.minutes.inWholeMilliseconds,
                required = now - 1.minutes.inWholeMilliseconds,
            )
        )
        whenever(cacheStore.backupStatuses).thenReturn(flowOf(backupStatuses))

        sut = createSut()

        sut.healthState.test {
            val state = awaitItem()
            assertEquals(HealthState.READY, state.backups)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `backup status is error when backup required beyond grace period`() = test {
        val now = fixedTime.toEpochMilliseconds()
        val backupStatuses = mapOf(
            BackupCategory.WALLET to BackupItemStatus(
                synced = now - 10.minutes.inWholeMilliseconds,
                required = now - 6.minutes.inWholeMilliseconds,
            )
        )
        whenever(cacheStore.backupStatuses).thenReturn(flowOf(backupStatuses))

        sut = createSut()

        sut.healthState.test {
            val state = awaitItem()
            assertEquals(HealthState.ERROR, state.backups)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `backup status excludes LIGHTNING_CONNECTIONS category`() = test {
        val now = fixedTime.toEpochMilliseconds()
        val backupStatuses = mapOf(
            BackupCategory.WALLET to BackupItemStatus(synced = now, required = now - 1000),
            BackupCategory.LIGHTNING_CONNECTIONS to BackupItemStatus(
                synced = now - 10.minutes.inWholeMilliseconds,
                required = now - 6.minutes.inWholeMilliseconds,
            )
        )
        whenever(cacheStore.backupStatuses).thenReturn(flowOf(backupStatuses))

        sut = createSut()

        sut.healthState.test {
            val state = awaitItem()
            assertEquals(HealthState.READY, state.backups)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `backup status handles missing backup status as ready`() = test {
        val backupStatuses = emptyMap<BackupCategory, BackupItemStatus>()
        whenever(cacheStore.backupStatuses).thenReturn(flowOf(backupStatuses))

        sut = createSut()

        sut.healthState.test {
            val state = awaitItem()
            assertEquals(HealthState.READY, state.backups)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `channels status is ready when open channels exist`() = test {
        val mockChannel = mock<ChannelDetails> {
            on { isChannelReady } doReturn true
        }
        val lightningState = LightningState(
            nodeLifecycleState = NodeLifecycleState.Running,
            channels = listOf(mockChannel),
        )
        whenever(lightningRepo.lightningState).thenReturn(MutableStateFlow(lightningState))

        sut = createSut()

        sut.healthState.test {
            val state = awaitItem()
            assertEquals(HealthState.READY, state.channels)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `channels status is pending when only pending channels exist`() = test {
        val mockChannel = mock<ChannelDetails> {
            on { isChannelReady } doReturn false
        }
        val lightningState = LightningState(
            nodeLifecycleState = NodeLifecycleState.Running,
            channels = listOf(mockChannel),
        )
        whenever(lightningRepo.lightningState).thenReturn(MutableStateFlow(lightningState))

        sut = createSut()

        sut.healthState.test {
            val state = awaitItem()
            assertEquals(HealthState.PENDING, state.channels)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `channels status is error when no channels exist`() = test {
        val lightningState = LightningState(
            nodeLifecycleState = NodeLifecycleState.Running,
            channels = emptyList(),
        )
        whenever(lightningRepo.lightningState).thenReturn(MutableStateFlow(lightningState))

        sut = createSut()

        sut.healthState.test {
            val state = awaitItem()
            assertEquals(HealthState.ERROR, state.channels)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `paid orders override channels error to pending`() = test {
        val lightningState = LightningState(
            nodeLifecycleState = NodeLifecycleState.Running,
            channels = emptyList(),
        )
        whenever(lightningRepo.lightningState).thenReturn(MutableStateFlow(lightningState))

        val paidOrder = mock<IBtOrder>()
        val blocktankState = BlocktankState(paidOrders = listOf(paidOrder))
        whenever(blocktankRepo.blocktankState).thenReturn(MutableStateFlow(blocktankState))

        sut = createSut()

        sut.healthState.test {
            val state = awaitItem()
            assertEquals(HealthState.PENDING, state.channels)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `lightning node state maps correctly when online`() = test {
        val lightningState = LightningState(nodeLifecycleState = NodeLifecycleState.Running)
        whenever(lightningRepo.lightningState).thenReturn(MutableStateFlow(lightningState))

        sut = createSut()

        sut.healthState.test {
            val state = awaitItem()
            assertEquals(HealthState.READY, state.node)
            assertEquals(HealthState.READY, state.electrum)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `all statuses except backup go to error when internet connectivity is off`() = test {
        whenever(connectivityRepo.isOnline).thenReturn(flowOf(ConnectivityState.DISCONNECTED))

        // Set up conditions that would normally be READY/PENDING if online
        val mockChannel = mock<ChannelDetails> {
            on { isChannelReady } doReturn true
        }
        val lightningState = LightningState(
            nodeLifecycleState = NodeLifecycleState.Running,
            channels = listOf(mockChannel),
        )
        whenever(lightningRepo.lightningState).thenReturn(MutableStateFlow(lightningState))

        val now = fixedTime.toEpochMilliseconds()
        val backupStatuses = mapOf(
            BackupCategory.WALLET to BackupItemStatus(synced = now, required = now - 1000),
        )
        whenever(cacheStore.backupStatuses).thenReturn(flowOf(backupStatuses))

        sut = createSut()

        sut.healthState.test {
            val state = awaitItem()
            assertEquals(HealthState.ERROR, state.internet)
            assertEquals(HealthState.ERROR, state.node)
            assertEquals(HealthState.ERROR, state.electrum)
            assertEquals(HealthState.ERROR, state.channels)
            // backups should still be READY as they don't depend on internet
            assertEquals(HealthState.READY, state.backups)
            // app should be ERROR due to other components being ERROR
            assertEquals(HealthState.ERROR, state.app)
            assertEquals(false, state.isOnline())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `internet status maps to error when disconnected`() = test {
        whenever(connectivityRepo.isOnline).thenReturn(flowOf(ConnectivityState.DISCONNECTED))

        sut = createSut()

        sut.healthState.test {
            val disconnectedState = awaitItem()
            assertEquals(HealthState.ERROR, disconnectedState.internet)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `isOnline returns true when internet is ready`() = test {
        sut = createSut()

        sut.healthState.test {
            val state = awaitItem()
            assertEquals(true, state.isOnline())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `isOnline returns false when internet is not ready`() = test {
        whenever(connectivityRepo.isOnline).thenReturn(flowOf(ConnectivityState.DISCONNECTED))

        sut = createSut()

        sut.healthState.test {
            val state = awaitItem()
            assertEquals(false, state.isOnline())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
