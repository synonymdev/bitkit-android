package to.bitkit.models.widget

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import to.bitkit.R

const val MAX_BLOCKS_FIELDS = 4

enum class BlocksWidgetField(
    @DrawableRes val icon: Int,
    @StringRes val labelRes: Int,
    val testTag: String,
) {
    BLOCK(R.drawable.ic_cube, R.string.widgets__blocks__field__block, "block"),
    TIME(R.drawable.ic_clock, R.string.widgets__blocks__field__time, "time"),
    DATE(R.drawable.ic_calendar, R.string.widgets__blocks__field__date, "date"),
    TRANSACTIONS(R.drawable.ic_transfer, R.string.widgets__blocks__field__transactions, "transactions"),
    SIZE(R.drawable.ic_file_text, R.string.widgets__blocks__field__size, "size"),
    FEES(R.drawable.ic_coins, R.string.widgets__blocks__field__fees, "fees");

    fun value(block: BlockModel): String = when (this) {
        BLOCK -> block.height
        TIME -> block.time
        DATE -> block.date
        TRANSACTIONS -> block.transactionCount
        SIZE -> block.size
        FEES -> block.fees
    }

    fun isEnabled(prefs: BlocksPreferences): Boolean = when (this) {
        BLOCK -> prefs.showBlock
        TIME -> prefs.showTime
        DATE -> prefs.showDate
        TRANSACTIONS -> prefs.showTransactions
        SIZE -> prefs.showSize
        FEES -> prefs.showFees
    }
}

fun BlocksPreferences.enabledFields(): List<BlocksWidgetField> =
    BlocksWidgetField.entries.filter { it.isEnabled(this) }

/**
 * Toggles [field], enforcing the [MAX_BLOCKS_FIELDS] cap: an already-enabled field can always be
 * turned off, but a disabled field cannot be turned on once the limit is reached (no-op).
 */
fun BlocksPreferences.toggleField(field: BlocksWidgetField): BlocksPreferences {
    if (!field.isEnabled(this) && enabledFields().size >= MAX_BLOCKS_FIELDS) return this
    return when (field) {
        BlocksWidgetField.BLOCK -> copy(showBlock = !showBlock)
        BlocksWidgetField.TIME -> copy(showTime = !showTime)
        BlocksWidgetField.DATE -> copy(showDate = !showDate)
        BlocksWidgetField.TRANSACTIONS -> copy(showTransactions = !showTransactions)
        BlocksWidgetField.SIZE -> copy(showSize = !showSize)
        BlocksWidgetField.FEES -> copy(showFees = !showFees)
    }
}

/**
 * Caps enabled fields at [MAX_BLOCKS_FIELDS], keeping the first ones in declared order. Used when
 * importing legacy preferences (e.g. RN migration) that predate the limit and may exceed it; live
 * editing is already capped by [toggleField].
 */
fun BlocksPreferences.limitedToMax(): BlocksPreferences {
    var count = 0
    fun keep(enabled: Boolean): Boolean = (enabled && count < MAX_BLOCKS_FIELDS).also { if (it) count++ }
    return BlocksPreferences(
        showBlock = keep(showBlock),
        showTime = keep(showTime),
        showDate = keep(showDate),
        showTransactions = keep(showTransactions),
        showSize = keep(showSize),
        showFees = keep(showFees),
    )
}
