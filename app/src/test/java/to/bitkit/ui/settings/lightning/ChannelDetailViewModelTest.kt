package to.bitkit.ui.settings.lightning

import android.content.Context
import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.ActivityFilter
import com.synonym.bitkitcore.OnchainActivity
import com.synonym.bitkitcore.PaymentType
import com.synonym.bitkitcore.SortDirection
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.R
import to.bitkit.ext.create
import to.bitkit.ext.createChannelDetails
import to.bitkit.repositories.ActivityRepo
import to.bitkit.repositories.BlocktankRepo
import to.bitkit.repositories.BlocktankState
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.LightningState
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class ChannelDetailViewModelTest : BaseUnitTest() {

    private companion object {
        const val CHANNEL_ID = "channel-id"
        const val TIMESTAMP = 1_700_000_000uL
    }

    private val context = mock<Context>()
    private val lightningRepo = mock<LightningRepo>()
    private val blocktankRepo = mock<BlocktankRepo>()
    private val activityRepo = mock<ActivityRepo>()

    @Before
    fun setUp() {
        whenever(context.getString(R.string.lightning__connection)).thenReturn("Connection")
        whenever(lightningRepo.lightningState).thenReturn(
            MutableStateFlow(
                LightningState(
                    channels = persistentListOf(
                        createChannelDetails().copy(channelId = CHANNEL_ID),
                    )
                )
            )
        )
        whenever(blocktankRepo.blocktankState).thenReturn(MutableStateFlow(BlocktankState()))
        whenever {
            activityRepo.getClosedChannels(SortDirection.DESC)
        }.thenReturn(Result.success(emptyList()))
    }

    @Test
    fun `loads opening timestamp from hardware wallet transfer`() = test {
        val transferActivity = Activity.Onchain(
            OnchainActivity.create(
                walletId = "hardware-wallet",
                id = "funding-txid",
                txType = PaymentType.SENT,
                txId = "funding-txid",
                value = 100uL,
                fee = 1uL,
                address = "bc1qtest",
                timestamp = TIMESTAMP,
                isTransfer = true,
                channelId = CHANNEL_ID,
            )
        )
        whenever {
            activityRepo.getActivities(
                walletId = null,
                filter = ActivityFilter.ONCHAIN,
                txType = PaymentType.SENT,
            )
        }.thenReturn(Result.success(listOf(transferActivity)))
        val sut = createViewModel()

        sut.loadChannel(CHANNEL_ID)
        advanceUntilIdle()

        assertEquals(TIMESTAMP, sut.uiState.value.txTime)
        verify(activityRepo).getActivities(
            walletId = null,
            filter = ActivityFilter.ONCHAIN,
            txType = PaymentType.SENT,
        )
    }

    private fun createViewModel() = ChannelDetailViewModel(
        context = context,
        lightningRepo = lightningRepo,
        blocktankRepo = blocktankRepo,
        activityRepo = activityRepo,
    )
}
