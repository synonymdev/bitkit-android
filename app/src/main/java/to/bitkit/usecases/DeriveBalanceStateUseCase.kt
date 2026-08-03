package to.bitkit.usecases

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.lightningdevkit.ldknode.BalanceDetails
import org.lightningdevkit.ldknode.BalanceSource
import org.lightningdevkit.ldknode.ChannelDetails
import org.lightningdevkit.ldknode.LightningBalance
import to.bitkit.data.SettingsStore
import to.bitkit.data.SpendingBackend
import to.bitkit.data.entities.TransferEntity
import to.bitkit.di.BgDispatcher
import to.bitkit.env.Defaults
import to.bitkit.ext.amountSats
import to.bitkit.ext.channelId
import to.bitkit.ext.totalNextOutboundHtlcLimitSats
import to.bitkit.models.BalanceState
import to.bitkit.models.TransferType
import to.bitkit.models.safe
import to.bitkit.models.toBalance
import to.bitkit.repositories.BarkRepo
import to.bitkit.repositories.HwWalletRepo
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.TransferRepo
import to.bitkit.utils.Logger
import to.bitkit.utils.jsonLogOf
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeriveBalanceStateUseCase @Inject constructor(
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val lightningRepo: LightningRepo,
    private val barkRepo: BarkRepo,
    private val transferRepo: TransferRepo,
    private val settingsStore: SettingsStore,
    private val hwWalletRepo: HwWalletRepo,
) {
    suspend operator fun invoke(): Result<BalanceState> = withContext(bgDispatcher) {
        runCatching {
            val balanceDetails = lightningRepo.getBalancesAsync().getOrThrow()
            val channels = lightningRepo.getChannels().orEmpty()
            val activeTransfers = transferRepo.activeTransfers.first()

            val paidOrdersSats = getOrderPaymentsSats(activeTransfers, channels, balanceDetails)
            val pendingChannelsSats = getPendingChannelsSats(activeTransfers, channels, balanceDetails)

            val toSavingsAmount = getTransferToSavingsSats(activeTransfers, channels, balanceDetails)
            val coopCloseSavingsSats = getCoopCloseTransferSats(activeTransfers, channels, balanceDetails)
            val lingeringCoopCloseSats = getLingeringCoopCloseSats(activeTransfers, channels, balanceDetails)
            val toSpendingAmount = paidOrdersSats.safe() + pendingChannelsSats.safe()
            val orderPaymentsOnchainToSubtract = getOrderPaymentOnchainToSubtract(
                activeTransfers = activeTransfers,
                currentOnchainSats = balanceDetails.totalOnchainBalanceSats,
            )

            val totalOnchainSats = balanceDetails.totalOnchainBalanceSats.safe() - orderPaymentsOnchainToSubtract.safe()
            val channelFundableBalance = getMaxChannelFundableAmount(lightningRepo.getChannelFundableBalance())
            val afterPendingChannels = balanceDetails.totalLightningBalanceSats.safe() - pendingChannelsSats.safe()
            val afterClosingChannels = afterPendingChannels.safe() - toSavingsAmount.safe()
            val totalLightningSats = afterClosingChannels.safe() - lingeringCoopCloseSats.safe()

            // In bark mode the spending balance comes from Ark VTXOs instead of channels; savings
            // stays on the ldk-node onchain wallet, so only the lightning half is swapped out.
            val isBarkBackend = settingsStore.data.first().spendingBackend == SpendingBackend.BARK
            val barkState = barkRepo.barkState.value

            val balanceState = BalanceState(
                totalOnchainSats = totalOnchainSats,
                channelFundableBalance = if (isBarkBackend) 0uL else channelFundableBalance,
                totalLightningSats = if (isBarkBackend) barkState.spendableSats else totalLightningSats,
                maxSendLightningSats = if (isBarkBackend) {
                    barkState.spendableSats
                } else {
                    lightningRepo.getChannels().totalNextOutboundHtlcLimitSats()
                },
                maxSendOnchainSats = getMaxSendAmount(balanceDetails),
                balanceInTransferToSavings = if (isBarkBackend) {
                    barkState.pendingExitSats
                } else {
                    toSavingsAmount.safe() - coopCloseSavingsSats.safe()
                },
                balanceInTransferToSpending = if (isBarkBackend) {
                    barkState.pendingIncomingSats
                } else {
                    toSpendingAmount
                },
                hardwareWallets = hwWalletRepo.wallets.value.map { it.toBalance() },
            )

            val height = lightningRepo.lightningState.value.block()?.height
            Logger.verbose("Active transfers at block height=$height: ${jsonLogOf(activeTransfers)}", context = TAG)
            Logger.verbose("Balances in ldk-node at block height=$height: ${jsonLogOf(balanceDetails)}", context = TAG)
            Logger.verbose("Balances in state at block height=$height: ${jsonLogOf(balanceState)}", context = TAG)

            return@runCatching balanceState
        }
    }

    private suspend fun getOrderPaymentsSats(
        transfers: List<TransferEntity>,
        channels: List<ChannelDetails>,
        balances: BalanceDetails,
    ): ULong {
        var amount = 0uL
        val paidOrders = transfers.filter { it.type.isToSpending() && it.lspOrderId != null }

        for (transfer in paidOrders) {
            val channelId = transferRepo.resolveChannelIdForTransfer(transfer, channels)
            val channelBalance = channelId?.let { id ->
                balances.lightningBalances.find { it.channelId() == id }
            }
            if (channelBalance == null) {
                amount = amount.safe() + transfer.amountSats.toULong().safe()
            }
        }

        return amount
    }

    private fun getOrderPaymentOnchainToSubtract(
        activeTransfers: List<TransferEntity>,
        currentOnchainSats: ULong,
    ): ULong {
        var amount = 0uL
        val orderPayments = activeTransfers.filter {
            it.type.isToSpending() && it.fundingTxId != null && it.lspOrderId != null
        }

        for (transfer in orderPayments) {
            val txTotalSats = transfer.txTotalSats?.takeIf { it > 0 }?.toULong()
            val preTransferOnchainSats = transfer.preTransferOnchainSats?.takeIf { it > 0 }?.toULong()

            if (txTotalSats != null && preTransferOnchainSats != null && currentOnchainSats >= preTransferOnchainSats) {
                amount = amount.safe() + txTotalSats.safe()
            }
        }

        return amount
    }

    private suspend fun getPendingChannelsSats(
        transfers: List<TransferEntity>,
        channels: List<ChannelDetails>,
        balances: BalanceDetails,
    ): ULong {
        var amount = 0uL
        val pendingTransfers = transfers.filter { it.type.isToSpending() }

        for (transfer in pendingTransfers) {
            val channelId = transferRepo.resolveChannelIdForTransfer(transfer, channels)
            val channel = channels.find { it.channelId == channelId }
            if (channel != null && !channel.isChannelReady) {
                val channelBalance = balances.lightningBalances.find { it.channelId() == channel.channelId }
                amount = amount.safe() + (channelBalance?.amountSats() ?: 0uL).safe()
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
            toSavingsAmount = toSavingsAmount.safe() + (channelBalance?.amountSats() ?: 0uL).safe()
        }

        return toSavingsAmount
    }

    private suspend fun getMaxChannelFundableAmount(fundableBalance: ULong): ULong {
        if (fundableBalance == 0uL) return 0u

        val fallback = (fundableBalance.toDouble() * FALLBACK_FEE_PERCENT).toULong()
        val speed = settingsStore.data.first().defaultTransactionSpeed
        val fee = lightningRepo.estimateSendAllFee(
            speed = speed,
        ).onFailure {
            Logger.debug("Could not calculate channel funding fee, using fallback of: $fallback", context = TAG)
        }.getOrDefault(fallback)

        return fundableBalance.safe() - fee.safe()
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
            amount = amount.safe() + (channelBalance?.amountSats() ?: 0uL).safe()
        }
        return amount
    }

    private suspend fun getLingeringCoopCloseSats(
        transfers: List<TransferEntity>,
        channels: List<ChannelDetails>,
        balanceDetails: BalanceDetails,
    ): ULong {
        val channelIds = channels.map { it.channelId }.toSet()
        val transferChannelIds = mutableSetOf<String>()
        for (transfer in transfers.filter { it.type.isToSavings() }) {
            transferRepo.resolveChannelIdForTransfer(transfer, channels)?.let { transferChannelIds.add(it) }
        }

        var amount = 0uL
        val claimableBalances = balanceDetails.lightningBalances
            .filterIsInstance<LightningBalance.ClaimableAwaitingConfirmations>()
        for (balance in claimableBalances) {
            val isLingeringCoopClose = balance.source == BalanceSource.COOP_CLOSE &&
                balance.channelId !in channelIds &&
                balance.channelId !in transferChannelIds

            if (isLingeringCoopClose) {
                amount = amount.safe() + balance.amountSatoshis.safe()
            }
        }
        return amount
    }

    private suspend fun getMaxSendAmount(balanceDetails: BalanceDetails): ULong {
        val spendableOnchainSats = balanceDetails.spendableOnchainBalanceSats
        if (spendableOnchainSats == 0uL) return 0u

        val fallback = (spendableOnchainSats.toDouble() * FALLBACK_FEE_PERCENT).toULong()
        val speed = settingsStore.data.first().defaultTransactionSpeed

        val fee = lightningRepo.estimateSendAllFee(
            speed = speed,
        ).onFailure {
            Logger.debug("Could not calculate max send amount, using fallback of: $fallback", context = TAG)
        }.getOrDefault(fallback)

        return spendableOnchainSats.safe() - fee.safe()
    }

    companion object {
        const val TAG = "DeriveBalanceStateUseCase"
        const val FALLBACK_FEE_PERCENT = Defaults.fallbackFeePercent
    }
}
