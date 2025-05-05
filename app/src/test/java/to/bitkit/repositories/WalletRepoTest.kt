package to.bitkit.repositories

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.lightningdevkit.ldknode.BalanceDetails
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.annotation.Config
import to.bitkit.data.AppDb
import to.bitkit.data.AppStorage
import to.bitkit.data.SettingsStore
import to.bitkit.data.keychain.Keychain
import to.bitkit.ext.toHex
import to.bitkit.services.BlocktankNotificationsService
import to.bitkit.services.CoreService
import to.bitkit.test.BaseUnitTest
import to.bitkit.test.TestApp
import to.bitkit.utils.AddressChecker
import uniffi.bitkitcore.Activity
import uniffi.bitkitcore.ActivityFilter
import uniffi.bitkitcore.PaymentType
import uniffi.bitkitcore.Scanner
import uniffi.bitkitcore.decode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days

@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
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
            lightningRepo = lightningRepo
        )
    }

    @Test
    fun `init should collect from sync flow and sync balances`() = test {
        val lightningRepo: LightningRepo = mock()

        val testFlow = MutableStateFlow(Unit)
        whenever(lightningRepo.getSyncFlow()).thenReturn(testFlow)
        whenever(lightningRepo.sync()).thenReturn(Result.success(Unit))

        // Recreate sut with the new mock
        val testSut = WalletRepo(
            bgDispatcher = testDispatcher,
            appStorage = appStorage,
            db = db,
            keychain = keychain,
            coreService = coreService,
            blocktankNotificationsService = blocktankNotificationsService,
            firebaseMessaging = firebaseMessaging,
            settingsStore = settingsStore,
            addressChecker = addressChecker,
            lightningRepo = lightningRepo
        )

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
    fun `createWallet should save mnemonic and passphrase to keychain`() = test {
        val mnemonic = "test mnemonic"
        val passphrase = "test passphrase"
        whenever(keychain.saveString(any(), any())).thenReturn(Unit)

        val result = sut.createWallet(passphrase)

        assertTrue(result.isSuccess)
        verify(keychain).saveString(Keychain.Key.BIP39_MNEMONIC.name, any())
        verify(keychain).saveString(Keychain.Key.BIP39_PASSPHRASE.name, passphrase)
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
    fun `wipeWallet should clear all data when on regtest`() = test {
//        Env.network = Network.REGTEST
        whenever(keychain.wipe()).thenReturn(Unit)
        whenever(appStorage.clear()).thenReturn(Unit)
        whenever(settingsStore.wipe()).thenReturn(Unit)

        val result = sut.wipeWallet()

        assertTrue(result.isSuccess)
        verify(keychain).wipe()
        verify(appStorage).clear()
        verify(settingsStore).wipe()
        verify(coreService.activity).removeAll()
    }

    @Test
    fun `wipeWallet should fail when not on regtest`() = test {
//        Env.network = Network.TESTNET

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

//    @Test
//    fun `refreshBip21 should generate new address when current has transactions`() = test {
//        whenever(sut.getOnchainAddress()).thenReturn("usedAddress")
//        whenever(lightningRepo.checkAddressUsage("usedAddress")).thenReturn(Result.success(true))
//        whenever(lightningRepo.newAddress()).thenReturn(Result.success("newAddress"))
//
//        val result = sut.refreshBip21()
//
//        assertTrue(result.isSuccess)
//        verify(lightningRepo).newAddress()
//    }

//    @Test
//    fun `refreshBip21 should not generate new address when current has no transactions`() = test {
//        whenever(sut.getOnchainAddress()).thenReturn("unusedAddress")
//        whenever(lightningRepo.checkAddressUsage("unusedAddress")).thenReturn(Result.success(false))
//
//        val result = sut.refreshBip21()
//
//        assertTrue(result.isSuccess)
//        verify(lightningRepo, never()).newAddress()
//    }

    @Test
    fun `updateBip21Invoice should build correct BIP21 URL`() = test {
        val address = "testAddress"
        val amount = 1000uL
        val description = "test description"
        val bolt11 = "testBolt11"

        whenever(sut.getOnchainAddress()).thenReturn(address)
        whenever(lightningRepo.hasChannels()).thenReturn(true)
        whenever(lightningRepo.createInvoice(anyOrNull(), anyOrNull())).thenReturn(Result.success(bolt11))

        sut.updateBip21Invoice(amount, description)
        verify(appStorage).bip21 = any() // Verify BIP21 was saved
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

//    @Test
//    fun `registerForNotifications should save token when different from cached`() = test {
//        val token = "testToken"
//        whenever(firebaseMessaging.token).thenReturn(mock {
//            on { await() } doReturn token
//        })
//        whenever(keychain.loadString(Keychain.Key.PUSH_NOTIFICATION_TOKEN.name)).thenReturn("oldToken")
//
//        val result = sut.registerForNotifications()
//
//        assertThat(result.isSuccess).isTrue()
//        verify(blocktankNotificationsService).registerDevice(token)
//    }

//    @Test
//    fun `registerForNotifications should skip when token matches cached`() = test {
//        val token = "testToken"
//        whenever(firebaseMessaging.token).thenReturn(mock {
//            on { await() } doReturn token
//        })
//        whenever(keychain.loadString(Keychain.Key.PUSH_NOTIFICATION_TOKEN.name)).thenReturn(token)
//
//        val result = sut.registerForNotifications()
//
//        assertThat(result.isSuccess).isTrue()
//        verify(blocktankNotificationsService, never()).registerDevice(any())
//    }

    @Test
    fun `saveInvoiceWithTags should save invoice with tags`() = test {
        val bip21 = "bitcoin:address?lightning=lnbc123"
        val tags = listOf("tag1", "tag2")
        val decoded = mock<Scanner.Lightning>()
        whenever(decode(bip21)).thenReturn(decoded)
        whenever(decoded.invoice.paymentHash.toHex()).thenReturn("paymentHash")

        sut.saveInvoiceWithTags(bip21, tags)

        verify(db.invoiceTagDao()).saveInvoice(any())
    }

    @Test
    fun `deleteExpiredInvoices should delete invoices older than 2 days`() = test {
        val twoDaysAgo = System.currentTimeMillis() - 2.days.inWholeMilliseconds
        sut.deleteExpiredInvoices()

        verify(db.invoiceTagDao()).deleteExpiredInvoices(twoDaysAgo)
    }

    @Test
    fun `attachTagsToActivity should attach tags to matching activity`() = test {
        val paymentHash = "testHash"
        val tags = listOf("tag1", "tag2")
        val activity = mock<Activity.Lightning>()
        whenever(activity.v1.id).thenReturn(paymentHash)
        whenever(coreService.activity.get(any(), any(), any())).thenReturn(listOf(activity))
        whenever(coreService.activity.appendTags(any(), any())).thenReturn(Result.success(Unit))

        val result = sut.attachTagsToActivity(
            paymentHashOrTxId = paymentHash,
            type = ActivityFilter.ALL,
            txType = PaymentType.RECEIVED,
            tags = tags
        )

        assertTrue(result.isSuccess)
        verify(coreService.activity).appendTags(paymentHash, tags)
    }
}
