package to.bitkit.repositories

import com.synonym.bitkitcore.AddressType
import com.synonym.bitkitcore.PreActivityMetadata
import com.synonym.bitkitcore.Scanner
import com.synonym.bitkitcore.decode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lightningdevkit.ldknode.Event
import to.bitkit.data.CacheStore
import to.bitkit.data.SettingsStore
import to.bitkit.data.keychain.Keychain
import to.bitkit.di.BgDispatcher
import to.bitkit.env.Env
import to.bitkit.ext.filterOpen
import to.bitkit.ext.nowTimestamp
import to.bitkit.ext.toHex
import to.bitkit.models.AddressModel
import to.bitkit.models.BalanceState
import to.bitkit.models.toDerivationPath
import to.bitkit.services.CoreService
import to.bitkit.usecases.DeriveBalanceStateUseCase
import to.bitkit.usecases.WipeWalletUseCase
import to.bitkit.utils.Bip21Utils
import to.bitkit.utils.Logger
import to.bitkit.utils.ServiceError
import javax.inject.Inject
import javax.inject.Singleton

@Suppress("LongParameterList")
@Singleton
class WalletRepo @Inject constructor(
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val keychain: Keychain,
    private val coreService: CoreService,
    private val settingsStore: SettingsStore,
    private val lightningRepo: LightningRepo,
    private val cacheStore: CacheStore,
    private val preActivityMetadataRepo: PreActivityMetadataRepo,
    private val deriveBalanceStateUseCase: DeriveBalanceStateUseCase,
    private val wipeWalletUseCase: WipeWalletUseCase,
) {
    private val repoScope = CoroutineScope(bgDispatcher + SupervisorJob())

    private val _walletState = MutableStateFlow(WalletState(walletExists = walletExists()))
    val walletState = _walletState.asStateFlow()

    private val _balanceState = MutableStateFlow(BalanceState())
    val balanceState = _balanceState.asStateFlow()

    fun loadFromCache() {
        // TODO try keeping in sync with cache if performant and reliable
        repoScope.launch {
            val cacheData = cacheStore.data.first()
            _walletState.update { currentState ->
                currentState.copy(
                    onchainAddress = cacheData.onchainAddress,
                    bolt11 = cacheData.bolt11,
                    bip21 = cacheData.bip21,
                )
            }
            cacheData.balance?.let { balance ->
                _balanceState.update { balance }
            }
        }
    }

    fun walletExists(): Boolean = keychain.exists(Keychain.Key.BIP39_MNEMONIC.name)

    fun setWalletExistsState() {
        _walletState.update { it.copy(walletExists = walletExists()) }
    }

    suspend fun checkAddressUsage(address: String): Result<Boolean> = withContext(bgDispatcher) {
        return@withContext try {
            val result = coreService.isAddressUsed(address)
            Result.success(result)
        } catch (e: Exception) {
            Logger.error("checkAddressUsage error", e, context = TAG)
            Result.failure(e)
        }
    }

    suspend fun refreshBip21(): Result<Unit> = withContext(bgDispatcher) {
        Logger.debug("Refreshing bip21", context = TAG)

        // Get old payment ID and tags before refreshing (which may change payment ID)
        val oldPaymentId = paymentId()
        val tagsToMigrate = if (oldPaymentId != null && oldPaymentId.isNotEmpty()) {
            preActivityMetadataRepo
                .getPreActivityMetadata(oldPaymentId, searchByAddress = false)
                .getOrNull()
                ?.tags ?: emptyList()
        } else {
            emptyList()
        }

        val (_, shouldBlockLightningReceive) = coreService.checkGeoBlock()
        _walletState.update {
            it.copy(receiveOnSpendingBalance = !shouldBlockLightningReceive)
        }
        clearBip21State(clearTags = false)
        refreshAddressIfNeeded()
        updateBip21Invoice()

        val newPaymentId = paymentId()
        val newBip21Url = _walletState.value.bip21
        if (newPaymentId != null && newPaymentId.isNotEmpty() && newBip21Url.isNotEmpty()) {
            persistPreActivityMetadata(newPaymentId, tagsToMigrate, newBip21Url)
        }

        return@withContext Result.success(Unit)
    }

    private suspend fun persistPreActivityMetadata(
        paymentId: String,
        tags: List<String>,
        bip21Url: String,
    ) {
        val onChainAddress = getOnchainAddress()
        val paymentHash = runCatching {
            when (val decoded = decode(bip21Url)) {
                is Scanner.Lightning -> decoded.invoice.paymentHash.toHex()
                is Scanner.OnChain -> decoded.extractLightningHash()
                else -> null
            }
        }.getOrNull()

        val preActivityMetadata = PreActivityMetadata(
            paymentId = paymentId,
            createdAt = nowTimestamp().toEpochMilli().toULong(),
            tags = tags,
            paymentHash = paymentHash,
            txId = null,
            address = onChainAddress,
            isReceive = true,
            feeRate = 0u,
            isTransfer = false,
            channelId = "",
        )

        preActivityMetadataRepo.addPreActivityMetadata(preActivityMetadata)
    }

    suspend fun observeLdkWallet() = withContext(bgDispatcher) {
        lightningRepo.getSyncFlow()
            .collect {
                runCatching {
                    syncNodeAndWallet()
                }
            }
    }

    suspend fun syncNodeAndWallet(): Result<Unit> = withContext(bgDispatcher) {
        val startHeight = lightningRepo.lightningState.value.block()?.height
        Logger.verbose("syncNodeAndWallet started at block height=$startHeight", context = TAG)
        syncBalances()
        lightningRepo.sync().onSuccess {
            syncBalances()
            val endHeight = lightningRepo.lightningState.value.block()?.height
            Logger.verbose("syncNodeAndWallet completed at block height=$endHeight", context = TAG)
            return@withContext Result.success(Unit)
        }.onFailure { e ->
            if (e is TimeoutCancellationException) {
                syncBalances()
            }
            return@withContext Result.failure(e)
        }
    }

    suspend fun syncBalances() {
        deriveBalanceStateUseCase().onSuccess { balanceState ->
            runCatching { cacheStore.cacheBalance(balanceState) }
            _balanceState.update { balanceState }
        }.onFailure {
            Logger.warn("Could not sync balances", context = TAG)
        }
    }

    suspend fun refreshBip21ForEvent(event: Event) {
        when (event) {
            is Event.ChannelReady -> {
                // Only refresh bolt11 if we can now receive on lightning
                if (lightningRepo.canReceive()) {
                    lightningRepo.createInvoice(
                        amountSats = _walletState.value.bip21AmountSats,
                        description = _walletState.value.bip21Description,
                    ).onSuccess { bolt11 ->
                        setBolt11(bolt11)
                        updateBip21Url()
                    }
                }
            }

            is Event.ChannelClosed -> {
                // Clear bolt11 if we can no longer receive on lightning
                if (!lightningRepo.canReceive()) {
                    setBolt11("")
                    updateBip21Url()
                }
            }

            is Event.PaymentReceived -> {
                // Check if onchain address was used, generate new one if needed
                refreshAddressIfNeeded()
                updateBip21Url()
            }

            else -> Unit
        }
    }

    private suspend fun refreshAddressIfNeeded() = withContext(bgDispatcher) {
        val address = getOnchainAddress()
        if (address.isEmpty()) {
            newAddress()
        } else {
            checkAddressUsage(address).onSuccess { wasUsed ->
                if (wasUsed) {
                    newAddress()
                }
            }
        }
    }

    private suspend fun updateBip21Url(
        amountSats: ULong? = _walletState.value.bip21AmountSats,
        message: String = _walletState.value.bip21Description,
    ): String {
        val address = getOnchainAddress()
        val newBip21 = buildBip21Url(
            bitcoinAddress = address,
            amountSats = amountSats,
            message = message.ifBlank { Env.DEFAULT_INVOICE_MESSAGE },
            lightningInvoice = getBolt11(),
        )
        setBip21(newBip21)

        return newBip21
    }

    suspend fun createWallet(bip39Passphrase: String?): Result<Unit> = withContext(bgDispatcher) {
        lightningRepo.setRecoveryMode(enabled = false)
        try {
            val mnemonic = generateEntropyMnemonic()
            keychain.saveString(Keychain.Key.BIP39_MNEMONIC.name, mnemonic)
            if (bip39Passphrase != null) {
                keychain.saveString(Keychain.Key.BIP39_PASSPHRASE.name, bip39Passphrase)
            }
            setWalletExistsState()
            Result.success(Unit)
        } catch (e: Throwable) {
            Logger.error("Create wallet error", e, context = TAG)
            Result.failure(e)
        }
    }

    suspend fun restoreWallet(mnemonic: String, bip39Passphrase: String?): Result<Unit> = withContext(bgDispatcher) {
        lightningRepo.setRecoveryMode(enabled = false)
        try {
            keychain.saveString(Keychain.Key.BIP39_MNEMONIC.name, mnemonic)
            if (bip39Passphrase != null) {
                keychain.saveString(Keychain.Key.BIP39_PASSPHRASE.name, bip39Passphrase)
            }
            setWalletExistsState()
            Result.success(Unit)
        } catch (e: Throwable) {
            Logger.error("Restore wallet error", e)
            Result.failure(e)
        }
    }

    suspend fun wipeWallet(walletIndex: Int = 0): Result<Unit> = withContext(bgDispatcher) {
        return@withContext wipeWalletUseCase(
            walletIndex = walletIndex,
            resetWalletState = ::resetState,
            onSuccess = ::setWalletExistsState,
        )
    }

    fun resetState() {
        _walletState.update { WalletState() }
        _balanceState.update { BalanceState() }
    }

    // Blockchain address management
    fun getOnchainAddress(): String = _walletState.value.onchainAddress

    suspend fun setOnchainAddress(address: String) {
        cacheStore.setOnchainAddress(address)
        _walletState.update { it.copy(onchainAddress = address) }
    }

    suspend fun newAddress(): Result<String> = withContext(bgDispatcher) {
        return@withContext lightningRepo.newAddress()
            .onSuccess { address -> setOnchainAddress(address) }
            .onFailure { error -> Logger.error("Error generating new address", error) }
    }

    suspend fun getAddresses(
        startIndex: Int = 0,
        isChange: Boolean = false,
        count: Int = 20,
    ): Result<List<AddressModel>> = withContext(bgDispatcher) {
        return@withContext try {
            val mnemonic = keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name) ?: throw ServiceError.MnemonicNotFound

            val passphrase = keychain.loadString(Keychain.Key.BIP39_PASSPHRASE.name)

            val baseDerivationPath = AddressType.P2WPKH.toDerivationPath(
                index = 0,
                isChange = isChange,
            ).substringBeforeLast("/0")

            val result = coreService.onchain.deriveBitcoinAddresses(
                mnemonicPhrase = mnemonic,
                derivationPathStr = baseDerivationPath,
                network = Env.network,
                bip39Passphrase = passphrase,
                isChange = isChange,
                startIndex = startIndex.toUInt(),
                count = count.toUInt(),
            )

            val addresses = result.addresses.mapIndexed { index, address ->
                AddressModel(
                    address = address.address,
                    index = startIndex + index,
                    path = address.path,
                )
            }

            Result.success(addresses)
        } catch (e: Exception) {
            Logger.error("Error getting addresses", e)
            Result.failure(e)
        }
    }

    // Bolt11 management
    fun getBolt11(): String = _walletState.value.bolt11

    suspend fun setBolt11(bolt11: String) {
        runCatching { cacheStore.saveBolt11(bolt11) }
        _walletState.update { it.copy(bolt11 = bolt11) }
    }

    // BIP21 management
    suspend fun setBip21(bip21: String) {
        runCatching { cacheStore.setBip21(bip21) }
        _walletState.update { it.copy(bip21 = bip21) }
    }

    fun buildBip21Url(
        bitcoinAddress: String,
        amountSats: ULong? = null,
        message: String = Env.DEFAULT_INVOICE_MESSAGE,
        lightningInvoice: String = "",
    ): String {
        return Bip21Utils.buildBip21Url(
            bitcoinAddress = bitcoinAddress,
            amountSats = amountSats,
            message = message,
            lightningInvoice = lightningInvoice
        )
    }

    // BIP21 state management
    fun setBip21AmountSats(amount: ULong?) = _walletState.update { it.copy(bip21AmountSats = amount) }

    fun setBip21Description(description: String) = _walletState.update { it.copy(bip21Description = description) }

    fun clearBip21State(clearTags: Boolean = true) {
        _walletState.update {
            it.copy(
                bip21 = "",
                selectedTags = if (clearTags) emptyList() else it.selectedTags,
                bip21AmountSats = null,
                bip21Description = "",
            )
        }
    }

    suspend fun toggleReceiveOnSpendingBalance(): Result<Unit> = withContext(bgDispatcher) {
        if (!_walletState.value.receiveOnSpendingBalance && coreService.checkGeoBlock().second) {
            return@withContext Result.failure(ServiceError.GeoBlocked)
        }

        _walletState.update { it.copy(receiveOnSpendingBalance = !it.receiveOnSpendingBalance) }

        return@withContext Result.success(Unit)
    }

    // Payment ID management
    private suspend fun paymentHash(): String? = withContext(bgDispatcher) {
        val bolt11 = getBolt11()
        if (bolt11.isEmpty()) return@withContext null
        return@withContext runCatching {
            when (val decoded = decode(bolt11)) {
                is Scanner.Lightning -> decoded.invoice.paymentHash.toHex()
                else -> null
            }
        }.onFailure { e ->
            Logger.error("Error extracting payment hash from bolt11", e, context = TAG)
        }.getOrNull()
    }

    suspend fun paymentId(): String? = withContext(bgDispatcher) {
        val hash = paymentHash()
        if (hash != null) return@withContext hash
        val address = getOnchainAddress()
        return@withContext if (address.isEmpty()) null else address
    }

    // Pre-activity metadata tag management
    suspend fun addTagToSelected(newTag: String): Result<Unit> = withContext(bgDispatcher) {
        val paymentId = paymentId()
        if (paymentId == null || paymentId.isEmpty()) {
            Logger.warn("Cannot add tag: payment ID not available", context = TAG)
            return@withContext Result.failure(
                IllegalStateException("Cannot add tag: payment ID not available")
            )
        }

        return@withContext preActivityMetadataRepo.addPreActivityMetadataTags(paymentId, listOf(newTag))
            .onSuccess {
                _walletState.update {
                    it.copy(
                        selectedTags = (it.selectedTags + newTag).distinct()
                    )
                }
                settingsStore.addLastUsedTag(newTag)
            }.onFailure { e ->
                Logger.error("Failed to add tag to pre-activity metadata", e, context = TAG)
            }
    }

    suspend fun removeTag(tag: String): Result<Unit> = withContext(bgDispatcher) {
        val paymentId = paymentId()
        if (paymentId == null || paymentId.isEmpty()) {
            Logger.warn("Cannot remove tag: payment ID not available", context = TAG)
            return@withContext Result.failure(
                IllegalStateException("Cannot remove tag: payment ID not available")
            )
        }

        return@withContext preActivityMetadataRepo.removePreActivityMetadataTags(paymentId, listOf(tag))
            .onSuccess {
                _walletState.update {
                    it.copy(
                        selectedTags = it.selectedTags.filterNot { tagItem -> tagItem == tag }
                    )
                }
            }.onFailure { e ->
                Logger.error("Failed to remove tag from pre-activity metadata", e, context = TAG)
            }
    }

    suspend fun resetPreActivityMetadataTagsForCurrentInvoice() = withContext(bgDispatcher) {
        val paymentId = paymentId()
        if (paymentId == null || paymentId.isEmpty()) return@withContext

        preActivityMetadataRepo.resetPreActivityMetadataTags(paymentId).onSuccess {
            _walletState.update { it.copy(selectedTags = emptyList()) }
        }.onFailure { e ->
            Logger.error("Failed to reset tags for pre-activity metadata", e, context = TAG)
        }
    }

    suspend fun loadTagsForCurrentInvoice() {
        val paymentId = paymentId()
        if (paymentId == null || paymentId.isEmpty()) {
            _walletState.update { it.copy(selectedTags = emptyList()) }
            return
        }

        preActivityMetadataRepo.getPreActivityMetadata(paymentId, searchByAddress = false)
            .onSuccess { metadata ->
                _walletState.update {
                    it.copy(selectedTags = metadata?.tags ?: emptyList())
                }
            }
            .onFailure { e ->
                Logger.error("Failed to load tags for current invoice", e, context = TAG)
            }
    }

    // BIP21 invoice creation and persistence
    suspend fun updateBip21Invoice(
        amountSats: ULong? = walletState.value.bip21AmountSats,
        description: String = walletState.value.bip21Description,
    ): Result<Unit> = withContext(bgDispatcher) {
        return@withContext runCatching {
            val oldPaymentId = paymentId()
            val tagsToMigrate = if (oldPaymentId != null && oldPaymentId.isNotEmpty()) {
                preActivityMetadataRepo
                    .getPreActivityMetadata(oldPaymentId, searchByAddress = false)
                    .getOrNull()
                    ?.tags ?: emptyList()
            } else {
                emptyList()
            }

            setBip21AmountSats(amountSats)
            setBip21Description(description)

            val canReceive = lightningRepo.canReceive()
            if (canReceive && _walletState.value.receiveOnSpendingBalance) {
                lightningRepo.createInvoice(amountSats, description).onSuccess {
                    setBolt11(it)
                }
            } else {
                setBolt11("")
            }
            val newBip21Url = updateBip21Url(amountSats, description)
            setBip21(newBip21Url)

            // Persist metadata with migrated tags
            val newPaymentId = paymentId()
            if (newPaymentId != null && newPaymentId.isNotEmpty() && newBip21Url.isNotEmpty()) {
                persistPreActivityMetadata(newPaymentId, tagsToMigrate, newBip21Url)
            }
        }.onFailure { e ->
            Logger.error("Update BIP21 invoice error", e, context = TAG)
        }
    }

    suspend fun shouldRequestAdditionalLiquidity(): Result<Boolean> = withContext(bgDispatcher) {
        return@withContext try {
            if (!_walletState.value.receiveOnSpendingBalance) return@withContext Result.success(false)

            if (coreService.checkGeoBlock().first) return@withContext Result.success(false)

            val channels = lightningRepo.lightningState.value.channels
            if (channels.filterOpen().isEmpty()) return@withContext Result.success(false)

            val inboundBalanceSats = channels.sumOf { it.inboundCapacityMsat / 1000u }

            Result.success((_walletState.value.bip21AmountSats ?: 0uL) >= inboundBalanceSats)
        } catch (e: Exception) {
            Logger.error("shouldRequestAdditionalLiquidity error", e, context = TAG)
            Result.failure(e)
        }
    }

    private suspend fun Scanner.OnChain.extractLightningHash(): String? {
        val lightningInvoice: String = this.invoice.params?.get("lightning") ?: return null

        return when (val decoded = decode(lightningInvoice)) {
            is Scanner.Lightning -> decoded.invoice.paymentHash.toHex()
            else -> null
        }
    }

    private fun generateEntropyMnemonic(): String {
        return org.lightningdevkit.ldknode.generateEntropyMnemonic()
    }

    private companion object {
        const val TAG = "WalletRepo"
    }
}

data class WalletState(
    val onchainAddress: String = "",
    val bolt11: String = "",
    val bip21: String = "",
    val bip21AmountSats: ULong? = null,
    val bip21Description: String = "",
    val selectedTags: List<String> = listOf(),
    val receiveOnSpendingBalance: Boolean = true,
    val walletExists: Boolean = false,
)
