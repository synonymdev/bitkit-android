package to.bitkit.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.google.android.gms.tasks.Task
import com.google.firebase.messaging.FirebaseMessaging
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.kotlin.wheneverBlocking
import org.robolectric.annotation.Config
import to.bitkit.data.AppDb
import to.bitkit.data.BlocktankClient
import to.bitkit.data.keychain.Keychain
import to.bitkit.env.Env.SEED
import to.bitkit.services.BlocktankService
import to.bitkit.services.LightningService
import to.bitkit.services.OnChainService
import to.bitkit.test.BaseUnitTest
import to.bitkit.test.TestApp
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class WalletViewModelTest : BaseUnitTest() {
    private var db: AppDb = mock()
    private var keychain: Keychain = mock()
    private var firebaseMessaging: FirebaseMessaging = mock()
    private var blocktankService: BlocktankService = mock()
    private var blocktankClient: BlocktankClient = mock()
    private var onChainService: OnChainService = mock()
    private var lightningService: LightningService = mock()

    private lateinit var sut: WalletViewModel

    @Before
    fun setUp() {
        whenever(lightningService.nodeId).thenReturn("nodeId")
        whenever(lightningService.balances).thenReturn(mock())
        whenever(lightningService.balances?.totalLightningBalanceSats).thenReturn(1000u)
        wheneverBlocking { onChainService.getAddress() }.thenReturn("btcAddress")
        whenever(onChainService.balance).thenReturn(mock())
        whenever(onChainService.balance?.total).thenReturn(mock())
        whenever(onChainService.balance?.total?.toSat()).thenReturn(500u)

        sut = WalletViewModel(
            uiThread = testDispatcher,
            bgDispatcher = testDispatcher,
            db = db,
            keychain = keychain,
            blocktankService = blocktankService,
            blocktankClient = blocktankClient,
            onChainService = onChainService,
            lightningService = lightningService,
            firebaseMessaging = firebaseMessaging,
        )
    }

    @Test
    fun `uiState should emit Content state after sync`() = test {
        val expectedUiState = MainUiState.Content(
            ldkNodeId = "nodeId",
            ldkBalance = "1000",
            btcAddress = "btcAddress",
            btcBalance = "500",
            mnemonic = SEED,
            peers = emptyList(),
            channels = emptyList(),
            orders = emptyList(),
        )

        sut.uiState.test {
            val initial = awaitItem()
            assertEquals(MainUiState.Loading, initial)

            val content = awaitItem()
            assertEquals(expectedUiState, content)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `registerForNotifications should register device with provided FCM token`() = test {
        val token = "test"

        sut.registerForNotifications(token)

        verify(blocktankService).registerDevice(token)
    }

    @Test
    fun `registerForNotifications should register device with default FCM token`() = test {
        val token = "test"
        val task = mock<Task<String>> {
            on(it.isComplete).thenReturn(true)
            on(it.result).thenReturn(token)
        }
        whenever(firebaseMessaging.token).thenReturn(task)

        sut.registerForNotifications(null)

        verify(blocktankService).registerDevice(token)
    }
}
