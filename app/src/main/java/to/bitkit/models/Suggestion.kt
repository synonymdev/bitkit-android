package to.bitkit.models

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import to.bitkit.R
import to.bitkit.ui.theme.Colors

enum class Suggestion(
    @StringRes title: Int,
    @StringRes description: Int,
    @DrawableRes icon: Int,
    color: Color
) {
    BUY(
        title = R.string.cards__buyBitcoin__title,
        description = R.string.cards__buyBitcoin__description,
        color = Colors.Brand,
        icon = R.drawable.b_emboss
    ),
    SPEND(
        title = R.string.cards__lightning__title,
        description = R.string.cards__lightning__description,
        color = Colors.Purple,
        icon = R.drawable.lightning
    ),
    BACK_UP(
        title = R.string.cards__backupSeedPhrase__title,
        description = R.string.cards__backupSeedPhrase__description,
        color = Colors.Blue,
        icon = R.drawable.safe
    ),
    SECURE(
        title = R.string.cards__pin__title,
        description = R.string.cards__pin__description,
        color = Colors.Green,
        icon = R.drawable.shield
    ),
    SUPPORT(
        title = R.string.cards__support__title,
        description = R.string.cards__support__description,
        color = Colors.Yellow,
        icon = R.drawable.lightbulb
    ),
    INVITE(
        title = R.string.cards__invite__title,
        description = R.string.cards__invite__description,
        color = Colors.Blue,
        icon = R.drawable.group
    ),
    PROFILE(
        title = R.string.cards__slashtagsProfile__title,
        description = R.string.cards__slashtagsProfile__description,
        color = Colors.Brand,
        icon = R.drawable.crown
    ),
    SHOP(
        title = R.string.cards__shop__title,
        description = R.string.cards__shop__description,
        color = Colors.Yellow,
        icon = R.drawable.shopping_bag
    ),
    QUICK_PAY(
        title = R.string.cards__quickpay__title,
        description = R.string.cards__shop__description,
        color = Colors.Green,
        icon = R.drawable.fast_forward
    ),
}

fun String.toSuggestionOrNull() = Suggestion.entries.firstOrNull { it.name == this }
