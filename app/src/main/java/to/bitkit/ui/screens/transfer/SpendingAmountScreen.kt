package to.bitkit.ui.screens.transfer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.ui.appViewModel
import to.bitkit.ui.blocktankViewModel
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent
import to.bitkit.viewmodels.TransferViewModel

@Composable
fun SpendingAmountScreen(
    viewModel: TransferViewModel,
    onBackClick: () -> Unit = {},
    onCloseClick: () -> Unit = {},
    onOrderCreated: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val app = appViewModel ?: return
    val blocktank = blocktankViewModel ?: return

    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.lightning__transfer__nav_title),
            onBackClick = onBackClick,
            actions = {
                IconButton(onClick = onCloseClick) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.common__close),
                    )
                }
            },
        )
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxSize()
        ) {
            var isLoading by remember { mutableStateOf(false) }
            var spendingBalanceSats by remember { mutableIntStateOf(50_000) }

            Spacer(modifier = Modifier.height(32.dp))
            Display(text = stringResource(R.string.lightning__spending_amount__title).withAccent(accentColor = Colors.Purple))
            Spacer(modifier = Modifier.height(32.dp))

            OutlinedTextField(
                label = { Text("Sats") },
                value = "$spendingBalanceSats",
                onValueChange = { spendingBalanceSats = it.toIntOrNull() ?: 0 },
                textStyle = MaterialTheme.typography.labelSmall,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.weight(1f))

            PrimaryButton(
                text = stringResource(R.string.common__continue),
                onClick = {
                    isLoading = true
                    scope.launch {
                        try {
                            val order = blocktank.createOrder(spendingBalanceSats.toULong())
                            viewModel.onOrderCreated(order)
                            onOrderCreated()
                        } catch (e: Throwable) {
                            app.toast(e)
                        } finally {
                            isLoading = false
                        }
                    }
                },
                enabled = !isLoading,
                isLoading = isLoading,
            )
        }
    }
}
