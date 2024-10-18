package to.bitkit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import to.bitkit.ui.shared.BalanceSummary

@Composable
fun WalletScreen(
    viewModel: WalletViewModel,
    uiState: MainUiState.Content,
    content: @Composable () -> Unit = {},
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(24.dp),
        modifier = Modifier
            .padding(horizontal = 24.dp)
            .verticalScroll(rememberScrollState())
    ) {
        BalanceSummary(uiState)

        // TODO: Debug UI (Hide)
        content()
        Spacer(modifier = Modifier.height(1.dp))
    }
}
