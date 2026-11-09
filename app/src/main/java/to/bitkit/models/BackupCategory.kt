package to.bitkit.models

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import kotlinx.serialization.Serializable
import to.bitkit.R

@Serializable
enum class BackupCategory(
    @DrawableRes val icon: Int,
    @StringRes val title: Int,
) {
    LIGHTNING_CONNECTIONS(
        icon = R.drawable.ic_lightning,
        title = R.string.settings__backup__category_connections,
    ),
    BLOCKTANK(
        icon = R.drawable.ic_note,
        title = R.string.settings__backup__category_connection_receipts,
    ),
    ACTIVITY(
        icon = R.drawable.ic_transfer,
        title = R.string.settings__backup__category_transaction_log,
    ),
    WALLET(
        icon = R.drawable.ic_timer_alt,
        title = R.string.settings__backup__category_wallet,
    ),
    USDT_RECOVERY(
        icon = R.drawable.ic_timer_alt,
        title = R.string.usdt__settings_title,
    ),
    SETTINGS(
        icon = R.drawable.ic_settings,
        title = R.string.settings__backup__category_settings,
    ),
    WIDGETS(
        icon = R.drawable.ic_rectangles_two,
        title = R.string.settings__backup__category_widgets,
    ),
    METADATA(
        icon = R.drawable.ic_tag,
        title = R.string.settings__backup__category_tags,
    ),
    // Descoped in v1, will return in v2:
    // PROFILE(
    //     icon = R.drawable.ic_user,
    //     title = R.string.settings__backup__category_profile,
    // ),
    // CONTACTS(
    //     icon = R.drawable.ic_users,
    //     title = R.string.settings__backup__category_contacts,
    // ),
}

/**
 * @property running Backup is currently in progress
 * @property synced Timestamp in millis of last time this backup succeeded
 * @property required Timestamp in millis of last time the data changed
 */
@Serializable
data class BackupItemStatus(
    val running: Boolean = false,
    val synced: Long = 0,
    val required: Long = 0,
) {
    val isRequired: Boolean get() = synced < required
}
