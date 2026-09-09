package to.bitkit.ui.screens.wallets.receive

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import to.bitkit.R
import to.bitkit.ui.screens.wallets.activity.components.TabItem
import to.bitkit.ui.theme.Colors

enum class ReceiveTab : TabItem {
    SAVINGS,
    AUTO,
    SPENDING,
    TREZOR;

    override val uiText: String
        @Composable
        get() = when (this) {
            SAVINGS -> stringResource(R.string.wallet__receive_tab_savings)
            AUTO -> stringResource(R.string.wallet__receive_tab_auto)
            SPENDING -> stringResource(R.string.wallet__receive_tab_spending)
            TREZOR -> stringResource(R.string.hardware__device_model_trezor)
        }

    val accentColor: Color
        get() = when (this) {
            SAVINGS -> Colors.Brand
            AUTO -> Colors.Brand
            SPENDING -> Colors.Purple
            TREZOR -> Colors.Blue
        }
}
