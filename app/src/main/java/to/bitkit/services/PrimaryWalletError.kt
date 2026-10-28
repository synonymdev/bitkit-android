package to.bitkit.services

import android.content.Context
import androidx.annotation.StringRes
import org.lightningdevkit.ldknode.NodeException
import to.bitkit.R
import to.bitkit.utils.AppError
import to.bitkit.utils.LdkError

sealed class PrimaryWalletError(
    @StringRes val messageRes: Int,
    message: String? = null,
    cause: Throwable? = null,
) : AppError(message, cause) {
    class NotReady : PrimaryWalletError(R.string.primary_node__not_ready)
    class PaymentPending : PrimaryWalletError(R.string.wallet_primary__pending)
    class InsufficientFunds : PrimaryWalletError(R.string.primary_node__insufficient)
    class PaymentFailed : PrimaryWalletError(R.string.primary_node__payment_failed)
    class StateUnreadable(cause: Throwable) : PrimaryWalletError(R.string.wallet_primary__pending, cause = cause)
}

fun Throwable.primaryWalletMessage(context: Context): String? =
    generateSequence(this) { it.cause }.take(PRIMARY_ERROR_CAUSE_DEPTH)
        .filterIsInstance<PrimaryWalletError>().firstOrNull()?.let { context.getString(it.messageRes) }

fun Throwable.primaryReceiveMessage(context: Context): String? =
    generateSequence(this) { it.cause }.take(PRIMARY_ERROR_CAUSE_DEPTH)
        .mapNotNull {
            when (it) {
                is NodeException -> it
                is LdkError -> it.nodeExceptionOrNull()
                else -> null
            }
        }
        .firstNotNullOfOrNull { it.primaryReceiveMessageRes() }
        ?.let(context::getString)

@StringRes
private fun NodeException.primaryReceiveMessageRes(): Int? = when (this) {
    is NodeException.LiquiditySourceInsufficientFunds -> R.string.wallet_primary__receive_insufficient_funds
    is NodeException.LiquidityRequestRejected -> R.string.wallet_primary__receive_rejected
    is NodeException.LiquidityRequestTimedOut -> R.string.wallet_primary__receive_timed_out
    is NodeException.LiquidityReceiveAmountTooSmall -> R.string.wallet_primary__receive_amount_too_small
    else -> null
}

/** Maximum cause depth inspected when locating a primary wallet failure. */
private const val PRIMARY_ERROR_CAUSE_DEPTH = 8
