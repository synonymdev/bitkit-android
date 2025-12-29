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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import to.bitkit.data.SettingsStore
import to.bitkit.data.WidgetsStore
import to.bitkit.data.keychain.Keychain
import to.bitkit.di.json
import to.bitkit.env.Env
import to.bitkit.models.BitcoinDisplayUnit
import to.bitkit.models.CoinSelectionPreference
import to.bitkit.models.PrimaryDisplay
import to.bitkit.models.TransactionSpeed
import to.bitkit.models.WidgetType
import to.bitkit.models.WidgetWithPosition
import to.bitkit.repositories.ActivityRepo
import to.bitkit.utils.Logger
import java.io.File
import java.security.KeyStore
import javax.inject.Inject
import javax.inject.Singleton

private val Context.rnMigrationDataStore:
    androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> by preferencesDataStore(
        name = "rn_migration"
    )

private val Context.rnKeychainDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "RN_KEYCHAIN"
)

@Suppress("LargeClass", "TooManyFunctions")
@Singleton
class MigrationService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keychain: Keychain,
    private val settingsStore: SettingsStore,
    private val widgetsStore: WidgetsStore,
    private val activityRepo: ActivityRepo,
    private val coreService: CoreService,
) {
    companion object {
        private const val TAG = "Migration"
        const val RN_MIGRATION_COMPLETED_KEY = "rnMigrationCompleted"
        const val RN_MIGRATION_CHECKED_KEY = "rnMigrationChecked"
        private const val RN_WALLET_NAME = "wallet0"
        private const val MNEMONIC_WORD_COUNT_12 = 12
        private const val MNEMONIC_WORD_COUNT_24 = 24
        private const val MILLISECONDS_TO_SECONDS = 1000
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH = 128
    }

    private val rnMigrationStore = context.rnMigrationDataStore

    private val _isShowingMigrationLoading = MutableStateFlow(false)
    val isShowingMigrationLoading: StateFlow<Boolean> = _isShowingMigrationLoading.asStateFlow()

    fun setShowingMigrationLoading(value: Boolean) {
        _isShowingMigrationLoading.value = value
    }

    @Volatile
    private var pendingChannelMigration: PendingChannelMigration? = null

    fun consumePendingChannelMigration(): PendingChannelMigration? {
        return pendingChannelMigration.also { pendingChannelMigration = null }
    }

    private val rnNetworkString: String
        get() = when (Env.network) {
            org.lightningdevkit.ldknode.Network.BITCOIN -> "bitcoin"
            org.lightningdevkit.ldknode.Network.REGTEST -> "bitcoinRegtest"
            org.lightningdevkit.ldknode.Network.TESTNET -> "bitcoinTestnet"
            org.lightningdevkit.ldknode.Network.SIGNET -> "signet"
        }

    private val rnLdkBasePath: File
        get() = File(context.filesDir, "ldk")

    private val rnLdkAccountPath: File
        get() {
            val accountName = buildString {
                append(RN_WALLET_NAME)
                append(rnNetworkString)
                append("ldkaccountv3")
            }
            return File(rnLdkBasePath, accountName)
        }

    private val rnMmkvPath: File
        get() = File(context.filesDir, "mmkv/mmkv.default")

    suspend fun isMigrationChecked(): Boolean {
        val key = stringPreferencesKey(RN_MIGRATION_CHECKED_KEY)
        return rnMigrationStore.data.first()[key] == "true"
    }

    suspend fun isMigrationCompleted(): Boolean {
        val key = stringPreferencesKey(RN_MIGRATION_COMPLETED_KEY)
        return rnMigrationStore.data.first()[key] == "true"
    }

    suspend fun markMigrationChecked() {
        val key = stringPreferencesKey(RN_MIGRATION_CHECKED_KEY)
        rnMigrationStore.edit { it[key] = "true" }
    }

    suspend fun hasRNWalletData(): Boolean {
        val mnemonic = loadStringFromRNKeychain(RNKeychainKey.MNEMONIC)
        if (mnemonic?.isNotEmpty() == true) {
            return true
        }

        return hasRNMmkvData() || hasRNLdkData()
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    suspend fun hasNativeWalletData(): Boolean {
        return try {
            keychain.exists(Keychain.Key.BIP39_MNEMONIC.name)
        } catch (e: Exception) {
            false
        }
    }

    suspend fun hasRNLdkData(): Boolean {
        return File(rnLdkAccountPath, "channel_manager.bin").exists()
    }

    suspend fun hasRNMmkvData(): Boolean {
        return rnMmkvPath.exists()
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun loadStringFromRNKeychain(key: RNKeychainKey): String? {
        val datastorePath = File(context.filesDir, "datastore/RN_KEYCHAIN.preferences_pb")
        if (!datastorePath.exists()) {
            return null
        }

        return try {
            val preferences = context.rnKeychainDataStore.data.first()

            val passwordKey = stringPreferencesKey("${key.service}:p")
            val cipherKey = stringPreferencesKey("${key.service}:c")

            val encryptedValue = preferences[passwordKey] ?: return null
            val cipherInfo = preferences[cipherKey]

            val fullEncryptedValue = if (cipherInfo != null && !encryptedValue.contains(":")) {
                "$cipherInfo:$encryptedValue"
            } else {
                encryptedValue
            }
            decryptRNKeychainValue(fullEncryptedValue, key.service)
        } catch (e: Exception) {
            Logger.error("Error reading from RN_KEYCHAIN DataStore: $e", e, context = TAG)
            null
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun decryptRNKeychainValue(encryptedValue: String, service: String): String? {
        if (!encryptedValue.contains(":")) {
            return try {
                val encryptedBytes = android.util.Base64.decode(encryptedValue, android.util.Base64.DEFAULT)
                decryptWithKeystore(encryptedBytes, service)
            } catch (e: Exception) {
                Logger.error("Failed to decrypt without cipher prefix: $e", e, context = TAG)
                null
            }
        }

        val parts = encryptedValue.split(":", limit = 2)
        val cipherName = parts[0]
        val encryptedDataBase64 = parts[1]

        if (!cipherName.contains("KeystoreAESGCM")) {
            Logger.warn("Unsupported cipher: $cipherName. Only KeystoreAESGCM is supported.", context = TAG)
            return null
        }

        return try {
            val encryptedBytes = android.util.Base64.decode(encryptedDataBase64, android.util.Base64.DEFAULT)
            decryptWithKeystore(encryptedBytes, service)
        } catch (e: Exception) {
            Logger.error("Failed to decrypt RN keychain value: $e", e, context = TAG)
            null
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun decryptWithKeystore(encryptedBytes: ByteArray, service: String): String? {
        if (encryptedBytes.size < GCM_IV_LENGTH) {
            Logger.error("Encrypted data too short: ${encryptedBytes.size} bytes", context = TAG)
            return null
        }

        val iv = encryptedBytes.sliceArray(0 until GCM_IV_LENGTH)
        val ciphertext = encryptedBytes.sliceArray(GCM_IV_LENGTH until encryptedBytes.size)

        return try {
            val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

            val alias = service
            if (!keystore.containsAlias(alias)) {
                Logger.error("Keystore alias '$alias' not found", context = TAG)
                return null
            }

            val secretKey = keystore.getKey(alias, null) as javax.crypto.SecretKey

            val transformation = "AES/GCM/NoPadding"
            val spec = javax.crypto.spec.GCMParameterSpec(GCM_TAG_LENGTH, iv)
            val cipher = javax.crypto.Cipher.getInstance(transformation).apply {
                init(javax.crypto.Cipher.DECRYPT_MODE, secretKey, spec)
            }

            val decryptedBytes = cipher.doFinal(ciphertext)
            String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            Logger.error("Failed to decrypt with Keystore: $e", e, context = TAG)
            null
        }
    }

    @Suppress("TooGenericExceptionCaught")
    suspend fun migrateFromReactNative() {
        setShowingMigrationLoading(true)

        try {
            val mnemonicMigrated = try {
                migrateMnemonic()
                true
            } catch (e: Exception) {
                Logger.warn(
                    "Could not migrate mnemonic: $e. User will need to manually restore.",
                    context = TAG
                )
                false
            }

            if (mnemonicMigrated) {
                migratePassphrase()
                migratePin()

                if (hasRNLdkData()) {
                    migrateLdkData().onFailure { e ->
                        Logger.warn(
                            "LDK data migration failed, continuing with other migrations: $e",
                            e,
                            context = TAG
                        )
                    }
                }

                if (hasRNMmkvData()) {
                    migrateMMKVData()
                }

                rnMigrationStore.edit {
                    it[stringPreferencesKey(RN_MIGRATION_COMPLETED_KEY)] = "true"
                    it[stringPreferencesKey(RN_MIGRATION_CHECKED_KEY)] = "true"
                }

                if (!hasRNLdkData()) {
                    setShowingMigrationLoading(false)
                }
            } else {
                markMigrationChecked()
                setShowingMigrationLoading(false)
                throw to.bitkit.utils.AppError(
                    "Migration data unavailable. Please restore your wallet using your recovery phrase."
                )
            }
        } catch (e: Exception) {
            Logger.error("RN migration failed: $e", e, context = TAG)
            markMigrationChecked()
            setShowingMigrationLoading(false)
            throw e
        }
    }

    private suspend fun migrateMnemonic() {
        val mnemonic = loadStringFromRNKeychain(RNKeychainKey.MNEMONIC)

        if (mnemonic.isNullOrEmpty()) {
            throw to.bitkit.utils.AppError(
                "Migration data unavailable. Please restore your wallet using your recovery phrase."
            )
        }

        val words = mnemonic.split(" ").filter { it.isNotBlank() }
        if (words.size != MNEMONIC_WORD_COUNT_12 && words.size != MNEMONIC_WORD_COUNT_24) {
            throw to.bitkit.utils.AppError(
                "Recovery phrase format is invalid. Please use your 12 or 24 word recovery phrase to restore manually."
            )
        }

        keychain.saveString(Keychain.Key.BIP39_MNEMONIC.name, mnemonic)
    }

    private suspend fun migratePassphrase() {
        val passphrase = loadStringFromRNKeychain(RNKeychainKey.PASSPHRASE)
        if (passphrase.isNullOrEmpty()) {
            return
        }
        keychain.saveString(Keychain.Key.BIP39_PASSPHRASE.name, passphrase)
    }

    private suspend fun migratePin() {
        val pin = loadStringFromRNKeychain(RNKeychainKey.PIN)
        if (pin.isNullOrEmpty()) {
            return
        }
        keychain.saveString(Keychain.Key.PIN.name, pin)
    }

    private suspend fun migrateLdkData() = runCatching {
        val accountPath = rnLdkAccountPath
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
    }.onFailure { e ->
        Logger.error("Failed to migrate LDK data: $e", e, context = TAG)
    }

    suspend fun loadRNMmkvData(): Map<String, String>? = runCatching {
        if (!hasRNMmkvData()) {
            return@runCatching null
        }

        val data = rnMmkvPath.readBytes()
        val parser = MmkvParser(data)
        parser.parse().takeIf { it.isNotEmpty() }
    }.onFailure { e ->
        Logger.error("Failed to read MMKV data: $e", e, context = TAG)
    }.getOrNull()

    @Suppress("TooGenericExceptionCaught")
    private suspend fun extractRNSettings(mmkvData: Map<String, String>): RNSettings? {
        val rootJson = mmkvData["persist:root"] ?: return null

        return try {
            val jsonStart = rootJson.indexOf("{")
            val jsonString = if (jsonStart >= 0) rootJson.substring(jsonStart) else rootJson

            val root = json.parseToJsonElement(jsonString).jsonObject
            val settingsJsonString = root["settings"]?.jsonPrimitive?.content ?: return null

            json.decodeFromString<RNSettings>(settingsJsonString)
        } catch (e: Exception) {
            Logger.error("Failed to decode RN settings: $e", e, context = TAG)
            null
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun extractRNMetadata(mmkvData: Map<String, String>): RNMetadata? {
        val rootJson = mmkvData["persist:root"] ?: return null

        return try {
            val jsonStart = rootJson.indexOf("{")
            val jsonString = if (jsonStart >= 0) rootJson.substring(jsonStart) else rootJson

            val root = json.parseToJsonElement(jsonString).jsonObject
            val metadataJsonString = root["metadata"]?.jsonPrimitive?.content ?: return null

            json.decodeFromString<RNMetadata>(metadataJsonString)
        } catch (e: Exception) {
            Logger.error("Failed to decode RN metadata: $e", e, context = TAG)
            null
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun extractRNWidgets(mmkvData: Map<String, String>): RNWidgetsWithOptions? {
        val rootJson = mmkvData["persist:root"] ?: return null

        return try {
            val jsonStart = rootJson.indexOf("{")
            val jsonString = if (jsonStart >= 0) rootJson.substring(jsonStart) else rootJson

            val root = json.parseToJsonElement(jsonString).jsonObject
            val widgetsJsonString = root["widgets"]?.jsonPrimitive?.content ?: return null

            val widgets = json.decodeFromString<RNWidgets>(widgetsJsonString)

            val widgetsData = json.parseToJsonElement(widgetsJsonString).jsonObject
            val widgetOptions = convertRNWidgetPreferences(widgetsData["widgets"]?.jsonObject ?: widgetsData)

            RNWidgetsWithOptions(widgets = widgets, widgetOptions = widgetOptions)
        } catch (e: Exception) {
            Logger.error("Failed to decode RN widgets: $e", e, context = TAG)
            null
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun extractRNActivities(mmkvData: Map<String, String>): List<RNActivityItem>? {
        val rootJson = mmkvData["persist:root"] ?: return null

        return try {
            val jsonStart = rootJson.indexOf("{")
            val jsonString = if (jsonStart >= 0) rootJson.substring(jsonStart) else rootJson

            val root = json.parseToJsonElement(jsonString).jsonObject
            val activityJsonString = root["activity"]?.jsonPrimitive?.content ?: return null

            val activityState = json.decodeFromString<RNActivityState>(activityJsonString)
            activityState.items ?: emptyList()
        } catch (e: Exception) {
            Logger.error("Failed to decode RN activities: $e", e, context = TAG)
            null
        }
    }

    @Suppress("NestedBlockDepth", "TooGenericExceptionCaught")
    private suspend fun extractRNClosedChannels(mmkvData: Map<String, String>): List<RNChannel>? {
        val rootJson = mmkvData["persist:root"] ?: return null

        return try {
            val jsonStart = rootJson.indexOf("{")
            val jsonString = if (jsonStart >= 0) rootJson.substring(jsonStart) else rootJson

            val root = json.parseToJsonElement(jsonString).jsonObject
            val lightningJsonString = root["lightning"]?.jsonPrimitive?.content ?: return null

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
        } catch (e: Exception) {
            Logger.error("Failed to decode RN lightning state: $e", e, context = TAG)
            null
        }
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
        val allTags = metadata.tags?.mapNotNull { (txId, tagList) ->
            runCatching {
                var activityId = txId
                activityRepo.getOnchainActivityByTxId(txId)?.let {
                    activityId = it.id
                }
                ActivityTags(activityId = activityId, tags = tagList)
            }.onFailure { e ->
                Logger.error("Failed to get activity ID for $txId: $e", e, context = TAG)
            }.getOrNull()
        } ?: emptyList()

        if (allTags.isNotEmpty()) {
            runCatching {
                coreService.activity.upsertTags(allTags)
            }.onFailure { e ->
                Logger.error("Failed to migrate tags: $e", e, context = TAG)
            }
        }

        metadata.lastUsedTags?.forEach {
            settingsStore.addLastUsedTag(it)
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

            val timestampSecs = (item.timestamp / MILLISECONDS_TO_SECONDS).toULong()
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
        val now = (System.currentTimeMillis() / MILLISECONDS_TO_SECONDS).toULong()

        val closedChannels = channels.mapNotNull { channel ->
            val fundingTxid = channel.fundingTxid ?: return@mapNotNull null

            val closedAtSecs = channel.createdAt?.let {
                (it / MILLISECONDS_TO_SECONDS).toULong()
            } ?: now

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

    @Suppress("LongMethod", "CyclomaticComplexMethod", "TooGenericExceptionCaught")
    private suspend fun applyRNWidgetPreferences(widgetOptions: Map<String, ByteArray>) {
        widgetOptions["price"]?.let { priceData ->
            try {
                val priceJson = json.decodeFromString<kotlinx.serialization.json.JsonObject>(
                    priceData.decodeToString()
                )
                val selectedPairs = priceJson["selectedPairs"]?.jsonArray?.mapNotNull { pairElement ->
                    val pairStr = pairElement.jsonPrimitive.content.replace("_", "/")
                    when (pairStr) {
                        "BTC/USD" -> to.bitkit.data.dto.price.TradingPair.BTC_USD
                        "BTC/EUR" -> to.bitkit.data.dto.price.TradingPair.BTC_EUR
                        "BTC/GBP" -> to.bitkit.data.dto.price.TradingPair.BTC_GBP
                        "BTC/JPY" -> to.bitkit.data.dto.price.TradingPair.BTC_JPY
                        else -> null
                    }
                } ?: listOf(to.bitkit.data.dto.price.TradingPair.BTC_USD)

                val periodStr = priceJson["selectedPeriod"]?.jsonPrimitive?.content ?: "1D"
                val period = when (periodStr) {
                    "1D" -> to.bitkit.data.dto.price.GraphPeriod.ONE_DAY
                    "1W" -> to.bitkit.data.dto.price.GraphPeriod.ONE_WEEK
                    "1M" -> to.bitkit.data.dto.price.GraphPeriod.ONE_MONTH
                    "1Y" -> to.bitkit.data.dto.price.GraphPeriod.ONE_YEAR
                    else -> to.bitkit.data.dto.price.GraphPeriod.ONE_DAY
                }

                val showSource = priceJson["showSource"]?.jsonPrimitive?.content
                    ?.toBooleanStrictOrNull() ?: false

                widgetsStore.updatePricePreferences(
                    to.bitkit.models.widget.PricePreferences(
                        enabledPairs = selectedPairs,
                        period = period,
                        showSource = showSource
                    )
                )
            } catch (e: Exception) {
                Logger.error("Failed to migrate price preferences: $e", e, context = TAG)
            }
        }

        widgetOptions["weather"]?.let { weatherData ->
            try {
                val weatherJson = json.decodeFromString<kotlinx.serialization.json.JsonObject>(
                    weatherData.decodeToString()
                )
                val showTitle = weatherJson["showStatus"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true
                val showDescription = weatherJson["showText"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
                val showCurrentFee = weatherJson["showMedian"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
                val showNextBlockFee = weatherJson["showNextBlockFee"]?.jsonPrimitive?.content
                    ?.toBooleanStrictOrNull() ?: false

                widgetsStore.updateWeatherPreferences(
                    to.bitkit.models.widget.WeatherPreferences(
                        showTitle = showTitle,
                        showDescription = showDescription,
                        showCurrentFee = showCurrentFee,
                        showNextBlockFee = showNextBlockFee
                    )
                )
            } catch (e: Exception) {
                Logger.error("Failed to migrate weather preferences: $e", e, context = TAG)
            }
        }

        widgetOptions["news"]?.let { newsData ->
            try {
                val newsJson = json.decodeFromString<kotlinx.serialization.json.JsonObject>(
                    newsData.decodeToString()
                )
                val showTime = newsJson["showDate"]?.jsonPrimitive?.content
                    ?.toBooleanStrictOrNull() ?: true
                val showSource = newsJson["showSource"]?.jsonPrimitive?.content
                    ?.toBooleanStrictOrNull() ?: true

                widgetsStore.updateHeadlinePreferences(
                    to.bitkit.models.widget.HeadlinePreferences(
                        showTime = showTime,
                        showSource = showSource
                    )
                )
            } catch (e: Exception) {
                Logger.error("Failed to migrate news preferences: $e", e, context = TAG)
            }
        }

        widgetOptions["blocks"]?.let { blocksData ->
            try {
                val blocksJson = json.decodeFromString<kotlinx.serialization.json.JsonObject>(
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
                    to.bitkit.models.widget.BlocksPreferences(
                        showBlock = showBlock,
                        showTime = showTime,
                        showDate = showDate,
                        showTransactions = showTransactions,
                        showSize = showSize,
                        showSource = showSource
                    )
                )
            } catch (e: Exception) {
                Logger.error("Failed to migrate blocks preferences: $e", e, context = TAG)
            }
        }

        widgetOptions["facts"]?.let { factsData ->
            try {
                val factsJson = json.decodeFromString<kotlinx.serialization.json.JsonObject>(
                    factsData.decodeToString()
                )
                val showSource = factsJson["showSource"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false

                widgetsStore.updateFactsPreferences(
                    to.bitkit.models.widget.FactsPreferences(
                        showSource = showSource
                    )
                )
            } catch (e: Exception) {
                Logger.error("Failed to migrate facts preferences: $e", e, context = TAG)
            }
        }
    }

    private suspend fun migrateMMKVData() {
        val mmkvData = loadRNMmkvData() ?: return

        extractRNActivities(mmkvData)?.let { activities ->
            applyRNActivities(activities)
        }

        extractRNClosedChannels(mmkvData)?.let { channels ->
            applyRNClosedChannels(channels)
        }

        extractRNSettings(mmkvData)?.let { settings ->
            applyRNSettings(settings)
        }

        extractRNMetadata(mmkvData)?.let { metadata ->
            applyRNMetadata(metadata)
        }

        extractRNWidgets(mmkvData)?.let { widgets ->
            applyRNWidgets(widgets)
        }
    }

    suspend fun reapplyMetadataAfterSync() {
        if (!hasRNMmkvData()) return

        val mmkvData = loadRNMmkvData() ?: return

        extractRNMetadata(mmkvData)?.let { metadata ->
            applyRNMetadata(metadata)
        }

        extractRNActivities(mmkvData)?.let { activities ->
            applyOnchainMetadata(activities)
        }
    }

    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth")
    private suspend fun applyOnchainMetadata(items: List<RNActivityItem>) {
        val onchainItems = items.filter { it.activityType == "onchain" }

        onchainItems.forEach { item ->
            val txId = item.txId ?: item.id.takeIf { it.isNotEmpty() } ?: return@forEach

            val onchain = activityRepo.getOnchainActivityByTxId(txId)
            if (onchain == null) {
                Logger.warn("Onchain activity not found for txId: $txId", context = TAG)
                return@forEach
            }
            var updated: OnchainActivity = onchain
            var wasUpdated = false

            if (item.timestamp > 0) {
                val migratedTimestamp = (item.timestamp / MILLISECONDS_TO_SECONDS).toULong()
                if (updated.timestamp != migratedTimestamp) {
                    updated = updated.copy(
                        timestamp = migratedTimestamp
                    )
                    wasUpdated = true
                }
            }
            item.confirmTimestamp?.let { confirmTimestamp ->
                if (confirmTimestamp > 0) {
                    val migratedConfirmTimestamp = (confirmTimestamp / MILLISECONDS_TO_SECONDS).toULong()
                    if (updated.confirmTimestamp != migratedConfirmTimestamp) {
                        updated = updated.copy(
                            confirmTimestamp = migratedConfirmTimestamp
                        )
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

            if (wasUpdated) {
                activityRepo.updateActivity(updated.id, Activity.Onchain(updated))
                    .onFailure { e ->
                        Logger.error(
                            "Failed to update onchain activity metadata for $txId: $e",
                            e,
                            context = TAG
                        )
                    }
            }
        }
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private fun convertRNWidgetPreferences(
        widgetsDict: kotlinx.serialization.json.JsonObject?
    ): Map<String, ByteArray> {
        val result = mutableMapOf<String, ByteArray>()
        if (widgetsDict == null) return result

        fun getBool(key: String, fallbackKey: String? = null, defaultValue: Boolean = false): Boolean {
            val keys = if (fallbackKey != null) listOf(key, fallbackKey) else listOf(key)
            for (k in keys) {
                widgetsDict[k]?.let { element ->
                    when {
                        element is kotlinx.serialization.json.JsonPrimitive && element.isString -> {
                            val str = element.content.lowercase()
                            return str == "true" || str == "1"
                        }
                        element is kotlinx.serialization.json.JsonPrimitive -> {
                            return element.content.toBooleanStrictOrNull()
                                ?: defaultValue
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
            val selectedPairs = if (mappedPairs.isNotEmpty()) mappedPairs else listOf("BTC/USD")

            val rnPeriod = prefs["period"]?.jsonPrimitive?.content ?: "1D"
            val periodMap = mapOf(
                "ONE_DAY" to "1D",
                "ONE_WEEK" to "1W",
                "ONE_MONTH" to "1M",
                "ONE_YEAR" to "1Y"
            )
            val period = periodMap[rnPeriod] ?: rnPeriod

            val showSource = getBool("showSource", defaultValue = false)
            val pairsJson = selectedPairs.joinToString(",", "[", "]") { "\"$it\"" }
            val priceOptionsJson =
                """{"selectedPairs":$pairsJson,"selectedPeriod":"$period","showSource":$showSource}"""
            result["price"] = priceOptionsJson.encodeToByteArray()
        }

        val weatherPrefs = widgetsDict["weatherPreferences"]?.jsonObject
            ?: widgetsDict["weather"]?.jsonObject
        weatherPrefs?.let {
            val weatherOptions = buildJsonObject {
                put("showStatus", getBool("showTitle", "showStatus", defaultValue = true))
                put("showText", getBool("showDescription", "showText", defaultValue = false))
                put("showMedian", getBool("showCurrentFee", "showMedian", defaultValue = false))
                put("showNextBlockFee", getBool("showNextBlockFee", defaultValue = false))
            }
            result["weather"] = weatherOptions.toString().encodeToByteArray()
        }

        val newsPrefs = widgetsDict["headlinePreferences"]?.jsonObject
            ?: widgetsDict["headline"]?.jsonObject
            ?: widgetsDict["news"]?.jsonObject
        newsPrefs?.let {
            val newsOptions = buildJsonObject {
                put("showDate", getBool("showDate", "showTime", defaultValue = true))
                put("showTitle", getBool("showTitle", defaultValue = true))
                put("showSource", getBool("showSource", defaultValue = true))
            }
            result["news"] = newsOptions.toString().encodeToByteArray()
        }

        val blocksPrefs = widgetsDict["blocksPreferences"]?.jsonObject
            ?: widgetsDict["blocks"]?.jsonObject
        blocksPrefs?.let {
            val blocksOptions = buildJsonObject {
                put("height", getBool("height", "showBlock", defaultValue = true))
                put("time", getBool("time", "showTime", defaultValue = true))
                put("date", getBool("date", "showDate", defaultValue = true))
                put("transactionCount", getBool("transactionCount", "showTransactions", defaultValue = false))
                put("size", getBool("size", "showSize", defaultValue = false))
                put("showSource", getBool("showSource", defaultValue = false))
            }
            result["blocks"] = blocksOptions.toString().encodeToByteArray()
        }

        val factsPrefs = widgetsDict["factsPreferences"]?.jsonObject
            ?: widgetsDict["facts"]?.jsonObject
        factsPrefs?.let {
            val factsOptions = buildJsonObject {
                put("showSource", getBool("showSource", defaultValue = false))
            }
            result["facts"] = factsOptions.toString().encodeToByteArray()
        }

        return result
    }
}

data class PendingChannelMigration(
    val channelManager: ByteArray,
    val channelMonitors: List<ByteArray>,
)

@Serializable
data class RNSettings(
    val enableAutoReadClipboard: Boolean? = null,
    val enableSendAmountWarning: Boolean? = null,
    val enableSwipeToHideBalance: Boolean? = null,
    val pin: Boolean? = null,
    val pinOnLaunch: Boolean? = null,
    val pinOnIdle: Boolean? = null,
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

private enum class RNKeychainKey(val service: String) {
    MNEMONIC("wallet0"),
    PASSPHRASE("wallet0passphrase"),
    PIN("pin"),
}
