package to.bitkit.ui.utils

import android.content.Context
import com.synonym.paykit.PaykitException
import to.bitkit.R
import to.bitkit.ext.isPaykitSignupCodeRejected
import to.bitkit.ext.isPaykitStateFromNewerVersion
import to.bitkit.models.PubkyAuthRequestError
import to.bitkit.repositories.WatchOnlyAccountError

fun Throwable.localizedPubkyAuthMessage(context: Context): String? {
    var current: Throwable? = this
    while (current != null) {
        val messageResource = current.pubkyAuthMessageResource()
        if (messageResource != null) {
            return context.getString(messageResource)
        }
        current = current.cause
    }
    return context.getString(R.string.common__error_body)
}

/** Shared Paykit state saved by a newer Bitkit is unreadable until an update, so it replaces the raw storage error. */
fun Throwable.profileErrorDescription(context: Context): String? =
    if (isPaykitStateFromNewerVersion()) context.getString(R.string.profile__update_required) else message

private fun Throwable.pubkyAuthMessageResource() = when (this) {
    is PubkyAuthRequestError.InvalidUrl -> R.string.profile__auth_error_invalid_url
    PubkyAuthRequestError.InvalidPaymentDetails -> R.string.profile__auth_approval_usdt_unavailable
    PubkyAuthRequestError.RequesterChanged -> R.string.profile__auth_error_invalid_url
    PubkyAuthRequestError.MissingBitkitClaim -> R.string.profile__auth_error_missing_claim
    PubkyAuthRequestError.DuplicateBitkitClaim -> R.string.profile__auth_error_duplicate_claim
    is PubkyAuthRequestError.UnsupportedBitkitClaim -> R.string.profile__auth_error_unsupported_claim
    PubkyAuthRequestError.InvalidBitkitClaimCapabilities -> R.string.profile__auth_error_invalid_capabilities
    is PaykitException.Identity if isPaykitSignupCodeRejected() -> R.string.profile__auth_error_signup_code_rejected
    is PaykitException.Transport -> R.string.pubky_auth__identity_unavailable_desc
    is WatchOnlyAccountError.AuthorizationAccountMissing -> R.string.watch_only_accounts__setup_not_finished
    is WatchOnlyAccountError.InvalidAccountName -> R.string.watch_only_accounts__error_invalid_name
    is WatchOnlyAccountError.InvalidExtendedPublicKey -> R.string.watch_only_accounts__error_invalid_xpub
    else -> null
}
