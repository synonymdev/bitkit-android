package to.bitkit.models

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import to.bitkit.R
import to.bitkit.ui.theme.Colors

enum class Suggestion(
    @StringRes val title: Int,
    @StringRes val description: Int,
    @DrawableRes val icon: Int,
    val color: Color
) {
    BUY(
        title = R.string.cards__buyBitcoin__title,
        description = R.string.cards__buyBitcoin__description,
        color = Colors.Brand24,
        icon = R.drawable.b_emboss
    ),
    SPEND(
        title = R.string.cards__lightning__title,
        description = R.string.cards__lightning__description,
        color = Colors.Purple24,
        icon = R.drawable.lightning
    ),
    BACK_UP(
        title = R.string.cards__backupSeedPhrase__title,
        description = R.string.cards__backupSeedPhrase__description,
        color = Colors.Blue24,
        icon = R.drawable.safe
    ),
    SECURE(
        title = R.string.cards__pin__title,
        description = R.string.cards__pin__description,
        color = Colors.Green24,
        icon = R.drawable.shield
    ),
    SUPPORT(
        title = R.string.cards__support__title,
        description = R.string.cards__support__description,
        color = Colors.Yellow24,
        icon = R.drawable.lightbulb
    ),
    INVITE(
        title = R.string.cards__invite__title,
        description = R.string.cards__invite__description,
        color = Colors.Blue24,
        icon = R.drawable.group
    ),
    PROFILE(
        title = R.string.cards__slashtagsProfile__title,
        description = R.string.cards__slashtagsProfile__description,
        color = Colors.Brand24,
        icon = R.drawable.crown
    ),
    SHOP(
        title = R.string.cards__shop__title,
        description = R.string.cards__shop__description,
        color = Colors.Yellow24,
        icon = R.drawable.shopping_bag
    ),
    QUICK_PAY(
        title = R.string.cards__quickpay__title,
        description = R.string.cards__quickpay__description,
        color = Colors.Green24,
        icon = R.drawable.fast_forward
    ),
    TRANSFER_PENDING(
        title = R.string.cards__lightningSettingUp__title,
        description = R.string.cards__transferPending__description,
        color = Colors.Purple24,
        icon = R.drawable.transfer
    ),
    TRANSFER_CLOSING_CHANNEL(
        title = R.string.cards__transferClosingChannel__title,
        description = R.string.cards__transferClosingChannel__description,
        color = Colors.Red24,
        icon = R.drawable.transfer
    ),
    LIGHTNING_SETTING_UP(
        title = R.string.cards__lightningSettingUp__title,
        description = R.string.cards__lightningSettingUp__description,
        color = Colors.Purple24,
        icon = R.drawable.transfer
    ),
    LIGHTNING_READY(
        title = R.string.cards__lightningReady__title,
        description = R.string.cards__lightningReady__description,
        color = Colors.Purple24,
        icon = R.drawable.transfer
    ),
}

fun String.toSuggestionOrNull() = Suggestion.entries.firstOrNull { it.name == this }
