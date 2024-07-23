package to.bitkit.ldk

import org.ldk.enums.ConfirmationTarget
import org.ldk.structs.FeeEstimator

object LdkFeeEstimator : FeeEstimator.FeeEstimatorInterface {
    private const val DEFAULT_FEE = 500
    private const val MAX_ALLOWED_NON_ANCHOR_CHANNEL_REMOTE_FEE = 500
    private const val CHANNEL_CLOSE_MIN = 1000
    private const val ONCHAIN_SWEEP = 1000

    override fun get_est_sat_per_1000_weight(confirmationTarget: ConfirmationTarget?): Int {
        return when (confirmationTarget) {
            ConfirmationTarget.LDKConfirmationTarget_MaxAllowedNonAnchorChannelRemoteFee ->
                MAX_ALLOWED_NON_ANCHOR_CHANNEL_REMOTE_FEE

            ConfirmationTarget.LDKConfirmationTarget_ChannelCloseMinimum ->
                CHANNEL_CLOSE_MIN

            ConfirmationTarget.LDKConfirmationTarget_OnChainSweep ->
                ONCHAIN_SWEEP

            else ->
                DEFAULT_FEE
        }
    }
}