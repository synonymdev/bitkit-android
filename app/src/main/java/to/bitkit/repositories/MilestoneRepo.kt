package to.bitkit.repositories

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import to.bitkit.data.MilestoneStore
import to.bitkit.data.MilestoneStoreData
import to.bitkit.data.UnlockedMilestoneData
import to.bitkit.di.IoDispatcher
import to.bitkit.models.Milestone
import to.bitkit.models.MilestoneDefinition
import to.bitkit.models.MilestoneDefinitions
import to.bitkit.models.MilestoneId
import to.bitkit.models.MilestoneMetric
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MilestoneRepo @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val milestoneStore: MilestoneStore,
) {
    private val scope = CoroutineScope(ioDispatcher + SupervisorJob())

    val milestones: StateFlow<ImmutableList<Milestone>> = milestoneStore.data
        .map { data -> MilestoneDefinitions.all.map { it.toMilestone(data) }.toImmutableList() }
        .stateIn(
            scope,
            SharingStarted.Eagerly,
            MilestoneDefinitions.all.map { it.toMilestone(MilestoneStoreData()) }.toImmutableList(),
        )

    val visibleMilestones: StateFlow<ImmutableList<Milestone>> = milestones
        .map { all ->
            all.filter { milestone ->
                milestone.isUnlocked || milestone.progress > 0
            }
                .sortedWith(
                    compareByDescending<Milestone> { it.isUnlocked }
                        .thenByDescending { it.unlockedAtMs ?: 0L }
                        .thenByDescending { it.progress }
                )
                .toImmutableList()
        }
        .stateIn(scope, SharingStarted.Eagerly, emptyList<Milestone>().toImmutableList())

    fun getMilestone(id: MilestoneId): Milestone? = milestones.value.firstOrNull { it.id == id }

    suspend fun recordProfileConnected(): ImmutableList<Milestone> = withContext(ioDispatcher) {
        updateStore { current ->
            current.copy(hasConnectedProfile = true).unlockEligibleMilestones()
        }
    }

    suspend fun recordOnchainReceived(txid: String): ImmutableList<Milestone> = withContext(ioDispatcher) {
        updateStore { current ->
            if (txid in current.onchainReceivedTxids) {
                current
            } else {
                current.copy(onchainReceivedTxids = current.onchainReceivedTxids + txid)
                    .unlockEligibleMilestones()
            }
        }
    }

    suspend fun recordOnchainSent(txid: String): ImmutableList<Milestone> = withContext(ioDispatcher) {
        updateStore { current ->
            if (txid in current.onchainSentTxids) {
                current
            } else {
                current.copy(onchainSentTxids = current.onchainSentTxids + txid)
                    .unlockEligibleMilestones()
            }
        }
    }

    suspend fun recordChannelOpened(channelId: String): ImmutableList<Milestone> = withContext(ioDispatcher) {
        updateStore { current ->
            if (channelId in current.openedChannelIds) {
                current
            } else {
                current.copy(openedChannelIds = current.openedChannelIds + channelId)
                    .unlockEligibleMilestones()
            }
        }
    }

    suspend fun recordLightningSent(paymentHash: String): ImmutableList<Milestone> = withContext(ioDispatcher) {
        updateStore { current ->
            if (paymentHash in current.lightningSentPaymentHashes) {
                current
            } else {
                current.copy(lightningSentPaymentHashes = current.lightningSentPaymentHashes + paymentHash)
                    .unlockEligibleMilestones()
            }
        }
    }

    suspend fun recordLightningReceived(paymentHash: String): ImmutableList<Milestone> = withContext(ioDispatcher) {
        updateStore { current ->
            if (paymentHash in current.lightningReceivedPaymentHashes) {
                current
            } else {
                current.copy(lightningReceivedPaymentHashes = current.lightningReceivedPaymentHashes + paymentHash)
                    .unlockEligibleMilestones()
            }
        }
    }

    suspend fun reset() = withContext(ioDispatcher) {
        milestoneStore.reset()
    }

    private suspend fun updateStore(
        transform: (MilestoneStoreData) -> MilestoneStoreData,
    ): ImmutableList<Milestone> {
        var unlockedIds = emptyList<MilestoneId>()

        milestoneStore.update { current ->
            val next = transform(current)
            val currentIds = current.unlockedMilestoneIds()
            val nextIds = next.unlockedMilestoneIds()
            unlockedIds = nextIds.filterNot { it in currentIds }
            next
        }

        return milestones.value.filter { it.id in unlockedIds }.toImmutableList()
    }

    private fun MilestoneDefinition.toMilestone(data: MilestoneStoreData): Milestone {
        val progress = metric.countFor(data)
        val unlocked = data.unlockedMilestoneById(id) != null

        return Milestone(
            id = id,
            title = title,
            description = description,
            category = category,
            iconRes = iconRes,
            progress = progress.coerceAtMost(target),
            target = target,
            isUnlocked = unlocked,
            unlockedAtMs = data.unlockedMilestoneById(id)?.unlockedAtMs,
        )
    }

    private fun MilestoneMetric.countFor(data: MilestoneStoreData): Int = when (this) {
        MilestoneMetric.ProfileConnected -> if (data.hasConnectedProfile) 1 else 0
        MilestoneMetric.OnchainReceived -> data.onchainReceivedTxids.size
        MilestoneMetric.OnchainSent -> data.onchainSentTxids.size
        MilestoneMetric.ChannelOpened -> data.openedChannelIds.size
        MilestoneMetric.LightningSent -> data.lightningSentPaymentHashes.size
        MilestoneMetric.LightningReceived -> data.lightningReceivedPaymentHashes.size
    }

    private fun MilestoneStoreData.unlockEligibleMilestones(): MilestoneStoreData {
        val currentIds = unlockedMilestoneIds().toMutableSet()
        if (MilestoneDefinitions.all.none { it.metric.countFor(this) >= it.target && it.id !in currentIds }) {
            return this
        }

        val newUnlocks = MilestoneDefinitions.all
            .filter { definition ->
                definition.metric.countFor(this) >= definition.target && definition.id !in currentIds
            }
            .map { definition ->
                currentIds += definition.id
                UnlockedMilestoneData(id = definition.id.value, unlockedAtMs = System.currentTimeMillis())
            }

        return copy(unlockedMilestones = unlockedMilestones + newUnlocks)
    }

    private fun MilestoneStoreData.unlockedMilestoneById(id: MilestoneId): UnlockedMilestoneData? =
        unlockedMilestones.firstOrNull { it.id == id.value }

    private fun MilestoneStoreData.unlockedMilestoneIds(): List<MilestoneId> =
        unlockedMilestones.mapNotNull { unlocked ->
            MilestoneId.entries.firstOrNull { it.value == unlocked.id }
        }
}
