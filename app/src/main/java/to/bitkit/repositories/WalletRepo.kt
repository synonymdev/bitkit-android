package to.bitkit.repositories

import android.content.Context
import android.icu.util.Calendar
import com.google.firebase.messaging.FirebaseMessaging
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDateTime
import org.lightningdevkit.ldknode.Network
import org.lightningdevkit.ldknode.PaymentHash
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
import to.bitkit.models.Toast
import to.bitkit.services.BlocktankNotificationsService
import to.bitkit.services.CoreService
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.utils.Bip21Utils
import to.bitkit.utils.Logger
import uniffi.bitkitcore.IBtInfo
import javax.inject.Inject
import javax.inject.Singleton

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

    suspend fun saveInvoice(txId: Txid, tags: List<String>) = withContext(bgDispatcher) {
        try {
            db.invoiceTagDao().saveInvoice(
                invoiceTag = InvoiceTagEntity(
                    paymentHash = txId,
                    tags = tags,
                    createdAt = Calendar.getInstance().time.time
                )
            )
        } catch (e: Throwable) {
            Logger.error("saveInvoice error", e, context = TAG)
        }
    }

    suspend fun searchInvoice(txId: Txid): Result<InvoiceTagEntity> = withContext(bgDispatcher) {
        return@withContext try {
            val invoiceTag = db.invoiceTagDao().searchInvoice(paymentHash = txId) ?: return@withContext Result.failure(Exception("Result not found"))
            Result.success(invoiceTag)
        } catch (e: Throwable) {
            Logger.error("saveInvoice error", e, context = TAG)
            Result.failure(e)
        }
    }

    private fun generateEntropyMnemonic(): String {
        return org.lightningdevkit.ldknode.generateEntropyMnemonic()
    }

    private companion object {
        const val TAG = "WalletRepo"
    }
}
