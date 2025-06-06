package to.bitkit.models

import to.bitkit.R

/**
 * @property running In progress
 * @property synced Timestamp in ms of last time this backup was synced
 * @property required Timestamp in ms of last time this backup was required
 */
data class BackupItemStatus(
    val running: Boolean = false,
    val synced: Long = 0L,
    val required: Long = 0L,
)

enum class BackupCategory {
    LIGHTNING_CONNECTIONS,
    BLOCKTANK,
    LDK_ACTIVITY,
    WALLET,
    SETTINGS,
    WIDGETS,
    METADATA,
    SLASHTAGS,
}

fun BackupCategory.uiIconRes(): Int {
    return when (this) {
        BackupCategory.LIGHTNING_CONNECTIONS -> R.drawable.ic_lightning
        BackupCategory.BLOCKTANK -> R.drawable.ic_note
        BackupCategory.LDK_ACTIVITY -> R.drawable.ic_transfer
        BackupCategory.WALLET -> R.drawable.ic_timer_alt
        BackupCategory.SETTINGS -> R.drawable.ic_settings
        BackupCategory.WIDGETS -> R.drawable.ic_rectangles_two
        BackupCategory.METADATA -> R.drawable.ic_tag
        BackupCategory.SLASHTAGS -> R.drawable.ic_users
    }
}

fun BackupCategory.uiTitleRes(): Int {
    return when (this) {
        BackupCategory.LIGHTNING_CONNECTIONS -> R.string.settings__backup__category_connections
        BackupCategory.BLOCKTANK -> R.string.settings__backup__category_connection_receipts
        BackupCategory.LDK_ACTIVITY -> R.string.settings__backup__category_transaction_log
        BackupCategory.WALLET -> R.string.settings__backup__category_wallet
        BackupCategory.SETTINGS -> R.string.settings__backup__category_settings
        BackupCategory.WIDGETS -> R.string.settings__backup__category_widgets
        BackupCategory.METADATA -> R.string.settings__backup__category_tags
        BackupCategory.SLASHTAGS -> R.string.settings__backup__category_contacts
    }
}
