package to.bitkit.models

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import to.bitkit.R
import to.bitkit.ui.theme.Colors

enum class Suggestion(
    @StringRes title: Int,
    @StringRes description: Int,
    @DrawableRes icon: Int? = null,
    colors: Color
) {
    BUY(
        title = R.string.cards__buyBitcoin__title,
        description = R.string.cards__buyBitcoin__description,
        colors = Colors.Brand
    ),
    SPEND(
        title = R.string.cards__lightning__title,
        description = R.string.cards__lightning__description,
        colors = Colors.Purple
    ),
    BACK_UP(
        title = R.string.cards__backupSeedPhrase__title,
        description = R.string.cards__backupSeedPhrase__description,
        colors = Colors.Blue
    ),
    SECURE(
        title = R.string.cards__pin__title,
        description = R.string.cards__pin__description,
        colors = Colors.Green
    ),
    SUPPORT(
        title = R.string.cards__support__title,
        description = R.string.cards__support__description,
        colors = Colors.Yellow
    ),
    INVITE(
        title = R.string.cards__invite__title,
        description = R.string.cards__invite__description,
        colors = Colors.Blue
    ),
    PROFILE(
        title = R.string.cards__slashtagsProfile__title,
        description = R.string.cards__slashtagsProfile__description,
        colors = Colors.Brand
    ),
    SHOP(
        title = R.string.cards__shop__title,
        description = R.string.cards__shop__description,
        colors = Colors.Yellow
    ),
    QUICK_PAY(
        title = R.string.cards__quickpay__title,
        description = R.string.cards__shop__description,
        colors = Colors.Green
    ),
}
