package to.bitkit.ui.settings

import androidx.compose.runtime.Composable
import to.bitkit.ui.WalletViewModel
import to.bitkit.ui.shared.Payments

@Composable
fun PaymentsScreen(
    viewModel: WalletViewModel,
) {
    Payments(viewModel)
}
