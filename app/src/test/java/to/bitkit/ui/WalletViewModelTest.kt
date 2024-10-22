package to.bitkit.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.google.android.gms.tasks.Task
import com.google.firebase.messaging.FirebaseMessaging
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.lightningdevkit.ldknode.BalanceDetails
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.kotlin.wheneverBlocking
import org.robolectric.annotation.Config
import to.bitkit.data.AppDb
import to.bitkit.data.AppStorage
import to.bitkit.data.keychain.Keychain
import to.bitkit.services.BlocktankService
import to.bitkit.services.LightningService
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
    private var lightningService: LightningService = mock()
    private var appStorage: AppStorage = mock()

    private lateinit var sut: WalletViewModel

    private val balanceDetails = mock<BalanceDetails>()

    @Before
    fun setUp() {
        whenever(lightningService.nodeId).thenReturn("nodeId")
        whenever(lightningService.balances).thenReturn(balanceDetails)
        whenever(lightningService.balances?.totalLightningBalanceSats).thenReturn(1000u)
        whenever(lightningService.balances?.totalOnchainBalanceSats).thenReturn(10_000u)
        wheneverBlocking { lightningService.newAddress() }.thenReturn("onchainAddress")
        whenever(db.configDao()).thenReturn(mock())
        whenever(db.configDao().getAll()).thenReturn(mock())
        whenever(db.ordersDao()).thenReturn(mock())
        whenever(db.ordersDao().getAll()).thenReturn(mock())

        sut = WalletViewModel(
            uiThread = testDispatcher,
            bgDispatcher = testDispatcher,
            appContext = mock(),
            appStorage = appStorage,
            db = db,
            keychain = keychain,
            blocktankService = blocktankService,
            lightningService = lightningService,
            firebaseMessaging = firebaseMessaging,
        )
    }

    @Test
    fun `uiState should emit Content state after sync`() = test {
        whenever(keychain.exists(Keychain.Key.BIP39_MNEMONIC.name)).thenReturn(true)
        whenever(keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name)).thenReturn("mnemonic")
        val expectedUiState = MainUiState.Content(
            nodeId = "nodeId",
            onchainAddress = "onchainAddress",
            peers = emptyList(),
            channels = emptyList(),
            orders = emptyList(),
            balanceDetails = balanceDetails,
            totalBalanceSats = 11_000u,
            totalOnchainSats = 10_000u,
            totalLightningSats = 1000u,
            bolt11 = "",
            bip21 = "bitcoin:onchainAddress",
            nodeLifecycleState = NodeLifecycleState.Running,
            nodeStatus = null,
        )

        sut.start()

        sut.uiState.test {
            val content = awaitItem()
            assertEquals(expectedUiState, content)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `registerForNotifications should register device with FCM token`() = test {
        val token = "test"
        val task = mock<Task<String>> {
            on(it.isComplete).thenReturn(true)
            on(it.result).thenReturn(token)
        }
        whenever(firebaseMessaging.token).thenReturn(task)

        sut.registerForNotifications()

        verify(blocktankService).registerDevice(token)
    }
}
