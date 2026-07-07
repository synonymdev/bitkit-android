package to.bitkit.models

import androidx.compose.runtime.Stable
import kotlinx.serialization.Serializable

@Stable
@Serializable
data class BalanceState(
    val totalOnchainSats: ULong = 0uL,
    val channelFundableBalance: ULong = 0uL,
    val totalLightningSats: ULong = 0uL,
    val maxSendLightningSats: ULong = 0uL, // Without account routing fees
    val maxSendOnchainSats: ULong = 0uL,
    val balanceInTransferToSavings: ULong = 0uL,
    val balanceInTransferToSpending: ULong = 0uL,
    val hardwareWallets: List<HwWalletBalance> = emptyList(),
) {
    val totalSats
        get() = totalOnchainSats
            .safe()
            .plus(totalLightningSats.safe())
            .safe()
            .plus(balanceInTransferToSavings.safe())
            .safe()
            .plus(balanceInTransferToSpending.safe())

    val totalHardwareSats get() = hardwareWallets.fold(0uL) { acc, wallet -> acc.safe() + wallet.sats.safe() }

    val totalWithHardwareSats get() = totalSats.safe() + totalHardwareSats.safe()
}
