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
) {
    SPENDING(
        color = Colors.Purple,
        icon = R.drawable.ic_transfer,
        title = R.string.lightning__transfer_in_progress
    ),
    SAVINGS(
        color = Colors.Brand,
        icon = R.drawable.ic_transfer,
        title = R.string.lightning__transfer_in_progress
    )
}
