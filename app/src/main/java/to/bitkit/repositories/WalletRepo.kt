package to.bitkit.repositories

import android.content.Context
import android.icu.util.Calendar
import androidx.lifecycle.viewModelScope
import com.google.firebase.messaging.FirebaseMessaging
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.lightningdevkit.ldknode.Network
import org.lightningdevkit.ldknode.Txid
import to.bitkit.data.AppDb
import to.bitkit.data.AppStorage
import to.bitkit.data.SettingsStore
import to.bitkit.data.entities.ConfigEntity
import to.bitkit.data.entities.InvoiceTagEntity
import to.bitkit.data.keychain.Keychain
import to.bitkit.di.BgDispatcher
import to.bitkit.env.Env
import to.bitkit.models.BalanceState
import to.bitkit.models.NewTransactionSheetDetails
import to.bitkit.models.NewTransactionSheetDirection
import to.bitkit.models.NewTransactionSheetType
import to.bitkit.services.BlocktankNotificationsService
import to.bitkit.services.CoreService
import to.bitkit.utils.Bip21Utils
import to.bitkit.utils.Logger
import uniffi.bitkitcore.Activity
import uniffi.bitkitcore.ActivityFilter
import uniffi.bitkitcore.IBtInfo
import uniffi.bitkitcore.PaymentType
import uniffi.bitkitcore.Scanner
import uniffi.bitkitcore.decode
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

