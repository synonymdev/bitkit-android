package to.bitkit.repositories

import app.cash.turbine.test
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.lightningdevkit.ldknode.ChannelDetails
import org.lightningdevkit.ldknode.Event
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.data.AppCacheData
import to.bitkit.data.CacheStore
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.data.keychain.Keychain
import to.bitkit.models.BalanceState
import to.bitkit.services.CoreService
import to.bitkit.services.OnchainService
import to.bitkit.test.BaseUnitTest
import to.bitkit.usecases.DeriveBalanceStateUseCase
import to.bitkit.usecases.WipeWalletUseCase
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WalletRepoTest : BaseUnitTest() {
    private lateinit var sut: WalletRepo

    private val keychain = mock<Keychain>()
    private val coreService = mock<CoreService>()
    private val onchainService = mock<OnchainService>()
    private val settingsStore = mock<SettingsStore>()
    private val lightningRepo = mock<LightningRepo>()
    private val cacheStore = mock<CacheStore>()
    private val preActivityMetadataRepo = mock<PreActivityMetadataRepo>()
    private val deriveBalanceStateUseCase = mock<DeriveBalanceStateUseCase>()
    private val wipeWalletUseCase = mock<WipeWalletUseCase>()
    private val transferRepo = mock<TransferRepo>()

    companion object Fixtures {
        const val ACTIVITY_TAG = "testTag"
        const val ADDRESS = "bc1qTest"
        const val ADDRESS_NEW = "newAddress"
        const val INVOICE = "testInvoice"
        const val SATS = 1000uL
        val error = RuntimeException("Test Error")
        val channels = listOf(
            mock<ChannelDetails> {
                on { inboundCapacityMsat } doReturn 500_000u
                on { isChannelReady } doReturn true
            },
            mock<ChannelDetails> {
                on { inboundCapacityMsat } doReturn 500_000u
                on { isChannelReady } doReturn true
            }
        )
        private val channelReady = Event.ChannelReady(
            channelId = "testChannelId",
            userChannelId = "testUserChannelId",
            counterpartyNodeId = null,
            fundingTxo = null,
        )
    }

    @Before
    fun setUp() = runBlocking {
        whenever(coreService.checkGeoBlock()).thenReturn(Pair(false, false))
        whenever(cacheStore.data).thenReturn(flowOf(AppCacheData(bolt11 = "", onchainAddress = ADDRESS)))
        whenever(lightningRepo.lightningState).thenReturn(MutableStateFlow(LightningState()))
        whenever(lightningRepo.nodeEvents).thenReturn(MutableSharedFlow())
        whenever(lightningRepo.listSpendableOutputs()).thenReturn(Result.success(emptyList()))
        whenever(lightningRepo.calculateTotalFee(any(), any(), any(), any(), anyOrNull()))
            .thenReturn(Result.success(SATS))
        whenever(lightningRepo.canReceive()).thenReturn(false)
        whenever(settingsStore.data).thenReturn(flowOf(SettingsData()))
        whenever(deriveBalanceStateUseCase.invoke()).thenReturn(Result.success(BalanceState()))

        whenever(keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name)).thenReturn("test mnemonic")
        whenever(keychain.loadString(Keychain.Key.BIP39_PASSPHRASE.name)).thenReturn(null)

        whenever(coreService.onchain).thenReturn(onchainService)
        whenever(preActivityMetadataRepo.addPreActivityMetadataTags(any(), any())).thenReturn(Result.success(Unit))
        whenever(preActivityMetadataRepo.removePreActivityMetadataTags(any(), any())).thenReturn(Result.success(Unit))
        whenever(preActivityMetadataRepo.getPreActivityMetadata(any(), any())).thenReturn(Result.success(null))
        whenever(preActivityMetadataRepo.upsertPreActivityMetadata(any())).thenReturn(Result.success(Unit))
        whenever(preActivityMetadataRepo.addPreActivityMetadata(any())).thenReturn(Result.success(Unit))
        whenever(preActivityMetadataRepo.resetPreActivityMetadataTags(any())).thenReturn(Result.success(Unit))
        whenever(preActivityMetadataRepo.deletePreActivityMetadata(any())).thenReturn(Result.success(Unit))
        sut = createSut()
    }

    private fun createSut() = WalletRepo(
        bgDispatcher = testDispatcher,
        keychain = keychain,
        coreService = coreService,
        settingsStore = settingsStore,
        lightningRepo = lightningRepo,
        cacheStore = cacheStore,
        preActivityMetadataRepo = preActivityMetadataRepo,
        deriveBalanceStateUseCase = deriveBalanceStateUseCase,
        wipeWalletUseCase = wipeWalletUseCase,
        transferRepo = transferRepo,
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
        whenever(lightningRepo.newAddress()).thenReturn(Result.success(ADDRESS_NEW))

        val result = sut.refreshBip21()

        assertTrue(result.isSuccess)
        verify(lightningRepo).newAddress()
    }

    @Test
    fun `refreshBip21 should set receiveOnSpendingBalance false when shouldBlockLightning is true`() = test {
        whenever(coreService.checkGeoBlock()).thenReturn(Pair(true, true))
        whenever(lightningRepo.newAddress()).thenReturn(Result.success(ADDRESS_NEW))

        val result = sut.refreshBip21()

        assertTrue(result.isSuccess)
        assertEquals(false, sut.walletState.value.receiveOnSpendingBalance)
    }

    @Test
    fun `refreshBip21 should set receiveOnSpendingBalance true when shouldBlockLightning is false`() = test {
        whenever(coreService.checkGeoBlock()).thenReturn(Pair(true, false))
        whenever(lightningRepo.newAddress()).thenReturn(Result.success(ADDRESS_NEW))

        val result = sut.refreshBip21()

        assertTrue(result.isSuccess)
        assertEquals(true, sut.walletState.value.receiveOnSpendingBalance)
    }

    @Test
    fun `refreshBip21 should generate new address when current has transactions`() = test {
        whenever(cacheStore.data).thenReturn(flowOf(AppCacheData(onchainAddress = ADDRESS)))
        whenever(lightningRepo.newAddress()).thenReturn(Result.success(ADDRESS_NEW))
        whenever(coreService.isAddressUsed(any())).thenReturn(true)

        val result = sut.refreshBip21()

        assertTrue(result.isSuccess)
        verify(lightningRepo).newAddress()
    }

    @Test
    fun `refreshBip21 should keep address when current has no transactions`() = test {
        val existingAddress = "existingAddress"
        whenever(cacheStore.data).thenReturn(flowOf(AppCacheData(onchainAddress = existingAddress)))
        whenever(coreService.isAddressUsed(any())).thenReturn(false)
        sut = createSut()
        sut.loadFromCache()

        val result = sut.refreshBip21()

        assertTrue(result.isSuccess)
        verify(lightningRepo, never()).newAddress()
    }

    @Test
    fun `syncBalances should update balance cache and state`() = test {
        val expectedState = BalanceState(
            totalOnchainSats = 100_000u,
            totalLightningSats = 50_000u,
            maxSendLightningSats = 1000u,
            maxSendOnchainSats = 0u,
            balanceInTransferToSavings = 0u,
            balanceInTransferToSpending = 0u,
        )
        whenever(deriveBalanceStateUseCase.invoke()).thenReturn(Result.success(expectedState))

        sut.syncBalances()

        verify(cacheStore).cacheBalance(expectedState)
        sut.balanceState.test {
            val state = awaitItem()
            assertEquals(expectedState, state)
            assertEquals(expectedState.totalSats, state.totalSats)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `refreshBip21ForEvent should not refresh for other events`() = test {
        sut.refreshBip21ForEvent(
            Event.PaymentSuccessful(
                paymentId = "",
                paymentHash = "",
                paymentPreimage = "",
                feePaidMsat = 10uL,
            )
        )

        verify(onchainService, never()).deriveBitcoinAddress(any(), any(), any(), anyOrNull())
    }

    @Test
    fun `updateBip21Invoice should create bolt11 when node can receive`() = test {
        whenever(lightningRepo.canReceive()).thenReturn(true)
        whenever(lightningRepo.createInvoice(anyOrNull(), any(), any())).thenReturn(Result.success(INVOICE))

        sut.updateBip21Invoice(amountSats = SATS, description = "test").let { result ->
            assertTrue(result.isSuccess)
            assertEquals(INVOICE, sut.walletState.value.bolt11)
        }
    }

    @Test
    fun `updateBip21Invoice should not create bolt11 when node cannot receive`() = test {
        sut.updateBip21Invoice(amountSats = SATS, description = "test").let { result ->
            assertTrue(result.isSuccess)
            assertEquals("", sut.walletState.value.bolt11)
        }
    }

    @Test
    fun `updateBip21Invoice should build correct BIP21 URL`() = test {
        whenever(cacheStore.data).thenReturn(flowOf(AppCacheData(onchainAddress = ADDRESS)))
        whenever(lightningRepo.createInvoice(anyOrNull(), any(), any())).thenReturn(Result.success(INVOICE))
        sut = createSut()
        sut.loadFromCache()

        sut.updateBip21Invoice(amountSats = SATS, description = "test").let { result ->
            assertTrue(result.isSuccess)
            assertTrue(sut.walletState.value.bip21.contains(ADDRESS))
            assertTrue(sut.walletState.value.bip21.contains("amount=0.00001"))
            assertTrue(sut.walletState.value.bip21.contains("message=test"))
        }
    }

    @Test
    fun `setOnchainAddress should update storage and state`() = test {
        sut.setOnchainAddress(ADDRESS)

        assertEquals(ADDRESS, sut.walletState.value.onchainAddress)
        verify(cacheStore).setOnchainAddress(ADDRESS)
    }

    @Test
    fun `setBolt11 should update storage and state`() = test {
        sut.setBolt11(INVOICE)

        assertEquals(INVOICE, sut.walletState.value.bolt11)
        verify(cacheStore).saveBolt11(INVOICE)
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
        val testMessage = "test message"

        val result = sut.buildBip21Url(ADDRESS, SATS, testMessage, INVOICE)

        assertTrue(result.contains(ADDRESS))
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
        whenever(coreService.checkGeoBlock()).thenReturn(Pair(true, true))

        if (sut.walletState.value.receiveOnSpendingBalance) {
            sut.toggleReceiveOnSpendingBalance()
        }

        val result = sut.toggleReceiveOnSpendingBalance()

        assert(result.isFailure)
    }

    @Test
    fun `addTagToSelected should add tag and update lastUsedTags`() = test {
        // Set address in wallet state so paymentId() returns it
        sut.setOnchainAddress(ADDRESS)

        val result = sut.addTagToSelected(ACTIVITY_TAG)

        assertTrue(result.isSuccess)
        assertEquals(listOf(ACTIVITY_TAG), sut.walletState.value.selectedTags)
        verify(settingsStore).addLastUsedTag(ACTIVITY_TAG)
        verify(preActivityMetadataRepo).addPreActivityMetadataTags(ADDRESS, listOf(ACTIVITY_TAG))
    }

    @Test
    fun `removeTag should remove tag`() = test {
        // Set address in wallet state so paymentId() returns it
        sut.setOnchainAddress(ADDRESS)

        val addResult = sut.addTagToSelected(ACTIVITY_TAG)
        assertTrue(addResult.isSuccess)

        val removeResult = sut.removeTag(ACTIVITY_TAG)
        assertTrue(removeResult.isSuccess)

        assertTrue(sut.walletState.value.selectedTags.isEmpty())
    }

    @Test
    fun `addTagToSelected should fail when payment ID is not available`() = test {
        // Don't set address, so paymentId() returns null
        val result = sut.addTagToSelected(ACTIVITY_TAG)

        assertTrue(result.isFailure)
        assertNotNull(result.exceptionOrNull())
        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertTrue(sut.walletState.value.selectedTags.isEmpty())
    }

    @Test
    fun `removeTag should fail when payment ID is not available`() = test {
        // Don't set address, so paymentId() returns null
        val result = sut.removeTag(ACTIVITY_TAG)

        assertTrue(result.isFailure)
        assertNotNull(result.exceptionOrNull())
        assertTrue(result.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun `addTagToSelected should fail when metadata repo fails`() = test {
        sut.setOnchainAddress(ADDRESS)
        whenever(preActivityMetadataRepo.addPreActivityMetadataTags(ADDRESS, listOf(ACTIVITY_TAG)))
            .thenReturn(Result.failure(error))

        val result = sut.addTagToSelected(ACTIVITY_TAG)

        assertTrue(result.isFailure)
        assertEquals(error, result.exceptionOrNull())
        assertTrue(sut.walletState.value.selectedTags.isEmpty())
    }

    @Test
    fun `removeTag should fail when metadata repo fails`() = test {
        sut.setOnchainAddress(ADDRESS)
        whenever(preActivityMetadataRepo.removePreActivityMetadataTags(ADDRESS, listOf(ACTIVITY_TAG)))
            .thenReturn(Result.failure(error))

        val result = sut.removeTag(ACTIVITY_TAG)

        assertTrue(result.isFailure)
        assertEquals(error, result.exceptionOrNull())
    }

    @Test
    fun `shouldRequestAdditionalLiquidity should return false when geoBlocked is true`() = test {
        whenever(coreService.checkGeoBlock()).thenReturn(Pair(true, false))
        sut.toggleReceiveOnSpendingBalance() // Set to false (initial is true)

        val result = sut.shouldRequestAdditionalLiquidity()

        assertTrue(result.isSuccess)
        assertFalse(result.getOrThrow())
    }

    @Test
    fun `shouldRequestAdditionalLiquidity should return false when geo status is true`() = test {
        whenever(coreService.checkGeoBlock()).thenReturn(Pair(true, false))

        val result = sut.shouldRequestAdditionalLiquidity()

        assertTrue(result.isSuccess)
        assertFalse(result.getOrThrow())
    }

    @Test
    fun `shouldRequestAdditionalLiquidity should return true when amount exceeds inbound capacity`() = test {
        whenever(coreService.checkGeoBlock()).thenReturn(Pair(false, false))
        whenever(lightningRepo.lightningState).thenReturn(MutableStateFlow(LightningState(channels = channels)))
        sut.updateBip21Invoice(amountSats = 1000uL)

        val result = sut.shouldRequestAdditionalLiquidity()

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow())
    }

    @Test
    fun `should not request additional liquidity for 0 channels`() = test {
        whenever(coreService.checkGeoBlock()).thenReturn(Pair(false, false))
        whenever(lightningRepo.lightningState).thenReturn(MutableStateFlow(LightningState()))
        sut.updateBip21Invoice(amountSats = 1000uL)

        val result = sut.shouldRequestAdditionalLiquidity()

        assertTrue(result.isSuccess)
        assertFalse(result.getOrThrow())
    }

    @Test
    fun `shouldRequestAdditionalLiquidity should return false when amount is less than inbound capacity`() = test {
        whenever(coreService.checkGeoBlock()).thenReturn(Pair(false, false))
        whenever(lightningRepo.lightningState).thenReturn(MutableStateFlow(LightningState(channels = channels)))
        sut.updateBip21Invoice(amountSats = 900uL)

        val result = sut.shouldRequestAdditionalLiquidity()

        assertTrue(result.isSuccess)
        assertFalse(result.getOrThrow())
    }

    @Test
    fun `clearBip21State should clear all bip21 related state`() = test {
        sut.setOnchainAddress(ADDRESS)
        val addResult = sut.addTagToSelected(ACTIVITY_TAG)
        assertTrue(addResult.isSuccess)
        sut.updateBip21Invoice(amountSats = 1000uL, description = "test")

        sut.clearBip21State()

        assertEquals("", sut.walletState.value.bip21)
        assertEquals(null, sut.walletState.value.bip21AmountSats)
        assertEquals("", sut.walletState.value.bip21Description)
        assertTrue(sut.walletState.value.selectedTags.isEmpty())
    }

    @Test
    fun `setBip21AmountSats should update state`() = test {
        sut.setBip21AmountSats(SATS)

        assertEquals(SATS, sut.walletState.value.bip21AmountSats)
    }

    @Test
    fun `setBip21Description should update state`() = test {
        val testDescription = "test description"

        sut.setBip21Description(testDescription)

        assertEquals(testDescription, sut.walletState.value.bip21Description)
    }

    @Test
    fun `refreshBip21ForEvent ChannelReady should update bolt11 and preserve amount`() = test {
        val testDescription = "test"
        sut.setBip21AmountSats(SATS)
        sut.setBip21Description(testDescription)
        whenever(lightningRepo.canReceive()).thenReturn(true)
        whenever(lightningRepo.createInvoice(anyOrNull(), any(), any())).thenReturn(Result.success(INVOICE))

        sut.refreshBip21ForEvent(channelReady)

        assertEquals(INVOICE, sut.walletState.value.bolt11)
        assertEquals(SATS, sut.walletState.value.bip21AmountSats)
        assertEquals(testDescription, sut.walletState.value.bip21Description)
    }

    @Test
    fun `refreshBip21ForEvent ChannelReady should not create invoice when cannot receive`() = test {
        sut.setBip21AmountSats(SATS)
        whenever(lightningRepo.canReceive()).thenReturn(false)

        sut.refreshBip21ForEvent(channelReady)

        verify(lightningRepo, never()).createInvoice(anyOrNull(), any(), any())
    }

    @Test
    fun `refreshBip21ForEvent ChannelClosed should clear bolt11 when cannot receive`() = test {
        whenever(cacheStore.data).thenReturn(flowOf(AppCacheData(onchainAddress = ADDRESS)))
        sut = createSut()
        sut.loadFromCache()
        sut.setBolt11(INVOICE)
        whenever(lightningRepo.canReceive()).thenReturn(false)

        sut.refreshBip21ForEvent(
            Event.ChannelClosed(
                channelId = "testChannelId",
                userChannelId = "testUserChannelId",
                counterpartyNodeId = null,
                reason = null
            )
        )

        assertEquals("", sut.walletState.value.bolt11)
    }

    @Test
    fun `refreshBip21ForEvent ChannelClosed should not clear bolt11 when can still receive`() = test {
        sut.setBolt11(INVOICE)
        whenever(lightningRepo.canReceive()).thenReturn(true)

        sut.refreshBip21ForEvent(
            Event.ChannelClosed(
                channelId = "testChannelId",
                userChannelId = "testUserChannelId",
                counterpartyNodeId = null,
                reason = null
            )
        )

        assertEquals(INVOICE, sut.walletState.value.bolt11)
    }

    @Test
    fun `refreshBip21ForEvent PaymentReceived should refresh address if used`() = test {
        whenever(cacheStore.data).thenReturn(flowOf(AppCacheData(onchainAddress = ADDRESS)))
        whenever(coreService.isAddressUsed(any())).thenReturn(true)
        whenever(lightningRepo.newAddress()).thenReturn(Result.success(ADDRESS_NEW))
        sut = createSut()
        sut.loadFromCache()

        sut.refreshBip21ForEvent(
            Event.PaymentReceived(
                paymentId = "testPaymentId",
                paymentHash = "testPaymentHash",
                amountMsat = 1000uL,
                customRecords = emptyList()
            )
        )

        verify(lightningRepo).newAddress()
    }

    @Test
    fun `refreshBip21ForEvent PaymentReceived should not refresh address if not used`() = test {
        whenever(cacheStore.data).thenReturn(flowOf(AppCacheData(onchainAddress = ADDRESS)))
        whenever(coreService.isAddressUsed(any())).thenReturn(false)
        sut = createSut()
        sut.loadFromCache()

        sut.refreshBip21ForEvent(
            Event.PaymentReceived(
                paymentId = "testPaymentId",
                paymentHash = "testPaymentHash",
                amountMsat = 1000uL,
                customRecords = emptyList()
            )
        )

        verify(lightningRepo, never()).newAddress()
    }

    @Test
    fun `wipeWallet should call use case`() = test {
        whenever(wipeWalletUseCase.invoke(any(), any(), any())).thenReturn(Result.success(Unit))

        val result = sut.wipeWallet()

        assertTrue(result.isSuccess)
        verify(wipeWalletUseCase).invoke(any(), any(), any())
    }

    @Test
    fun `wipeWallet should return failure when use case fails`() = test {
        whenever(wipeWalletUseCase.invoke(any(), any(), any())).thenReturn(Result.failure(error))

        val result = sut.wipeWallet()

        assertTrue(result.isFailure)
    }
}
