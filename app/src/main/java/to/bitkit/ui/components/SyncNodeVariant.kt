package to.bitkit.ui.components

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import to.bitkit.R
import to.bitkit.ui.theme.Colors

/** The look of the wait for the node: bitcoin while the wallet has no Lightning channels, Spending otherwise. */
enum class SyncNodeVariant(
    @DrawableRes val largeCircleRes: Int,
    @DrawableRes val smallCircleRes: Int,
    @DrawableRes val contentRes: Int,
    val accentColor: Color,
    @StringRes val textRes: Int,
    /** Content width as a fraction of the large ring; 1 keeps it at the full available width. */
    val contentWidthFraction: Float = 1f,
) {
    LIGHTNING(
        largeCircleRes = R.drawable.ln_sync_large,
        smallCircleRes = R.drawable.ln_sync_small,
        contentRes = R.drawable.lightning,
        accentColor = Colors.Purple,
        textRes = R.string.lightning__wait_text_top,
    ),
    BITCOIN(
        largeCircleRes = R.drawable.onchain_sync_large,
        smallCircleRes = R.drawable.onchain_sync_small,
        contentRes = R.drawable.bitcoin_emboss,
        accentColor = Colors.Brand,
        textRes = R.string.lightning__wait_text_top_onchain,
        contentWidthFraction = 0.82f,
    ),
}
