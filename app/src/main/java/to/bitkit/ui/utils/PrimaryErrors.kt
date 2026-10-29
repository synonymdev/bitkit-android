package to.bitkit.ui.utils

import androidx.annotation.StringRes
import to.bitkit.R
import to.bitkit.models.PrimaryNodeError
import to.bitkit.repositories.PrimaryReceiveError
import to.bitkit.repositories.PrimaryWalletBusyError
import to.bitkit.repositories.toPrimaryReceiveError

/** The message shown to the user for a primary node failure. */
@StringRes
fun Throwable.primaryErrorMessageRes(): Int = when (toPrimaryReceiveError()) {
    is PrimaryReceiveError.InsufficientFunds -> R.string.primary_node__error_insufficient_funds
    is PrimaryReceiveError.Rejected -> R.string.primary_node__error_rejected
    is PrimaryReceiveError.TimedOut -> R.string.primary_node__error_timed_out
    is PrimaryReceiveError.AmountTooSmall -> R.string.primary_node__error_amount_too_small
    is PrimaryReceiveError.Offline -> R.string.primary_node__error_offline
    is PrimaryNodeError.InvalidInput -> R.string.primary_node__invalid
    is PrimaryNodeError.AddressUnknown -> R.string.primary_node__address_required
    is PrimaryNodeError.ConnectionInUse -> R.string.primary_node__connection_in_use
    is PrimaryNodeError.NotConfigured -> R.string.primary_node__not_configured
    is PrimaryWalletBusyError -> R.string.primary_node__busy
    else -> R.string.primary_node__error_generic
}
