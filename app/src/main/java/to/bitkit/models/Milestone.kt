package to.bitkit.models

import androidx.annotation.DrawableRes
import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import to.bitkit.R
import to.bitkit.di.json

enum class MilestoneId(val value: String) {
    ProofOfSelf("proof_of_self"),
    FirstStack("first_stack"),
    ChainReaction("chain_reaction"),
    SteadyStacker("steady_stacker"),
    OpenCircuit("open_circuit"),
    ZapAway("zap_away"),
    SignalFound("signal_found"),
    ;

    companion object {
        fun fromValue(value: String): MilestoneId? = entries.firstOrNull { it.value == value }
    }
}

enum class MilestoneCategory {
    Pubky,
    Onchain,
    Lightning,
    ;

    companion object {
        fun fromValue(value: String): MilestoneCategory? =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}

@Immutable
data class Milestone(
    val id: MilestoneId,
    val title: String,
    val description: String,
    val category: MilestoneCategory,
    @DrawableRes val iconRes: Int,
    val progress: Int,
    val target: Int,
    val isUnlocked: Boolean,
    val isPublished: Boolean,
    val unlockedAtMs: Long?,
)

internal enum class MilestoneMetric {
    ProfileConnected,
    OnchainReceived,
    OnchainSent,
    ChannelOpened,
    LightningSent,
    LightningReceived,
}

internal data class MilestoneDefinition(
    val id: MilestoneId,
    val title: String,
    val description: String,
    val category: MilestoneCategory,
    @DrawableRes val iconRes: Int,
    val metric: MilestoneMetric,
    val target: Int,
)

internal object MilestoneDefinitions {
    val all = listOf(
        MilestoneDefinition(
            id = MilestoneId.ProofOfSelf,
            title = "Proof of Self",
            description = "Created or imported a Pubky profile",
            category = MilestoneCategory.Pubky,
            iconRes = R.drawable.ic_user_square,
            metric = MilestoneMetric.ProfileConnected,
            target = 1,
        ),
        MilestoneDefinition(
            id = MilestoneId.FirstStack,
            title = "First Stack",
            description = "Received bitcoin on-chain for the first time",
            category = MilestoneCategory.Onchain,
            iconRes = R.drawable.ic_btc_circle,
            metric = MilestoneMetric.OnchainReceived,
            target = 1,
        ),
        MilestoneDefinition(
            id = MilestoneId.ChainReaction,
            title = "Chain Reaction",
            description = "Sent bitcoin on-chain for the first time",
            category = MilestoneCategory.Onchain,
            iconRes = R.drawable.ic_transfer,
            metric = MilestoneMetric.OnchainSent,
            target = 1,
        ),
        MilestoneDefinition(
            id = MilestoneId.SteadyStacker,
            title = "Steady Stacker",
            description = "Received bitcoin on-chain 5 times",
            category = MilestoneCategory.Onchain,
            iconRes = R.drawable.ic_stack,
            metric = MilestoneMetric.OnchainReceived,
            target = 5,
        ),
        MilestoneDefinition(
            id = MilestoneId.OpenCircuit,
            title = "Open Circuit",
            description = "Opened a Lightning channel for the first time",
            category = MilestoneCategory.Lightning,
            iconRes = R.drawable.ic_lightning,
            metric = MilestoneMetric.ChannelOpened,
            target = 1,
        ),
        MilestoneDefinition(
            id = MilestoneId.ZapAway,
            title = "Zap Away",
            description = "Sent a Lightning payment for the first time",
            category = MilestoneCategory.Lightning,
            iconRes = R.drawable.ic_ln_circle,
            metric = MilestoneMetric.LightningSent,
            target = 1,
        ),
        MilestoneDefinition(
            id = MilestoneId.SignalFound,
            title = "Signal Found",
            description = "Received a Lightning payment for the first time",
            category = MilestoneCategory.Lightning,
            iconRes = R.drawable.ic_lightning_alt,
            metric = MilestoneMetric.LightningReceived,
            target = 1,
        ),
    )

    fun definitionOf(id: MilestoneId): MilestoneDefinition? = all.firstOrNull { it.id == id }
}

@Serializable
data class PublicMilestoneRecord(
    val schemaVersion: Int = 1,
    val app: String = "bitkit",
    val milestoneId: String,
    val title: String,
    val description: String,
    val category: String,
    val unlockedAtMs: Long,
    val progress: Int,
    val target: Int,
) {
    companion object {
        fun fromMilestone(milestone: Milestone): PublicMilestoneRecord? {
            val unlockedAtMs = milestone.unlockedAtMs ?: return null
            return PublicMilestoneRecord(
                milestoneId = milestone.id.value,
                title = milestone.title,
                description = milestone.description,
                category = milestone.category.name.lowercase(),
                unlockedAtMs = unlockedAtMs,
                progress = milestone.progress,
                target = milestone.target,
            )
        }

        fun decode(rawJson: String): PublicMilestoneRecord =
            json.decodeFromString(rawJson)
    }

    fun encode(): ByteArray =
        json.encodeToString(this).toByteArray(Charsets.UTF_8)
}

@Serializable
data class PublicMilestonesIndex(
    val schemaVersion: Int = 1,
    val app: String = "bitkit",
    val milestones: List<PublicMilestoneRecord> = emptyList(),
) {
    companion object {
        fun decode(rawJson: String): PublicMilestonesIndex =
            json.decodeFromString(rawJson)
    }

    fun encode(): ByteArray =
        json.encodeToString(this).toByteArray(Charsets.UTF_8)
}
