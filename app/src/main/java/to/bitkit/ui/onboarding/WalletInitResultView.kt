package to.bitkit.ui.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.scaffold.AppAlertDialog
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent

sealed class WalletInitResult {
    data object Restored : WalletInitResult()
    data class Failed(val error: Throwable) : WalletInitResult()
}

@Composable
fun WalletInitResultView(
    result: WalletInitResult,
    onButtonClick: () -> Unit,
    onProceedWithoutRestore: (() -> Unit)? = null,
) {
    val titleText = when (result) {
        is WalletInitResult.Restored -> stringResource(R.string.onboarding__restore_success_header)
        is WalletInitResult.Failed -> stringResource(R.string.onboarding__restore_failed_header)
    }

    val titleAccentColor = when (result) {
        is WalletInitResult.Restored -> Colors.Green
        is WalletInitResult.Failed -> Colors.Red
    }

    val description = when (result) {
        is WalletInitResult.Restored -> stringResource(R.string.onboarding__restore_success_text)
        is WalletInitResult.Failed -> stringResource(R.string.onboarding__restore_failed_text)
    }

    val buttonText = when (result) {
        is WalletInitResult.Restored -> stringResource(R.string.onboarding__get_started)
        is WalletInitResult.Failed -> stringResource(R.string.common__try_again)
    }

    val imageResource = when (result) {
        is WalletInitResult.Restored -> R.drawable.check
        is WalletInitResult.Failed -> R.drawable.cross
    }

    var showProceedDialog by remember { mutableStateOf(false) }
    var retryCount by remember { mutableIntStateOf(0) }

    // Reset retry count when result changes to success
    LaunchedEffect(result) {
        if (result is WalletInitResult.Restored) {
            retryCount = 0
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 32.dp),
    ) {
        Spacer(modifier = Modifier.height(24.dp))

        Column {
            Display(titleText.withAccent(accentColor = titleAccentColor))
            Spacer(modifier = Modifier.height(8.dp))
            BodyM(description, color = Colors.White80)
        }

        Spacer(modifier = Modifier.weight(1f))

        Image(
            painter = painterResource(id = imageResource),
            contentDescription = null,
            modifier = Modifier
                .size(256.dp)
                .align(Alignment.CenterHorizontally)
        )

        Spacer(modifier = Modifier.weight(1f))
        PrimaryButton(
            text = buttonText,
            onClick = {
                if (result is WalletInitResult.Failed) {
                    retryCount++
                }
                onButtonClick()
            },
        )

        if (result is WalletInitResult.Failed && retryCount > 1 && onProceedWithoutRestore != null) {
            Spacer(modifier = Modifier.height(12.dp))
            SecondaryButton(
                text = stringResource(R.string.onboarding__restore_no_backup_button),
                onClick = { showProceedDialog = true }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
    }

    if (showProceedDialog) {
        AppAlertDialog(
            title = stringResource(R.string.common__are_you_sure),
            text = stringResource(R.string.onboarding__restore_no_backup_warn),
            confirmText = stringResource(R.string.common__yes_proceed),
            onConfirm = {
                showProceedDialog = false
                retryCount = 0
                onProceedWithoutRestore?.invoke()
            },
            onDismiss = { showProceedDialog = false }
        )
    }
}

@Preview(showSystemUi = true)
@Composable
fun WalletInitResultViewRestoredPreview() {
    AppThemeSurface {
        WalletInitResultView(result = WalletInitResult.Restored, onButtonClick = {})
    }
}

@Preview(showSystemUi = true)
@Composable
fun WalletInitResultViewErrorPreview() {
    AppThemeSurface {
        WalletInitResultView(result = WalletInitResult.Failed(Error("Something went wrong")), onButtonClick = {})
    }
}

@Preview(showSystemUi = true)
@Composable
fun WalletInitResultViewErrorWithRetryPreview() {
    AppThemeSurface {
        WalletInitResultView(
            result = WalletInitResult.Failed(Error("Something went wrong")),
            onButtonClick = {},
            onProceedWithoutRestore = {}
        )
    }
}
