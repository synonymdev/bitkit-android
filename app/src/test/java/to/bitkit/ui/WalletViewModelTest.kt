package to.bitkit.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.google.android.gms.tasks.Task
import com.google.firebase.messaging.FirebaseMessaging
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.lightningdevkit.ldknode.BalanceDetails
import org.mockito.ArgumentMatchers.anyString
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.kotlin.wheneverBlocking
import org.robolectric.annotation.Config
import to.bitkit.data.AppDb
import to.bitkit.data.AppStorage
import to.bitkit.data.SettingsStore
import to.bitkit.data.keychain.Keychain
import to.bitkit.services.BlocktankNotificationsService
import to.bitkit.services.LightningService
import to.bitkit.test.BaseUnitTest
import to.bitkit.test.TestApp
import to.bitkit.viewmodels.MainUiState
import to.bitkit.models.NodeLifecycleState
import to.bitkit.services.CoreService
import to.bitkit.services.LdkNodeEventBus
import to.bitkit.utils.AddressChecker
import to.bitkit.utils.AddressInfo
import to.bitkit.utils.AddressStats
import to.bitkit.viewmodels.WalletViewModel
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class WalletViewModelTest : BaseUnitTest() {
    private var db: AppDb = mock()
    private var keychain: Keychain = mock()
    private var firebaseMessaging: FirebaseMessaging = mock()
    private var coreService: CoreService = mock()
    private var blocktankNotificationsService: BlocktankNotificationsService = mock()
    private var lightningService: LightningService = mock()
    private var appStorage: AppStorage = mock()
    private val ldkNodeEventBus: LdkNodeEventBus = mock()
    private val settingsStore: SettingsStore = mock()
    private val addressChecker: AddressChecker = mock()

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

        val task = mock<Task<String>> {
            on(it.isComplete).thenReturn(true)
            on(it.result).thenReturn("cachedToken")
        }
        whenever(firebaseMessaging.token).thenReturn(task)

        sut = WalletViewModel(
            bgDispatcher = testDispatcher,
            appContext = mock(),
            appStorage = appStorage,
            db = db,
            keychain = keychain,
            coreService = coreService,
            blocktankNotificationsService = blocktankNotificationsService,
            lightningService = lightningService,
            firebaseMessaging = firebaseMessaging,
            ldkNodeEventBus = ldkNodeEventBus,
            settingsStore = settingsStore,
            addressChecker = addressChecker,
        )
    }

    @Test
    fun `start should emit Content uiState`() = test {
        setupExistingWalletMocks()
        val expectedUiState = MainUiState(
            nodeId = "nodeId",
            onchainAddress = "onchainAddress",
            peers = emptyList(),
            channels = emptyList(),
            balanceDetails = balanceDetails,
            bolt11 = "bolt11",
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
    fun `start should register for notifications if token is not cached`() = test {
        setupExistingWalletMocks()
        val task = mock<Task<String>> {
            on(it.isComplete).thenReturn(true)
            on(it.result).thenReturn("newToken")
        }
        whenever(firebaseMessaging.token).thenReturn(task)
        whenever(keychain.loadString(Keychain.Key.PUSH_NOTIFICATION_TOKEN.name)).thenReturn("cachedToken")

        sut.start()

        verify(blocktankNotificationsService).registerDevice("newToken")
    }

    @Test
    fun `start should skip register for notifications if token is cached`() = test {
        setupExistingWalletMocks()
        whenever(keychain.loadString(Keychain.Key.PUSH_NOTIFICATION_TOKEN.name)).thenReturn("cachedToken")

        sut.start()

        verify(blocktankNotificationsService, never()).registerDevice(anyString())
    }

    @Test
    fun `manualRegisterForNotifications should register device with FCM token`() = test {
        sut.manualRegisterForNotifications()

        verify(blocktankNotificationsService).registerDevice("cachedToken")
    }

    private fun setupExistingWalletMocks() {
        whenever(keychain.exists(Keychain.Key.BIP39_MNEMONIC.name)).thenReturn(true)
        sut.setWalletExistsState()
        whenever(keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name)).thenReturn("mnemonic")
        whenever(appStorage.onchainAddress).thenReturn("onchainAddress")
        whenever(appStorage.bolt11).thenReturn("bolt11")
        whenever(appStorage.bip21).thenReturn("bitcoin:onchainAddress")
        wheneverBlocking { addressChecker.getAddressInfo(anyString()) }.thenReturn(mockAddressInfo)
    }
}

val mockAddressInfo = AddressInfo(
    address = "bc1qar...",
    chain_stats = AddressStats(
        funded_txo_count = 15,
        funded_txo_sum = 0,
        spent_txo_count = 10,
        spent_txo_sum = 0,
        tx_count = 25
    ),
    mempool_stats = AddressStats(
        funded_txo_count = 1,
        funded_txo_sum = 100000,
        spent_txo_count = 0,
        spent_txo_sum = 0,
        tx_count = 1
    )
)
