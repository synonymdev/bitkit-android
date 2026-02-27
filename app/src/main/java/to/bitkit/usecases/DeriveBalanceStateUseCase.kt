package to.bitkit.usecases

import kotlinx.coroutines.flow.first
import org.lightningdevkit.ldknode.BalanceDetails
import org.lightningdevkit.ldknode.ChannelDetails
import to.bitkit.data.SettingsStore
import to.bitkit.data.entities.TransferEntity
import to.bitkit.ext.amountSats
import to.bitkit.ext.channelId
import to.bitkit.ext.totalNextOutboundHtlcLimitSats
import to.bitkit.models.BalanceState
import to.bitkit.models.TransferType
import to.bitkit.models.safe
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.TransferRepo
import to.bitkit.utils.Logger
import to.bitkit.utils.jsonLogOf
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeriveBalanceStateUseCase @Inject constructor(
    private val lightningRepo: LightningRepo,
    private val transferRepo: TransferRepo,
    private val settingsStore: SettingsStore,
) {
    suspend operator fun invoke(): Result<BalanceState> = runCatching {
        val balanceDetails = lightningRepo.getBalancesAsync().getOrThrow()
        val channels = lightningRepo.getChannels().orEmpty()
        val activeTransfers = transferRepo.activeTransfers.first()

        val paidOrdersSats = getOrderPaymentsSats(activeTransfers)
        val pendingChannelsSats = getPendingChannelsSats(activeTransfers, channels, balanceDetails)

        val toSavingsAmount = getTransferToSavingsSats(activeTransfers, channels, balanceDetails)
        val coopCloseSavingsSats = getCoopCloseTransferSats(activeTransfers, channels, balanceDetails)
        val toSpendingAmount = paidOrdersSats.safe() + pendingChannelsSats.safe()

        val totalOnchainSats = balanceDetails.totalOnchainBalanceSats
        val afterPendingChannels = balanceDetails.totalLightningBalanceSats.safe() - pendingChannelsSats.safe()
        val totalLightningSats = afterPendingChannels.safe() - toSavingsAmount.safe()

        val balanceState = BalanceState(
            totalOnchainSats = totalOnchainSats,
            totalLightningSats = totalLightningSats,
            maxSendLightningSats = lightningRepo.getChannels().totalNextOutboundHtlcLimitSats(),
            maxSendOnchainSats = getMaxSendAmount(balanceDetails),
            balanceInTransferToSavings = toSavingsAmount.safe() - coopCloseSavingsSats.safe(),
            balanceInTransferToSpending = toSpendingAmount,
        )

        val height = lightningRepo.lightningState.value.block()?.height
        Logger.verbose("Active transfers at block height=$height: ${jsonLogOf(activeTransfers)}", context = TAG)
        Logger.verbose("Balances in ldk-node at block height=$height: ${jsonLogOf(balanceDetails)}", context = TAG)
        Logger.verbose("Balances in state at block height=$height: ${jsonLogOf(balanceState)}", context = TAG)

        return@runCatching balanceState
    }

    private fun getOrderPaymentsSats(transfers: List<TransferEntity>): ULong {
        return transfers
            .filter { it.type.isToSpending() && it.lspOrderId != null }
            .sumOf { it.amountSats.toULong() }
    }

    private fun getPendingChannelsSats(
        transfers: List<TransferEntity>,
        channels: List<ChannelDetails>,
        balances: BalanceDetails,
    ): ULong {
        var amount = 0uL
        val pendingTransfers = transfers.filter { it.type.isToSpending() && it.channelId != null }

        for (transfer in pendingTransfers) {
            val channel = channels.find { it.channelId == transfer.channelId }
            if (channel != null && !channel.isChannelReady) {
                val channelBalance = balances.lightningBalances.find { it.channelId() == channel.channelId }
                amount += channelBalance?.amountSats() ?: 0u
            }
        }

        return amount
    }

    private suspend fun getTransferToSavingsSats(
        transfers: List<TransferEntity>,
        channels: List<ChannelDetails>,
        balanceDetails: BalanceDetails,
    ): ULong {
        var toSavingsAmount = 0uL
        val toSavings = transfers.filter { it.type.isToSavings() }

        for (transfer in toSavings) {
            val channelId = transferRepo.resolveChannelIdForTransfer(transfer, channels)
            val channelBalance = balanceDetails.lightningBalances.find { it.channelId() == channelId }
            toSavingsAmount += channelBalance?.amountSats() ?: 0u
        }

        return toSavingsAmount
    }

    private suspend fun getCoopCloseTransferSats(
        transfers: List<TransferEntity>,
        channels: List<ChannelDetails>,
        balanceDetails: BalanceDetails,
    ): ULong {
        var amount = 0uL
        for (transfer in transfers.filter { it.type == TransferType.COOP_CLOSE }) {
            val channelId = transferRepo.resolveChannelIdForTransfer(transfer, channels)
            val channelBalance = balanceDetails.lightningBalances.find { it.channelId() == channelId }
            amount += channelBalance?.amountSats() ?: 0u
        }
        return amount
    }

    private suspend fun getMaxSendAmount(balanceDetails: BalanceDetails): ULong {
        val spendableOnchainSats = balanceDetails.spendableOnchainBalanceSats
        if (spendableOnchainSats == 0uL) return 0u

        val fallback = (spendableOnchainSats.toDouble() * FALLBACK_FEE_PERCENT).toULong()
        val speed = settingsStore.data.first().defaultTransactionSpeed

        val fee = lightningRepo.calculateTotalFee(
            amountSats = spendableOnchainSats,
            speed = speed,
            utxosToSpend = lightningRepo.listSpendableOutputs().getOrNull()
        ).onFailure {
            Logger.debug("Could not calculate max send amount, using fallback of: $fallback", context = TAG)
        }.getOrDefault(fallback)

        return spendableOnchainSats.safe() - fee.safe()
    }

    companion object {
        const val TAG = "DeriveBalanceStateUseCase"
        const val FALLBACK_FEE_PERCENT = 0.1
    }
}
