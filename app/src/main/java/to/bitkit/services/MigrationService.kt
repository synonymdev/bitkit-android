package to.bitkit.services

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.ActivityTags
import com.synonym.bitkitcore.ClosedChannelDetails
import com.synonym.bitkitcore.LightningActivity
import com.synonym.bitkitcore.OnchainActivity
import com.synonym.bitkitcore.PaymentState
import com.synonym.bitkitcore.PaymentType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.lightningdevkit.ldknode.Network
import to.bitkit.data.CacheStore
import to.bitkit.data.SettingsStore
import to.bitkit.data.WidgetsStore
import to.bitkit.data.dao.TransferDao
import to.bitkit.data.dto.price.GraphPeriod
import to.bitkit.data.dto.price.TradingPair
import to.bitkit.data.entities.TransferEntity
import to.bitkit.data.keychain.Keychain
import to.bitkit.data.resetPin
import to.bitkit.di.json
import to.bitkit.env.Env
import to.bitkit.models.BitcoinDisplayUnit
import to.bitkit.models.CoinSelectionPreference
import to.bitkit.models.PrimaryDisplay
import to.bitkit.models.Suggestion
import to.bitkit.models.TransactionSpeed
import to.bitkit.models.TransferType
import to.bitkit.models.WidgetType
import to.bitkit.models.WidgetWithPosition
import to.bitkit.models.safe
import to.bitkit.models.widget.BlocksPreferences
import to.bitkit.models.widget.FactsPreferences
import to.bitkit.models.widget.HeadlinePreferences
import to.bitkit.models.widget.PricePreferences
import to.bitkit.models.widget.WeatherPreferences
import to.bitkit.repositories.ActivityRepo
import to.bitkit.services.core.Bip39Service
import to.bitkit.utils.AppError
import to.bitkit.utils.Logger
import java.io.File
import java.security.KeyStore
import javax.inject.Inject
import javax.inject.Singleton

