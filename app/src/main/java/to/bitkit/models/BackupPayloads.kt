package to.bitkit.models

import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.ActivityTags
import com.synonym.bitkitcore.ClosedChannelDetails
import com.synonym.bitkitcore.IBtInfo
import com.synonym.bitkitcore.IBtOrder
import com.synonym.bitkitcore.IcJitEntry
import com.synonym.bitkitcore.PreActivityMetadata
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import to.bitkit.data.AppCacheData
import to.bitkit.data.SettingsData
import to.bitkit.data.WidgetsData
import to.bitkit.data.entities.TransferEntity

@Serializable
data class WalletBackupV1(
    val version: Int = 1,
    val createdAt: Long,
    val transfers: List<TransferEntity>,
    val privatePaykitHighestReservedReceiveIndexByAddressType: Map<String, Int>? = null,
    val paykitSdkBackupState: String? = null,
    val watchOnlyAccounts: List<WatchOnlyAccountRecord>? = null,
)

@Serializable
data class MetadataBackupV1(
    val version: Int = 1,
    val createdAt: Long,
    val tagMetadata: List<PreActivityMetadata>,
    val cache: AppCacheData,
    val pubkySession: PubkySessionBackupV1? = null,
    val pubkyContactProfileOverrides: Map<String, PubkyProfileData>? = null,
)

@Serializable
data class PubkySessionBackupV1(
    val kind: PubkySessionBackupKind,
    val sessionSecret: String? = null,
)

@Serializable
enum class PubkySessionBackupKind {
    @SerialName("localSeed")
    LocalSeed,

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
