package to.bitkit.models

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import to.bitkit.R
import to.bitkit.ui.theme.Colors

enum class ActivityBannerType(
    @DrawableRes val icon: Int,
    @StringRes val title: Int,
    val color: Color,
    val testTag: String,
) {
    SPENDING(
        color = Colors.Purple,
        icon = R.drawable.ic_transfer,
        title = R.string.lightning__transfer_in_progress,
        testTag = "TransferToSpendingBanner",
    ),
    SAVINGS(
        color = Colors.Brand,
        icon = R.drawable.ic_transfer,
        title = R.string.lightning__transfer_in_progress,
        testTag = "TransferToSavingsBanner",
    ),
    CHANNELS_CLOSING(
        color = Colors.Yellow,
        icon = R.drawable.ic_warning,
        title = R.string.lightning__channels_closing_banner,
        testTag = "ChannelsClosingBanner",
    ),
}

data class BannerItem(
    val type: ActivityBannerType,
    val title: String,
)
