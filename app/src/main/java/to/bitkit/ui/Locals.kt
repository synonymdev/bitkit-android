package to.bitkit.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import to.bitkit.models.BalanceState

// Locals
val LocalBalances = compositionLocalOf { BalanceState() }

//  Statics
val LocalAppViewModel = staticCompositionLocalOf<AppViewModel?> { null }
val LocalWalletViewModel = staticCompositionLocalOf<WalletViewModel?> { null }

val appViewModel: AppViewModel?
    @Composable
    get() = LocalAppViewModel.current

val walletViewModel: WalletViewModel?
    @Composable
    get() = LocalWalletViewModel.current
