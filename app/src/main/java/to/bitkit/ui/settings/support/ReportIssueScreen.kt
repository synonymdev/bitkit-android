package to.bitkit.ui.settings.support

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.TextInput
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.CloseNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import androidx.compose.runtime.getValue

@Composable
fun ReportIssueScreen(
    viewModel: ReportIssueViewModel = hiltViewModel(),
    onBack: () -> Unit,
    onClose: () -> Unit,
    navigateResultScreen: (Boolean) -> Unit,
) {

    //TODO HANDLE NAVIGATION
    LaunchedEffect(Unit) {
        viewModel.reportIssueEffect.collect { event ->
            when(event) {
                ReportIssueEffects.NavigateError -> navigateResultScreen(false)
                ReportIssueEffects.NavigateSuccess -> navigateResultScreen(true)
            }
        }
    }

    val uiState: ReportIssueUiState by viewModel.uiState.collectAsStateWithLifecycle()

    ReportIssueContent(
        onBack = onBack,
        onClose = onClose,
        onConfirm = viewModel::sendMessage,
        onUpdateEmail = viewModel::updateEmail,
        onUpdateMessage = viewModel::updateMessage,
        uiState = uiState
    )
}

@Composable
fun ReportIssueContent(
    onBack: () -> Unit,
    onClose: () -> Unit,
    onConfirm: () -> Unit,
    onUpdateEmail: (String) -> Unit,
    onUpdateMessage: (String) -> Unit,
    uiState: ReportIssueUiState
) {

    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.settings__support__report),
            onBackClick = onBack,
            actions = { CloseNavIcon(onClick = onClose) },
        )

        Column(
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(32.dp))

            BodyM(text = stringResource(R.string.settings__support__report_text), color = Colors.White64)

            Spacer(modifier = Modifier.height(32.dp))

            BodyM(text = stringResource(R.string.settings__support__label_address), color = Colors.White64)

            Spacer(modifier = Modifier.height(8.dp))

            TextInput(
                placeholder = stringResource(R.string.settings__support__placeholder_address),
                value = uiState.emailInput,
                onValueChange = onUpdateEmail,
                isError = uiState.errorEmail,
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Next
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(32.dp))

            BodyM(text = stringResource(R.string.settings__support__label_message), color = Colors.White64)

            Spacer(modifier = Modifier.height(8.dp))

            TextInput(
                placeholder = stringResource(R.string.settings__support__placeholder_message),
                value = uiState.messageInput,
                onValueChange = onUpdateMessage,
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Done
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )

            Spacer(modifier = Modifier.height(16.dp))

            PrimaryButton(
                stringResource(R.string.settings__support__text_button),
                enabled = uiState.isSendEnabled,
                isLoading = uiState.isLoading,
                onClick = onConfirm
            )

            Spacer(modifier = Modifier.height(16.dp))
        }

    }
}


@Preview(showBackground = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        ReportIssueScreen(
            onBack = {},
            onClose = {},
            navigateResultScreen = {}
        )
    }
}
