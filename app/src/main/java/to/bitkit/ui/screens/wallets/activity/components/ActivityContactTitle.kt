package to.bitkit.ui.screens.wallets.activity.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.synonym.bitkitcore.Activity
import kotlinx.collections.immutable.ImmutableList
import to.bitkit.R
import to.bitkit.ext.contact
import to.bitkit.ext.isSent
import to.bitkit.models.PubkyProfile
import to.bitkit.models.PubkyPublicKeyFormat

@Composable
fun contactActivityTitle(activity: Activity, contacts: ImmutableList<PubkyProfile>): String? {
    val contactName = remember(activity, contacts) {
        val contact = activity.contact() ?: return@remember null
        contacts.firstOrNull { PubkyPublicKeyFormat.matches(it.publicKey, contact) }?.name
    } ?: return null

    val titleRes = if (activity.isSent()) {
        R.string.contacts__activity_sent_to
    } else {
        R.string.contacts__activity_received_from
    }
    return stringResource(titleRes, contactName)
}
