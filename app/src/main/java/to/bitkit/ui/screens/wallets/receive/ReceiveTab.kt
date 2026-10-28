package to.bitkit.ui.screens.wallets.receive

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import to.bitkit.R
import to.bitkit.ui.screens.wallets.activity.components.TabItem
import to.bitkit.ui.theme.Colors

enum class ReceiveTab : TabItem {
    SAVINGS,
    AUTO,
    SPENDING,
    USDT,

    /** The paired hardware wallet; its label carries the vendor name (see [ReceiveQrScreen]). */
    HARDWARE;

    override val uiText: String
        @Composable
        get() = when (this) {
            SAVINGS -> stringResource(R.string.wallet__receive_tab_savings)
            AUTO -> stringResource(R.string.wallet_primary__bitcoin)
            SPENDING -> stringResource(R.string.wallet_primary__lightning)
            USDT -> "USDT"
            HARDWARE -> stringResource(R.string.hardware__receive_tab_hardware)
        }

    val accentColor: Color
        get() = when (this) {
            SAVINGS -> Colors.Brand
            AUTO -> Colors.Brand
            SPENDING -> Colors.Purple
            USDT -> Colors.Usdt
            HARDWARE -> Colors.Blue
        }
}

internal fun visibleReceiveTabs(hasHardwareWallet: Boolean, usdtEnabled: Boolean): ImmutableList<ReceiveTab> =
    buildList {
        if (hasHardwareWallet) add(ReceiveTab.HARDWARE)
        add(ReceiveTab.AUTO)
        add(ReceiveTab.SPENDING)
        if (usdtEnabled) add(ReceiveTab.USDT)
    }.toImmutableList()
