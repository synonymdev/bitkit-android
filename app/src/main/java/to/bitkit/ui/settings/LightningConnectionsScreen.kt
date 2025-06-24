package to.bitkit.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import to.bitkit.R
import to.bitkit.models.formatToModernDisplay
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.Title
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.navigateToLogs
import to.bitkit.ui.navigateToTransferFunding
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun LightningConnectionsScreen(
    navController: NavController,
    viewModel: LightningConnectionsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.collectState()
    }

    Content(
        uiState = uiState,
        onBack = { navController.popBackStack() },
        onClickAddConnection = { navController.navigateToTransferFunding() },
        onClickExportLogs = { /* TODO: zip & share logs  */ },
    )
}

@Composable
private fun Content(
    uiState: LightningConnectionsUiState,
    onBack: () -> Unit = {},
    onClickAddConnection: () -> Unit = {},
    onClickExportLogs: () -> Unit = {},
) {
    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.lightning__connections),
            onBackClick = onBack,
            actions = {
                IconButton(onClick = onClickAddConnection) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = stringResource(R.string.lightning__conn_button_add),
                    )
                }
            }
        )
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            VerticalSpacer(16.dp)
            LightningBalancesSection(uiState.localBalance, uiState.remoteBalance)
            VerticalSpacer(32.dp)

            Caption13Up(stringResource(R.string.lightning__conn_open), modifier = Modifier.padding(top = 16.dp))
            // TODO add list
            FillHeight()

            // Bottom Section
            VerticalSpacer(16.dp)
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                SecondaryButton(
                    text = stringResource(R.string.lightning__conn_button_export_logs),
                    onClick = onClickExportLogs,
                    modifier = Modifier.weight(1f)
                )
                PrimaryButton(
                    text = stringResource(R.string.lightning__conn_button_add),
                    onClick = onClickAddConnection,
                    modifier = Modifier.weight(1f)
                )
            }
            VerticalSpacer(16.dp)
        }
    }
}

@Composable
private fun LightningBalancesSection(localBalance: ULong, remoteBalance: ULong) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        BalanceColumn(
            label = stringResource(R.string.lightning__spending_label),
            balance = localBalance,
            icon = Icons.Default.ArrowUpward,
            color = Colors.Purple,
        )
        BalanceColumn(
            label = stringResource(R.string.lightning__receiving_label),
            balance = remoteBalance,
            icon = Icons.Default.ArrowDownward,
            color = Colors.White,
        )
    }
}

@Composable
private fun BalanceColumn(label: String, balance: ULong, icon: ImageVector, color: Color) {
    Column {
        Caption13Up(text = label, color = Colors.White64)
        VerticalSpacer(8.dp)
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(22.dp)
            )
            Title(text = balance.toLong().formatToModernDisplay(), color = color)
        }
    }
}

@Preview
@Composable
private fun Preview() {
    AppThemeSurface {
        Content(
            uiState = LightningConnectionsUiState(
                localBalance = 50_000u,
                remoteBalance = 450_000u,
            )
        )
    }
}
