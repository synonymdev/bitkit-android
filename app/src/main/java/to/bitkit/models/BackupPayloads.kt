package to.bitkit.models

import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.ActivityTags
import com.synonym.bitkitcore.ClosedChannelDetails
import com.synonym.bitkitcore.IBtInfo
import com.synonym.bitkitcore.IBtOrder
import com.synonym.bitkitcore.IcJitEntry
import com.synonym.bitkitcore.PreActivityMetadata
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import to.bitkit.data.AppCacheData
import to.bitkit.data.SettingsData
import to.bitkit.data.WatchOnlyAccountAllocationState
import to.bitkit.data.WidgetsData
import to.bitkit.data.entities.TransferEntity

@Serializable
data class WalletBackupV1(
    val version: Int = 1,
    val createdAt: Long,
    val transfers: List<TransferEntity>,
    val privatePaykitHighestReservedReceiveIndexByAddressType: Map<String, Int>? = null,
    val publicPaykitLegacyAddresses: List<String>? = null,
    val paykitSdkBackupState: String? = null,
    val watchOnlyAccounts: List<WatchOnlyAccountRecord>? = null,
    val watchOnlyAccountAllocationState: WatchOnlyAccountAllocationState? = null,
    val usdtWallet: String? = null,
    val paykitPaymentState: PaykitPaymentStateBackup? = null,
)

@Serializable
data class MetadataBackupV1(
    val version: Int = 1,
    val createdAt: Long,
    val tagMetadata: List<PreActivityMetadata>,
    val cache: AppCacheData,
    val pubkySession: PubkySessionBackupV1? = null,
    val pubkyContactProfileOverrides: Map<String, PubkyProfileData>? = null,
    /** User-set hardware wallet names, keyed by wallet id. Null in envelopes written before this field. */
    val hwWalletNames: Map<String, String>? = null,
    /**
     * The earlier Spark exit addresses each wallet watches, keyed `{network}/wallet{n}` like the cache's
     * `sparkExitNextFundingIndexes`. Null in envelopes written before this field.
     */
    val sparkExitEarlierAddresses: Map<String, List<SparkExitEarlierAddressBackupV1>>? = null,
    /**
     * The payment hashes of moves to Spark, lowercase hex and sorted, which Activity shows as moves. Null in envelopes
     * written before this field or with none; a value that is not a list of strings reads as none.
     */
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @Serializable(with = LenientStringListSerializer::class)
    val moveToSparkPaymentHashes: List<String>? = null,
    /**
     * The payment hashes of failed move parts that smaller parts of the same move replaced, lowercase hex and sorted,
     * which Activity leaves out. Null in envelopes written before this field or with none.
     */
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @Serializable(with = LenientStringListSerializer::class)
    val moveToSparkDroppedPaymentHashes: List<String>? = null,
)

/** Reads a list of strings, or an empty list for any other value, so one unreadable field keeps the rest. */
internal object LenientStringListSerializer : KSerializer<List<String>> {
    private val delegate = ListSerializer(String.serializer())
    override val descriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: List<String>) = delegate.serialize(encoder, value)

    override fun deserialize(decoder: Decoder): List<String> {
        val element = (decoder as? JsonDecoder)?.decodeJsonElement() ?: return delegate.deserialize(decoder)
        val items = (element as? JsonArray)?.map { it as? JsonPrimitive } ?: return emptyList()
        if (items.any { it == null || !it.isString }) return emptyList()
        return items.map { requireNotNull(it).content }
    }
}

/**
 * An earlier Spark exit address as both apps back it up. [kind] is `keptChange` or `cancelledFunding`; a reader skips
 * one it does not know. What the address holds is read again from the chain.
 */
@Serializable
data class SparkExitEarlierAddressBackupV1(
    val address: String,
    val path: String,
    val kind: String,
    val atMillis: Long,
)

@Serializable
data class PubkySessionBackupV1(
    /** Null when bitkit-ios wrote a kind it no longer restores, which it then encodes as `{}`. */
    val kind: PubkySessionBackupKind? = null,
)

@Serializable
enum class PubkySessionBackupKind {
    @SerialName("localSeed")
    LocalSeed,

    /** Legacy: sessions imported through the removed Pubky Ring relay flow; restored as no identity. */
    @SerialName("externalSession")
    ExternalSession,
}

@Serializable
data class BlocktankBackupV1(
    val version: Int = 1,
    val createdAt: Long,
    val orders: List<IBtOrder>,
    val cjitEntries: List<IcJitEntry>,
    val info: IBtInfo? = null,
)

@Serializable
data class ActivityBackupV1(
    val version: Int = 1,
    val createdAt: Long,
    val activities: List<Activity>,
    val activityTags: List<ActivityTags>,
    val closedChannels: List<ClosedChannelDetails>,
)

@Serializable
data class SettingsBackupV1(
    val version: Int = 1,
    val createdAt: Long,
    val settings: SettingsData,
)

@Serializable
data class WidgetsBackupV1(
    val version: Int = 1,
    val createdAt: Long,
    val widgets: WidgetsData,
)

/**
 * The Spark wallet's unilateral exit data, as the SDK exports it, kept so a wallet restored on another device can
 * still exit on-chain while the Spark operators are unreachable.
 */
@Serializable
data class SparkExitBackupV1(
    val version: Int = 1,
    val createdAt: Long,
    val network: String,
    val exitState: String,
)
