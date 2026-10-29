package to.bitkit.repositories

import androidx.compose.runtime.Immutable
import com.synonym.bitkitcore.AddressType
import com.synonym.bitkitcore.LegacyRnCloseRecoveryScanResult
import com.synonym.bitkitcore.LegacyRnCloseRecoverySweepPreview
import com.synonym.bitkitcore.PreActivityMetadata
import com.synonym.bitkitcore.Scanner
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.lightningdevkit.ldknode.Bolt11Invoice
import org.lightningdevkit.ldknode.ChannelDetails
import org.lightningdevkit.ldknode.Event
import org.lightningdevkit.ldknode.WordCount
import to.bitkit.async.appScope
import to.bitkit.data.CacheStore
import to.bitkit.data.SettingsStore
import to.bitkit.data.WalletConnectionRecovery
import to.bitkit.data.keychain.Keychain
import to.bitkit.di.BgDispatcher
import to.bitkit.env.Env
import to.bitkit.ext.calculateRemoteBalance
import to.bitkit.ext.nowTimestamp
import to.bitkit.ext.runSuspendCatching
import to.bitkit.ext.toHex
import to.bitkit.ext.totalNextOutboundHtlcLimitSats
import to.bitkit.models.ALL_ADDRESS_TYPE_STRINGS
import to.bitkit.models.AddressModel
import to.bitkit.models.BalanceState
import to.bitkit.models.DEFAULT_ADDRESS_TYPE_STRING
import to.bitkit.models.ReceiveLiquidityDecision
import to.bitkit.models.WalletScope
import to.bitkit.models.toAccountDerivationPath
import to.bitkit.models.toBalance
import to.bitkit.models.toDerivationPath
import to.bitkit.services.AddressDerivationInfo
import to.bitkit.services.CoreService
import to.bitkit.usecases.DeriveBalanceStateUseCase
import to.bitkit.usecases.WipeIncomplete
import to.bitkit.usecases.WipeWalletUseCase
import to.bitkit.utils.Bip21Utils
import to.bitkit.utils.Logger
import to.bitkit.utils.ServiceError
import to.bitkit.utils.measured
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

