package to.bitkit.repositories

import app.cash.turbine.test
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Before
import org.junit.Test
import org.lightningdevkit.ldknode.BalanceDetails
import org.lightningdevkit.ldknode.Network
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.data.AppDb
import to.bitkit.data.AppStorage
import to.bitkit.data.SettingsStore
import to.bitkit.data.keychain.Keychain
import to.bitkit.env.Env
import to.bitkit.services.BlocktankNotificationsService
import to.bitkit.services.CoreService
import to.bitkit.test.BaseUnitTest
import to.bitkit.utils.AddressChecker
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WalletRepoTest : BaseUnitTest() {

    private lateinit var sut: WalletRepo

    private val appStorage: AppStorage = mock()
    private val db: AppDb = mock()
    private val keychain: Keychain = mock()
    private val coreService: CoreService = mock()
    private val blocktankNotificationsService: BlocktankNotificationsService = mock()
    private val firebaseMessaging: FirebaseMessaging = mock()
    private val settingsStore: SettingsStore = mock()
    private val addressChecker: AddressChecker = mock()
    private val lightningRepo: LightningRepo = mock()

    @Before
    fun setUp() {
        whenever(appStorage.onchainAddress).thenReturn("")
        whenever(appStorage.bolt11).thenReturn("")
        whenever(appStorage.bip21).thenReturn("")

        whenever(lightningRepo.getSyncFlow()).thenReturn(flowOf(Unit))

        sut = WalletRepo(
            bgDispatcher = testDispatcher,
            appStorage = appStorage,
            db = db,
            keychain = keychain,
            coreService = coreService,
            blocktankNotificationsService = blocktankNotificationsService,
            firebaseMessaging = firebaseMessaging,
            settingsStore = settingsStore,
            addressChecker = addressChecker,
            lightningRepo = lightningRepo,
            network = Network.REGTEST
        )
    }

    @Test
    fun `init should collect from sync flow and sync balances`() = test {
        val lightningRepo: LightningRepo = mock()

        val testFlow = MutableStateFlow(Unit)
        whenever(lightningRepo.getSyncFlow()).thenReturn(testFlow)
        whenever(lightningRepo.sync()).thenReturn(Result.success(Unit))
        verify(lightningRepo).sync()
    }

    @Test
    fun `walletExists should return true when mnemonic exists in keychain`() = test {
        whenever(keychain.exists(Keychain.Key.BIP39_MNEMONIC.name)).thenReturn(true)

        val result = sut.walletExists()

        assertTrue(result)
    }

    @Test
    fun `walletExists should return false when mnemonic does not exist in keychain`() = test {
        whenever(keychain.exists(Keychain.Key.BIP39_MNEMONIC.name)).thenReturn(false)

        val result = sut.walletExists()

        assertFalse(result)
    }

    @Test
    fun `setWalletExistsState should update walletState with current existence status`() = test {
        whenever(keychain.exists(Keychain.Key.BIP39_MNEMONIC.name)).thenReturn(true)

        sut.setWalletExistsState()

        sut.walletState.test {
            val state = awaitItem()
            assertTrue(state.walletExists)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `restoreWallet should save provided mnemonic and passphrase to keychain`() = test {
        val mnemonic = "restore mnemonic"
        val passphrase = "restore passphrase"
        whenever(keychain.saveString(any(), any())).thenReturn(Unit)

        val result = sut.restoreWallet(mnemonic, passphrase)

        assertTrue(result.isSuccess)
        verify(keychain).saveString(Keychain.Key.BIP39_MNEMONIC.name, mnemonic)
        verify(keychain).saveString(Keychain.Key.BIP39_PASSPHRASE.name, passphrase)
    }

    @Test
    fun `wipeWallet should fail when not on regtest`() = test {

        val result = sut.wipeWallet()

        assertTrue(result.isFailure)
    }

    @Test
    fun `refreshBip21 should generate new address when current is empty`() = test {
        whenever(sut.getOnchainAddress()).thenReturn("")
        whenever(lightningRepo.newAddress()).thenReturn(Result.success("newAddress"))

        val result = sut.refreshBip21()

        assertTrue(result.isSuccess)
        verify(lightningRepo).newAddress()
    }

    @Test
    fun `syncBalances should update balance state`() = test {
        val balanceDetails = mock<BalanceDetails> {
            on { totalLightningBalanceSats } doReturn 500uL
            on { totalOnchainBalanceSats } doReturn 1000uL
        }
        whenever(lightningRepo.getBalances()).thenReturn(balanceDetails)

        sut.syncBalances()

        sut.balanceState.test {
            val state = awaitItem()
            assertEquals(expected = 1500uL, state.totalSats)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
