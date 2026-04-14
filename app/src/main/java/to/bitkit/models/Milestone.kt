package to.bitkit.models

import androidx.annotation.DrawableRes
import androidx.compose.runtime.Immutable
import to.bitkit.R

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
            description = "Created or imported your Pubky profile in Bitkit",
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
            description = "Sent your first on-chain transaction",
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
            description = "Opened your first Lightning channel",
            category = MilestoneCategory.Lightning,
            iconRes = R.drawable.ic_lightning,
            metric = MilestoneMetric.ChannelOpened,
            target = 1,
        ),
        MilestoneDefinition(
            id = MilestoneId.ZapAway,
            title = "Zap Away",
            description = "Sent your first Lightning payment",
            category = MilestoneCategory.Lightning,
            iconRes = R.drawable.ic_ln_circle,
            metric = MilestoneMetric.LightningSent,
            target = 1,
        ),
        MilestoneDefinition(
            id = MilestoneId.SignalFound,
            title = "Signal Found",
            description = "Received your first Lightning payment",
            category = MilestoneCategory.Lightning,
            iconRes = R.drawable.ic_lightning_alt,
            metric = MilestoneMetric.LightningReceived,
            target = 1,
        ),
    )
}
