package to.bitkit.repositories

import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.ActivityFilter
import com.synonym.bitkitcore.AddressType
import com.synonym.bitkitcore.GetAddressResponse
import com.synonym.bitkitcore.PaymentType
import com.synonym.bitkitcore.Scanner
import com.synonym.bitkitcore.decode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import org.lightningdevkit.ldknode.BalanceDetails
import org.lightningdevkit.ldknode.Event
import org.lightningdevkit.ldknode.Network
import org.lightningdevkit.ldknode.Txid
import to.bitkit.data.AppDb
import to.bitkit.data.AppStorage
import to.bitkit.data.CacheStore
import to.bitkit.data.SettingsStore
import to.bitkit.data.entities.InvoiceTagEntity
import to.bitkit.data.keychain.Keychain
import to.bitkit.di.BgDispatcher
import to.bitkit.env.Env
import to.bitkit.ext.toHex
import to.bitkit.models.BalanceState
import to.bitkit.models.NodeLifecycleState
import to.bitkit.models.toDerivationPath
import to.bitkit.services.CoreService
import to.bitkit.utils.AddressChecker
import to.bitkit.utils.Bip21Utils
import to.bitkit.utils.Logger
import to.bitkit.utils.ServiceError
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

@Singleton
class WalletRepo @Inject constructor(
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val appStorage: AppStorage,
    private val db: AppDb,
    private val keychain: Keychain,
    private val coreService: CoreService,
    private val settingsStore: SettingsStore,
    private val addressChecker: AddressChecker,
    private val lightningRepo: LightningRepo,
    private val cacheStore: CacheStore,
    private val network: Network,
) {

    private val _walletState = MutableStateFlow(
        WalletState(
            onchainAddress = appStorage.onchainAddress,
            bolt11 = appStorage.bolt11,
            bip21 = appStorage.bip21,
            walletExists = walletExists()
        )
    )
    val walletState = _walletState.asStateFlow()

    private val _balanceState = MutableStateFlow(getBalanceState())
    val balanceState = _balanceState.asStateFlow()

    fun walletExists(): Boolean = keychain.exists(Keychain.Key.BIP39_MNEMONIC.name)

    fun setWalletExistsState() {
        _walletState.update { it.copy(walletExists = walletExists()) }
    }

    suspend fun checkAddressUsage(address: String): Result<Boolean> = withContext(bgDispatcher) {
        return@withContext try {
            val addressInfo = addressChecker.getAddressInfo(address)
            val hasTransactions = addressInfo.chain_stats.tx_count > 0 || addressInfo.mempool_stats.tx_count > 0
            Result.success(hasTransactions)
        } catch (e: Exception) {
            Logger.error("checkAddressUsage error", e, context = TAG)
            Result.failure(e)
        }
    }

    suspend fun refreshBip21(force: Boolean = false): Result<Unit> = withContext(bgDispatcher) {
        Logger.debug("Refreshing bip21 (force: $force)", context = TAG)

        if (coreService.shouldBlockLightning()) {
            _walletState.update {
                it.copy(receiveOnSpendingBalance = false)
            }
        }

        // Reset invoice state
        _walletState.update {
            it.copy(
                selectedTags = emptyList(),
                bip21Description = "",
                balanceInput = "",
                bip21 = ""
            )
        }

        // Check current address or generate new one
        val currentAddress = getOnchainAddress()
        if (force || currentAddress.isEmpty()) {
            newAddress()
                .onSuccess { address -> setOnchainAddress(address) }
                .onFailure { error -> Logger.error("Error generating new address", error) }
        } else {
            // Check if current address has been used
            checkAddressUsage(currentAddress)
                .onSuccess { hasTransactions ->
                    if (hasTransactions) {
                        // Address has been used, generate a new one
                        newAddress().onSuccess { address -> setOnchainAddress(address) }
                    }
                }
        }

        updateBip21Invoice()
        return@withContext Result.success(Unit)
    }

    suspend fun observeLdkWallet() = withContext(bgDispatcher) {
        lightningRepo.getSyncFlow()
            .filter { lightningRepo.lightningState.value.nodeLifecycleState == NodeLifecycleState.Running }
            .collect {
                runCatching {
                    syncNodeAndWallet()
                }
            }
    }

    suspend fun syncNodeAndWallet(): Result<Unit> = withContext(bgDispatcher) {
        syncBalances()
        lightningRepo.sync().onSuccess {
            syncBalances()
            return@withContext Result.success(Unit)
        }.onFailure { e ->
            return@withContext Result.failure(e)
        }
    }

    suspend fun syncBalances() {
        lightningRepo.getBalances()?.let { balance ->
            val totalSats = balance.totalLightningBalanceSats + balance.totalOnchainBalanceSats

            val newBalance = BalanceState(
                totalOnchainSats = balance.totalOnchainBalanceSats,
                totalLightningSats = balance.totalLightningBalanceSats,
                totalSats = totalSats,
            )
            _balanceState.update { newBalance }
            _walletState.update { it.copy(balanceDetails = lightningRepo.getBalances()) }
            saveBalanceState(newBalance)

            setShowEmptyState(totalSats <= 0u)
        }
    }

    suspend fun refreshBip21ForEvent(event: Event) {
        when (event) {
            is Event.PaymentReceived, is Event.ChannelReady, is Event.ChannelClosed -> refreshBip21()
            else -> Unit
        }
    }

    fun setRestoringWalletState(isRestoring: Boolean) {
        _walletState.update { it.copy(isRestoringWallet = isRestoring) }
    }

    suspend fun createWallet(bip39Passphrase: String?): Result<Unit> = withContext(bgDispatcher) {
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
        try {
            keychain.wipe()
            appStorage.clear()
            settingsStore.reset()
            cacheStore.reset()
            // TODO CLEAN ACTIVITY'S AND UPDATE STATE. CHECK ActivityListViewModel.removeAllActivities
            coreService.activity.removeAll()
            deleteAllInvoices()
            _walletState.update { WalletState() }
            _balanceState.update { BalanceState() }
            setWalletExistsState()

            return@withContext lightningRepo.wipeStorage(walletIndex = walletIndex)
        } catch (e: Throwable) {
            Logger.error("Wipe wallet error", e)
            Result.failure(e)
        }
    }

    // Blockchain address management
    fun getOnchainAddress(): String = appStorage.onchainAddress

    fun setOnchainAddress(address: String) {
        appStorage.onchainAddress = address
        _walletState.update { it.copy(onchainAddress = address) }
    }

    suspend fun newAddress(): Result<String> = withContext(bgDispatcher) {
        return@withContext lightningRepo.newAddress()

        // TODO uncomment & update when ldk-node supports different address types
        // try {
        //     val mnemonic = keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name) ?: throw ServiceError.MnemonicNotFound
        //     val passphrase = keychain.loadString(Keychain.Key.BIP39_PASSPHRASE.name)
        //
        //     val addressType = settingsStore.data.first().addressType
        //
        //     // TODO: manage address index
        //     val nextIndex = 0
        //     val derivationPath = addressType.toDerivationPath(network, index = nextIndex)
        //
        //     val result: GetAddressResponse = coreService.onchain.deriveBitcoinAddress(
        //         mnemonicPhrase = mnemonic,
        //         derivationPathStr = derivationPath,
        //         network = network,
        //         bip39Passphrase = passphrase,
        //     )
        //
        //     Logger.info("Generated new receive address: ${result.address},' type: $addressType, index: $nextIndex")
        //
        //     Result.success(result.address)
        // } catch (e: Throwable) {
        //     Logger.error("Address generation error", e, context = TAG)
        //     Result.failure(e)
        // }
    }

    // Bolt11 management
    fun getBolt11(): String = _walletState.value.bolt11

    fun setBolt11(bolt11: String) {
        appStorage.bolt11 = bolt11
        _walletState.update { it.copy(bolt11 = bolt11) }
    }

    // BIP21 management
    fun getBip21(): String = _walletState.value.bip21

    fun setBip21(bip21: String) {
        appStorage.bip21 = bip21
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

    // Balance management
    fun getBalanceState(): BalanceState = appStorage.loadBalance() ?: BalanceState()

    suspend fun saveBalanceState(balanceState: BalanceState) {
        appStorage.cacheBalance(balanceState)
        _balanceState.update { balanceState }

        if (balanceState.totalSats > 0u) {
            setShowEmptyState(false)
        }
    }

    // Settings
    suspend fun setShowEmptyState(show: Boolean) {
        settingsStore.update { it.copy(showEmptyState = show) }
        _walletState.update { it.copy(showEmptyState = show) }
    }

    // BIP21 state management
    fun updateBip21AmountSats(amount: ULong?) {
        _walletState.update { it.copy(bip21AmountSats = amount) }
    }

    fun updateBip21Description(description: String) {
        _walletState.update { it.copy(bip21Description = description) }
    }

    fun updateBalanceInput(newText: String) {
        _walletState.update { it.copy(balanceInput = newText) }
    }

    suspend fun toggleReceiveOnSpendingBalance(): Result<Unit> = withContext(bgDispatcher) {
        if (_walletState.value.receiveOnSpendingBalance == false && coreService.shouldBlockLightning()) {
            return@withContext Result.failure(ServiceError.GeoBlocked)
        }

        _walletState.update { it.copy(receiveOnSpendingBalance = !it.receiveOnSpendingBalance) }

        return@withContext Result.success(Unit)
    }

    // Tags
    suspend fun addTagToSelected(newTag: String) {
        _walletState.update {
            it.copy(
                selectedTags = (it.selectedTags + newTag).distinct()
            )
        }
        settingsStore.addLastUsedTag(newTag)
    }

    fun removeTag(tag: String) {
        _walletState.update {
            it.copy(
                selectedTags = it.selectedTags.filterNot { tagItem -> tagItem == tag }
            )
        }
    }

    // BIP21 invoice creation
    suspend fun updateBip21Invoice(
        amountSats: ULong? = null,
        description: String = "",
    ): Result<Unit> = withContext(bgDispatcher) {
        try {
            updateBip21AmountSats(amountSats)
            updateBip21Description(description)

            val hasChannels = lightningRepo.hasChannels()

            if (hasChannels && _walletState.value.receiveOnSpendingBalance) {
                lightningRepo.createInvoice(
                    amountSats = _walletState.value.bip21AmountSats,
                    description = _walletState.value.bip21Description,
                ).onSuccess { bolt11 ->
                    setBolt11(bolt11)
                }
            } else {
                setBolt11("")
            }

            val newBip21 = buildBip21Url(
                bitcoinAddress = getOnchainAddress(),
                amountSats = _walletState.value.bip21AmountSats,
                message = description.ifBlank { Env.DEFAULT_INVOICE_MESSAGE },
                lightningInvoice = getBolt11()
            )
            setBip21(newBip21)
            saveInvoiceWithTags(bip21Invoice = newBip21, tags = _walletState.value.selectedTags)
            Result.success(Unit)
        } catch (e: Throwable) {
            Logger.error("Update BIP21 invoice error", e, context = TAG)
            Result.failure(e)
        }
    }

    suspend fun shouldRequestAdditionalLiquidity(): Result<Boolean> = withContext(bgDispatcher) {
        return@withContext try {
            if (_walletState.value.receiveOnSpendingBalance == false) return@withContext Result.success(false)

            if (coreService.checkGeoStatus() == true) return@withContext Result.success(false)

            val channels = lightningRepo.lightningState.value.channels
            val inboundBalanceSats = channels.sumOf { it.inboundCapacityMsat / 1000u }

            Result.success((_walletState.value.bip21AmountSats ?: 0uL) >= inboundBalanceSats)
        } catch (e: Exception) {
            Logger.error("shouldRequestAdditionalLiquidity error", e, context = TAG)
            Result.failure(e)
        }
    }

    suspend fun saveInvoiceWithTags(bip21Invoice: String, tags: List<String>) = withContext(bgDispatcher) {
        if (tags.isEmpty()) return@withContext

        try {
            deleteExpiredInvoices()
            val decoded = decode(bip21Invoice)
            val paymentHashOrAddress = when (decoded) {
                is Scanner.Lightning -> decoded.invoice.paymentHash.toHex()
                is Scanner.OnChain -> decoded.extractLightningHashOrAddress()
                else -> null
            }

            paymentHashOrAddress?.let {
                db.invoiceTagDao().saveInvoice(
                    invoiceTag = InvoiceTagEntity(
                        paymentHash = paymentHashOrAddress,
                        tags = tags,
                        createdAt = System.currentTimeMillis()
                    )
                )
            }
        } catch (e: Throwable) {
            Logger.error("saveInvoice error", e, context = TAG)
        }
    }

    suspend fun searchInvoice(txId: Txid): Result<InvoiceTagEntity> = withContext(bgDispatcher) {
        return@withContext try {
            val invoiceTag = db.invoiceTagDao().searchInvoice(paymentHash = txId) ?: return@withContext Result.failure(
                Exception("Invoice not found")
            )
            Result.success(invoiceTag)
        } catch (e: Throwable) {
            Logger.error("searchInvoice error", e, context = TAG)
            Result.failure(e)
        }
    }

    suspend fun deleteInvoice(txId: Txid) = withContext(bgDispatcher) {
        try {
            db.invoiceTagDao().deleteInvoiceByPaymentHash(paymentHash = txId)
        } catch (e: Throwable) {
            Logger.error("deleteInvoice error", e, context = TAG)
        }
    }

    suspend fun deleteAllInvoices() = withContext(bgDispatcher) {
        try {
            db.invoiceTagDao().deleteAllInvoices()
        } catch (e: Throwable) {
            Logger.error("deleteAllInvoices error", e, context = TAG)
        }
    }

    suspend fun deleteExpiredInvoices() = withContext(bgDispatcher) {
        try {
            val twoDaysAgoMillis = Clock.System.now().minus(2.days).toEpochMilliseconds()
            db.invoiceTagDao().deleteExpiredInvoices(expirationTimeStamp = twoDaysAgoMillis)
        } catch (e: Throwable) {
            Logger.error("deleteExpiredInvoices error", e, context = TAG)
        }
    }

    suspend fun deleteActivityById(id: String) = withContext(bgDispatcher) {
        runCatching {
            coreService.activity.delete(id)
        }.onFailure {
            Logger.error("Error deleting activity", context = TAG)
        }
    }


    suspend fun getActivityById(id: String): Result<Activity> {
        return runCatching {
            val activity =
                coreService.activity.getActivity(id) ?: return Result.failure(Exception("Activity not found"))
            return Result.success(activity)
        }.onFailure { e ->
            Logger.error("Error updating activity", context = TAG)
            return Result.failure(e)
        }
    }

    suspend fun updateActivity(id: String, activity: Activity) {
        runCatching {
            coreService.activity.update(id, activity)
        }.onFailure {
            Logger.error("Error updating activity", context = TAG)
        }
    }

    suspend fun attachTagsToActivity(
        paymentHashOrTxId: String?,
        type: ActivityFilter,
        txType: PaymentType,
        tags: List<String>,
    ): Result<Unit> = withContext(bgDispatcher) {
        Logger.debug("attachTagsToActivity $tags", context = TAG)

        when {
            tags.isEmpty() -> {
                Logger.debug("selectedTags empty", context = TAG)
                return@withContext Result.failure(IllegalArgumentException("selectedTags empty"))
            }

            paymentHashOrTxId == null -> {
                Logger.error(msg = "null paymentHashOrTxId", context = TAG)
                return@withContext Result.failure(IllegalArgumentException("null paymentHashOrTxId"))
            }
        }

        val activity = findActivityWithRetry(
            paymentHashOrTxId = paymentHashOrTxId,
            type = type,
            txType = txType
        ) ?: return@withContext Result.failure(IllegalStateException("Activity not found"))

        if (!activity.matchesId(paymentHashOrTxId)) {
            Logger.error(
                "ID mismatch. Expected: $paymentHashOrTxId found: ${activity.idValue}",
                context = TAG
            )
            return@withContext Result.failure(IllegalStateException("Activity ID mismatch"))
        }

        coreService.activity.appendTags(
            toActivityId = activity.idValue,
            tags = tags
        ).fold(
            onFailure = { error ->
                Logger.error("Error attaching tags $tags", error, context = TAG)
                Result.failure(Exception("Error attaching tags $tags", error))
            },
            onSuccess = {
                Logger.info("Success attaching tags $tags to activity ${activity.idValue}", context = TAG)
                deleteInvoice(txId = paymentHashOrTxId)
                Result.success(Unit)
            }
        )
    }

    private suspend fun findActivityWithRetry(
        paymentHashOrTxId: String,
        type: ActivityFilter,
        txType: PaymentType,
    ): Activity? {

        suspend fun findActivity(): Activity? = coreService.activity.get(
            filter = type,
            txType = txType,
            limit = 10u
        ).firstOrNull { it.matchesId(paymentHashOrTxId) }

        var activity = findActivity()
        if (activity == null) {
            Logger.warn("activity not found, trying again after delay", context = TAG)
            delay(5.seconds)
            activity = findActivity()
        }
        return activity
    }

    private fun Activity.matchesId(paymentHashOrTxId: String): Boolean = when (this) {
        is Activity.Lightning -> paymentHashOrTxId == v1.id
        is Activity.Onchain -> paymentHashOrTxId == v1.txId
    }

    private val Activity.idValue: String
        get() = when (this) {
            is Activity.Lightning -> v1.id
            is Activity.Onchain -> v1.txId
        }

    private suspend fun Scanner.OnChain.extractLightningHashOrAddress(): String {
        val address = this.invoice.address
        val lightningInvoice: String = this.invoice.params?.get("lightning") ?: address
        val decoded = decode(lightningInvoice)

        return when (decoded) {
            is Scanner.Lightning -> decoded.invoice.paymentHash.toHex()
            else -> address
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
    val balanceInput: String = "",
    val bolt11: String = "",
    val bip21: String = "",
    val bip21AmountSats: ULong? = null,
    val bip21Description: String = "",
    val selectedTags: List<String> = listOf(),
    val receiveOnSpendingBalance: Boolean = true,
    val showEmptyState: Boolean = true,
    val walletExists: Boolean = false,
    val isRestoringWallet: Boolean = false,
    val balanceDetails: BalanceDetails? = null, // TODO KEEP ONLY BalanceState IF POSSIBLE
)
