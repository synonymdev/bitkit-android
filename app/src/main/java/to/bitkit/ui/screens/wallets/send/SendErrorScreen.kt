package to.bitkit.ui.screens.wallets.send

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BottomSheetPreview
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.theme.Shapes

@Composable
fun SendErrorScreen(
    errorMessage: String?,
    stackTrace: String? = null,
    onRetry: () -> Unit,
    onClose: () -> Unit,
) {
    var displayDetails by remember { mutableStateOf(false) }

    Content(
        errorMessage = errorMessage,
        stackTrace = stackTrace,
        onRetry = onRetry,
        onClose = onClose,
        displayDetails = displayDetails,
        onDisplayDetails = {
            displayDetails = true
        }
    )
}

@Composable
private fun Content(
    errorMessage: String?,
    stackTrace: String?,
    displayDetails: Boolean,
    modifier: Modifier = Modifier,
    onDisplayDetails: () -> Unit = {},
    onSendReportClick: () -> Unit = {},
    onRetry: () -> Unit = {},
    onClose: () -> Unit = {},
) {
    val errorText = errorMessage.orEmpty().ifEmpty { "Unknown error." }
    Column(
        modifier = modifier
            .fillMaxSize()
            .gradientBackground()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
    ) {
        SheetTopBar(stringResource(R.string.wallet__send_error_tx_failed))

        Box(
            modifier = Modifier.weight(1f)
        ) {
            Image(
                painter = painterResource(R.drawable.cross),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(256.dp)
                    .align(Alignment.Center)
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
            ) {
                Spacer(modifier = Modifier.height(16.dp))

                BodyM(text = errorText, color = Colors.White64)

                AnimatedVisibility(
                    visible = displayDetails && !stackTrace.isNullOrBlank(),
                    label = ""
                ) {
                    BodyM(
                        text = stackTrace.orEmpty(),
                        color = Colors.Red,
                        modifier = Modifier
                            .padding(top = 16.dp)
                            .fillMaxWidth()
                            .background(color = Colors.White10, shape = Shapes.medium)
                            .padding(16.dp)
                    )
                }

                Spacer(modifier = Modifier.weight(1f))


            }
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            AnimatedVisibility(visible = !stackTrace.isNullOrBlank() && !displayDetails) {
                SecondaryButton(
                    text = "Technical Details", // TODO Transifex
                    onClick = onDisplayDetails,
                    modifier = Modifier
                )
            }
            AnimatedVisibility(visible = !stackTrace.isNullOrBlank() && displayDetails) {
                SecondaryButton(
                    text = "Send Anonymous Report", // TODO Transifex
                    onClick = onSendReportClick,
                    modifier = Modifier
                )
            }
            if (stackTrace.isNullOrBlank()) {
                SecondaryButton(
                    text = stringResource(R.string.common__cancel),
                    onClick = onClose,
                    fullWidth = true,
                    modifier = Modifier
                        .testTag("Close")
                )
            }
            PrimaryButton(
                text = stringResource(R.string.common__try_again),
                onClick = onRetry,
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        BottomSheetPreview {
            Content(
                errorMessage = stringResource(R.string.wallet__send_error_create_tx),
                stackTrace = "Test render error\n" +
                    "\n" +
                    "     in Tabbar (at WalletNavigator.tsx:59)\n" +
                    "     in Tabbar (at WalletNavigator.tsx:59)\n" +
                    "     in Tabbar (at WalletNavigator.tsx:59)\n" +
                    "     in Tabbar (at WalletNavigator.tsx:59)\n" +
                    "     in WalletsStack (at SceneView.tsx:132)",
                modifier = Modifier.sheetHeight(),
                displayDetails = true,
                onDisplayDetails = {}
            )
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewDontDisplayDetails() {
    AppThemeSurface {
        BottomSheetPreview {
            Content(
                errorMessage = stringResource(R.string.wallet__send_error_create_tx),
                stackTrace = "Test render error\n" +
                    "\n" +
                    "     in Tabbar (at WalletNavigator.tsx:59)\n" +
                    "     in Tabbar (at WalletNavigator.tsx:59)\n" +
                    "     in Tabbar (at WalletNavigator.tsx:59)\n" +
                    "     in Tabbar (at WalletNavigator.tsx:59)\n" +
                    "     in WalletsStack (at SceneView.tsx:132)",
                modifier = Modifier.sheetHeight(),
                displayDetails = false,
                onDisplayDetails = {}
            )
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewUnknown() {
    AppThemeSurface {
        BottomSheetPreview {
            Content(
                errorMessage = "",
                stackTrace = null,
                modifier = Modifier.sheetHeight(),
                displayDetails = false,
                onDisplayDetails = {}
            )
        }
    }
}