@Singleton
class WalletRepo @Inject constructor(
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    @ApplicationContext private val appContext: Context,
    private val appStorage: AppStorage,
    private val db: AppDb,
    private val keychain: Keychain,
    private val coreService: CoreService,
    private val blocktankNotificationsService: BlocktankNotificationsService,
    private val firebaseMessaging: FirebaseMessaging,
    private val settingsStore: SettingsStore,
) {
    fun walletExists(): Boolean = keychain.exists(Keychain.Key.BIP39_MNEMONIC.name)

    suspend fun createWallet(bip39Passphrase: String?): Result<Unit> = withContext(bgDispatcher) {
        try {
            val mnemonic = generateEntropyMnemonic()
            keychain.saveString(Keychain.Key.BIP39_MNEMONIC.name, mnemonic)
            if (bip39Passphrase != null) {
                keychain.saveString(Keychain.Key.BIP39_PASSPHRASE.name, bip39Passphrase)
            }
            Result.success(Unit)
        } catch (e: Throwable) {
            Logger.error("Create wallet error", e)
            Result.failure(e)
        }
    }

    suspend fun restoreWallet(mnemonic: String, bip39Passphrase: String?): Result<Unit> = withContext(bgDispatcher) {
        try {
            keychain.saveString(Keychain.Key.BIP39_MNEMONIC.name, mnemonic)
            if (bip39Passphrase != null) {
                keychain.saveString(Keychain.Key.BIP39_PASSPHRASE.name, bip39Passphrase)
            }
            Result.success(Unit)
        } catch (e: Throwable) {
            Logger.error("Restore wallet error", e)
            Result.failure(e)
        }
    }

    suspend fun wipeWallet(): Result<Unit> = withContext(bgDispatcher) {
        if (Env.network != Network.REGTEST) {
            return@withContext Result.failure(Exception("Can only wipe on regtest."))
        }

        try { //TODO CLEAN ACTIVITY'S AND UPDATE STATE. CHECK ActivityListViewModel.removeAllActivities
            keychain.wipe()
            appStorage.clear()
            settingsStore.wipe()
            coreService.activity.removeAll()
            deleteAllInvoices()
            Result.success(Unit)
        } catch (e: Throwable) {
            Logger.error("Wipe wallet error", e)
            Result.failure(e)
        }
    }

    // Blockchain address management
    fun getOnchainAddress(): String = appStorage.onchainAddress

    fun setOnchainAddress(address: String) {
        appStorage.onchainAddress = address
    }

    // Bolt11 management
    fun getBolt11(): String = appStorage.bolt11

    fun setBolt11(bolt11: String) {
        appStorage.bolt11 = bolt11
    }

    // BIP21 management
    fun getBip21(): String = appStorage.bip21

    fun setBip21(bip21: String) {
        appStorage.bip21 = bip21
    }

    fun buildBip21Url(
        bitcoinAddress: String,
        amountSats: ULong? = null,
        message: String = Env.DEFAULT_INVOICE_MESSAGE,
        lightningInvoice: String = ""
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

    fun saveBalanceState(balanceState: BalanceState) {
        appStorage.cacheBalance(balanceState)
    }

    // Settings
    suspend fun setShowEmptyState(show: Boolean) {
        settingsStore.setShowEmptyState(show)
    }

    // Notification handling
    suspend fun registerForNotifications(): Result<Unit> = withContext(bgDispatcher) {
        try {
            val token = firebaseMessaging.token.await()
            val cachedToken = keychain.loadString(Keychain.Key.PUSH_NOTIFICATION_TOKEN.name)

            if (cachedToken == token) {
                Logger.debug("Skipped registering for notifications, current device token already registered")
                return@withContext Result.success(Unit)
            }

            blocktankNotificationsService.registerDevice(token)
            Result.success(Unit)
        } catch (e: Throwable) {
            Logger.error("Register for notifications error", e)
            Result.failure(e)
        }
    }

    suspend fun testNotification(): Result<Unit> = withContext(bgDispatcher) {
        try {
            val token = firebaseMessaging.token.await()
            blocktankNotificationsService.testNotification(token)
            Result.success(Unit)
        } catch (e: Throwable) {
            Logger.error("Test notification error", e)
            Result.failure(e)
        }
    }

    suspend fun getBlocktankInfo(): Result<IBtInfo> = withContext(bgDispatcher) {
        try {
            val info = coreService.blocktank.info(refresh = true)
                ?: return@withContext Result.failure(Exception("Couldn't get info"))
            Result.success(info)
        } catch (e: Throwable) {
            Logger.error("Blocktank info error", e)
            Result.failure(e)
        }
    }

    suspend fun createTransactionSheet(
        type: NewTransactionSheetType,
        direction: NewTransactionSheetDirection,
        sats: Long
    ): Result<Unit> = withContext(bgDispatcher) {
        try {
            NewTransactionSheetDetails.save(
                appContext,
                NewTransactionSheetDetails(
                    type = type,
                    direction = direction,
                    sats = sats,
                )
            )
            Result.success(Unit)
        } catch (e: Throwable) {
            Logger.error("Create transaction sheet error", e)
            Result.failure(e)
        }
    }

    // Debug methods
    suspend fun debugKeychain(key: String, value: String): Result<String?> = withContext(bgDispatcher) {
        try {
            if (keychain.exists(key)) {
                val existingValue = keychain.loadString(key)
                keychain.delete(key)
                keychain.saveString(key, value)
                Result.success(existingValue)
            } else {
                keychain.saveString(key, value)
                Result.success(null)
            }
        } catch (e: Throwable) {
            Logger.error("Debug keychain error", e)
            Result.failure(e)
        }
    }

    suspend fun getMnemonic(): Result<String> = withContext(bgDispatcher) {
        try {
            val mnemonic = keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name)
                ?: return@withContext Result.failure(Exception("Mnemonic not found"))
            Result.success(mnemonic)
        } catch (e: Throwable) {
            Logger.error("Get mnemonic error", e)
            Result.failure(e)
        }
    }

    suspend fun getFcmToken(): Result<String> = withContext(bgDispatcher) {
        try {
            val token = firebaseMessaging.token.await()
            Result.success(token)
        } catch (e: Throwable) {
            Logger.error("Get FCM token error", e)
            Result.failure(e)
        }
    }

    suspend fun getDbConfig(): Flow<List<ConfigEntity>> {
        return db.configDao().getAll()
    }

    @OptIn(ExperimentalStdlibApi::class)
    suspend fun saveInvoiceWithTags(bip21Invoice: String, tags: List<String>) = withContext(bgDispatcher) {
        if (tags.isEmpty()) return@withContext

        try {
            deleteExpiredInvoices()
            val decoded = decode(bip21Invoice)
            val paymentHashOrAddress = when(decoded) {
                is Scanner.Lightning -> decoded.invoice.paymentHash.toHexString()
                is Scanner.OnChain -> decoded.extractLightningHashOrAddress()
                else -> null
            }

            paymentHashOrAddress?.let {
                db.invoiceTagDao().saveInvoice(
                    invoiceTag = InvoiceTagEntity(
                        paymentHash = paymentHashOrAddress,
                        tags = tags,
                        createdAt = Calendar.getInstance().time.time
                    )
                )
            }
        } catch (e: Throwable) {
            Logger.error("saveInvoice error", e, context = TAG)
        }
    }

    suspend fun searchInvoice(txId: Txid): Result<InvoiceTagEntity> = withContext(bgDispatcher) {
        return@withContext try {
            val invoiceTag = db.invoiceTagDao().searchInvoice(paymentHash = txId) ?: return@withContext Result.failure(Exception("Invoice not found"))
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
            val twoDaysExpiration = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, 2)
            }.time.time

            db.invoiceTagDao().deleteExpiredInvoices(expirationTimeStamp = twoDaysExpiration)
        } catch (e: Throwable) {
            Logger.error("deleteExpiredInvoices error", e, context = TAG)
        }
    }

    suspend fun attachTagsToActivity(
        paymentHashOrTxId: String?,
        type: ActivityFilter,
        txType: PaymentType,
        tags: List<String>
    ) : Result<Unit> = withContext(bgDispatcher) {
        Logger.debug("attachTagsToActivity $tags")
        if (tags.isEmpty()) {
            Logger.debug("selectedTags empty")
            return@withContext Result.failure(Exception("selectedTags empty"))
        }

        if (paymentHashOrTxId == null) {
            Logger.error(msg = "null paymentHashOrTxId")
            return@withContext Result.failure(Exception("null paymentHashOrTxId"))
        }

        var activity = coreService.activity.get(filter = type, txType = txType, limit = 10u).firstOrNull { activityItem ->
            when (activityItem) {
                is Activity.Lightning -> paymentHashOrTxId == activityItem.v1.id
                is Activity.Onchain -> paymentHashOrTxId == activityItem.v1.txId
            }
        }

        if (activity == null) {
            Logger.warn("activity not found, trying again after delay")
            delay(5.seconds)
            activity = coreService.activity.get(filter = type, txType = txType, limit = 10u).firstOrNull { activityItem ->
                when (activityItem) {
                    is Activity.Lightning -> paymentHashOrTxId == activityItem.v1.id
                    is Activity.Onchain -> paymentHashOrTxId == activityItem.v1.txId
                }
            }
        }

        if (activity == null) {
            Logger.error(msg = "Activity not found")
            return@withContext Result.failure(Exception("Activity not found"))
        }

        return@withContext when (activity) {
            is Activity.Lightning -> {
                if (paymentHashOrTxId == activity.v1.id) {
                    coreService.activity.appendTags(
                        toActivityId = activity.v1.id,
                        tags = tags
                    ).onFailure { e ->
                        Logger.error("Error attaching tags $tags", e)
                        return@withContext Result.failure(Exception("Error attaching tags $tags"))
                    }.onSuccess {
                        Logger.info("Success attatching tags $tags to activity ${activity.v1.id}")
                        deleteInvoice(txId = paymentHashOrTxId)
                       return@withContext Result.success(Unit)
                    }
                } else {
                    Logger.error("Different activity id. Expected: $paymentHashOrTxId found: ${activity.v1.id}")
                    return@withContext Result.failure(Exception("Error attaching tags $tags"))
                }
            }

            is Activity.Onchain -> {
                if (paymentHashOrTxId == activity.v1.txId) {
                    coreService.activity.appendTags(
                        toActivityId = activity.v1.id,
                        tags = tags
                    ).onFailure {
                        Logger.error("Error attaching tags $tags")
                        return@withContext Result.failure(Exception("Error attaching tags $tags"))
                    }.onSuccess {
                        Logger.info("Success attatching tags $tags to activity ${activity.v1.id}")
                        deleteInvoice(txId = paymentHashOrTxId)
                        return@onSuccess
                    }
                } else {
                    Logger.error("Different txId. Expected: $paymentHashOrTxId found: ${activity.v1.txId}")
                    return@withContext Result.failure(Exception("Error attaching tags $tags"))
                }
            }
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    private suspend fun Scanner.OnChain.extractLightningHashOrAddress(): String {
        val address = this.invoice.address
        val lightningInvoice: String = this.invoice.params?.get("lightning") ?: address
        val decoded = decode(lightningInvoice)

        val paymentHash = when(decoded) {
            is Scanner.Lightning -> decoded.invoice.paymentHash.toHexString()
            else -> null
        } ?: address

        return paymentHash

        return address
    }

    private fun generateEntropyMnemonic(): String {
        return org.lightningdevkit.ldknode.generateEntropyMnemonic()
    }

    private companion object {
        const val TAG = "WalletRepo"
    }
}
