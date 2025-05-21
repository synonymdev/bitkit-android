package to.bitkit.repositories

import app.cash.turbine.test
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Before
import org.junit.Test
import org.lightningdevkit.ldknode.BalanceDetails
import org.lightningdevkit.ldknode.ChannelDetails
import org.lightningdevkit.ldknode.Event
import org.lightningdevkit.ldknode.Network
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.kotlin.wheneverBlocking
import to.bitkit.data.AppDb
import to.bitkit.data.AppStorage
import to.bitkit.data.SettingsStore
import to.bitkit.data.keychain.Keychain
import to.bitkit.services.CoreService
import to.bitkit.test.BaseUnitTest
import to.bitkit.utils.AddressChecker
import to.bitkit.utils.AddressInfo
import to.bitkit.utils.AddressStats
import uniffi.bitkitcore.ActivityFilter
import uniffi.bitkitcore.PaymentType
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WalletRepoTest : BaseUnitTest() {

    private lateinit var sut: WalletRepo

    private val appStorage: AppStorage = mock()
    private val db: AppDb = mock()
    private val keychain: Keychain = mock()
    private val coreService: CoreService = mock()
    private val settingsStore: SettingsStore = mock()
    private val addressChecker: AddressChecker = mock()
    private val lightningRepo: LightningRepo = mock()

    @Before
    fun setUp() {
        wheneverBlocking { coreService.shouldBlockLightning() }.thenReturn(false)
        whenever(appStorage.onchainAddress).thenReturn("")
        whenever(appStorage.bolt11).thenReturn("")
        whenever(appStorage.bip21).thenReturn("")
        whenever(appStorage.loadBalance()).thenReturn(null)
        whenever(lightningRepo.getSyncFlow()).thenReturn(flowOf(Unit))
        whenever(lightningRepo.lightningState).thenReturn(MutableStateFlow(LightningState()))

        sut = WalletRepo(
            bgDispatcher = testDispatcher,
            appStorage = appStorage,
            db = db,
            keychain = keychain,
            coreService = coreService,
            settingsStore = settingsStore,
            addressChecker = addressChecker,
            lightningRepo = lightningRepo,
            network = Network.REGTEST
        )
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
    fun `restoreWallet should work without passphrase`() = test {
        val mnemonic = "restore mnemonic"
        whenever(keychain.saveString(any(), any())).thenReturn(Unit)

        val result = sut.restoreWallet(mnemonic, null)

        assertTrue(result.isSuccess)
        verify(keychain).saveString(Keychain.Key.BIP39_MNEMONIC.name, mnemonic)
    }

    @Test
    fun `wipeWallet should fail when not on regtest`() = test {
        val nonRegtestRepo = WalletRepo(
            bgDispatcher = testDispatcher,
            appStorage = appStorage,
            db = db,
            keychain = keychain,
            coreService = coreService,
            settingsStore = settingsStore,
            addressChecker = addressChecker,
            lightningRepo = lightningRepo,
            network = Network.BITCOIN
        )

        val result = nonRegtestRepo.wipeWallet()

        assertTrue(result.isFailure)
    }

    @Test
    fun `refreshBip21 should generate new address when current is empty`() = test {
        whenever(sut.getOnchainAddress()).thenReturn("")
        whenever(lightningRepo.newAddress()).thenReturn(Result.success("newAddress"))
        whenever(addressChecker.getAddressInfo(any())).thenReturn(mock())

        val result = sut.refreshBip21()

        assertTrue(result.isSuccess)
        verify(lightningRepo).newAddress()
    }

    @Test
    fun `refreshBip21 should set receiveOnSpendingBalance as false if shouldBlockLightning is true`() = test {
        wheneverBlocking { coreService.shouldBlockLightning() }.thenReturn(true)
        whenever(sut.getOnchainAddress()).thenReturn("")
        whenever(lightningRepo.newAddress()).thenReturn(Result.success("newAddress"))
        whenever(addressChecker.getAddressInfo(any())).thenReturn(mock())

        val result = sut.refreshBip21()

        assertTrue(result.isSuccess)
        assertEquals(false, sut.walletState.value.receiveOnSpendingBalance)
    }

    @Test
    fun `refreshBip21 should generate new address when current has transactions`() = test {
        whenever(sut.getOnchainAddress()).thenReturn("bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq")
        whenever(lightningRepo.newAddress()).thenReturn(Result.success("newAddress"))
        whenever(addressChecker.getAddressInfo(any())).thenReturn(AddressInfo(
            address = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq",
            chain_stats = AddressStats(
                funded_txo_count = 1,
                funded_txo_sum = 2,
                spent_txo_count = 1,
                spent_txo_sum = 1,
                tx_count = 5
            ),
            mempool_stats = AddressStats(
                funded_txo_count = 1,
                funded_txo_sum = 2,
                spent_txo_count = 1,
                spent_txo_sum = 1,
                tx_count = 5
            )
        ))

        val result = sut.refreshBip21()

        assertTrue(result.isSuccess)
        verify(lightningRepo).newAddress()
    }

    @Test
    fun `refreshBip21 should keep address when current has no transactions`() = test {
        val existingAddress = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"
        whenever(sut.getOnchainAddress()).thenReturn(existingAddress)
        whenever(addressChecker.getAddressInfo(any())).thenReturn(AddressInfo(
            address = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq",
            chain_stats = AddressStats(
                funded_txo_count = 1,
                funded_txo_sum = 2,
                spent_txo_count = 1,
                spent_txo_sum = 1,
                tx_count = 0
            ),
            mempool_stats = AddressStats(
                funded_txo_count = 1,
                funded_txo_sum = 2,
                spent_txo_count = 1,
                spent_txo_sum = 1,
                tx_count = 0
            )
        ))

        val result = sut.refreshBip21()

        assertTrue(result.isSuccess)
        verify(lightningRepo, never()).newAddress()
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
            assertEquals(1500uL, state.totalSats)
            assertEquals(500uL, state.totalLightningSats)
            assertEquals(1000uL, state.totalOnchainSats)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `syncBalances should update wallet state with balance details`() = test {
        val balanceDetails = mock<BalanceDetails>()
        whenever(lightningRepo.getBalances()).thenReturn(balanceDetails)

        sut.syncBalances()

        assertEquals(balanceDetails, sut.walletState.value.balanceDetails)
    }

    @Test
    fun `syncBalances should set showEmptyState to false when balance is positive`() = test {
        val balanceDetails = mock<BalanceDetails> {
            on { totalLightningBalanceSats } doReturn 500uL
            on { totalOnchainBalanceSats } doReturn 1000uL
        }
        whenever(lightningRepo.getBalances()).thenReturn(balanceDetails)

        sut.syncBalances()

        assertFalse(sut.walletState.value.showEmptyState)
    }

    @Test
    fun `syncBalances should set showEmptyState to true when balance is zero`() = test {
        val balanceDetails = mock<BalanceDetails> {
            on { totalLightningBalanceSats } doReturn 0uL
            on { totalOnchainBalanceSats } doReturn 0uL
        }
        whenever(lightningRepo.getBalances()).thenReturn(balanceDetails)

        sut.syncBalances()

        assertTrue(sut.walletState.value.showEmptyState)
    }

    @Test
    fun `refreshBip21ForEvent should not refresh for other events`() = test {
        sut.refreshBip21ForEvent(Event.PaymentSuccessful(paymentId = "", paymentHash = "", paymentPreimage = "", feePaidMsat = 10uL))

        verify(lightningRepo, never()).newAddress()
    }

    @Test
    fun `updateBip21Invoice should create bolt11 when channels exist`() = test {
        val testInvoice = "testInvoice"
        whenever(lightningRepo.hasChannels()).thenReturn(true)
        whenever(lightningRepo.createInvoice(1000uL, description = "test")).thenReturn(Result.success(testInvoice))

        sut.updateBip21Invoice(amountSats = 1000uL, description = "test").let { result ->
            assertTrue(result.isSuccess)
            assertEquals(testInvoice, sut.walletState.value.bolt11)
        }
    }

    @Test
    fun `updateBip21Invoice should not create bolt11 when no channels exist`() = test {
        whenever(lightningRepo.hasChannels()).thenReturn(false)

        sut.updateBip21Invoice(amountSats = 1000uL, description = "test").let { result ->
            assertTrue(result.isSuccess)
            assertEquals("", sut.walletState.value.bolt11)
        }
    }

    @Test
    fun `updateBip21Invoice should build correct BIP21 URL`() = test {
        val testAddress = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"
        whenever(sut.getOnchainAddress()).thenReturn(testAddress)
        whenever(lightningRepo.hasChannels()).thenReturn(false)

        sut.updateBip21Invoice(amountSats = 1000uL, description = "test").let { result ->
            assertTrue(result.isSuccess)
            assertTrue(sut.walletState.value.bip21.contains(testAddress))
            assertTrue(sut.walletState.value.bip21.contains("amount=0.00001"))
            assertTrue(sut.walletState.value.bip21.contains("message=test"))
        }
    }

    @Test
    fun `attachTagsToActivity should fail with empty tags`() = test {
        val result = sut.attachTagsToActivity("txId", ActivityFilter.ALL, PaymentType.SENT, emptyList())

        assertTrue(result.isFailure)
    }

    @Test
    fun `attachTagsToActivity should fail with null payment hash`() = test {
        val result = sut.attachTagsToActivity(null, ActivityFilter.ALL, PaymentType.SENT, listOf("tag1"))

        assertTrue(result.isFailure)
    }

    @Test
    fun `setRestoringWalletState should update state`() = test {
        sut.setRestoringWalletState(true)

        assertTrue(sut.walletState.value.isRestoringWallet)
    }

    @Test
    fun `setOnchainAddress should update storage and state`() = test {
        val testAddress = "testAddress"

        sut.setOnchainAddress(testAddress)

        assertEquals(testAddress, sut.walletState.value.onchainAddress)
        verify(appStorage).onchainAddress = testAddress
    }

    @Test
    fun `setBolt11 should update storage and state`() = test {
        val testBolt11 = "testBolt11"

        sut.setBolt11(testBolt11)

        assertEquals(testBolt11, sut.walletState.value.bolt11)
        verify(appStorage).bolt11 = testBolt11
    }

    @Test
    fun `setBip21 should update storage and state`() = test {
        val testBip21 = "testBip21"

        sut.setBip21(testBip21)

        assertEquals(testBip21, sut.walletState.value.bip21)
        verify(appStorage).bip21 = testBip21
    }

    @Test
    fun `buildBip21Url should create correct URL`() = test {
        val testAddress = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"
        val testAmount = 1000uL
        val testMessage = "test message"
        val testInvoice = "testInvoice"

        val result = sut.buildBip21Url(testAddress, testAmount, testMessage, testInvoice)

        assertTrue(result.contains(testAddress))
        assertTrue(result.contains("amount=0.00001"))
        assertTrue(result.contains("message=test+message"))
        assertTrue(result.contains("lightning=testInvoice"))
    }

    @Test
    fun `toggleReceiveOnSpendingBalance should toggle state`() = test {
        val initialValue = sut.walletState.value.receiveOnSpendingBalance

        sut.toggleReceiveOnSpendingBalance()

        assertEquals(!initialValue, sut.walletState.value.receiveOnSpendingBalance)
    }

    @Test
    fun `toggleReceiveOnSpendingBalance should return failure if shouldBlockLightning is true`() = test {
        wheneverBlocking { coreService.shouldBlockLightning() }.thenReturn(true)

        if (sut.walletState.value.receiveOnSpendingBalance == true) {
            sut.toggleReceiveOnSpendingBalance()
        }

        val result = sut.toggleReceiveOnSpendingBalance()

        assert(result.isFailure)
    }

    @Test
    fun `addTagToSelected should add tag`() = test {
        val testTag = "testTag"

        sut.addTagToSelected(testTag)

        assertEquals(listOf(testTag), sut.walletState.value.selectedTags)
    }

    @Test
    fun `removeTag should remove tag`() = test {
        val testTag = "testTag"
        sut.addTagToSelected(testTag)

        sut.removeTag(testTag)

        assertTrue(sut.walletState.value.selectedTags.isEmpty())
    }

    @Test
    fun `shouldRequestAdditionalLiquidity should return false when receiveOnSpendingBalance is false`() = test {
        // Given
        whenever(coreService.checkGeoStatus()).thenReturn(false)
        sut.toggleReceiveOnSpendingBalance() // Set to false (initial is true)

        // When
        val result = sut.shouldRequestAdditionalLiquidity()

        // Then
        assertTrue(result.isSuccess)
        assertFalse(result.getOrThrow())
    }

    @Test
    fun `shouldRequestAdditionalLiquidity should return false when geo status is true`() = test {
        // Given
        whenever(coreService.checkGeoStatus()).thenReturn(true)

        // When
        val result = sut.shouldRequestAdditionalLiquidity()

        // Then
        assertTrue(result.isSuccess)
        assertFalse(result.getOrThrow())
    }

    @Test
    fun `shouldRequestAdditionalLiquidity should return true when amount exceeds inbound capacity`() = test {
        // Given
        whenever(coreService.checkGeoStatus()).thenReturn(false)
        val testChannels = listOf(
            mock<ChannelDetails> {
                on { inboundCapacityMsat } doReturn 500_000u // 500 sats
            },
            mock<ChannelDetails> {
                on { inboundCapacityMsat } doReturn 300_000u // 300 sats
            }
        )
        whenever(lightningRepo.lightningState).thenReturn(MutableStateFlow(LightningState(channels = testChannels)))
        sut.updateBip21Invoice(amountSats = 1000uL) // 1000 sats

        // When
        val result = sut.shouldRequestAdditionalLiquidity()

        // Then
        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow())
    }

    @Test
    fun `shouldRequestAdditionalLiquidity should return false when amount is less than inbound capacity`() = test {
        // Given
        whenever(coreService.checkGeoStatus()).thenReturn(false)
        val testChannels = listOf(
            mock<ChannelDetails> {
                on { inboundCapacityMsat } doReturn 500_000u // 500 sats
            },
            mock<ChannelDetails> {
                on { inboundCapacityMsat } doReturn 500_000u // 500 sats
            }
        )
        whenever(lightningRepo.lightningState).thenReturn(MutableStateFlow(LightningState(channels = testChannels)))
        sut.updateBip21Invoice(amountSats = 900uL) // 900 sats

        // When
        val result = sut.shouldRequestAdditionalLiquidity()

        // Then
        assertTrue(result.isSuccess)
        assertFalse(result.getOrThrow())
    }

    @Test
    fun `shouldRequestAdditionalLiquidity should return failure when exception occurs`() = test {
        // Given
        whenever(coreService.checkGeoStatus()).thenThrow(RuntimeException("Test error"))

        // When
        val result = sut.shouldRequestAdditionalLiquidity()

        // Then
        assertTrue(result.isFailure)
    }
}
