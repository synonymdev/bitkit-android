package to.bitkit.ui.utils

import android.content.Context
import to.bitkit.R
import to.bitkit.models.PubkyAuthRequestError
import to.bitkit.repositories.WatchOnlyAccountError

fun Throwable.localizedPubkyAuthMessage(context: Context): String? {
    var current: Throwable? = this
    while (current != null) {
        val messageResource = when (current) {
            is PubkyAuthRequestError.InvalidUrl -> R.string.profile__auth_error_invalid_url
            PubkyAuthRequestError.MissingBitkitClaim -> R.string.profile__auth_error_missing_claim
            PubkyAuthRequestError.DuplicateBitkitClaim -> R.string.profile__auth_error_duplicate_claim
            is PubkyAuthRequestError.UnsupportedBitkitClaim -> R.string.profile__auth_error_unsupported_claim
            PubkyAuthRequestError.InvalidBitkitClaimCapabilities -> R.string.profile__auth_error_invalid_capabilities
            WatchOnlyAccountError.AuthorizationAccountMissing -> R.string.watch_only_accounts__setup_not_finished
            WatchOnlyAccountError.InvalidAccountName -> R.string.watch_only_accounts__error_invalid_name
            WatchOnlyAccountError.InvalidExtendedPublicKey -> R.string.watch_only_accounts__error_invalid_xpub
            WatchOnlyAccountError.NodeUnavailable -> R.string.watch_only_accounts__error_node_unavailable
            else -> null
        }
        if (messageResource != null) {
            return context.getString(messageResource)
        }
        current = current.cause
    }
    return message
}
