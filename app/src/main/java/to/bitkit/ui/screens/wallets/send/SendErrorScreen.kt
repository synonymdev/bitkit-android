package to.bitkit.ui.screens.wallets.send

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BottomSheetPreview
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun SendErrorScreen(
    title: String,
    message: String?,
    isRetrying: Boolean,
    onRetry: () -> Unit,
    onContactSupport: () -> Unit,
) {
    Content(
        title = title,
        message,
        isRetrying = isRetrying,
        onRetry = onRetry,
        onContactSupport = onContactSupport,
    )
}

@Composable
private fun Content(
    title: String,
    message: String?,
    modifier: Modifier = Modifier,
    isRetrying: Boolean = false,
    onRetry: () -> Unit = {},
    onContactSupport: () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .gradientBackground()
            .navigationBarsPadding()
    ) {
        SheetTopBar(title)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            VerticalSpacer(16.dp)

            BodyM(
                text = message ?: stringResource(R.string.wallet__payment_failed_description),
                color = Colors.White64,
            )

            FillHeight()
            Image(
                painter = painterResource(R.drawable.cross),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(256.dp)
            )
            FillHeight()

            SecondaryButton(
                text = stringResource(R.string.wallet__send_error_support),
                onClick = onContactSupport,
                enabled = !isRetrying,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("Support")
            )

            VerticalSpacer(16.dp)

            PrimaryButton(
                text = stringResource(R.string.common__try_again),
                onClick = onRetry,
                isLoading = isRetrying,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("Retry")
            )

            VerticalSpacer(16.dp)
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        BottomSheetPreview {
            Content(
                title = stringResource(R.string.wallet__send_error_tx_failed),
                message = stringResource(R.string.wallet__send_error_create_tx),
                modifier = Modifier.sheetHeight(),
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
                title = stringResource(R.string.wallet__toast_payment_failed_title),
                message = null,
                modifier = Modifier.sheetHeight(),
            )
        }
    }
}
