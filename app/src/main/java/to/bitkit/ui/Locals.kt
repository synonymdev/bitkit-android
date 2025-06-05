package to.bitkit.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import to.bitkit.models.BalanceState
import to.bitkit.viewmodels.ActivityListViewModel
import to.bitkit.viewmodels.AppViewModel
import to.bitkit.viewmodels.BlocktankViewModel
import to.bitkit.viewmodels.CurrencyUiState
import to.bitkit.viewmodels.CurrencyViewModel
import to.bitkit.viewmodels.SettingsViewModel
import to.bitkit.viewmodels.TransferViewModel
import to.bitkit.viewmodels.WalletViewModel

// Locals
val LocalBalances = compositionLocalOf { BalanceState() }
val LocalCurrencies = compositionLocalOf { CurrencyUiState() }

//  Statics
val LocalAppViewModel = staticCompositionLocalOf<AppViewModel?> { null }
val LocalWalletViewModel = staticCompositionLocalOf<WalletViewModel?> { null }
val LocalBlocktankViewModel = staticCompositionLocalOf<BlocktankViewModel?> { null }
val LocalCurrencyViewModel = staticCompositionLocalOf<CurrencyViewModel?> { null }
val LocalActivityListViewModel = staticCompositionLocalOf<ActivityListViewModel?> { null }
val LocalTransferViewModel = staticCompositionLocalOf<TransferViewModel?> { null }
val LocalSettingsViewModel = staticCompositionLocalOf<SettingsViewModel?> { null }

val appViewModel: AppViewModel?
    @Composable get() = LocalAppViewModel.current

val walletViewModel: WalletViewModel?
    @Composable get() = LocalWalletViewModel.current

val blocktankViewModel: BlocktankViewModel?
    @Composable get() = LocalBlocktankViewModel.current

val currencyViewModel: CurrencyViewModel?
    @Composable get() = LocalCurrencyViewModel.current

val activityListViewModel: ActivityListViewModel?
    @Composable get() = LocalActivityListViewModel.current

val transferViewModel: TransferViewModel?
    @Composable get() = LocalTransferViewModel.current

val settingsViewModel: SettingsViewModel?
    @Composable get() = LocalSettingsViewModel.current
