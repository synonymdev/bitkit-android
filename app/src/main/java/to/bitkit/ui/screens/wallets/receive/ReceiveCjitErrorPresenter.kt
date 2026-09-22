package to.bitkit.ui.screens.wallets.receive

import android.content.Context
import to.bitkit.R
import to.bitkit.models.Toast
import to.bitkit.utils.ServiceError
import to.bitkit.viewmodels.AppViewModel

internal fun AppViewModel.toastReceiveCjitError(
    context: Context,
    error: Throwable,
): Boolean {
    val title: String
    val description: String
    when (error) {
        is ServiceError.CjitQuoteInvalid -> {
            title = context.getString(R.string.wallet__receive_cjit_error_invalid__title)
            description = context.getString(R.string.wallet__receive_cjit_error_invalid__description)
        }
        is ServiceError.NodeCapacityUnavailable -> {
            title = context.getString(R.string.wallet__receive_cjit_error_node_capacity__title)
            description = context.getString(R.string.wallet__receive_cjit_error_node_capacity__description)
        }
        else -> return false
    }

    toast(
        type = Toast.ToastType.ERROR,
        title = title,
        description = description,
    )
    return true
}
