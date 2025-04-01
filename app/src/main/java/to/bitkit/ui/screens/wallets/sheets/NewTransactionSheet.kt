package to.bitkit.ui.screens.wallets.sheets

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.models.NewTransactionSheetDetails
import to.bitkit.models.NewTransactionSheetDirection
import to.bitkit.models.NewTransactionSheetType
import to.bitkit.ui.components.BalanceHeaderView
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.moneyString
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppShapes
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.viewmodels.AppViewModel
import to.bitkit.viewmodels.SendEvent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewTransactionSheet(
    appViewModel: AppViewModel,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = { appViewModel.hideNewTransactionSheet() },
        sheetState = sheetState,
        shape = AppShapes.sheet,
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxSize()
            .gradientBackground()
    ) {
        NewTransactionSheetView(
            details = appViewModel.newTransaction,
            sheetState = sheetState,
            onCloseClick = { appViewModel.hideNewTransactionSheet() }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewTransactionSheetView(
    details: NewTransactionSheetDetails,
    sheetState: SheetState,
    onCloseClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        val titleText = when (details.type) {
            NewTransactionSheetType.LIGHTNING -> when (details.direction) {
                NewTransactionSheetDirection.SENT -> stringResource(R.string.wallet__send_sent)
                else -> stringResource(R.string.wallet__payment_received)
            }

            NewTransactionSheetType.ONCHAIN -> when (details.direction) {
                NewTransactionSheetDirection.SENT -> stringResource(R.string.wallet__send_sent)
                else -> stringResource(R.string.wallet__payment_received)
            }
        }

        SheetTopBar(titleText)

        Spacer(modifier = Modifier.height(24.dp))

        BalanceHeaderView(sats = details.sats, modifier = Modifier.fillMaxWidth())

        if (details.direction == NewTransactionSheetDirection.SENT) {
            Spacer(modifier = Modifier.weight(1f))

            Image(
                painter = painterResource(R.drawable.check),
                contentDescription = null,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp)
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        if (details.direction == NewTransactionSheetDirection.SENT) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                SecondaryButton(
                    text = stringResource(R.string.wallet__send_details),
                    onClick = onCloseClick, //TODO IMPLEMENT
                    fullWidth = false,
                    modifier = Modifier.weight(1f)
                )
                PrimaryButton(
                    text = stringResource(R.string.common__close),
                    onClick = onCloseClick,
                    fullWidth = false,
                    modifier = Modifier.weight(1f)
                )
            }
        } else {
            PrimaryButton(
                text = stringResource(R.string.common__close), //TODO get correct text
                onClick = onCloseClick,
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}
/*
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewTransactionSheetView(
    details: NewTransactionSheetDetails,
    sheetState: SheetState,
    onCloseClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
    ) {
        Text(
            text = when (details.type) {
                NewTransactionSheetType.LIGHTNING -> when (details.direction) {
                    NewTransactionSheetDirection.SENT -> "Sent Instant Bitcoin"
                    else -> "Received Instant Bitcoin"
                }

                NewTransactionSheetType.ONCHAIN -> when (details.direction) {
                    NewTransactionSheetDirection.SENT -> "Sent Bitcoin"
                    else -> "Received Bitcoin"
                }
            },
            style = MaterialTheme.typography.titleMedium,
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = moneyString(details.sats),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.align(Alignment.Start)
        )

        Spacer(modifier = Modifier.weight(1f))
        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = onCloseClick,
        ) {
            Text(stringResource(R.string.close))
        }
    }
}*/

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true)
@Composable
private fun Preview() {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    LaunchedEffect(Unit) {
        sheetState.show()
    }

    AppThemeSurface {
        NewTransactionSheetView(
            details = NewTransactionSheetDetails(
                type = NewTransactionSheetType.LIGHTNING,
                direction = NewTransactionSheetDirection.SENT,
                sats = 123456789,
            ),
            sheetState,
            onCloseClick = {},
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true)
@Composable
private fun Preview2() {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    LaunchedEffect(Unit) {
        sheetState.show()
    }

    AppThemeSurface {
        NewTransactionSheetView(
            details = NewTransactionSheetDetails(
                type = NewTransactionSheetType.ONCHAIN,
                direction = NewTransactionSheetDirection.SENT,
                sats = 123456789,
            ),
            sheetState,
            onCloseClick = {},
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true)
@Composable
private fun Preview3() {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    LaunchedEffect(Unit) {
        sheetState.show()
    }

    AppThemeSurface {
        NewTransactionSheetView(
            details = NewTransactionSheetDetails(
                type = NewTransactionSheetType.LIGHTNING,
                direction = NewTransactionSheetDirection.RECEIVED,
                sats = 123456789,
            ),
            sheetState,
            onCloseClick = {},
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true)
@Composable
private fun Preview4() {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    LaunchedEffect(Unit) {
        sheetState.show()
    }

    AppThemeSurface {
        NewTransactionSheetView(
            details = NewTransactionSheetDetails(
                type = NewTransactionSheetType.ONCHAIN,
                direction = NewTransactionSheetDirection.RECEIVED,
                sats = 123456789,
            ),
            sheetState,
            onCloseClick = {},
        )
    }
}
