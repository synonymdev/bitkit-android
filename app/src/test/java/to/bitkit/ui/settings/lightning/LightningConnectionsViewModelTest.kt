package to.bitkit.ui.settings.lightning

import android.content.Context
import com.synonym.bitkitcore.SortDirection
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import to.bitkit.R
import to.bitkit.ext.createChannelDetails
import to.bitkit.models.NodeLifecycleState
import to.bitkit.repositories.ActivityRepo
import to.bitkit.repositories.BlocktankRepo
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.LightningState
import to.bitkit.repositories.LogsRepo
import to.bitkit.test.BaseUnitTest
import to.bitkit.test.idleArkadeRepo
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LightningConnectionsViewModelTest : BaseUnitTest() {

    private val context = mock<Context>()
    private val lightningRepo = mock<LightningRepo>()
    private val blocktankRepo = mock<BlocktankRepo>()
    private val logsRepo = mock<LogsRepo>()
    private val activityRepo = mock<ActivityRepo>()

    private val lightningState = MutableStateFlow(LightningState(nodeLifecycleState = NodeLifecycleState.Running))
    private val lastKnownChannels = MutableStateFlow(
        persistentListOf(createChannelDetails().copy(channelId = "channel_1", isChannelReady = true))
    )

    @Before
    fun setUp() {
        whenever(context.getString(R.string.lightning__connection)).thenReturn("Connection")
        whenever(lightningRepo.lightningState).thenReturn(lightningState)
        whenever(lightningRepo.lastKnownChannels).thenReturn(lastKnownChannels)
        whenever(lightningRepo.nodeEvents).thenReturn(emptyFlow())
        whenever {
            activityRepo.getClosedChannels(SortDirection.DESC)
        }.thenReturn(Result.success(emptyList()))
    }

    @Test
    fun `keeps last known channels while node restarts`() = test {
        val sut = createViewModel()
        advanceUntilIdle()

        lightningState.value = LightningState(nodeLifecycleState = NodeLifecycleState.Stopped)
        advanceUntilIdle()

        assertFalse(sut.uiState.value.isNodeRunning)
        assertEquals("channel_1", sut.uiState.value.openChannels.single().details.channelId)

        lightningState.value = LightningState(nodeLifecycleState = NodeLifecycleState.Running)
        advanceUntilIdle()

        assertTrue(sut.uiState.value.isNodeRunning)
        assertEquals("channel_1", sut.uiState.value.openChannels.single().details.channelId)
    }

    private fun createViewModel() = LightningConnectionsViewModel(
        context = context,
        bgDispatcher = testDispatcher,
        lightningRepo = lightningRepo,
        blocktankRepo = blocktankRepo,
        logsRepo = logsRepo,
        activityRepo = activityRepo,
        arkadeRepo = idleArkadeRepo(),
    )
}
