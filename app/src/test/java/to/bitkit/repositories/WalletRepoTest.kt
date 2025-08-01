package to.bitkit.repositories

import app.cash.turbine.test
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Before
import org.junit.Test
import org.lightningdevkit.ldknode.BalanceDetails
import org.lightningdevkit.ldknode.ChannelDetails
import org.lightningdevkit.ldknode.Event
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.kotlin.wheneverBlocking
import to.bitkit.data.AppCacheData
import to.bitkit.data.AppDb
import to.bitkit.data.CacheStore
import to.bitkit.data.SettingsStore
import to.bitkit.data.keychain.Keychain
import to.bitkit.services.CoreService
import to.bitkit.services.OnchainService
import to.bitkit.test.BaseUnitTest
import to.bitkit.utils.AddressChecker
import to.bitkit.utils.AddressInfo
import to.bitkit.utils.AddressStats
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WalletRepoTest : BaseUnitTest() {

    private lateinit var sut: WalletRepo

    private val db: AppDb = mock()
    private val keychain: Keychain = mock()
    private val coreService: CoreService = mock()
    private val onchainService: OnchainService = mock()
    private val settingsStore: SettingsStore = mock()
    private val addressChecker: AddressChecker = mock()
    private val lightningRepo: LightningRepo = mock()
    private val cacheStore: CacheStore = mock()

    @Before
    fun setUp() {
        wheneverBlocking { coreService.shouldBlockLightning() }.thenReturn(false)
        whenever(cacheStore.data).thenReturn(flowOf(AppCacheData()))
        whenever(lightningRepo.getSyncFlow()).thenReturn(flowOf(Unit))
        whenever(lightningRepo.lightningState).thenReturn(MutableStateFlow(LightningState()))

        whenever(keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name)).thenReturn("test mnemonic")
        whenever(keychain.loadString(Keychain.Key.BIP39_PASSPHRASE.name)).thenReturn(null)

        whenever(coreService.onchain).thenReturn(onchainService)
        sut = createSut()
    }

    private fun createSut() = WalletRepo(
        bgDispatcher = testDispatcher,
        db = db,
        keychain = keychain,
        coreService = coreService,
        settingsStore = settingsStore,
        addressChecker = addressChecker,
        lightningRepo = lightningRepo,
        cacheStore = cacheStore,
    )

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
    fun `refreshBip21 should generate new address when current is empty`() = test {
        whenever(lightningRepo.newAddress()).thenReturn(Result.success("newAddress"))
        whenever(addressChecker.getAddressInfo(any())).thenReturn(mock())

        val result = sut.refreshBip21()

        assertTrue(result.isSuccess)
        verify(lightningRepo).newAddress()
    }

    @Test
    fun `refreshBip21 should set receiveOnSpendingBalance as false if shouldBlockLightning is true`() = test {
        wheneverBlocking { coreService.shouldBlockLightning() }.thenReturn(true)
        whenever(lightningRepo.newAddress()).thenReturn(Result.success("newAddress"))
        whenever(addressChecker.getAddressInfo(any())).thenReturn(mock())

        val result = sut.refreshBip21()

        assertTrue(result.isSuccess)
        assertEquals(false, sut.walletState.value.receiveOnSpendingBalance)
    }

    @Test
    fun `refreshBip21 should generate new address when current has transactions`() = test {
        val testAddress = "testAddress"
        whenever(cacheStore.data).thenReturn(flowOf(AppCacheData(onchainAddress = testAddress)))
        whenever(lightningRepo.newAddress()).thenReturn(Result.success("newAddress"))
        whenever(addressChecker.getAddressInfo(any())).thenReturn(
            mockAddressInfo().let { addressInfo ->
                addressInfo.copy(
                    chain_stats = addressInfo.chain_stats.copy(tx_count = 5),
                    mempool_stats = addressInfo.mempool_stats.copy(tx_count = 5)
                )
            }
        )

        val result = sut.refreshBip21()

        assertTrue(result.isSuccess)
        verify(lightningRepo).newAddress()
    }

    @Test
    fun `refreshBip21 should keep address when current has no transactions`() = test {
        val existingAddress = "existingAddress"
        whenever(cacheStore.data).thenReturn(flowOf(AppCacheData(onchainAddress = existingAddress)))
        whenever(addressChecker.getAddressInfo(any())).thenReturn(mockAddressInfo())
        sut = createSut()

        val result = sut.refreshBip21()

        assertTrue(result.isSuccess)
        verify(lightningRepo, never()).newAddress()
    }

    @Test
    fun `refreshBip21 forced should always generate new address`() = test {
        val existingAddress = "existingAddress"
        whenever(cacheStore.data).thenReturn(flowOf(AppCacheData(onchainAddress = existingAddress)))
        whenever(lightningRepo.newAddress()).thenReturn(Result.success("newAddress"))
        whenever(addressChecker.getAddressInfo(any())).thenReturn(mockAddressInfo())

        val result = sut.refreshBip21(force = true)

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

        val channels = listOf(
            mock<ChannelDetails> {
                on { isUsable } doReturn true
                on { nextOutboundHtlcLimitMsat } doReturn 1000uL
            },
        )
        whenever(lightningRepo.getChannels()).thenReturn(channels)

        sut.syncBalances()

        sut.balanceState.test {
            val state = awaitItem()
            assertEquals(1500uL, state.totalSats)
            assertEquals(500uL, state.totalLightningSats)
            assertEquals(1000uL, state.totalOnchainSats)
            assertEquals(1uL, state.maxSendLightningSats)
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
    fun `refreshBip21ForEvent should not refresh for other events`() = test {
        sut.refreshBip21ForEvent(
            Event.PaymentSuccessful(
                paymentId = "",
                paymentHash = "",
                paymentPreimage = "",
                feePaidMsat = 10uL
            )
        )

        verify(onchainService, never()).deriveBitcoinAddress(any(), any(), any(), anyOrNull())
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
        val testAddress = "testAddress"
        whenever(cacheStore.data).thenReturn(flowOf(AppCacheData(onchainAddress = testAddress)))
        whenever(lightningRepo.hasChannels()).thenReturn(false)
        sut = createSut()

        sut.updateBip21Invoice(amountSats = 1000uL, description = "test").let { result ->
            assertTrue(result.isSuccess)
            assertTrue(sut.walletState.value.bip21.contains(testAddress))
            assertTrue(sut.walletState.value.bip21.contains("amount=0.00001"))
            assertTrue(sut.walletState.value.bip21.contains("message=test"))
        }
    }

    @Test
    fun `setOnchainAddress should update storage and state`() = test {
        val testAddress = "testAddress"

        sut.setOnchainAddress(testAddress)

        assertEquals(testAddress, sut.walletState.value.onchainAddress)
        verify(cacheStore).setOnchainAddress(testAddress)
    }

    @Test
    fun `setBolt11 should update storage and state`() = test {
        val testBolt11 = "testBolt11"

        sut.setBolt11(testBolt11)

        assertEquals(testBolt11, sut.walletState.value.bolt11)
        verify(cacheStore).saveBolt11(testBolt11)
    }

    @Test
    fun `setBip21 should update storage and state`() = test {
        val testBip21 = "testBip21"

        sut.setBip21(testBip21)

        assertEquals(testBip21, sut.walletState.value.bip21)
        verify(cacheStore).setBip21(testBip21)
    }

    @Test
    fun `buildBip21Url should create correct URL`() = test {
        val testAddress = "testAddress"
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

        if (sut.walletState.value.receiveOnSpendingBalance) {
            sut.toggleReceiveOnSpendingBalance()
        }

        val result = sut.toggleReceiveOnSpendingBalance()

        assert(result.isFailure)
    }

    @Test
    fun `addTagToSelected should add tag and update lastUsedTags`() = test {
        val testTag = "testTag"

        sut.addTagToSelected(testTag)

        assertEquals(listOf(testTag), sut.walletState.value.selectedTags)
        verify(settingsStore).addLastUsedTag(testTag)
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

private fun mockAddressInfo() = AddressInfo(
    address = "testAddress",
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
)
