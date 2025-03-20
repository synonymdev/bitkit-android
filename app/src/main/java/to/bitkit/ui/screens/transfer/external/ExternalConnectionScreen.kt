package to.bitkit.ui.screens.transfer.external

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.ButtonSize
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppShapes
import to.bitkit.ui.theme.AppTextFieldDefaults
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent

@Composable
fun ExternalConnectionScreen(
    onContinueClick: () -> Unit = {},
    onBackClick: () -> Unit,
    onCloseClick: () -> Unit,
) {
    fun onPasteClick() {
        println("onPasteClick") // TODO implement paste & parse
    }

    fun onScanClick() {
        println("onScanClick") // TODO implement scan externalNode
    }

    ExternalConnectionContent(
        onContinueClick = {
            // TODO handle: connectPeer & persist
            onContinueClick()
        },
        onScanClick = { onScanClick() },
        onPasteClick = { onPasteClick() },
        onBackClick = onBackClick,
        onCloseClick = onCloseClick,
    )
}

@Composable
private fun ExternalConnectionContent(
    onContinueClick: () -> Unit = {},
    onScanClick: () -> Unit = {},
    onPasteClick: () -> Unit = {},
    onBackClick: () -> Unit = {},
    onCloseClick: () -> Unit = {},
) {
    var nodeId by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }

    var isLoading by remember { mutableStateOf(false) }
    val isValid = nodeId.length == 66 && host.isNotBlank() && port.isNotBlank()

    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.lightning__external__nav_title),
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
                .imePadding()
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(16.dp))
            Display(stringResource(R.string.lightning__external_manual__title).withAccent(accentColor = Colors.Purple))
            Spacer(modifier = Modifier.height(8.dp))
            BodyM(stringResource(R.string.lightning__external_manual__text), color = Colors.White64)
            Spacer(modifier = Modifier.height(16.dp))

            Spacer(modifier = Modifier.height(16.dp))
            Caption13Up(text = stringResource(R.string.lightning__external_manual__node_id), color = Colors.White64)
            Spacer(modifier = Modifier.height(8.dp))
            TextField(
                placeholder = { Text("00000000000000000000000000000000000000000000000000000000000000") },
                value = nodeId,
                onValueChange = { nodeId = it },
                singleLine = false,
                colors = AppTextFieldDefaults.semiTransparent,
                shape = AppShapes.smallInput,
                keyboardOptions = KeyboardOptions(
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Done,
                    capitalization = KeyboardCapitalization.None,
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))
            Caption13Up(text = stringResource(R.string.lightning__external_manual__host), color = Colors.White64)
            Spacer(modifier = Modifier.height(8.dp))
            TextField(
                placeholder = { Text("00.00.00.00") },
                value = host,
                onValueChange = { host = it },
                singleLine = false,
                colors = AppTextFieldDefaults.semiTransparent,
                shape = AppShapes.smallInput,
                keyboardOptions = KeyboardOptions(
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Done,
                    capitalization = KeyboardCapitalization.None,
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))
            Caption13Up(text = stringResource(R.string.lightning__external_manual__host), color = Colors.White64)
            Spacer(modifier = Modifier.height(8.dp))
            TextField(
                placeholder = { Text("9735") },
                value = port,
                onValueChange = { port = it },
                singleLine = false,
                colors = AppTextFieldDefaults.semiTransparent,
                shape = AppShapes.smallInput,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Done,
                    capitalization = KeyboardCapitalization.None,
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))
            PrimaryButton(
                text = stringResource(R.string.lightning__external_manual__paste),
                size = ButtonSize.Small,
                onClick = onPasteClick,
                icon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_clipboard_text_simple),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                },
                fullWidth = false,
            )

            Spacer(modifier = Modifier.height(16.dp))
            Spacer(modifier = Modifier.weight(1f))

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                SecondaryButton(
                    text = stringResource(R.string.lightning__external_manual__scan),
                    onClick = onScanClick,
                    modifier = Modifier.weight(1f)
                )
                PrimaryButton(
                    text = stringResource(R.string.common__continue),
                    onClick = onContinueClick,
                    enabled = isValid,
                    isLoading = isLoading,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Preview(showSystemUi = true, showBackground = true)
@Composable
private fun ExternalConnectionScreenPreview() {
    AppThemeSurface {
        ExternalConnectionContent()
    }
}