@Suppress("LargeClass", "TooManyFunctions", "LongParameterList")
@Singleton
class MigrationService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keychain: Keychain,
    private val settingsStore: SettingsStore,
    private val widgetsStore: WidgetsStore,
    private val cacheStore: CacheStore,
    private val activityRepo: ActivityRepo,
    private val coreService: CoreService,
    private val rnBackupClient: RNBackupClient,
    private val bip39Service: Bip39Service,
    private val transferDao: TransferDao,
) {
    companion object {
        private const val TAG = "Migration"
        const val RN_MIGRATION_COMPLETED_KEY = "rnMigrationCompleted"
        const val RN_MIGRATION_CHECKED_KEY = "rnMigrationChecked"
        private const val RN_NEEDS_POST_MIGRATION_SYNC_KEY = "rnNeedsPostMigrationSync"
        private const val RN_PENDING_BLOCKTANK_ORDER_IDS_KEY = "rnPendingBlocktankOrderIds"
        private const val RN_PENDING_PAID_ORDERS_KEY = "rnPendingPaidOrders"
        private const val RN_PENDING_METADATA_KEY = "rnPendingMetadata"
        private const val RN_PENDING_TRANSFERS_KEY = "rnPendingTransfers"
        private const val RN_PENDING_BOOSTS_KEY = "rnPendingBoosts"
        private const val OPENING_CURLY_BRACE = "{"
        private const val MMKV_ROOT = "persist:root"
        private const val RN_WALLET_NAME = "wallet0"
        private const val MS_PER_SEC = 1000
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH = 128
    }

    private val rnMigrationStore = context.rnMigrationDataStore

    private inline fun <reified T> decodeBackupData(data: ByteArray): T {
        val jsonElement = json.parseToJsonElement(String(data))
        val dataElement = jsonElement.jsonObject["data"] ?: error("Missing 'data' field")
        return json.decodeFromJsonElement(dataElement)
    }

    private val _isShowingMigrationLoading = MutableStateFlow(false)
    val isShowingMigrationLoading: StateFlow<Boolean> = _isShowingMigrationLoading.asStateFlow()

    fun setShowingMigrationLoading(value: Boolean) = _isShowingMigrationLoading.update { value }

    private val _isRestoringFromRNRemoteBackup = MutableStateFlow(false)
    val isRestoringFromRNRemoteBackup: StateFlow<Boolean> = _isRestoringFromRNRemoteBackup.asStateFlow()

    fun setRestoringFromRNRemoteBackup(value: Boolean) = _isRestoringFromRNRemoteBackup.update { value }

    @Volatile
    private var pendingChannelMigration: PendingChannelMigration? = null

    fun consumePendingChannelMigration(): PendingChannelMigration? {
        val migration = pendingChannelMigration ?: return null
        pendingChannelMigration = null
        return migration
    }

    fun peekPendingChannelMigration(): PendingChannelMigration? = pendingChannelMigration

    @Volatile
    private var pendingRemoteActivityData: List<RNActivityItem>? = null

    @Volatile
    private var pendingRemoteTransfers: Map<String, String>? = null

    @Volatile
    private var pendingRemoteBoosts: Map<String, String>? = null

    @Volatile
    private var pendingRemoteMetadata: RNMetadata? = null

    @Volatile
    private var pendingRemotePaidOrders: Map<String, String>? = null

    @Volatile
    private var pendingBlocktankOrderIds: List<String>? = null

    suspend fun needsPostMigrationSync(): Boolean {
        val key = stringPreferencesKey(RN_NEEDS_POST_MIGRATION_SYNC_KEY)
        return rnMigrationStore.data.first()[key] == "true"
    }

    suspend fun setNeedsPostMigrationSync(value: Boolean) {
        val key = stringPreferencesKey(RN_NEEDS_POST_MIGRATION_SYNC_KEY)
        rnMigrationStore.edit {
            if (value) {
                it[key] = "true"
            } else {
                it.remove(key)
            }
        }
    }

    private suspend fun loadPersistedMigrationData() {
        val prefs = rnMigrationStore.data.first()

        prefs[stringPreferencesKey(RN_PENDING_BLOCKTANK_ORDER_IDS_KEY)]?.let { data ->
            runCatching {
                pendingBlocktankOrderIds = json.decodeFromString<List<String>>(data)
                Logger.debug("Loaded ${pendingBlocktankOrderIds?.size} pending Blocktank order IDs", context = TAG)
            }.onFailure {
                Logger.warn("Failed to load pending Blocktank order IDs", it, context = TAG)
            }
        }

        prefs[stringPreferencesKey(RN_PENDING_PAID_ORDERS_KEY)]?.let { data ->
            runCatching {
                pendingRemotePaidOrders = json.decodeFromString<Map<String, String>>(data)
                Logger.debug("Loaded ${pendingRemotePaidOrders?.size} pending paid orders", context = TAG)
            }.onFailure {
                Logger.warn("Failed to load pending paid orders", it, context = TAG)
            }
        }

        prefs[stringPreferencesKey(RN_PENDING_METADATA_KEY)]?.let { data ->
            runCatching {
                pendingRemoteMetadata = json.decodeFromString<RNMetadata>(data)
                Logger.debug("Loaded pending metadata (tags: ${pendingRemoteMetadata?.tags?.size})", context = TAG)
            }.onFailure {
                Logger.warn("Failed to load pending metadata", it, context = TAG)
            }
        }

        prefs[stringPreferencesKey(RN_PENDING_TRANSFERS_KEY)]?.let { data ->
            runCatching {
                pendingRemoteTransfers = json.decodeFromString<Map<String, String>>(data)
                Logger.debug("Loaded ${pendingRemoteTransfers?.size} pending transfers", context = TAG)
            }.onFailure {
                Logger.warn("Failed to load pending transfers", it, context = TAG)
            }
        }

        prefs[stringPreferencesKey(RN_PENDING_BOOSTS_KEY)]?.let { data ->
            runCatching {
                pendingRemoteBoosts = json.decodeFromString<Map<String, String>>(data)
                Logger.debug("Loaded ${pendingRemoteBoosts?.size} pending boosts", context = TAG)
            }.onFailure {
                Logger.warn("Failed to load pending boosts", it, context = TAG)
            }
        }
    }

    private suspend fun persistBlocktankOrderIds(orderIds: List<String>) {
        val key = stringPreferencesKey(RN_PENDING_BLOCKTANK_ORDER_IDS_KEY)
        rnMigrationStore.edit {
            it[key] = json.encodeToString(orderIds)
        }
        pendingBlocktankOrderIds = orderIds
        Logger.info("Persisted ${orderIds.size} Blocktank order IDs for retry", context = TAG)
    }

    private suspend fun persistPaidOrders(paidOrders: Map<String, String>) {
        val key = stringPreferencesKey(RN_PENDING_PAID_ORDERS_KEY)
        rnMigrationStore.edit {
            it[key] = json.encodeToString(paidOrders)
        }
        pendingRemotePaidOrders = paidOrders
    }

    private suspend fun persistMetadata(metadata: RNMetadata) {
        val key = stringPreferencesKey(RN_PENDING_METADATA_KEY)
        rnMigrationStore.edit {
            it[key] = json.encodeToString(metadata)
        }
        pendingRemoteMetadata = metadata
        Logger.debug("Persisted pending metadata for retry", context = TAG)
    }

    private suspend fun persistTransfers(transfers: Map<String, String>) {
        val key = stringPreferencesKey(RN_PENDING_TRANSFERS_KEY)
        rnMigrationStore.edit {
            it[key] = json.encodeToString(transfers)
        }
        pendingRemoteTransfers = transfers
        Logger.debug("Persisted ${transfers.size} transfers for retry", context = TAG)
    }

    private suspend fun persistBoosts(boosts: Map<String, String>) {
        val key = stringPreferencesKey(RN_PENDING_BOOSTS_KEY)
        rnMigrationStore.edit {
            it[key] = json.encodeToString(boosts)
        }
        pendingRemoteBoosts = boosts
        Logger.debug("Persisted ${boosts.size} boosts for retry", context = TAG)
    }

    private suspend fun clearPersistedBlocktankData() {
        rnMigrationStore.edit {
            it.remove(stringPreferencesKey(RN_PENDING_BLOCKTANK_ORDER_IDS_KEY))
            it.remove(stringPreferencesKey(RN_PENDING_PAID_ORDERS_KEY))
        }
        pendingBlocktankOrderIds = null
        pendingRemotePaidOrders = null
        Logger.debug("Cleared persisted Blocktank data", context = TAG)
    }

    private suspend fun clearPersistedTransfers() {
        rnMigrationStore.edit {
            it.remove(stringPreferencesKey(RN_PENDING_TRANSFERS_KEY))
        }
        pendingRemoteTransfers = null
        Logger.debug("Cleared persisted transfers", context = TAG)
    }

    private suspend fun clearPersistedBoosts() {
        rnMigrationStore.edit {
            it.remove(stringPreferencesKey(RN_PENDING_BOOSTS_KEY))
        }
        pendingRemoteBoosts = null
        Logger.debug("Cleared persisted boosts", context = TAG)
    }

    private suspend fun clearPersistedMetadata() {
        rnMigrationStore.edit {
            it.remove(stringPreferencesKey(RN_PENDING_METADATA_KEY))
        }
        pendingRemoteMetadata = null
        Logger.debug("Cleared persisted metadata", context = TAG)
    }

    private suspend fun clearPersistedMigrationData() {
        rnMigrationStore.edit {
            it.remove(stringPreferencesKey(RN_PENDING_BLOCKTANK_ORDER_IDS_KEY))
            it.remove(stringPreferencesKey(RN_PENDING_PAID_ORDERS_KEY))
            it.remove(stringPreferencesKey(RN_PENDING_METADATA_KEY))
            it.remove(stringPreferencesKey(RN_PENDING_TRANSFERS_KEY))
            it.remove(stringPreferencesKey(RN_PENDING_BOOSTS_KEY))
        }
        pendingBlocktankOrderIds = null
        pendingRemotePaidOrders = null
        pendingRemoteMetadata = null
        pendingRemoteTransfers = null
        pendingRemoteBoosts = null
        Logger.debug("Cleared all persisted migration data", context = TAG)
    }

    val canCleanupAfterMigration: Boolean
        get() {
            if (pendingBlocktankOrderIds != null || pendingRemotePaidOrders != null) {
                Logger.debug("Cannot cleanup: pending Blocktank data exists", context = TAG)
                return false
            }
            if (pendingRemoteMetadata != null || pendingRemoteTransfers != null || pendingRemoteBoosts != null) {
                Logger.debug("Cannot cleanup: pending metadata/transfers/boosts exists", context = TAG)
                return false
            }
            return true
        }

    private fun buildRnLdkAccountPath(): File = run {
        val rnNetworkString = when (Env.network) {
            Network.BITCOIN -> "bitcoin"
            Network.REGTEST -> "bitcoinRegtest"
            Network.TESTNET -> "bitcoinTestnet"
            Network.SIGNET -> "signet"
        }
        val rnLdkBasePath = File(context.filesDir, "ldk")

        @Suppress("SpellCheckingInspection")
        val accountName = buildString {
            append(RN_WALLET_NAME)
            append(rnNetworkString)
            append("ldkaccountv3")
        }

        return File(rnLdkBasePath, accountName)
    }

    private fun getRnMmkvPath(): File = File(context.filesDir, "mmkv/mmkv.default")

    suspend fun isMigrationChecked(): Boolean {
        val key = stringPreferencesKey(RN_MIGRATION_CHECKED_KEY)
        return rnMigrationStore.data.first()[key] == "true"
    }

    suspend fun markMigrationChecked() {
        val key = stringPreferencesKey(RN_MIGRATION_CHECKED_KEY)
        rnMigrationStore.edit { it[key] = "true" }
    }

    suspend fun hasRNWalletData(): Boolean {
        val mnemonic = loadStringFromRNKeychain(RNKeychainKey.MNEMONIC)
        if (mnemonic?.isNotEmpty() == true) return true

        return hasRNMmkvData() || hasRNLdkData()
    }

    fun hasNativeWalletData() = runCatching { keychain.exists(Keychain.Key.BIP39_MNEMONIC.name) }.getOrDefault(false)
    fun hasRNLdkData() = File(buildRnLdkAccountPath(), "channel_manager.bin").exists()
    fun hasRNMmkvData() = getRnMmkvPath().exists()

    private suspend fun loadStringFromRNKeychain(key: RNKeychainKey): String? {
        val datastorePath = File(context.filesDir, "datastore/RN_KEYCHAIN.preferences_pb")
        if (!datastorePath.exists()) return null

        return runCatching {
            val preferences = context.rnKeychainDataStore.data.first()

            val passwordKey = stringPreferencesKey("${key.service}:p")
            val cipherKey = stringPreferencesKey("${key.service}:c")

            val encryptedValue = preferences[passwordKey] ?: return@runCatching null
            val cipherInfo = preferences[cipherKey]

            val fullEncryptedValue = if (cipherInfo != null && !encryptedValue.contains(":")) {
                "$cipherInfo:$encryptedValue"
            } else {
                encryptedValue
            }
            decryptRNKeychainValue(fullEncryptedValue, key.service)
        }.onFailure {
            Logger.error("Error reading from RN_KEYCHAIN DataStore: $it", it, context = TAG)
        }.getOrNull()
    }

    private fun decryptRNKeychainValue(encryptedValue: String, service: String): String? {
        if (!encryptedValue.contains(":")) {
            return runCatching {
                val encryptedBytes = android.util.Base64.decode(encryptedValue, android.util.Base64.DEFAULT)
                decryptWithKeystore(encryptedBytes, service)
            }.onFailure {
                Logger.error("Failed to decrypt without cipher prefix: $it", it, context = TAG)
            }.getOrNull()
        }

        val parts = encryptedValue.split(":", limit = 2)
        val cipherName = parts[0]
        val encryptedDataBase64 = parts[1]

        if (!cipherName.contains("KeystoreAESGCM")) {
            Logger.warn("Unsupported cipher: $cipherName. Only KeystoreAESGCM is supported.", context = TAG)
            return null
        }

        return runCatching {
            val encryptedBytes = android.util.Base64.decode(encryptedDataBase64, android.util.Base64.DEFAULT)
            decryptWithKeystore(encryptedBytes, service)
        }.onFailure {
            Logger.error("Failed to decrypt RN keychain value: $it", it, context = TAG)
        }.getOrNull()
    }

    private fun decryptWithKeystore(encryptedBytes: ByteArray, service: String): String? {
        if (encryptedBytes.size < GCM_IV_LENGTH) {
            Logger.error("Encrypted data too short: ${encryptedBytes.size} bytes", context = TAG)
            return null
        }

        val iv = encryptedBytes.sliceArray(0 until GCM_IV_LENGTH)
        val ciphertext = encryptedBytes.sliceArray(GCM_IV_LENGTH until encryptedBytes.size)

        return runCatching {
            val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

            if (!keystore.containsAlias(service)) {
                Logger.error("Keystore alias '$service' not found", context = TAG)
                return@runCatching null
            }

            val secretKey = keystore.getKey(service, null) as javax.crypto.SecretKey

            val transformation = "AES/GCM/NoPadding"
            val spec = javax.crypto.spec.GCMParameterSpec(GCM_TAG_LENGTH, iv)
            val cipher = javax.crypto.Cipher.getInstance(transformation).apply {
                init(javax.crypto.Cipher.DECRYPT_MODE, secretKey, spec)
            }

            val decryptedBytes = cipher.doFinal(ciphertext)
            String(decryptedBytes, Charsets.UTF_8)
        }.onFailure {
            Logger.error("Failed to decrypt with Keystore: $it", it, context = TAG)
        }.getOrNull()
    }

    suspend fun migrateFromReactNative() = runCatching {
        setShowingMigrationLoading(true)

        val mnemonicMigrated = runCatching { migrateMnemonic() }.map { true }.onFailure {
            Logger.warn("Could not migrate mnemonic: $it. User will need to manually restore.", context = TAG)
        }.getOrDefault(false)

        if (mnemonicMigrated) {
            migratePassphrase()
            migratePin()

            if (hasRNLdkData()) {
                migrateLdkData().onFailure {
                    Logger.warn("LDK data migration failed, continuing with other migrations: $it", it, context = TAG)
                }
            }

            if (hasRNMmkvData()) {
                migrateMMKVData()
            }

            rnMigrationStore.edit {
                it[stringPreferencesKey(RN_MIGRATION_COMPLETED_KEY)] = "true"
                it[stringPreferencesKey(RN_MIGRATION_CHECKED_KEY)] = "true"
            }
            setNeedsPostMigrationSync(true)
            Logger.info("RN local migration completed, marked for post-migration sync", context = TAG)
        } else {
            markMigrationChecked()
            setShowingMigrationLoading(false)
            throw AppError("Migration data unavailable. Please restore your wallet using your recovery phrase.")
        }
    }.onFailure {
        Logger.error("RN migration failed: $it", it, context = TAG)
        markMigrationChecked()
        setShowingMigrationLoading(false)
    }.getOrThrow()

    private suspend fun migrateMnemonic() {
        val mnemonic = loadStringFromRNKeychain(RNKeychainKey.MNEMONIC)

        if (mnemonic.isNullOrEmpty()) {
            throw AppError("Migration data unavailable. Please restore your wallet using your recovery phrase.")
        }

        bip39Service.validateMnemonic(mnemonic).onFailure {
            throw AppError(
                "Recovery phrase is invalid. Please use your 12 or 24 word recovery phrase to restore manually."
            )
        }

        keychain.saveString(Keychain.Key.BIP39_MNEMONIC.name, mnemonic)
    }

    private suspend fun migratePassphrase() {
        val passphrase = loadStringFromRNKeychain(RNKeychainKey.PASSPHRASE)
        if (passphrase.isNullOrEmpty()) return
        keychain.saveString(Keychain.Key.BIP39_PASSPHRASE.name, passphrase)
    }

    private suspend fun migratePin() {
        val pin = loadStringFromRNKeychain(RNKeychainKey.PIN)
        if (pin.isNullOrEmpty()) return

        if (pin.length != Env.PIN_LENGTH) {
            Logger.warn(
                "Invalid PIN length during migration: ${pin.length}, expected: ${Env.PIN_LENGTH}",
                context = TAG,
            )
            return
        }

        if (!pin.all { it.isDigit() }) {
            Logger.warn("Invalid PIN format during migration: contains non-numeric characters", context = TAG)
            return
        }

        keychain.saveString(Keychain.Key.PIN.name, pin)
    }

    private fun migrateLdkData() = runCatching {
        val accountPath = buildRnLdkAccountPath()
        val managerPath = File(accountPath, "channel_manager.bin")

        if (!managerPath.exists()) {
            Logger.warn("LDK channel_manager.bin not found at ${managerPath.path}", context = TAG)
            return@runCatching
        }

        val managerData = managerPath.readBytes()
        val monitors = mutableListOf<ByteArray>()

        val channelsPath = File(accountPath, "channels")
        val monitorsPath = File(accountPath, "monitors")
        val monitorDir = if (channelsPath.exists()) channelsPath else monitorsPath

        monitorDir.takeIf { it.exists() }?.listFiles()?.forEach { file ->
            if (file.name.endsWith(".bin")) {
                monitors.add(file.readBytes())
            }
        }

        pendingChannelMigration = PendingChannelMigration(
            channelManager = managerData,
            channelMonitors = monitors,
        )
    }.onFailure {
        Logger.error("Failed to migrate LDK data: $it", it, context = TAG)
    }

    fun loadRNMmkvData(): Map<String, String>? = runCatching {
        if (!hasRNMmkvData()) return@runCatching null

        val data = getRnMmkvPath().readBytes()
        val parser = MmkvParser(data)
        parser.parse().takeIf { it.isNotEmpty() }
    }.onFailure {
        Logger.error("Failed to read MMKV data: $it", it, context = TAG)
    }.getOrNull()

    private fun extractRNSettings(mmkvData: Map<String, String>): RNSettings? {
        val rootJson = mmkvData[MMKV_ROOT] ?: return null

        return runCatching {
            val jsonStart = rootJson.indexOf(OPENING_CURLY_BRACE)
            val jsonString = if (jsonStart >= 0) rootJson.substring(jsonStart) else rootJson

            val root = json.parseToJsonElement(jsonString).jsonObject
            val settingsJsonString = root["settings"]?.jsonPrimitive?.content ?: return@runCatching null

            json.decodeFromString<RNSettings>(settingsJsonString)
        }.onFailure {
            Logger.error("Failed to decode RN settings: $it", it, context = TAG)
        }.getOrNull()
    }

    private fun extractRNMetadata(mmkvData: Map<String, String>): RNMetadata? {
        val rootJson = mmkvData[MMKV_ROOT] ?: return null

        return runCatching {
            val jsonStart = rootJson.indexOf(OPENING_CURLY_BRACE)
            val jsonString = if (jsonStart >= 0) rootJson.substring(jsonStart) else rootJson

            val root = json.parseToJsonElement(jsonString).jsonObject
            val metadataJsonString = root["metadata"]?.jsonPrimitive?.content ?: return@runCatching null

            json.decodeFromString<RNMetadata>(metadataJsonString)
        }.onFailure {
            Logger.error("Failed to decode RN metadata: $it", it, context = TAG)
        }.getOrNull()
    }

    private fun extractRNTodos(mmkvData: Map<String, String>): RNTodos? {
        val rootJson = mmkvData[MMKV_ROOT] ?: return null

        return runCatching {
            val jsonStart = rootJson.indexOf(OPENING_CURLY_BRACE)
            val jsonString = if (jsonStart >= 0) rootJson.substring(jsonStart) else rootJson

            val root = json.parseToJsonElement(jsonString).jsonObject
            val todosJsonString = root["todos"]?.jsonPrimitive?.content ?: return@runCatching null

            json.decodeFromString<RNTodos>(todosJsonString)
        }.onFailure {
            Logger.error("Failed to decode RN todos: $it", it, context = TAG)
        }.getOrNull()
    }

    private fun extractRNWidgets(mmkvData: Map<String, String>): RNWidgetsWithOptions? {
        val rootJson = mmkvData[MMKV_ROOT] ?: return null

        return runCatching {
            val jsonStart = rootJson.indexOf(OPENING_CURLY_BRACE)
            val jsonString = if (jsonStart >= 0) rootJson.substring(jsonStart) else rootJson

            val root = json.parseToJsonElement(jsonString).jsonObject
            val widgetsJsonString = root["widgets"]?.jsonPrimitive?.content
                ?: return@runCatching null

            val widgets = json.decodeFromString<RNWidgets>(widgetsJsonString)
            val widgetsData = json.parseToJsonElement(widgetsJsonString).jsonObject
            val widgetOptions = convertRNWidgetPreferences(widgetsData["widgets"]?.jsonObject ?: widgetsData)

            RNWidgetsWithOptions(widgets = widgets, widgetOptions = widgetOptions)
        }.onFailure {
            Logger.error("Failed to decode RN widgets: $it", it, context = TAG)
        }.getOrNull()
    }

    private fun extractRNActivities(mmkvData: Map<String, String>): List<RNActivityItem>? {
        val rootJson = mmkvData[MMKV_ROOT] ?: return null

        return runCatching {
            val jsonStart = rootJson.indexOf(OPENING_CURLY_BRACE)
            val jsonString = if (jsonStart >= 0) rootJson.substring(jsonStart) else rootJson

            val root = json.parseToJsonElement(jsonString).jsonObject
            val activityJsonString = root["activity"]?.jsonPrimitive?.content ?: return@runCatching null

            val activityState = json.decodeFromString<RNActivityState>(activityJsonString)
            activityState.items ?: emptyList()
        }.onFailure {
            Logger.error("Failed to decode RN activities: $it", it, context = TAG)
        }.getOrNull()
    }

    private fun extractTransfers(transfers: Map<String, List<RNRemoteTransfer>>?): Map<String, String> {
        val transferMap = mutableMapOf<String, String>()
        transfers?.values?.flatten()?.forEach { transfer ->
            transfer.txId?.let { txId ->
                transfer.type?.let { type ->
                    transferMap[txId] = type
                }
            }
        }
        return transferMap
    }

    private fun extractBoosts(boostedTxs: Map<String, Map<String, RNRemoteBoostedTx>>?): Map<String, String> {
        val boostMap = mutableMapOf<String, String>()
        boostedTxs?.values?.forEach { networkBoosts ->
            networkBoosts.forEach { (parentTxId, boost) ->
                val childTxId = boost.childTransaction ?: boost.newTxId
                childTxId?.let {
                    boostMap[parentTxId] = it
                }
            }
        }
        return boostMap
    }

    private fun extractFromWalletState(walletState: RNWalletState): Pair<Map<String, String>, Map<String, String>>? {
        val transferMap = mutableMapOf<String, String>()
        val boostMap = mutableMapOf<String, String>()

        walletState.wallets?.values?.forEach { walletDataItem ->
            walletDataItem.transfers?.let { transferMap.putAll(extractTransfers(it)) }
            walletDataItem.boostedTransactions?.let { boostMap.putAll(extractBoosts(it)) }
        }

        return if (transferMap.isNotEmpty() || boostMap.isNotEmpty()) {
            Pair(transferMap, boostMap)
        } else {
            null
        }
    }

    private fun extractFromWalletBackup(
        walletBackup: RNRemoteWalletBackup,
    ): Pair<Map<String, String>, Map<String, String>>? {
        val transferMap = extractTransfers(walletBackup.transfers)
        val boostMap = extractBoosts(walletBackup.boostedTransactions)

        return if (transferMap.isNotEmpty() || boostMap.isNotEmpty()) {
            Pair(transferMap, boostMap)
        } else {
            null
        }
    }

    private fun extractRNWalletBackup(mmkvData: Map<String, String>): Pair<Map<String, String>, Map<String, String>>? {
        val rootJson = mmkvData[MMKV_ROOT] ?: return null

        return runCatching {
            val jsonStart = rootJson.indexOf(OPENING_CURLY_BRACE)
            val jsonString = if (jsonStart >= 0) rootJson.substring(jsonStart) else rootJson

            val root = json.parseToJsonElement(jsonString).jsonObject
            val walletJsonString = root["wallet"]?.jsonPrimitive?.content ?: return@runCatching null
            val walletData = json.parseToJsonElement(walletJsonString).jsonObject

            val walletState = runCatching { json.decodeFromJsonElement<RNWalletState>(walletData) }.getOrNull()

            walletState?.let { extractFromWalletState(it) } ?: run {
                runCatching { json.decodeFromJsonElement<RNRemoteWalletBackup>(walletData) }.getOrNull()?.let {
                    extractFromWalletBackup(it)
                }
            }
        }.onFailure {
            Logger.error("Failed to decode RN wallet backup: $it", it, context = TAG)
        }.getOrNull()
    }

    private fun extractRNBlocktank(mmkvData: Map<String, String>): Pair<List<String>, Map<String, String>>? {
        val rootJson = mmkvData[MMKV_ROOT] ?: return null

        return runCatching {
            val jsonStart = rootJson.indexOf(OPENING_CURLY_BRACE)
            val jsonString = if (jsonStart >= 0) rootJson.substring(jsonStart) else rootJson

            val root = json.parseToJsonElement(jsonString).jsonObject
            val blocktankJsonString = root["blocktank"]?.jsonPrimitive?.content ?: return@runCatching null

            val blocktankData = json.parseToJsonElement(blocktankJsonString).jsonObject
            val orderIds = mutableListOf<String>()
            val paidOrdersMap = mutableMapOf<String, String>()

            blocktankData["orders"]?.jsonArray?.forEach { orderElement ->
                orderElement.jsonObject["id"]?.jsonPrimitive?.content?.let { id ->
                    orderIds.add(id)
                }
            }

            blocktankData["paidOrders"]?.jsonObject?.forEach { (orderId, txIdElement) ->
                val txId = txIdElement.jsonPrimitive.content
                paidOrdersMap[orderId] = txId
                if (orderId !in orderIds) {
                    orderIds.add(orderId)
                }
            }

            if (orderIds.isEmpty() && paidOrdersMap.isEmpty()) {
                return@runCatching null
            }

            Logger.info(
                "Extracted ${orderIds.size} order IDs and ${paidOrdersMap.size} paid orders from local blocktank",
                context = TAG,
            )
            Pair(orderIds, paidOrdersMap)
        }.onFailure {
            Logger.error("Failed to decode RN blocktank: $it", it, context = TAG)
        }.getOrNull()
    }

    @Suppress("NestedBlockDepth")
    private fun extractRNClosedChannels(mmkvData: Map<String, String>): List<RNChannel>? {
        val rootJson = mmkvData[MMKV_ROOT] ?: return null

        return runCatching {
            val jsonStart = rootJson.indexOf(OPENING_CURLY_BRACE)
            val jsonString = if (jsonStart >= 0) rootJson.substring(jsonStart) else rootJson

            val root = json.parseToJsonElement(jsonString).jsonObject
            val lightningJsonString = root["lightning"]?.jsonPrimitive?.content
                ?: return@runCatching null

            val lightningState = json.decodeFromString<RNLightningState>(lightningJsonString)
            val closedChannels = mutableListOf<RNChannel>()

            lightningState.nodes?.forEach { (_, node) ->
                node.channels?.forEach { (_, channels) ->
                    channels.forEach { (_, channel) ->
                        if (channel.status == "closed") {
                            closedChannels.add(channel)
                        }
                    }
                }
            }

            closedChannels.takeIf { it.isNotEmpty() }
        }.onFailure {
            Logger.error("Failed to decode RN lightning state: $it", it, context = TAG)
        }.getOrNull()
    }

    @Suppress("CyclomaticComplexMethod")
    private suspend fun applyRNSettings(settings: RNSettings) {
        settingsStore.update { current ->
            current.copy(
                selectedCurrency = settings.selectedCurrency ?: current.selectedCurrency,
                primaryDisplay = when (settings.unit) {
                    "BTC" -> PrimaryDisplay.BITCOIN
                    else -> PrimaryDisplay.FIAT
                },
                displayUnit = when (settings.denomination) {
                    "sats" -> BitcoinDisplayUnit.MODERN
                    "BTC" -> BitcoinDisplayUnit.CLASSIC
                    else -> current.displayUnit
                },
                hideBalance = settings.hideBalance ?: current.hideBalance,
                hideBalanceOnOpen = settings.hideBalanceOnOpen ?: current.hideBalanceOnOpen,
                enableSwipeToHideBalance = settings.enableSwipeToHideBalance ?: current.enableSwipeToHideBalance,
                isQuickPayEnabled = settings.enableQuickpay ?: current.isQuickPayEnabled,
                quickPayAmount = settings.quickpayAmount ?: current.quickPayAmount,
                enableAutoReadClipboard = settings.enableAutoReadClipboard ?: current.enableAutoReadClipboard,
                enableSendAmountWarning = settings.enableSendAmountWarning ?: current.enableSendAmountWarning,
                showWidgets = settings.showWidgets ?: current.showWidgets,
                showWidgetTitles = settings.showWidgetTitles ?: current.showWidgetTitles,
                defaultTransactionSpeed = when (settings.transactionSpeed) {
                    "fast" -> TransactionSpeed.Fast
                    "slow" -> TransactionSpeed.Slow
                    else -> TransactionSpeed.Medium
                },
                coinSelectAuto = settings.coinSelectAuto ?: current.coinSelectAuto,
                coinSelectPreference = when (settings.coinSelectPreference) {
                    "branchAndBound" -> CoinSelectionPreference.BranchAndBound
                    else -> CoinSelectionPreference.SmallestFirst
                },
                isPinEnabled = settings.pin ?: current.isPinEnabled,
                isPinForPaymentsEnabled = settings.pinForPayments ?: current.isPinForPaymentsEnabled,
                isBiometricEnabled = settings.biometrics ?: current.isBiometricEnabled,
                quickPayIntroSeen = settings.quickpayIntroSeen ?: current.quickPayIntroSeen,
                hasSeenShopIntro = settings.shopIntroSeen ?: current.hasSeenShopIntro,
                hasSeenTransferIntro = settings.transferIntroSeen ?: current.hasSeenTransferIntro,
                hasSeenSpendingIntro = settings.spendingIntroSeen ?: current.hasSeenSpendingIntro,
                hasSeenSavingsIntro = settings.savingsIntroSeen ?: current.hasSeenSavingsIntro,
            )
        }
    }

    private suspend fun applyRNMetadata(metadata: RNMetadata) {
        val tags = metadata.tags
        if (tags.isNullOrEmpty()) {
            Logger.debug("No tags to apply in metadata", context = TAG)
            return
        }

        var applied = 0
        val allTags = tags.mapNotNull { (activityId, tagList) ->
            val onchain = activityRepo.getOnchainActivityByTxId(activityId)
            if (onchain != null) {
                applied++
                ActivityTags(activityId = onchain.id, tags = tagList)
            } else {
                val activity = activityRepo.getActivity(activityId).getOrNull()
                if (activity != null) {
                    applied++
                    ActivityTags(activityId = activityId, tags = tagList)
                } else {
                    Logger.warn("Activity not found for tags: id=$activityId", context = TAG)
                    null
                }
            }
        }

        if (allTags.isNotEmpty()) {
            runCatching {
                coreService.activity.upsertTags(allTags)
                Logger.info("Applied $applied/${tags.size} pending tags", context = TAG)
            }.onFailure {
                Logger.error("Failed to upsert tags: $it", it, context = TAG)
            }
        }
    }

    private suspend fun applyRNTodos(todos: RNTodos) {
        val mapping = mapOf(
            "backupSeedPhrase" to Suggestion.BACK_UP,
            "buyBitcoin" to Suggestion.BUY,
            "lightning" to Suggestion.LIGHTNING,
            "quickpay" to Suggestion.QUICK_PAY,
            "shop" to Suggestion.SHOP,
            "slashtagsProfile" to Suggestion.PROFILE,
            "support" to Suggestion.SUPPORT,
            "invite" to Suggestion.INVITE,
            "pin" to Suggestion.SECURE,
        )

        todos.hide?.keys?.forEach { rnTodoType ->
            mapping[rnTodoType]?.let { suggestion ->
                settingsStore.addDismissedSuggestion(suggestion)
            }
        }
    }

    private suspend fun applyRNActivities(items: List<RNActivityItem>) {
        val activities = items.filter { it.activityType == "lightning" }.map { item ->
            val txType = if (item.txType == "sent") PaymentType.SENT else PaymentType.RECEIVED
            val status = when (item.status) {
                "successful", "succeeded" -> PaymentState.SUCCEEDED
                "failed" -> PaymentState.FAILED
                else -> PaymentState.PENDING
            }

            val timestampSecs = (item.timestamp / MS_PER_SEC).toULong()
            val invoice = item.address?.takeIf { it.isNotEmpty() } ?: "migrated:${item.id}"

            Activity.Lightning(
                LightningActivity(
                    id = item.id,
                    txType = txType,
                    status = status,
                    value = item.value.toULong(),
                    fee = item.fee?.toULong(),
                    invoice = invoice,
                    message = item.message ?: "",
                    timestamp = timestampSecs,
                    preimage = item.preimage,
                    createdAt = timestampSecs,
                    updatedAt = timestampSecs,
                    seenAt = timestampSecs,
                )
            )
        }

        activities.forEach { activity ->
            activityRepo.upsertActivity(activity)
        }
    }

    private suspend fun applyRNClosedChannels(channels: List<RNChannel>) {
        val now = (System.currentTimeMillis() / MS_PER_SEC).toULong()

        val closedChannels = channels.mapNotNull { channel ->
            val fundingTxid = channel.fundingTxid ?: return@mapNotNull null

            val closedAtSecs = channel.createdAt?.let { (it / MS_PER_SEC).toULong() } ?: now

            val outboundMsat = (channel.outboundCapacitySat ?: 0u) * 1000u
            val inboundMsat = (channel.inboundCapacitySat ?: 0u) * 1000u

            ClosedChannelDetails(
                channelId = channel.channelId,
                counterpartyNodeId = channel.counterpartyNodeId ?: "",
                fundingTxoTxid = fundingTxid,
                fundingTxoIndex = 0u,
                channelValueSats = channel.channelValueSatoshis ?: 0u,
                closedAt = closedAtSecs,
                outboundCapacityMsat = outboundMsat,
                inboundCapacityMsat = inboundMsat,
                counterpartyUnspendablePunishmentReserve = channel.counterpartyUnspendablePunishmentReserve ?: 0u,
                unspendablePunishmentReserve = channel.unspendablePunishmentReserve ?: 0u,
                forwardingFeeProportionalMillionths = 0u,
                forwardingFeeBaseMsat = 0u,
                channelName = "",
                channelClosureReason = channel.closureReason ?: "unknown",
            )
        }

        if (closedChannels.isNotEmpty()) {
            runCatching {
                coreService.activity.upsertClosedChannelList(closedChannels)
            }.onFailure { e ->
                Logger.error("Failed to migrate closed channels: $e", e, context = TAG)
            }
        }
    }

    private suspend fun applyRNWidgets(widgetsWithOptions: RNWidgetsWithOptions) {
        val widgets = widgetsWithOptions.widgets
        val widgetOptions = widgetsWithOptions.widgetOptions

        widgets.sortOrder?.let { sortOrder ->
            val widgetTypeMap = mapOf(
                "price" to WidgetType.PRICE,
                "news" to WidgetType.NEWS,
                "blocks" to WidgetType.BLOCK,
                "weather" to WidgetType.WEATHER,
                "facts" to WidgetType.FACTS,
                "calculator" to WidgetType.CALCULATOR,
            )

            val savedWidgets = sortOrder.mapNotNull { widgetName ->
                widgetTypeMap[widgetName]?.let { type ->
                    WidgetWithPosition(type = type, position = sortOrder.indexOf(widgetName))
                }
            }

            if (savedWidgets.isNotEmpty()) {
                widgetsStore.updateWidgets(savedWidgets)
            }
        }

        applyRNWidgetPreferences(widgetOptions)

        widgets.onboardedWidgets?.takeIf { it }?.let {
            settingsStore.update { it.copy(hasSeenWidgetsIntro = true) }
        }
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private suspend fun applyRNWidgetPreferences(widgetOptions: Map<String, ByteArray>) {
        widgetOptions["price"]?.let { priceData ->
            runCatching {
                val priceJson = json.decodeFromString<JsonObject>(
                    priceData.decodeToString()
                )
                val selectedPairs = priceJson["selectedPairs"]?.jsonArray?.mapNotNull { pairElement ->
                    val pairStr = pairElement.jsonPrimitive.content.replace("_", "/")
                    when (pairStr) {
                        "BTC/USD" -> TradingPair.BTC_USD
                        "BTC/EUR" -> TradingPair.BTC_EUR
                        "BTC/GBP" -> TradingPair.BTC_GBP
                        "BTC/JPY" -> TradingPair.BTC_JPY
                        else -> null
                    }
                } ?: listOf(TradingPair.BTC_USD)

                val periodStr = priceJson["selectedPeriod"]?.jsonPrimitive?.content ?: "1D"
                val period = when (periodStr) {
                    "1D" -> GraphPeriod.ONE_DAY
                    "1W" -> GraphPeriod.ONE_WEEK
                    "1M" -> GraphPeriod.ONE_MONTH
                    "1Y" -> GraphPeriod.ONE_YEAR
                    else -> GraphPeriod.ONE_DAY
                }

                val showSource = priceJson["showSource"]?.jsonPrimitive?.content
                    ?.toBooleanStrictOrNull() ?: false

                widgetsStore.updatePricePreferences(
                    PricePreferences(
                        enabledPairs = selectedPairs,
                        period = period,
                        showSource = showSource
                    )
                )
            }.onFailure {
                Logger.error("Failed to migrate price preferences: $it", it, context = TAG)
            }
        }

        widgetOptions["weather"]?.let { weatherData ->
            runCatching {
                val weatherJson = json.decodeFromString<JsonObject>(
                    weatherData.decodeToString()
                )
                val showTitle = weatherJson["showStatus"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true
                val showDescription = weatherJson["showText"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
                val showCurrentFee = weatherJson["showMedian"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
                val showNextBlockFee = weatherJson["showNextBlockFee"]?.jsonPrimitive?.content
                    ?.toBooleanStrictOrNull() ?: false

                widgetsStore.updateWeatherPreferences(
                    WeatherPreferences(
                        showTitle = showTitle,
                        showDescription = showDescription,
                        showCurrentFee = showCurrentFee,
                        showNextBlockFee = showNextBlockFee
                    )
                )
            }.onFailure {
                Logger.error("Failed to migrate weather preferences: $it", it, context = TAG)
            }
        }

        widgetOptions["news"]?.let { newsData ->
            runCatching {
                val newsJson = json.decodeFromString<JsonObject>(
                    newsData.decodeToString()
                )
                val showTime = newsJson["showDate"]?.jsonPrimitive?.content
                    ?.toBooleanStrictOrNull() ?: true
                val showSource = newsJson["showSource"]?.jsonPrimitive?.content
                    ?.toBooleanStrictOrNull() ?: true

                widgetsStore.updateHeadlinePreferences(
                    HeadlinePreferences(
                        showTime = showTime,
                        showSource = showSource
                    )
                )
            }.onFailure {
                Logger.error("Failed to migrate news preferences: $it", it, context = TAG)
            }
        }

        widgetOptions["blocks"]?.let { blocksData ->
            runCatching {
                val blocksJson = json.decodeFromString<JsonObject>(
                    blocksData.decodeToString()
                )
                val showBlock = blocksJson["height"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true
                val showTime = blocksJson["time"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true
                val showDate = blocksJson["date"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true
                val showTransactions = blocksJson["transactionCount"]?.jsonPrimitive?.content
                    ?.toBooleanStrictOrNull() ?: false
                val showSize = blocksJson["size"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
                val showSource = blocksJson["showSource"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false

                widgetsStore.updateBlocksPreferences(
                    BlocksPreferences(
                        showBlock = showBlock,
                        showTime = showTime,
                        showDate = showDate,
                        showTransactions = showTransactions,
                        showSize = showSize,
                        showSource = showSource
                    )
                )
            }.onFailure {
                Logger.error("Failed to migrate blocks preferences: $it", it, context = TAG)
            }
        }

        widgetOptions["facts"]?.let { factsData ->
            runCatching {
                val factsJson = json.decodeFromString<JsonObject>(
                    factsData.decodeToString()
                )
                val showSource = factsJson["showSource"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false

                widgetsStore.updateFactsPreferences(
                    FactsPreferences(
                        showSource = showSource
                    )
                )
            }.onFailure {
                Logger.error("Failed to migrate facts preferences: $it", it, context = TAG)
            }
        }
    }

    private suspend fun migrateMMKVData() {
        val mmkvData = loadRNMmkvData() ?: return

        extractRNActivities(mmkvData)?.let {
            applyRNActivities(it)
        }

        extractRNClosedChannels(mmkvData)?.let {
            applyRNClosedChannels(it)
        }

        extractRNSettings(mmkvData)?.let { settings ->
            applyRNSettings(settings)
        }

        extractRNMetadata(mmkvData)?.let { metadata ->
            Logger.info("Storing metadata for application after sync", context = TAG)
            persistMetadata(metadata)
            metadata.lastUsedTags?.forEach { settingsStore.addLastUsedTag(it) }
        }

        extractRNWidgets(mmkvData)?.let { widgets ->
            applyRNWidgets(widgets)
        }

        extractRNTodos(mmkvData)?.let { todos ->
            applyRNTodos(todos)
        }

        extractRNBlocktank(mmkvData)?.let { (orderIds, paidOrders) ->
            applyRNBlocktank(orderIds, paidOrders)
        }
    }

    private suspend fun applyRNBlocktank(orderIds: List<String>, paidOrders: Map<String, String>) {
        if (orderIds.isEmpty()) return

        paidOrders.forEach { (orderId, txId) ->
            cacheStore.addPaidOrder(orderId, txId)
        }

        runCatching {
            val fetchedOrders = coreService.blocktank.orders(
                orderIds = orderIds,
                filter = null,
                refresh = true,
            )
            if (fetchedOrders.isNotEmpty()) {
                coreService.blocktank.upsertOrderList(fetchedOrders)
                if (paidOrders.isNotEmpty()) {
                    createTransfersForPaidOrders(paidOrders, fetchedOrders)
                }
            }
        }.onFailure { e ->
            Logger.warn("Failed to fetch and upsert local Blocktank orders", e, context = TAG)
            persistBlocktankOrderIds(orderIds)
            if (paidOrders.isNotEmpty()) {
                persistPaidOrders(paidOrders)
            }
            Logger.info("Stored ${orderIds.size} Blocktank order IDs for retry", context = TAG)
        }
    }

    suspend fun getRNRemoteBackupTimestamp(): ULong? = runCatching {
        rnBackupClient.getLatestBackupTimestamp()
    }.getOrNull()

    suspend fun restoreFromRNRemoteBackup() {
        setRestoringFromRNRemoteBackup(true)

        runCatching {
            fetchRNRemoteLdkData()
            val bitkitFiles = rnBackupClient.listFiles(fileGroup = "bitkit")?.list ?: emptyList()
            retrieveAndApplyBitkitBackups(bitkitFiles)
            markMigrationCompleted()
        }.onSuccess {
            settingsStore.update { it.copy(backupVerified = true) }
        }.onFailure { e ->
            Logger.error("RN remote backup restore failed", e, context = TAG)
            throw e
        }
    }

    private suspend fun retrieveAndApplyBitkitBackups(availableFiles: List<String>) = coroutineScope {
        fun fileExists(name: String) = availableFiles.any { it.removeSuffix(".bin") == name }

        suspend fun retrieve(name: String): ByteArray? {
            if (!fileExists(name)) return null
            return rnBackupClient.retrieve(name, fileGroup = "bitkit")
        }

        val settingsData = async { retrieve("bitkit_settings") }
        val widgetsData = async { retrieve("bitkit_widgets") }
        val activityData = async { retrieve("bitkit_lightning_activity") }
        val metadataData = async { retrieve("bitkit_metadata") }
        val walletData = async { retrieve("bitkit_wallet") }
        val blocktankData = async { retrieve("bitkit_blocktank_orders") }

        settingsData.await()?.let { applyRNRemoteSettings(it) }
        widgetsData.await()?.let { applyRNRemoteWidgets(it) }
        activityData.await()?.let { applyRNRemoteActivity(it) }
        metadataData.await()?.let { applyRNRemoteMetadata(it) }
        walletData.await()?.let { applyRNRemoteWallet(it) }
        blocktankData.await()?.let { applyRNRemoteBlocktank(it) }
    }

    private suspend fun markMigrationCompleted() {
        rnMigrationStore.edit {
            it[stringPreferencesKey(RN_MIGRATION_COMPLETED_KEY)] = "true"
            it[stringPreferencesKey(RN_MIGRATION_CHECKED_KEY)] = "true"
        }
        setNeedsPostMigrationSync(true)
        Logger.info("RN migration completed, marked for post-migration sync", context = TAG)
    }

    suspend fun cleanupAfterMigration() {
        clearPersistedMigrationData()
        setNeedsPostMigrationSync(false)
        Logger.info("Post-migration cleanup completed", context = TAG)
    }

    private suspend fun fetchRNRemoteLdkData() {
        runCatching {
            val files = rnBackupClient.listFiles(fileGroup = "ldk") ?: return@runCatching
            if (!files.list.any { it.removeSuffix(".bin") == "channel_manager" }) return@runCatching

            val managerData = rnBackupClient.retrieve("channel_manager", fileGroup = "ldk")
                ?: return@runCatching

            val monitors = coroutineScope {
                files.channelMonitors.map { monitorFile ->
                    async {
                        val channelId = monitorFile.replace(".bin", "")
                        rnBackupClient.retrieveChannelMonitor(channelId)
                    }
                }.mapNotNull { it.await() }
            }

            if (monitors.isNotEmpty()) {
                pendingChannelMigration = PendingChannelMigration(
                    channelManager = managerData,
                    channelMonitors = monitors,
                )
            }
        }.onFailure { e ->
            Logger.error("Failed to fetch remote LDK data", e, context = TAG)
        }
    }

    private suspend fun applyRNRemoteSettings(data: ByteArray) {
        runCatching {
            applyRNSettings(decodeBackupData<RNSettings>(data))
            settingsStore.update { it.resetPin() }
        }.onFailure { e ->
            Logger.warn("Failed to decode RN remote settings backup: $e", context = TAG)
        }
    }

    private suspend fun applyRNRemoteWidgets(data: ByteArray) {
        runCatching {
            val widgets = decodeBackupData<RNWidgets>(data)
            val rawJson = runCatching { json.parseToJsonElement(String(data)) }.getOrNull()
            val widgetOptions = rawJson?.jsonObject?.get("data")?.jsonObject?.let { dataObj ->
                convertRNWidgetPreferences(dataObj["widgets"]?.jsonObject ?: dataObj)
            } ?: emptyMap()

            applyRNWidgets(RNWidgetsWithOptions(widgets = widgets, widgetOptions = widgetOptions))
        }.onFailure { e ->
            Logger.warn("Failed to decode RN remote widgets backup: $e", context = TAG)
        }
    }

    private suspend fun applyRNRemoteActivity(data: ByteArray) {
        runCatching {
            val items = decodeBackupData<List<RNRemoteActivityItem>>(data).map { item ->
                RNActivityItem(
                    id = item.id,
                    activityType = item.activityType,
                    txType = item.txType,
                    txId = item.txId,
                    value = item.value,
                    fee = item.fee,
                    feeRate = item.feeRate,
                    address = item.address,
                    confirmed = item.confirmed,
                    timestamp = item.timestamp,
                    isBoosted = item.isBoosted,
                    isTransfer = item.isTransfer,
                    exists = item.exists,
                    confirmTimestamp = item.confirmTimestamp,
                    channelId = item.channelId,
                    transferTxId = item.transferTxId,
                    status = item.status,
                    message = item.message,
                    preimage = item.preimage,
                    boostedParents = item.boostedParents,
                )
            }

            pendingRemoteActivityData = items
            applyRNActivities(items)
        }.onFailure { e ->
            Logger.warn("Failed to decode RN remote activity backup", e, context = TAG)
        }
    }

    private suspend fun applyRNRemoteMetadata(data: ByteArray) {
        runCatching {
            val metadata = decodeBackupData<RNMetadata>(data)
            persistMetadata(metadata)
        }.onFailure { e ->
            Logger.warn("Failed to decode RN remote metadata backup", e, context = TAG)
        }
    }

    private suspend fun applyRNRemoteWallet(data: ByteArray) {
        runCatching {
            val backup = decodeBackupData<RNRemoteWalletBackup>(data)

            backup.transfers?.let { transfers ->
                val transferMap = mutableMapOf<String, String>()
                transfers.values.flatten().forEach { transfer ->
                    transfer.txId?.let { txId ->
                        transfer.type?.let { type ->
                            transferMap[txId] = type
                        }
                    }
                }
                if (transferMap.isNotEmpty()) {
                    persistTransfers(transferMap)
                }
            }

            backup.boostedTransactions?.let { boostedTxs ->
                val boostMap = mutableMapOf<String, String>()
                boostedTxs.values.forEach { networkBoosts ->
                    networkBoosts.forEach { (oldTxId, boost) ->
                        val childTxId = boost.childTransaction ?: boost.newTxId
                        childTxId?.let {
                            boostMap[oldTxId] = it
                        }
                    }
                }
                if (boostMap.isNotEmpty()) {
                    Logger.info("Found ${boostMap.size} boosted transactions in remote backup", context = TAG)
                    persistBoosts(boostMap)
                } else {
                    Logger.debug("No boosted transactions found in RN remote wallet backup", context = TAG)
                }
            }
        }.onFailure { e ->
            Logger.warn("Failed to decode RN remote wallet backup: $e", context = TAG)
        }
    }

    private suspend fun applyRNRemoteBlocktank(data: ByteArray) {
        runCatching {
            val backup = decodeBackupData<RNRemoteBlocktankBackup>(data)

            backup.paidOrders?.let { paidOrders ->
                paidOrders.forEach { (orderId, txId) ->
                    cacheStore.addPaidOrder(orderId, txId)
                }
                if (paidOrders.isNotEmpty()) {
                    pendingRemotePaidOrders = paidOrders
                }
            }
        }.onFailure { e ->
            Logger.warn("Failed to decode RN remote blocktank backup: $e", context = TAG)
        }
    }

    private suspend fun createTransfersForPaidOrders(
        paidOrdersMap: Map<String, String>,
        orders: List<com.synonym.bitkitcore.IBtOrder>,
    ) {
        val now = System.currentTimeMillis() / 1000
        val transfers = paidOrdersMap.mapNotNull { (orderId, txId) ->
            val order = orders.find { it.id == orderId }
            when {
                order == null -> {
                    Logger.warn("Paid order $orderId not found in fetched orders", context = TAG)
                    null
                }

                order.state2 == com.synonym.bitkitcore.BtOrderState2.EXECUTED -> null
                else -> TransferEntity(
                    id = txId,
                    type = TransferType.TO_SPENDING,
                    amountSats = (order.clientBalanceSat.safe() + order.feeSat.safe()).toLong(),
                    channelId = null,
                    fundingTxId = null,
                    lspOrderId = orderId,
                    isSettled = false,
                    createdAt = now,
                    settledAt = null,
                )
            }
        }

        if (transfers.isNotEmpty()) {
            runCatching {
                transferDao.upsert(transfers)
                Logger.info("Created ${transfers.size} transfers for paid Blocktank orders", context = TAG)
            }.onFailure { e ->
                Logger.error("Failed to create transfers for paid orders: $e", context = TAG)
            }
        }
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    suspend fun reapplyMetadataAfterSync() {
        loadPersistedMigrationData()

        // Handle MMKV (local) migration data - apply activities FIRST, then metadata
        if (hasRNMmkvData()) {
            loadRNMmkvData()?.let { mmkvData ->
                extractRNActivities(mmkvData)?.let { activities ->
                    Logger.info("Applying ${activities.size} MMKV activities", context = TAG)
                    applyOnchainMetadata(activities)
                }

                extractRNWalletBackup(mmkvData)?.let { (transfers, boosts) ->
                    if (transfers.isNotEmpty()) {
                        Logger.info("Applying ${transfers.size} local transfer markers", context = TAG)
                        applyRemoteTransfers(transfers)
                    }
                    if (boosts.isNotEmpty()) {
                        Logger.info("Applying ${boosts.size} local boost markers", context = TAG)
                        applyBoostTransactions(boosts)
                    }
                }

                // Apply MMKV metadata (tags) AFTER activities are created
                extractRNMetadata(mmkvData)?.let { metadata ->
                    Logger.info("Applying MMKV metadata (tags: ${metadata.tags?.size})", context = TAG)
                    applyRNMetadata(metadata)
                }
            }
        }

        // Handle remote backup data - apply activities FIRST
        pendingRemoteActivityData?.let { remoteActivities ->
            Logger.info("Applying ${remoteActivities.size} remote activities", context = TAG)
            applyOnchainMetadata(remoteActivities)
            pendingRemoteActivityData = null
        }

        pendingRemoteTransfers?.let { transfers ->
            Logger.info("Applying ${transfers.size} remote transfer markers", context = TAG)
            applyRemoteTransfers(transfers)
            clearPersistedTransfers()
        }

        pendingRemoteBoosts?.let { boosts ->
            Logger.info("Applying ${boosts.size} remote boost markers", context = TAG)
            applyBoostTransactions(boosts)
            clearPersistedBoosts()
        }

        // Apply remote metadata (tags) AFTER activities are created
        pendingRemoteMetadata?.let { metadata ->
            Logger.info("Applying remote metadata (tags: ${metadata.tags?.size})", context = TAG)
            applyRNMetadata(metadata)
            clearPersistedMetadata()
        }

        var blocktankFetchFailed = false
        pendingBlocktankOrderIds?.let { orderIds ->
            if (orderIds.isNotEmpty()) {
                Logger.info("Retrying ${orderIds.size} pending Blocktank orders", context = TAG)
                runCatching {
                    val fetchedOrders = coreService.blocktank.orders(
                        orderIds = orderIds,
                        filter = null,
                        refresh = true,
                    )
                    if (fetchedOrders.isNotEmpty()) {
                        coreService.blocktank.upsertOrderList(fetchedOrders)
                        Logger.info("Upserted ${fetchedOrders.size} Blocktank orders after retry", context = TAG)

                        pendingRemotePaidOrders?.let { paidOrders ->
                            if (paidOrders.isNotEmpty()) {
                                Logger.info("Creating transfers for ${paidOrders.size} paid orders", context = TAG)
                                createTransfersForPaidOrders(paidOrders, fetchedOrders)
                            }
                        }
                    }
                    pendingBlocktankOrderIds = null
                    pendingRemotePaidOrders = null
                    clearPersistedBlocktankData()
                }.onFailure { e ->
                    Logger.warn("Still unable to fetch Blocktank orders", e, context = TAG)
                    blocktankFetchFailed = true
                }
            }
        }

        if (!blocktankFetchFailed) {
            pendingRemotePaidOrders?.let { paidOrders ->
                applyRemotePaidOrders(paidOrders)
                pendingRemotePaidOrders = null
            }
        }
    }

    private suspend fun applyRemotePaidOrders(paidOrders: Map<String, String>) {
        if (paidOrders.isEmpty()) return

        val orderIds = paidOrders.keys.toList()

        runCatching {
            val fetchedOrders = coreService.blocktank.orders(
                orderIds = orderIds,
                filter = null,
                refresh = true,
            )
            if (fetchedOrders.isNotEmpty()) {
                coreService.blocktank.upsertOrderList(fetchedOrders)
                createTransfersForPaidOrders(paidOrders, fetchedOrders)
            }
        }.onFailure { e ->
            Logger.warn("Failed to fetch and process remote paid orders: $e", context = TAG)
        }
    }

    private suspend fun applyRemoteTransfers(transfers: Map<String, String>) {
        transfers.forEach { (txId, channelId) ->
            val onchain = activityRepo.getOnchainActivityByTxId(txId) ?: return@forEach
            val updated = onchain.copy(isTransfer = true, channelId = channelId)
            activityRepo.updateActivity(onchain.id, Activity.Onchain(updated))
        }
    }

    private suspend fun applyBoostTransactions(boosts: Map<String, String>) {
        var applied = 0

        boosts.forEach { (oldTxId, newTxId) ->
            val oldOnchain = activityRepo.getOnchainActivityByTxId(oldTxId)
            val newOnchain = activityRepo.getOnchainActivityByTxId(newTxId)

            if (oldOnchain != null && newOnchain != null) {
                var parentOnchain = oldOnchain
                val updatedParentBoostTxIds = if (newTxId !in parentOnchain.boostTxIds) {
                    parentOnchain.boostTxIds + newTxId
                } else {
                    parentOnchain.boostTxIds
                }
                parentOnchain = parentOnchain.copy(
                    isBoosted = true,
                    boostTxIds = updatedParentBoostTxIds,
                )

                val updatedNewOnchain = newOnchain.copy(
                    isBoosted = false,
                    boostTxIds = newOnchain.boostTxIds.filter { it != oldTxId },
                )

                runCatching {
                    activityRepo.updateActivity(parentOnchain.id, Activity.Onchain(parentOnchain))
                    activityRepo.updateActivity(updatedNewOnchain.id, Activity.Onchain(updatedNewOnchain))
                    applied++
                }.onFailure { e ->
                    Logger.error(
                        "Failed to apply CPFP boost for parent $oldTxId / child $newTxId: $e",
                        e,
                        context = TAG
                    )
                }
            } else if (newOnchain != null) {
                val updatedBoostTxIds = if (oldTxId !in newOnchain.boostTxIds) {
                    newOnchain.boostTxIds + oldTxId
                } else {
                    newOnchain.boostTxIds
                }
                val updated = newOnchain.copy(
                    isBoosted = true,
                    boostTxIds = updatedBoostTxIds,
                )

                runCatching {
                    activityRepo.updateActivity(updated.id, Activity.Onchain(updated))
                    applied++
                }.onFailure { e ->
                    Logger.error("Failed to apply RBF boost for tx $newTxId: $e", e, context = TAG)
                }
            }
        }

        Logger.info("Applied $applied/${boosts.size} boost markers", context = TAG)
    }

    private suspend fun applyBoostedParents(boostedParents: List<String>, txId: String) {
        boostedParents.forEach { parentTxId ->
            val parentOnchain = activityRepo.getOnchainActivityByTxId(parentTxId)
            if (parentOnchain != null) {
                val updatedParentBoostTxIds = if (txId !in parentOnchain.boostTxIds) {
                    parentOnchain.boostTxIds + txId
                } else {
                    parentOnchain.boostTxIds
                }
                val updatedParent = parentOnchain.copy(
                    isBoosted = true,
                    boostTxIds = updatedParentBoostTxIds,
                )

                runCatching {
                    activityRepo.updateActivity(updatedParent.id, Activity.Onchain(updatedParent))
                }.onFailure { e ->
                    Logger.error("Failed to mark parent $parentTxId as boosted for CPFP: $e", e, context = TAG)
                }
            }
        }
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private suspend fun updateOnchainActivityMetadata(
        item: RNActivityItem,
        onchain: OnchainActivity,
    ): OnchainActivity? {
        var updated: OnchainActivity = onchain
        var wasUpdated = false

        if (item.timestamp > 0) {
            val migratedTimestamp = (item.timestamp / MS_PER_SEC).toULong()
            if (updated.timestamp != migratedTimestamp) {
                updated = updated.copy(timestamp = migratedTimestamp)
                wasUpdated = true
            }
        }
        item.confirmTimestamp?.let { confirmTimestamp ->
            if (confirmTimestamp > 0) {
                val migratedConfirmTimestamp = (confirmTimestamp / MS_PER_SEC).toULong()
                if (updated.confirmTimestamp != migratedConfirmTimestamp) {
                    updated = updated.copy(confirmTimestamp = migratedConfirmTimestamp)
                    wasUpdated = true
                }
            }
        }
        if (item.isTransfer == true) {
            if (!updated.isTransfer || updated.channelId != item.channelId ||
                updated.transferTxId != item.transferTxId
            ) {
                updated = updated.copy(
                    isTransfer = true,
                    channelId = item.channelId,
                    transferTxId = item.transferTxId,
                )
                wasUpdated = true
            }
        }

        if (item.boostedParents?.isNotEmpty() == true) {
            applyBoostedParents(item.boostedParents, item.txId ?: item.id)
            updated = updated.copy(
                isBoosted = false,
                boostTxIds = updated.boostTxIds.filter { it !in item.boostedParents },
            )
            wasUpdated = true
        } else if (item.isBoosted == true) {
            updated = updated.copy(isBoosted = true)
            wasUpdated = true
        }

        item.feeRate?.let { feeRate ->
            if (feeRate > 0 && updated.feeRate != feeRate.toULong()) {
                updated = updated.copy(feeRate = feeRate.toULong())
                wasUpdated = true
            }
        }

        val backupValue = item.value.toULong()
        if (backupValue > updated.value) {
            updated = updated.copy(value = backupValue)
            wasUpdated = true
        }

        item.fee?.let { backupFee ->
            if (backupFee.toULong() > updated.fee) {
                updated = updated.copy(fee = backupFee.toULong())
                wasUpdated = true
            }
        }

        item.address?.let { address ->
            if (address.isNotEmpty() && updated.address != address) {
                updated = updated.copy(address = address)
                wasUpdated = true
            }
        }

        return if (wasUpdated) updated else null
    }

    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth", "LongMethod")
    private suspend fun applyOnchainMetadata(items: List<RNActivityItem>) {
        val onchainItems = items.filter { it.activityType == "onchain" }
        var updatedCount = 0
        var createdCount = 0

        onchainItems.forEach { item ->
            val txId = item.txId ?: item.id.takeIf { it.isNotEmpty() } ?: return@forEach

            val onchain = activityRepo.getOnchainActivityByTxId(txId)
            if (onchain != null) {
                updateOnchainActivityMetadata(item, onchain)?.let { updated ->
                    activityRepo.updateActivity(updated.id, Activity.Onchain(updated))
                        .onSuccess { updatedCount++ }
                        .onFailure { e ->
                            Logger.error(
                                "Failed to update onchain activity metadata for $txId: $e",
                                e,
                                context = TAG
                            )
                        }
                }
            } else {
                val timestampSecs = (item.timestamp / MS_PER_SEC).toULong()
                val now = (System.currentTimeMillis() / MS_PER_SEC).toULong()

                val activityTimestamp = if (timestampSecs > 0u) timestampSecs else now

                val newOnchain = OnchainActivity(
                    id = item.id,
                    txType = if (item.txType == "sent") PaymentType.SENT else PaymentType.RECEIVED,
                    txId = txId,
                    value = item.value.toULong(),
                    fee = (item.fee ?: 0).toULong(),
                    feeRate = (item.feeRate ?: 1).toULong(),
                    address = item.address ?: "",
                    timestamp = activityTimestamp,
                    confirmed = item.confirmed ?: false,
                    isBoosted = item.isBoosted ?: false,
                    boostTxIds = emptyList(),
                    isTransfer = item.isTransfer ?: false,
                    confirmTimestamp = item.confirmTimestamp?.let { (it / MS_PER_SEC).toULong() },
                    channelId = item.channelId,
                    transferTxId = item.transferTxId,
                    doesExist = item.exists ?: true,
                    createdAt = activityTimestamp,
                    updatedAt = activityTimestamp,
                    seenAt = now,
                )

                activityRepo.upsertActivity(Activity.Onchain(newOnchain))
                    .onSuccess {
                        createdCount++

                        item.boostedParents?.takeIf { it.isNotEmpty() }?.let { parents ->
                            applyBoostedParents(parents, txId)
                        }
                    }
                    .onFailure { e ->
                        Logger.error(
                            "Failed to create onchain activity for unsupported address $txId: $e",
                            e,
                            context = TAG
                        )
                    }
            }
        }

        if (updatedCount > 0 || createdCount > 0) {
            Logger.info(
                "Applied metadata to $updatedCount onchain activities, created $createdCount for unsupported addresses",
                context = TAG
            )
        }
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth")
    private fun convertRNWidgetPreferences(
        widgetsDict: JsonObject?,
    ): Map<String, ByteArray> {
        val result = mutableMapOf<String, ByteArray>()
        if (widgetsDict == null) return result

        fun getBool(
            source: JsonObject,
            key: String,
            fallbackKey: String? = null,
            defaultValue: Boolean = false,
        ): Boolean {
            val keys = if (fallbackKey != null) listOf(key, fallbackKey) else listOf(key)
            for (k in keys) {
                source[k]?.let { element ->
                    when (element) {
                        is JsonPrimitive -> {
                            return if (element.isString) {
                                val str = element.content.lowercase()
                                str == "true" || str == "1"
                            } else {
                                element.content.toBooleanStrictOrNull() ?: defaultValue
                            }
                        }

                        else -> continue
                    }
                }
            }
            return defaultValue
        }

        val pricePrefs = widgetsDict["pricePreferences"]?.jsonObject
            ?: widgetsDict["price"]?.jsonObject
        pricePrefs?.let { prefs ->
            val pairsArray = prefs["pairs"]?.jsonArray
                ?: prefs["enabledPairs"]?.jsonArray
            val mappedPairs = pairsArray?.mapNotNull { pairElement ->
                pairElement.jsonPrimitive.content.replace("_", "/")
            } ?: emptyList()
            val selectedPairs = mappedPairs.ifEmpty { listOf("BTC/USD") }

            val rnPeriod = prefs["period"]?.jsonPrimitive?.content ?: "1D"
            val periodMap = mapOf(
                "ONE_DAY" to "1D",
                "ONE_WEEK" to "1W",
                "ONE_MONTH" to "1M",
                "ONE_YEAR" to "1Y"
            )
            val period = periodMap[rnPeriod] ?: rnPeriod

            val showSource = getBool(prefs, "showSource", defaultValue = false)
            val pairsJson = selectedPairs.joinToString(",", "[", "]") { "\"$it\"" }
            val priceOptionsJson =
                """{"selectedPairs":$pairsJson,"selectedPeriod":"$period","showSource":$showSource}"""
            result["price"] = priceOptionsJson.encodeToByteArray()
        }

        val weatherPrefs = widgetsDict["weatherPreferences"]?.jsonObject
            ?: widgetsDict["weather"]?.jsonObject
        weatherPrefs?.let { prefs ->
            val weatherOptions = buildJsonObject {
                put("showStatus", getBool(prefs, "showTitle", "showStatus", defaultValue = true))
                put("showText", getBool(prefs, "showDescription", "showText", defaultValue = false))
                put("showMedian", getBool(prefs, "showCurrentFee", "showMedian", defaultValue = false))
                put("showNextBlockFee", getBool(prefs, "showNextBlockFee", defaultValue = false))
            }
            result["weather"] = weatherOptions.toString().encodeToByteArray()
        }

        val newsPrefs = widgetsDict["headlinePreferences"]?.jsonObject
            ?: widgetsDict["headline"]?.jsonObject
            ?: widgetsDict["news"]?.jsonObject
        newsPrefs?.let { prefs ->
            val newsOptions = buildJsonObject {
                put("showDate", getBool(prefs, "showDate", "showTime", defaultValue = true))
                put("showTitle", getBool(prefs, "showTitle", defaultValue = true))
                put("showSource", getBool(prefs, "showSource", defaultValue = true))
            }
            result["news"] = newsOptions.toString().encodeToByteArray()
        }

        val blocksPrefs = widgetsDict["blocksPreferences"]?.jsonObject
            ?: widgetsDict["blocks"]?.jsonObject
        blocksPrefs?.let { prefs ->
            val blocksOptions = buildJsonObject {
                put("height", getBool(prefs, "height", "showBlock", defaultValue = true))
                put("time", getBool(prefs, "time", "showTime", defaultValue = true))
                put("date", getBool(prefs, "date", "showDate", defaultValue = true))
                put("transactionCount", getBool(prefs, "transactionCount", "showTransactions", defaultValue = false))
                put("size", getBool(prefs, "size", "showSize", defaultValue = false))
                put("showSource", getBool(prefs, "showSource", defaultValue = false))
            }
            result["blocks"] = blocksOptions.toString().encodeToByteArray()
        }

        val factsPrefs = widgetsDict["factsPreferences"]?.jsonObject
            ?: widgetsDict["facts"]?.jsonObject
        factsPrefs?.let { prefs ->
            val factsOptions = buildJsonObject {
                put("showSource", getBool(prefs, "showSource", defaultValue = false))
            }
            result["facts"] = factsOptions.toString().encodeToByteArray()
        }

        return result
    }
}

data class PendingChannelMigration(
    val channelManager: ByteArray,
    val channelMonitors: List<ByteArray>,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PendingChannelMigration

        if (!channelManager.contentEquals(other.channelManager)) return false
        if (channelMonitors != other.channelMonitors) return false

        return true
    }

    override fun hashCode(): Int {
        var result = channelManager.contentHashCode()
        result = 31 * result + channelMonitors.hashCode()
        return result
    }
}

@Serializable
data class RNSettings(
    val enableAutoReadClipboard: Boolean? = null,
    val enableSendAmountWarning: Boolean? = null,
    val enableSwipeToHideBalance: Boolean? = null,
    val pin: Boolean? = null,
    val pinForPayments: Boolean? = null,
    val biometrics: Boolean? = null,
    val rbf: Boolean? = null,
    val theme: String? = null,
    val unit: String? = null,
    val denomination: String? = null,
    val selectedCurrency: String? = null,
    val selectedLanguage: String? = null,
    val coinSelectAuto: Boolean? = null,
    val coinSelectPreference: String? = null,
    val enableDevOptions: Boolean? = null,
    val enableOfflinePayments: Boolean? = null,
    val enableQuickpay: Boolean? = null,
    val quickpayAmount: Int? = null,
    val showWidgets: Boolean? = null,
    val showWidgetTitles: Boolean? = null,
    val transactionSpeed: String? = null,
    val customFeeRate: Int? = null,
    val hideBalance: Boolean? = null,
    val hideBalanceOnOpen: Boolean? = null,
    val quickpayIntroSeen: Boolean? = null,
    val shopIntroSeen: Boolean? = null,
    val transferIntroSeen: Boolean? = null,
    val spendingIntroSeen: Boolean? = null,
    val savingsIntroSeen: Boolean? = null,
)

@Serializable
data class RNMetadata(
    val tags: Map<String, List<String>>? = null,
    val lastUsedTags: List<String>? = null,
)

@Serializable
data class RNTodos(
    val hide: Map<String, Long>? = null,
)

@Serializable
data class RNActivityState(
    val items: List<RNActivityItem>? = null,
)

@Serializable
data class RNActivityItem(
    val id: String,
    val activityType: String,
    val txType: String,
    val txId: String? = null,
    val value: Long,
    val fee: Long? = null,
    val feeRate: Long? = null,
    val address: String? = null,
    val confirmed: Boolean? = null,
    val timestamp: Long,
    val isBoosted: Boolean? = null,
    val isTransfer: Boolean? = null,
    val exists: Boolean? = null,
    val confirmTimestamp: Long? = null,
    val channelId: String? = null,
    val transferTxId: String? = null,
    val status: String? = null,
    val message: String? = null,
    val preimage: String? = null,
    val boostedParents: List<String>? = null,
)

@Serializable
data class RNLightningState(
    val nodes: Map<String, RNLightningNode>? = null,
)

@Serializable
data class RNLightningNode(
    val channels: Map<String, Map<String, RNChannel>>? = null,
)

@Serializable
data class RNChannel(
    @SerialName("channel_id")
    val channelId: String,
    val status: String? = null,
    val createdAt: Long? = null,
    @SerialName("counterparty_node_id")
    val counterpartyNodeId: String? = null,
    @SerialName("funding_txid")
    val fundingTxid: String? = null,
    @SerialName("channel_value_satoshis")
    val channelValueSatoshis: ULong? = null,
    @SerialName("balance_sat")
    val balanceSat: ULong? = null,
    @SerialName("claimable_balances")
    val claimableBalances: List<RNClaimableBalance>? = null,
    @SerialName("outbound_capacity_sat")
    val outboundCapacitySat: ULong? = null,
    @SerialName("inbound_capacity_sat")
    val inboundCapacitySat: ULong? = null,
    @SerialName("is_usable")
    val isUsable: Boolean? = null,
    @SerialName("is_channel_ready")
    val isChannelReady: Boolean? = null,
    val confirmations: UInt? = null,
    @SerialName("confirmations_required")
    val confirmationsRequired: UInt? = null,
    @SerialName("short_channel_id")
    val shortChannelId: String? = null,
    val closureReason: String? = null,
    @SerialName("unspendable_punishment_reserve")
    val unspendablePunishmentReserve: ULong? = null,
    @SerialName("counterparty_unspendable_punishment_reserve")
    val counterpartyUnspendablePunishmentReserve: ULong? = null,
)

@Serializable
data class RNClaimableBalance(
    @SerialName("amount_satoshis")
    val amountSatoshis: ULong? = null,
    val type: String? = null,
)

@Serializable
data class RNWidgets(
    val onboardedWidgets: Boolean? = null,
    val sortOrder: List<String>? = null,
)

data class RNWidgetsWithOptions(
    val widgets: RNWidgets,
    val widgetOptions: Map<String, ByteArray>,
)

private val Context.rnMigrationDataStore: DataStore<Preferences> by preferencesDataStore("rn_migration")
private val Context.rnKeychainDataStore: DataStore<Preferences> by preferencesDataStore("RN_KEYCHAIN")

private enum class RNKeychainKey(val service: String) {
    MNEMONIC("wallet0"),
    PASSPHRASE("wallet0passphrase"),
    PIN("pin"),
}

@Serializable
private data class RNWalletState(val wallets: Map<String, RNWalletData>? = null)

@Serializable
private data class RNWalletData(
    val transfers: Map<String, List<RNRemoteTransfer>>? = null,
    val boostedTransactions: Map<String, Map<String, RNRemoteBoostedTx>>? = null,
)

@Serializable
private data class RNRemoteActivityItem(
    val id: String,
    val activityType: String,
    val txType: String,
    val txId: String? = null,
    val value: Long,
    val fee: Long? = null,
    val feeRate: Long? = null,
    val address: String? = null,
    val confirmed: Boolean? = null,
    val timestamp: Long,
    val isBoosted: Boolean? = null,
    val isTransfer: Boolean? = null,
    val exists: Boolean? = null,
    val confirmTimestamp: Long? = null,
    val channelId: String? = null,
    val transferTxId: String? = null,
    val status: String? = null,
    val message: String? = null,
    val preimage: String? = null,
    val boostedParents: List<String>? = null,
)

@Serializable
private data class RNRemoteWalletBackup(
    val transfers: Map<String, List<RNRemoteTransfer>>? = null,
    val boostedTransactions: Map<String, Map<String, RNRemoteBoostedTx>>? = null,
)

@Serializable
private data class RNRemoteTransfer(val txId: String? = null, val type: String? = null)

@Serializable
private data class RNRemoteBoostedTx(
    val oldTxId: String? = null,
    val newTxId: String? = null,
    val childTransaction: String? = null,
)

@Serializable
private data class RNRemoteBlocktankBackup(
    val orders: List<RNRemoteBlocktankOrder>? = null,
    val paidOrders: Map<String, String>? = null,
)

@Serializable
private data class RNRemoteBlocktankOrder(
    val id: String,
    val state: String? = null,
    val lspBalanceSat: ULong? = null,
    val clientBalanceSat: ULong? = null,
    val channelExpiryWeeks: Int? = null,
    val createdAt: String? = null,
)
