package to.bitkit.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import to.bitkit.R
import to.bitkit.ui.components.ButtonSize
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.TextInput
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.components.settings.SectionFooter
import to.bitkit.ui.components.settings.SectionHeader
import to.bitkit.ui.components.settings.SettingsTextButtonRow
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.viewmodels.ProbeResult
import to.bitkit.viewmodels.ProbingToolUiState
import to.bitkit.viewmodels.ProbingToolViewModel

@Composable
fun ProbingToolScreen(
    navController: NavController,
    viewModel: ProbingToolViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    ProbingToolContent(
        uiState = uiState,
        onBackClick = { navController.popBackStack() },
        onInvoiceChange = viewModel::updateInvoice,
        onAmountChange = viewModel::updateAmountSats,
        onPasteInvoice = viewModel::pasteInvoice,
        onSendProbe = viewModel::sendProbe,
    )
}

@Composable
private fun ProbingToolContent(
    uiState: ProbingToolUiState,
    onBackClick: () -> Unit,
    onInvoiceChange: (String) -> Unit,
    onAmountChange: (String) -> Unit,
    onPasteInvoice: () -> Unit,
    onSendProbe: () -> Unit,
) {
    ScreenColumn {
        AppTopBar(
            titleText = "Probing Tool",
            onBackClick = onBackClick,
            actions = { DrawerNavIcon() },
        )
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .imePadding()
                .verticalScroll(rememberScrollState())
        ) {
            SectionHeader("PROBE INVOICE", padding = PaddingValues(0.dp))
            SectionFooter("Enter a Lightning invoice to probe the payment route")

            TextInput(
                value = uiState.invoice,
                onValueChange = onInvoiceChange,
                placeholder = "lnbc...",
                singleLine = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                SecondaryButton(
                    text = "Paste",
                    onClick = onPasteInvoice,
                    enabled = !uiState.isLoading,
                    size = ButtonSize.Small,
                    modifier = Modifier.weight(1f),
                )
            }

            SectionHeader("AMOUNT OVERRIDE (OPTIONAL)")
            SectionFooter("Override the invoice amount for variable-amount invoices")

            TextInput(
                value = uiState.amountSats,
                onValueChange = onAmountChange,
                placeholder = "Amount in sats",
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
            )

            VerticalSpacer(16.dp)

            PrimaryButton(
                text = "Send Probe",
                onClick = onSendProbe,
                enabled = !uiState.isLoading && uiState.invoice.isNotBlank(),
                isLoading = uiState.isLoading,
                modifier = Modifier.fillMaxWidth(),
            )

            AnimatedVisibility(
                visible = uiState.probeResult != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                uiState.probeResult?.let { result ->
                    Column {
                        VerticalSpacer(16.dp)
                        SectionHeader("PROBE RESULTS")
                        SettingsTextButtonRow(
                            title = "Status",
                            iconRes = if (result.success) R.drawable.ic_check else R.drawable.ic_x,
                            iconTint = if (result.success) Colors.Green else Colors.Red,
                            value = if (result.success) "Success" else "Failed",
                        )
                        SettingsTextButtonRow(
                            title = "Duration",
                            iconRes = R.drawable.ic_clock,
                            value = "${result.durationMs} ms",
                        )
                        result.estimatedFeeSats?.let { fee ->
                            SettingsTextButtonRow(
                                title = "Estimated Fee",
                                iconRes = R.drawable.ic_coins,
                                value = "$fee sats",
                            )
                        }
                        result.errorMessage?.let { error ->
                            SectionFooter(error)
                        }
                    }
                }
            }

            VerticalSpacer(32.dp)
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    var uiState by remember {
        mutableStateOf(
            ProbingToolUiState(
                invoice = "lnbc1000n1pj...",
                amountSats = "1000",
                probeResult = ProbeResult(
                    success = true,
                    durationMs = 342,
                    estimatedFeeSats = 5uL,
                ),
            )
        )
    }

    AppThemeSurface {
        ProbingToolContent(
            uiState = uiState,
            onBackClick = {},
            onInvoiceChange = { uiState = uiState.copy(invoice = it) },
            onAmountChange = { uiState = uiState.copy(amountSats = it) },
            onPasteInvoice = {},
            onSendProbe = {},
        )
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewFailed() {
    var uiState by remember {
        mutableStateOf(
            ProbingToolUiState(
                invoice = "lnbc1000n1pj...",
                probeResult = ProbeResult(
                    success = false,
                    durationMs = 1250,
                    errorMessage = "No route found to destination",
                ),
            )
        )
    }

    AppThemeSurface {
        ProbingToolContent(
            uiState = uiState,
            onBackClick = {},
            onInvoiceChange = { uiState = uiState.copy(invoice = it) },
            onAmountChange = { uiState = uiState.copy(amountSats = it) },
            onPasteInvoice = {},
            onSendProbe = {},
        )
    }
}
