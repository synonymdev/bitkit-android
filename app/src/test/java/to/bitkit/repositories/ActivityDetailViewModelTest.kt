package to.bitkit.repositories

import com.synonym.bitkitcore.IBtOrder
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import to.bitkit.data.SettingsStore
import to.bitkit.test.BaseUnitTest
import to.bitkit.viewmodels.ActivityDetailViewModel
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ActivityDetailViewModelTest : BaseUnitTest() {

    private val activityRepo = mock<ActivityRepo>()
    private val blocktankRepo = mock<BlocktankRepo>()
    private val settingsStore = mock<SettingsStore>()
    private val lightningRepo = mock<LightningRepo>()

    private lateinit var sut: ActivityDetailViewModel

    @Before
    fun setUp() {
        whenever(blocktankRepo.blocktankState).thenReturn(MutableStateFlow(BlocktankState()))

        sut = ActivityDetailViewModel(
            bgDispatcher = testDispatcher,
            activityRepo = activityRepo,
            blocktankRepo = blocktankRepo,
            settingsStore = settingsStore,
            lightningRepo = lightningRepo,
        )
    }

    @Test
    fun `findOrderForTransfer returns null when both channelId and txId are null`() = test {
        val result = sut.findOrderForTransfer(null, null)

        assertNull(result)
    }

    @Test
    fun `findOrderForTransfer finds order by channelId`() = test {
        val orderId = "test-order-id"
        val mockOrder = mock<IBtOrder> {
            on { id } doReturn orderId
        }

        whenever(blocktankRepo.blocktankState).thenReturn(
            MutableStateFlow(BlocktankState(orders = listOf(mockOrder)))
        )

        val result = sut.findOrderForTransfer(orderId, null)

        assertEquals(mockOrder, result)
    }

    @Test
    fun `findOrderForTransfer finds order by channelId matching order id`() = test {
        val orderId = "order-123"
        val mockOrder = mock<IBtOrder> {
            on { id } doReturn orderId
        }

        whenever(blocktankRepo.blocktankState).thenReturn(
            MutableStateFlow(BlocktankState(orders = listOf(mockOrder)))
        )

        val result = sut.findOrderForTransfer(orderId, null)

        assertEquals(mockOrder, result)
    }

    @Test
    fun `findOrderForTransfer returns null when order not found`() = test {
        whenever(blocktankRepo.blocktankState).thenReturn(
            MutableStateFlow(BlocktankState(orders = emptyList()))
        )

        val result = sut.findOrderForTransfer("non-existent-id", null)

        assertNull(result)
    }
}
