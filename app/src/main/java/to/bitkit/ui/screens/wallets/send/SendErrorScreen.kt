package to.bitkit.ui.screens.wallets.send

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun SendErrorScreen(
    errorMessage: String,
    onRetry: () -> Unit,
    onClose: () -> Unit,
) {
    Content(
        errorMessage = errorMessage,
        onRetry = onRetry,
        onClose = onClose,
    )
}

@Composable
private fun Content(
    errorMessage: String,
    onRetry: () -> Unit = {},
    onClose: () -> Unit = {},
) {
    val errorText = errorMessage.ifEmpty { "Unknown error." }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .gradientBackground()
            .navigationBarsPadding()
    ) {
        SheetTopBar(stringResource(R.string.wallet__send_error_tx_failed))

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            BodyM(text = errorText, color = Colors.White64)

            Spacer(modifier = Modifier.weight(1f))
            Image(
                painter = painterResource(R.drawable.cross),
                contentDescription = null,
                modifier = Modifier.fillMaxWidth().height(256.dp)
            )
            Spacer(modifier = Modifier.weight(1f))

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                SecondaryButton(
                    text = stringResource(R.string.common__cancel),
                    onClick = onClose,
                    modifier = Modifier.weight(1f)
                )
                PrimaryButton(
                    text = stringResource(R.string.common__try_again),
                    onClick = onRetry,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Preview
@Composable
private fun Preview() {
    AppThemeSurface {
        Content(
            errorMessage = stringResource(R.string.wallet__send_error_create_tx),
        )
    }
}

@Preview
@Composable
private fun PreviewUnknown() {
    AppThemeSurface {
        Content(
            errorMessage = "",
        )
    }
}
