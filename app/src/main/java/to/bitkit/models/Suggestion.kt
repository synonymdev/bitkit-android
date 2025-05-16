package to.bitkit.models

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import to.bitkit.R

enum class Suggestion(
    @StringRes title: Int,
    @StringRes description: Int,
    @DrawableRes icon: Int? = null
) {
    BUY(
        title = R.string.cards__buyBitcoin__title,
        description = R.string.cards__buyBitcoin__description,
    ),
    SPEND(
        title = R.string.cards__lightning__title,
        description = R.string.cards__lightning__description,
    ),
    BACK_UP(
        title = R.string.cards__backupSeedPhrase__title,
        description = R.string.cards__backupSeedPhrase__description,
    ),
    SECURE(
        title = R.string.cards__pin__title,
        description = R.string.cards__pin__description,
    ),
    SUPPORT(
        title = R.string.cards__support__title,
        description = R.string.cards__support__description,
    ),
    INVITE(
        title = R.string.cards__invite__title,
        description = R.string.cards__invite__description,
    ),
    PROFILE(
        title = R.string.cards__slashtagsProfile__title,
        description = R.string.cards__slashtagsProfile__description,
    ),
    SHOP(
        title = R.string.cards__shop__title,
        description = R.string.cards__shop__description,
    ),
    QUICK_PAY(
        title = R.string.cards__quickpay__title,
        description = R.string.cards__shop__description,
    ),
}