@Singleton
class WalletRepo @Inject constructor(
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val keychain: Keychain,
    private val coreService: CoreService,
    private val settingsStore: SettingsStore,
    private val lightningRepo: LightningRepo,
    private val privatePaykitAddressReservationRepo: PrivatePaykitAddressReservationRepo,
    private val cacheStore: CacheStore,
    private val preActivityMetadataRepo: PreActivityMetadataRepo,
    private val deriveBalanceStateUseCase: DeriveBalanceStateUseCase,
    private val wipeWalletUseCase: WipeWalletUseCase,
    private val transferRepo: TransferRepo,
    private val activityRepo: ActivityRepo,
    private val hwWalletRepo: HwWalletRepo,
    private val connectionRecovery: WalletConnectionRecovery,
    private val primaryNodeRepo: PrimaryNodeRepo,
) {
    private val repoScope = appScope(bgDispatcher, TAG)

    private val _walletState = MutableStateFlow(WalletState(walletExists = walletExists()))
    val walletState = _walletState.asStateFlow()

    private val _balanceState = MutableStateFlow(BalanceState())
    val balanceState = _balanceState.asStateFlow()

    private var eventSyncJob: Job? = null
    private var primaryFundingJob: Job? = null

    /** An invoice the primary node delivers, approved by the user for its amount; receive refreshes keep it. */
    @Volatile
    private var approvedPrimaryInvoice: Pair<String, ULong>? = null
    private val receiveStateMutex = Mutex()
    private val receiveUpdateStatusLock = Any()
    private var pendingReceiveUpdates = 0
    private var pendingReceiveCacheLoads = 0
    private var pendingReceiveValidation: String? = null
    private var committedReceiveBip21 = ""

    init {
        repoScope.launch {
            lightningRepo.nodeEventUpdates.collect { update ->
                if (!walletExists()) return@collect
                runSuspendCatching {
                    refreshBip21ForEvent(
                        event = update.event,
                        settledReceiveInvoice = update.settledReceiveInvoice,
                        settledReceiveAddress = update.settledReceiveAddress,
                    )
                }.onFailure {
                    validateReceiveInvoiceAfterEventFailure()
                    Logger.error("Failed to refresh receive state for event '${update.event}'", it, context = TAG)
                }
            }
        }
        repoScope.launch {
            hwWalletRepo.wallets.collect { wallets ->
                _balanceState.update { state -> state.copy(hardwareWallets = wallets.map { it.toBalance() }) }
            }
        }
    }

    private suspend fun <T> updateReceiveState(
        beforeLock: () -> Unit = {},
        trackPending: Boolean = true,
        block: suspend () -> T,
    ): T {
        if (trackPending) changePendingReceiveUpdates(1)
        try {
            beforeLock()
            return receiveStateMutex.withLock { withContext(bgDispatcher) { block() } }
        } finally {
            if (trackPending) changePendingReceiveUpdates(-1)
        }
    }

    private fun changePendingReceiveUpdates(delta: Int) = synchronized(receiveUpdateStatusLock) {
        pendingReceiveUpdates += delta
        updateReceivePendingState()
    }

    private fun setPendingReceiveValidation(bolt11: String?) = synchronized(receiveUpdateStatusLock) {
        pendingReceiveValidation = bolt11
        updateReceivePendingState()
    }

    private fun changePendingReceiveCacheLoads(delta: Int) = synchronized(receiveUpdateStatusLock) {
        pendingReceiveCacheLoads += delta
        updateReceivePendingState()
    }

    private fun updateReceivePendingState() {
        _walletState.update { state ->
            val isValidatingCurrentInvoice = pendingReceiveValidation != null &&
                state.bolt11 == pendingReceiveValidation
            val isUpdating = pendingReceiveUpdates > 0 || pendingReceiveCacheLoads > 0 || isValidatingCurrentInvoice
            state.copy(isReceiveInvoiceUpdating = isUpdating)
        }
    }

    private suspend fun validateReceiveInvoiceAfterEventFailure() {
        val failedState = _walletState.value
        setPendingReceiveValidation(failedState.bolt11)
        try {
            val hasUsableInvoice = runSuspendCatching {
                if (failedState.bolt11.isEmpty()) return@runSuspendCatching false
                val decoded = coreService.decode(failedState.bolt11) as? Scanner.Lightning
                decoded?.invoice?.isExpired == false
            }.getOrDefault(false)
            _walletState.update { current ->
                if (current.bolt11 != failedState.bolt11) {
                    current
                } else {
                    current.copy(hasReceiveInvoiceError = current.hasReceiveInvoiceError || !hasUsableInvoice)
                }
            }
        } finally {
            setPendingReceiveValidation(null)
        }
    }

    fun loadFromCache() {
        changePendingReceiveCacheLoads(1)
        repoScope.launch {
            receiveStateMutex.withLock {
                val cacheData = cacheStore.data.first()
                committedReceiveBip21 = cacheData.bip21
                _walletState.update {
                    it.copy(
                        onchainAddress = cacheData.onchainAddress,
                        bolt11 = cacheData.bolt11,
                        bip21 = cacheData.bip21,
                    )
                }
                synchronized(receiveUpdateStatusLock) { updateReceivePendingState() }
                cacheData.balance?.let { balance ->
                    _balanceState.update { balance }
                }
            }
        }.invokeOnCompletion { changePendingReceiveCacheLoads(-1) }
    }

    fun walletExists(): Boolean = keychain.exists(Keychain.Key.BIP39_MNEMONIC.name)

    fun setWalletExistsState() {
        _walletState.update { it.copy(walletExists = walletExists()) }
    }

    suspend fun checkAddressUsage(address: String): Result<Boolean> = withContext(bgDispatcher) {
        runCatching {
            coreService.isAddressUsed(address)
        }.onFailure {
            Logger.error("checkAddressUsage error", it, context = TAG)
        }
    }

    suspend fun scanLegacyRnNativeSegwitRecoveryFunds(
        indexLimit: UInt,
    ): Result<LegacyRnCloseRecoveryScanResult> = withContext(bgDispatcher) {
        runCatching {
            val (mnemonic, passphrase) = recoveryWalletCredentials()
            val electrumUrl = settingsStore.data.first().electrumServer

            coreService.onchain.scanLegacyRnNativeSegwitRecoveryFunds(
                mnemonicPhrase = mnemonic,
                network = Env.network,
                electrumUrl = electrumUrl,
                indexLimit = indexLimit,
                bip39Passphrase = passphrase,
            )
        }.onFailure {
            Logger.error("Legacy RN recovery scan failed", it, context = TAG)
        }
    }

    suspend fun prepareLegacyRnNativeSegwitRecoverySweep(
        indexLimit: UInt,
        feeRateSatsPerVbyte: UInt?,
    ): Result<LegacyRnCloseRecoverySweepPreview> = withContext(bgDispatcher) {
        runCatching {
            val (mnemonic, passphrase) = recoveryWalletCredentials()
            val electrumUrl = settingsStore.data.first().electrumServer
            val destinationAddress = recoverySweepDestinationAddress()

            coreService.onchain.prepareLegacyRnNativeSegwitRecoverySweep(
                mnemonicPhrase = mnemonic,
                network = Env.network,
                electrumUrl = electrumUrl,
                destinationAddress = destinationAddress,
                feeRateSatsPerVbyte = feeRateSatsPerVbyte,
                indexLimit = indexLimit,
                bip39Passphrase = passphrase,
            )
        }.onFailure {
            Logger.error("Legacy RN recovery sweep prepare failed", it, context = TAG)
        }
    }

    suspend fun broadcastLegacyRnNativeSegwitRecoverySweep(txHex: String): Result<String> = withContext(bgDispatcher) {
        runCatching {
            val electrumUrl = settingsStore.data.first().electrumServer
            val txid = coreService.onchain.broadcastRawTx(serializedTx = txHex, electrumUrl = electrumUrl)
            syncNodeAndWallet(SyncSource.MANUAL).onFailure {
                Logger.warn("Legacy RN recovery post-broadcast sync failed", it, context = TAG)
            }
            txid
        }.onFailure {
            Logger.error("Legacy RN recovery sweep broadcast failed", it, context = TAG)
        }
    }

    private fun recoveryWalletCredentials(): Pair<String, String?> {
        val mnemonic = keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name)
            ?: throw ServiceError.MnemonicNotFound()
        val passphrase = keychain.loadString(Keychain.Key.BIP39_PASSPHRASE.name)
        return mnemonic to passphrase
    }

    private suspend fun recoverySweepDestinationAddress(): String {
        val currentAddress = getOnchainAddress()
        if (currentAddress.isNotBlank()) return currentAddress

        return newAddress().getOrThrow().also {
            require(it.isNotBlank()) { "Destination address unavailable" }
        }
    }

    suspend fun refreshReceiveState(): Result<Unit> = updateReceiveState(
        beforeLock = { clearBip21State(clearTags = false) },
    ) {
        runSuspendCatching {
            lightningRepo.syncState()
            refreshBip21Locked().getOrThrow()
        }.onFailure {
            _walletState.update { state -> state.copy(hasReceiveInvoiceError = true) }
        }
    }

    suspend fun refreshBip21(): Result<Unit> = updateReceiveState(
        beforeLock = { clearBip21State(clearTags = false) },
    ) {
        runSuspendCatching { refreshBip21Locked().getOrThrow() }.onFailure {
            _walletState.update { state -> state.copy(hasReceiveInvoiceError = true) }
        }
    }

    suspend fun retryReceiveInvoice(): Result<Unit> {
        val state = _walletState.value
        return updateReceiveState {
            runSuspendCatching {
                lightningRepo.syncState()
                refreshAddressForInvoice().getOrThrow()
                updateBip21InvoiceLocked(state.bip21AmountSats, state.bip21Description).getOrThrow()
            }.onFailure {
                _walletState.update { current -> current.copy(hasReceiveInvoiceError = true) }
            }
        }
    }

    private suspend fun refreshBip21Locked(): Result<Unit> = withContext(bgDispatcher) {
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

        val addressResult = refreshAddressForInvoice()
        if (addressResult.isFailure) return@withContext addressResult
        val invoiceResult = updateBip21InvoiceLocked()
        if (invoiceResult.isFailure) return@withContext invoiceResult

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
            when (val decoded = coreService.decode(bip21Url)) {
                is Scanner.Lightning -> decoded.invoice.paymentHash.toHex()
                is Scanner.OnChain -> decoded.extractLightningHash()
                else -> null
            }
        }.getOrNull()

        val preActivityMetadata = PreActivityMetadata(
            walletId = WalletScope.default,
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

    suspend fun syncNodeAndWallet(source: SyncSource = SyncSource.AUTO): Result<Unit> = withContext(bgDispatcher) {
        if (!lightningRepo.lightningState.value.nodeLifecycleState.isRunning()) {
            Logger.debug("syncNodeAndWallet skipped: node not running", context = TAG)
            return@withContext Result.failure(Exception("Node not running"))
        }

        val sourceLabel = source.name.lowercase()
        val startHeight = lightningRepo.lightningState.value.block()?.height
        Logger.debug("Sync $sourceLabel started at block height=$startHeight", context = TAG)

        val result = measured(label = "Sync $sourceLabel", context = TAG, slowThreshold = SLOW_SYNC_THRESHOLD) {
            syncBalances()
            lightningRepo.sync().onSuccess {
                syncBalances()
                schedulePrimaryFunding()
            }.onFailure {
                if (it is TimeoutCancellationException) {
                    syncBalances()
                }
            }
        }

        val endHeight = lightningRepo.lightningState.value.block()?.height
        Logger.debug("Sync $sourceLabel completed at block height=$endHeight", context = TAG)

        result
    }

    /** Re-derives the Spending limit from the published channels, without the balances call of [syncBalances]. */
    fun refreshMaxSendLightning() {
        val state = lightningRepo.lightningState.value
        if (!state.nodeLifecycleState.isRunning()) return
        _balanceState.update { it.copy(maxSendLightningSats = state.channels.totalNextOutboundHtlcLimitSats()) }
    }

    suspend fun syncBalances() {
        deriveBalanceStateUseCase().onSuccess { balanceState ->
            runCatching { cacheStore.cacheBalance(balanceState) }
            _balanceState.update { balanceState }
        }.onFailure {
            if (it !is CancellationException) {
                Logger.warn("Could not sync balances", it, context = TAG)
            }
        }
    }

    /** Debounce syncs for [Event.SyncCompleted]. Rapid consecutive events are coalesced. */
    fun debounceSyncByEvent() {
        eventSyncJob?.cancel()
        eventSyncJob = repoScope.launch {
            delay(EVENT_SYNC_DEBOUNCE_MS)
            syncBalances()
            transferRepo.syncTransferStates()
            activityRepo.syncActivities()
            schedulePrimaryFunding()
        }
    }

    private fun schedulePrimaryFunding() {
        if (primaryFundingJob?.isActive == true) return
        primaryFundingJob = repoScope.launch {
            primaryNodeRepo.fundIfNeeded().onFailure {
                Logger.warn("Failed to fund primary channel", it, context = TAG)
            }
        }
    }

    /** Cancels any pending sync for [Event.SyncCompleted]. Called when manual pull-to-refresh takes priority. */
    fun cancelSyncByEvent() {
        eventSyncJob?.cancel()
        eventSyncJob = null
    }

    suspend fun refreshBip21ForEvent(
        event: Event,
        settledReceiveInvoice: SettledReceiveInvoice? = null,
        settledReceiveAddress: SettledReceiveAddress? = null,
    ) {
        if (settledReceiveInvoice != null && settledReceiveInvoice.bolt11 == approvedPrimaryInvoice?.first) {
            approvedPrimaryInvoice = null
        }
        val trackPending = synchronized(receiveUpdateStatusLock) {
            val cacheLoadPending = pendingReceiveCacheLoads > 0
            if (!shouldHandleReceiveEvent(event, settledReceiveInvoice, settledReceiveAddress, cacheLoadPending)) return
            val track = event !is Event.OnchainTransactionReceived || cacheLoadPending
            if (track) changePendingReceiveUpdates(1)
            track
        }
        try {
            updateReceiveState(trackPending = false) {
                refreshBip21ForEventLocked(event, settledReceiveInvoice, settledReceiveAddress)
            }
        } finally {
            if (trackPending) changePendingReceiveUpdates(-1)
        }
    }

    private suspend fun refreshBip21ForEventLocked(
        event: Event,
        settledReceiveInvoice: SettledReceiveInvoice?,
        settledReceiveAddress: SettledReceiveAddress?,
    ) {
        when (event) {
            is Event.ChannelReady,
            is Event.ChannelClosed,
            -> lightningRepo.syncState()
            else -> Unit
        }

        when (event) {
            is Event.ChannelReady -> {
                // Only refresh bolt11 if we can now receive on lightning
                Logger.debug("refreshBip21ForEvent: $event", context = TAG)
                val state = _walletState.value
                if (canCreateLightningInvoice(state.bip21AmountSats)) {
                    val bolt11 = lightningRepo.createInvoice(
                        amountSats = state.bip21AmountSats,
                        description = state.bip21Description,
                    ).getOrThrow()
                    setBolt11Locked(bolt11, state.bip21AmountSats, state.bip21Description)
                }
            }

            is Event.ChannelClosed -> {
                // Clear bolt11 if we can no longer receive on lightning
                Logger.debug("refreshBip21ForEvent: $event", context = TAG)
                if (!canCreateLightningInvoice(_walletState.value.bip21AmountSats)) {
                    setBolt11Locked("")
                }
            }

            is Event.PaymentReceived if settledReceiveInvoice != null -> {
                val settledBolt11 = settledReceiveInvoice.bolt11
                if (!invalidateSettledReceiveInvoice(settledBolt11)) return
                Logger.debug("Refreshing BIP21 for event '$event'", context = TAG)
                updateBip21Url()
                refreshAddressIfNeeded().getOrThrow()
            }

            is Event.PaymentReceived -> Unit

            is Event.OnchainTransactionReceived if settledReceiveAddress != null -> {
                val settledAddress = settledReceiveAddress.address
                if (!invalidateSettledReceiveAddress(settledAddress)) return
                Logger.debug("Refreshing BIP21 for event '$event'", context = TAG)
                refreshReusableReceiveAddressLocked()
            }

            is Event.OnchainTransactionReceived -> Unit

            else -> Unit
        }
    }

    private fun shouldHandleReceiveEvent(
        event: Event,
        settledReceiveInvoice: SettledReceiveInvoice?,
        settledReceiveAddress: SettledReceiveAddress?,
        cacheLoadPending: Boolean,
    ): Boolean {
        return when (event) {
            is Event.ChannelReady, is Event.ChannelClosed -> true
            is Event.PaymentReceived -> settledReceiveInvoice?.bolt11?.let {
                it.isNotEmpty() && (cacheLoadPending || it == getBolt11())
            } == true
            is Event.OnchainTransactionReceived -> settledReceiveAddress?.address?.let {
                it.isNotEmpty() && (cacheLoadPending || it == getOnchainAddress())
            } == true
            else -> false
        }
    }

    private suspend fun refreshAddressIfNeeded(): Result<Unit> = refreshReusableReceiveAddressLocked()

    private suspend fun refreshAddressForInvoice(): Result<Unit> {
        val result = refreshAddressIfNeeded()
        if (result.isFailure && getOnchainAddress().isNotEmpty()) return Result.success(Unit)
        return result
    }

    private fun invalidateSettledReceiveInvoice(settledBolt11: String): Boolean {
        while (true) {
            val state = _walletState.value
            if (state.bolt11 != settledBolt11) return false
            if (_walletState.compareAndSet(state, state.copy(bolt11 = "", bip21 = ""))) return true
        }
    }

    private fun invalidateSettledReceiveAddress(settledAddress: String): Boolean {
        while (true) {
            val state = _walletState.value
            if (state.onchainAddress != settledAddress) return false
            if (_walletState.compareAndSet(state, state.copy(onchainAddress = "", bip21 = ""))) return true
        }
    }

    suspend fun refreshReusableReceiveAddress(): Result<Unit> = updateReceiveState(trackPending = false) {
        refreshReusableReceiveAddressLocked()
    }

    private suspend fun refreshReusableReceiveAddressLocked(): Result<Unit> = withContext(bgDispatcher) {
        runSuspendCatching {
            refreshReusableReceiveAddressOrThrow()
            updateBip21Url()
            Unit
        }.onFailure {
            Logger.error("Failed to refresh reusable receive address", it, context = TAG)
        }
    }

    private suspend fun refreshReusableReceiveAddressOrThrow() {
        val address = getOnchainAddress()
        if (address.isEmpty()) {
            newAddress().getOrThrow()
        } else if (privatePaykitAddressReservationRepo.isUnavailableForReusableReceive(address)) {
            replaceReusableOnchainAddress().getOrThrow()
        } else {
            val wasUsed = checkAddressUsage(address).getOrThrow()
            if (wasUsed) {
                replaceReusableOnchainAddress().getOrThrow()
            }
        }
    }

    private suspend fun updateBip21Url(): String {
        val state = _walletState.value
        val newBip21 = if (state.bolt11.isNotEmpty()) {
            val query = committedReceiveBip21.substringAfter('?', "")
            if (query.split('&').any { it == "lightning=${state.bolt11}" }) {
                "bitcoin:${state.onchainAddress}?$query"
            } else {
                ""
            }
        } else {
            buildBip21Url(
                bitcoinAddress = state.onchainAddress,
                amountSats = state.bip21AmountSats,
                message = state.bip21Description,
            )
        }
        setBip21Locked(newBip21)
        return newBip21
    }

    suspend fun createWallet(bip39Passphrase: String?): Result<Unit> = withContext(bgDispatcher) {
        lightningRepo.setRecoveryMode(enabled = false)
        runSuspendCatching {
            connectionRecovery.withIdentityChange {
                val mnemonic = generateEntropyMnemonic()
                keychain.saveString(Keychain.Key.BIP39_MNEMONIC.name, mnemonic)
                if (bip39Passphrase != null) {
                    keychain.saveString(Keychain.Key.BIP39_PASSPHRASE.name, bip39Passphrase)
                }
                settingsStore.update {
                    it.copy(
                        selectedAddressType = DEFAULT_ADDRESS_TYPE_STRING,
                        addressTypesToMonitor = listOf(DEFAULT_ADDRESS_TYPE_STRING),
                    )
                }
            }
            setWalletExistsState()
        }.onFailure {
            Logger.error("createWallet error", it, context = TAG)
        }
    }

    suspend fun restoreWallet(mnemonic: String, bip39Passphrase: String?): Result<Unit> = withContext(bgDispatcher) {
        lightningRepo.setRecoveryMode(enabled = false)
        runSuspendCatching {
            connectionRecovery.withIdentityChange {
                keychain.saveString(Keychain.Key.BIP39_MNEMONIC.name, mnemonic)
                if (bip39Passphrase != null) {
                    keychain.saveString(Keychain.Key.BIP39_PASSPHRASE.name, bip39Passphrase)
                }
                settingsStore.update {
                    it.copy(
                        selectedAddressType = DEFAULT_ADDRESS_TYPE_STRING,
                        addressTypesToMonitor = ALL_ADDRESS_TYPE_STRINGS,
                    )
                }
            }
            setWalletExistsState()
        }.onFailure {
            Logger.error("restoreWallet error", it, context = TAG)
        }
    }

    suspend fun wipeWallet(walletIndex: Int = 0): Result<Unit> = withContext(bgDispatcher) {
        wipeWalletUseCase(
            walletIndex = walletIndex,
            resetWalletState = ::resetState,
            onSuccess = ::setWalletExistsState,
        ).onFailure {
            if (it is WipeIncomplete) setWalletExistsState()
        }
    }

    fun resetState() {
        _walletState.update { WalletState() }
        _balanceState.update { BalanceState() }
    }

    fun getOnchainAddress(): String = _walletState.value.onchainAddress

    suspend fun setOnchainAddress(address: String) {
        cacheStore.setOnchainAddress(address)
        _walletState.update { it.copy(onchainAddress = address) }
    }

    suspend fun newAddress(): Result<String> = withContext(bgDispatcher) {
        privatePaykitAddressReservationRepo.nextReusableReceiveAddress()
            .onSuccess { address -> setOnchainAddress(address) }
            .onFailure { error -> Logger.error("Failed to generate new address", error, context = TAG) }
    }

    suspend fun refreshReusableReceiveAddressIfReserved(): Result<Unit> = updateReceiveState(trackPending = false) {
        runSuspendCatching {
            if (!privatePaykitAddressReservationRepo.isUnavailableForReusableReceive(getOnchainAddress())) {
                return@runSuspendCatching
            }

            replaceReusableOnchainAddress().getOrThrow()
            updateBip21Url()
        }.onFailure {
            Logger.error("Failed to refresh reserved receive address", it, context = TAG)
        }
    }

    private suspend fun replaceReusableOnchainAddress(): Result<String> {
        clearReusableOnchainAddress()
        return newAddress()
    }

    private suspend fun clearReusableOnchainAddress() {
        _walletState.update { it.copy(onchainAddress = "", bip21 = "") }
        cacheStore.update { it.copy(onchainAddress = "", bip21 = "") }
    }

    suspend fun refreshReceiveAddressAfterTypeChange(): Result<Unit> = updateReceiveState {
        runSuspendCatching {
            cacheStore.update { it.resetBip21() }
            _walletState.update { it.copy(onchainAddress = "", bolt11 = "", bip21 = "") }
            newAddress().getOrThrow()
            updateBip21InvoiceLocked().getOrThrow()
            Unit
        }.onFailure {
            Logger.error("refreshReceiveAddressAfterTypeChange failed", it, context = TAG)
        }
    }

    suspend fun getAddresses(
        startIndex: Int = 0,
        isChange: Boolean = false,
        count: Int = 20,
        addressType: AddressType = AddressType.P2WPKH,
    ): Result<List<AddressModel>> = withContext(bgDispatcher) {
        runCatching {
            val result = lightningRepo.addressInfosForType(
                addressType = addressType,
                isChange = isChange,
                startIndex = startIndex,
                count = count,
            ).getOrElse {
                deriveAddressInfosFromMnemonic(
                    addressType = addressType,
                    isChange = isChange,
                    startIndex = startIndex,
                    count = count,
                )
            }

            val addresses = result.map { address ->
                AddressModel(
                    address = address.address,
                    index = address.index,
                    path = addressType.toDerivationPath(index = address.index, isChange = isChange),
                )
            }

            return@runCatching addresses
        }.onFailure {
            Logger.error("Error getting addresses", it, context = TAG)
        }
    }

    private suspend fun deriveAddressInfosFromMnemonic(
        addressType: AddressType,
        isChange: Boolean,
        startIndex: Int,
        count: Int,
    ): List<AddressDerivationInfo> {
        val mnemonic = keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name)
            ?: throw ServiceError.MnemonicNotFound()
        val passphrase = keychain.loadString(Keychain.Key.BIP39_PASSPHRASE.name)
        val baseDerivationPath = addressType.toAccountDerivationPath()

        return coreService.onchain.deriveBitcoinAddresses(
            mnemonicPhrase = mnemonic,
            derivationPathStr = baseDerivationPath,
            network = Env.network,
            bip39Passphrase = passphrase,
            isChange = isChange,
            startIndex = startIndex.toUInt(),
            count = count.toUInt(),
        ).addresses.mapIndexed { offset, address ->
            AddressDerivationInfo(
                address = address.address,
                index = startIndex + offset,
            )
        }
    }

    fun getBolt11(): String = _walletState.value.bolt11

    suspend fun setBolt11(bolt11: String) = updateReceiveState { setBolt11Locked(bolt11) }

    private suspend fun setBolt11Locked(
        bolt11: String,
        amountSats: ULong? = _walletState.value.bip21AmountSats,
        description: String = _walletState.value.bip21Description,
    ) {
        val state = _walletState.value
        val bip21 = buildBip21Url(
            bitcoinAddress = state.onchainAddress,
            amountSats = amountSats,
            message = description,
            lightningInvoice = bolt11,
        )
        val paymentHash = if (bolt11.isEmpty()) {
            ""
        } else {
            runCatching { Bolt11Invoice.fromStr(bolt11).paymentHash() }
                .onFailure { Logger.error("Failed to parse receive invoice", it, context = TAG) }
                .getOrDefault("")
        }
        cacheStore.update { it.copy(bolt11 = bolt11, bolt11PaymentHash = paymentHash, bip21 = bip21) }
        committedReceiveBip21 = bip21
        val isPrimaryInvoice = bolt11.isNotEmpty() && bolt11 == approvedPrimaryInvoice?.first
        _walletState.update {
            it.copy(bolt11 = bolt11, bip21 = bip21, hasReceiveInvoiceError = false, isPrimaryInvoice = isPrimaryInvoice)
        }
    }

    suspend fun setBip21(bip21: String) = updateReceiveState(trackPending = false) { setBip21Locked(bip21) }

    private suspend fun setBip21Locked(bip21: String) {
        cacheStore.setBip21(bip21)
        committedReceiveBip21 = bip21
        _walletState.update { it.copy(bip21 = bip21) }
    }

    fun buildBip21Url(
        bitcoinAddress: String,
        amountSats: ULong? = null,
        message: String = "",
        lightningInvoice: String = "",
    ): String {
        return Bip21Utils.buildBip21Url(
            bitcoinAddress = bitcoinAddress,
            amountSats = amountSats,
            message = message,
            lightningInvoice = lightningInvoice,
        )
    }

    fun setBip21AmountSats(amount: ULong?) = _walletState.update { it.copy(bip21AmountSats = amount) }

    suspend fun updateOnchainBip21Amount(amountSats: ULong?): Result<Unit> {
        val normalizedAmount = amountSats?.takeIf { it > 0uL }
        val description = _walletState.value.bip21Description
        return updateReceiveState(beforeLock = { setBip21AmountSats(normalizedAmount) }) {
            runSuspendCatching { setBolt11Locked("", normalizedAmount, description) }
        }
    }

    fun setBip21Description(description: String) = _walletState.update { it.copy(bip21Description = description) }

    fun clearBip21State(clearTags: Boolean = true) {
        _walletState.update {
            it.copy(
                bip21 = "",
                selectedTags = if (clearTags) persistentListOf() else it.selectedTags,
                bip21AmountSats = null,
                bip21Description = "",
                hasReceiveInvoiceError = false,
            )
        }
    }

    private suspend fun paymentHash(): String? = withContext(bgDispatcher) {
        val bolt11 = getBolt11()
        if (bolt11.isEmpty()) return@withContext null

        runCatching {
            when (val decoded = coreService.decode(bolt11)) {
                is Scanner.Lightning -> decoded.invoice.paymentHash.toHex()
                else -> null
            }
        }.onFailure {
            Logger.error("Error extracting payment hash from bolt11", it, context = TAG)
        }.getOrNull()
    }

    suspend fun paymentId(): String? = withContext(bgDispatcher) {
        val hash = paymentHash()
        if (hash != null) return@withContext hash
        val address = getOnchainAddress()

        return@withContext address.ifEmpty { null }
    }

    suspend fun addTagToSelected(newTag: String): Result<Unit> = withContext(bgDispatcher) {
        val paymentId = paymentId()
        if (paymentId == null || paymentId.isEmpty()) {
            val exception = IllegalStateException("Cannot add tag: payment ID not available")
            Logger.warn(exception.message, context = TAG)
            return@withContext Result.failure(exception)
        }

        preActivityMetadataRepo.addPreActivityMetadataTags(paymentId, listOf(newTag)).onSuccess {
            _walletState.update {
                it.copy(
                    selectedTags = (it.selectedTags + newTag).distinct().toImmutableList()
                )
            }
            settingsStore.addLastUsedTag(newTag)
        }.onFailure {
            Logger.error("Failed to add tag to pre-activity metadata", it, context = TAG)
        }
    }

    suspend fun removeTag(tag: String): Result<Unit> = withContext(bgDispatcher) {
        val paymentId = paymentId()
        if (paymentId == null || paymentId.isEmpty()) {
            val exception = IllegalStateException("Cannot remove tag: payment ID not available")
            Logger.warn(exception.message, context = TAG)
            return@withContext Result.failure(exception)
        }

        preActivityMetadataRepo.removePreActivityMetadataTags(paymentId, listOf(tag))
            .onSuccess {
                _walletState.update {
                    it.copy(
                        selectedTags = it.selectedTags.filterNot { tagItem -> tagItem == tag }.toImmutableList()
                    )
                }
            }.onFailure {
                Logger.error("Failed to remove tag from pre-activity metadata", it, context = TAG)
            }
    }

    suspend fun resetPreActivityMetadataTagsForCurrentInvoice() = withContext(bgDispatcher) {
        val paymentId = paymentId()
        if (paymentId == null || paymentId.isEmpty()) return@withContext

        preActivityMetadataRepo.resetPreActivityMetadataTags(paymentId).onSuccess {
            _walletState.update { it.copy(selectedTags = persistentListOf()) }
        }.onFailure {
            Logger.error("Failed to reset tags for pre-activity metadata", it, context = TAG)
        }
    }

    // BIP21 invoice creation and persistence
    suspend fun updateBip21Invoice(
        amountSats: ULong? = walletState.value.bip21AmountSats,
        description: String = walletState.value.bip21Description,
    ): Result<Unit> = updateReceiveState(
        beforeLock = {
            _walletState.update { it.copy(bip21AmountSats = amountSats, bip21Description = description) }
        },
    ) {
        updateBip21InvoiceLocked(amountSats, description)
    }

    /** Shows [bolt11], an invoice the primary node delivers, as the current receive request for [amountSats]. */
    suspend fun setPrimaryReceiveInvoice(
        bolt11: String,
        amountSats: ULong,
        description: String,
    ): Result<Unit> = updateReceiveState(
        beforeLock = {
            _walletState.update { it.copy(bip21AmountSats = amountSats, bip21Description = description) }
        },
    ) {
        approvedPrimaryInvoice = bolt11 to amountSats
        runSuspendCatching { setBolt11Locked(bolt11, amountSats, description) }
    }

    private suspend fun updateBip21InvoiceLocked(
        amountSats: ULong? = walletState.value.bip21AmountSats,
        description: String = walletState.value.bip21Description,
    ): Result<Unit> = withContext(bgDispatcher) {
        _walletState.update { it.copy(hasReceiveInvoiceError = false) }
        runSuspendCatching {
            val oldPaymentId = paymentId()
            val tagsToMigrate = if (oldPaymentId != null && oldPaymentId.isNotEmpty()) {
                preActivityMetadataRepo
                    .getPreActivityMetadata(oldPaymentId, searchByAddress = false)
                    .getOrNull()
                    ?.tags ?: emptyList()
            } else {
                emptyList()
            }

            val approved = approvedPrimaryInvoice?.takeIf { (invoice, sats) ->
                sats == amountSats && runCatching { !Bolt11Invoice.fromStr(invoice).isExpired() }.getOrDefault(false)
            }
            val bolt11 = when {
                approved != null -> approved.first
                canCreateLightningInvoice(
                    amountSats
                ) -> lightningRepo.createInvoice(amountSats, description).getOrThrow()
                else -> ""
            }
            setBolt11Locked(bolt11, amountSats, description)
            val newBip21Url = _walletState.value.bip21

            // Persist metadata with migrated tags
            val newPaymentId = paymentId()
            if (newPaymentId != null && newPaymentId.isNotEmpty() && newBip21Url.isNotEmpty()) {
                persistPreActivityMetadata(newPaymentId, tagsToMigrate, newBip21Url)
            }
        }.onFailure {
            _walletState.update { state -> state.copy(hasReceiveInvoiceError = true) }
            Logger.error("Update BIP21 invoice error", it, context = TAG)
        }
    }

    suspend fun inboundLiquiditySats(): ULong = withContext(bgDispatcher) {
        return@withContext currentReadyChannels().calculateRemoteBalance()
    }

    private fun canCreateLightningInvoice(amountSats: ULong?): Boolean {
        val readyChannels = currentReadyChannels()
        return ReceiveLiquidityDecision.canCreateLightningInvoice(
            hasReadyChannels = readyChannels.isNotEmpty(),
            inboundCapacitySats = readyChannels.calculateRemoteBalance(),
            invoiceAmountSats = amountSats,
        )
    }

    private fun currentChannels(): List<ChannelDetails> {
        return lightningRepo.getChannels() ?: lightningRepo.lightningState.value.channels
    }

    private fun currentReadyChannels(): List<ChannelDetails> {
        return currentChannels().filter { it.isChannelReady }
    }

    private suspend fun Scanner.OnChain.extractLightningHash(): String? {
        val lightningInvoice: String = this.invoice.params?.get("lightning") ?: return null

        return when (val decoded = coreService.decode(lightningInvoice)) {
            is Scanner.Lightning -> decoded.invoice.paymentHash.toHex()
            else -> null
        }
    }

    private fun generateEntropyMnemonic(): String {
        return org.lightningdevkit.ldknode.generateEntropyMnemonic(wordCount = WordCount.WORDS12)
    }

    private companion object {
        const val TAG = "WalletRepo"
        const val EVENT_SYNC_DEBOUNCE_MS = 500L
        val SLOW_SYNC_THRESHOLD = 5.seconds
    }
}

@Immutable
data class WalletState(
    val onchainAddress: String = "",
    val bolt11: String = "",
    val bip21: String = "",
    val hasReceiveInvoiceError: Boolean = false,
    val isReceiveInvoiceUpdating: Boolean = false,
    val bip21AmountSats: ULong? = null,
    val bip21Description: String = "",
    /** The Lightning invoice is one the user approved with the primary node's fee, not one inbound capacity covers. */
    val isPrimaryInvoice: Boolean = false,
    val selectedTags: ImmutableList<String> = persistentListOf(),
    val walletExists: Boolean = false,
)

enum class SyncSource { AUTO, MANUAL }
