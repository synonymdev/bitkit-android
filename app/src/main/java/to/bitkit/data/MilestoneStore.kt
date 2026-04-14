package to.bitkit.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.dataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import to.bitkit.data.serializers.MilestoneStoreSerializer
import javax.inject.Inject
import javax.inject.Singleton

private val Context.milestoneDataStore: DataStore<MilestoneStoreData> by dataStore(
    fileName = "milestones.json",
    serializer = MilestoneStoreSerializer,
)

@Singleton
class MilestoneStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val store = context.milestoneDataStore

    val data: Flow<MilestoneStoreData> = store.data

    suspend fun update(transform: (MilestoneStoreData) -> MilestoneStoreData) {
        store.updateData(transform)
    }

    suspend fun reset() {
        store.updateData { MilestoneStoreData() }
    }
}

@Serializable
data class MilestoneStoreData(
    val hasConnectedProfile: Boolean = false,
    val onchainReceivedTxids: List<String> = emptyList(),
    val onchainSentTxids: List<String> = emptyList(),
    val openedChannelIds: List<String> = emptyList(),
    val lightningSentPaymentHashes: List<String> = emptyList(),
    val lightningReceivedPaymentHashes: List<String> = emptyList(),
    val unlockedMilestones: List<UnlockedMilestoneData> = emptyList(),
)

@Serializable
data class UnlockedMilestoneData(
    val id: String,
    val unlockedAtMs: Long,
)
