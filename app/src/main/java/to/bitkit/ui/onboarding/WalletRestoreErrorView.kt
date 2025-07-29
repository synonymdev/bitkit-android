package to.bitkit.ui.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppAlertDialog
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent

@Composable
fun WalletRestoreErrorView(
    retryCount: Int,
    onRetry: () -> Unit,
    onProceedWithoutRestore: () -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }

    ScreenColumn(
        modifier = Modifier.padding(horizontal = 32.dp)
    ) {
        VerticalSpacer(24.dp)

        Column {
            Display(
                stringResource(R.string.onboarding__restore_failed_header).withAccent(accentColor = Colors.Red)
            )
            VerticalSpacer(8.dp)
            BodyM(stringResource(R.string.onboarding__restore_failed_text), color = Colors.White80)
        }

        FillHeight()

        Image(
            painter = painterResource(R.drawable.cross),
            contentDescription = null,
            modifier = Modifier
                .size(256.dp)
                .align(Alignment.CenterHorizontally)
        )

        FillHeight()

        PrimaryButton(
            text = stringResource(R.string.common__try_again),
            onClick = onRetry,
            modifier = Modifier.testTag("TryAgainButton")
        )

        if (retryCount > 1) {
            VerticalSpacer(12.dp)
            SecondaryButton(
                text = stringResource(R.string.onboarding__restore_no_backup_button),
                onClick = { showDialog = true },
                modifier = Modifier.testTag("ProceedWithoutBackupButton")
            )
        }

        VerticalSpacer(16.dp)
    }

    if (showDialog) {
        AppAlertDialog(
            title = stringResource(R.string.common__are_you_sure),
            text = stringResource(R.string.onboarding__restore_no_backup_warn),
            confirmText = stringResource(R.string.common__yes_proceed),
            onConfirm = {
                showDialog = false
                onProceedWithoutRestore.invoke()
            },
            onDismiss = { showDialog = false },
            modifier = Modifier.testTag("ProceedWithoutBackupDialog")
        )
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        WalletRestoreErrorView(
            retryCount = 0,
            onRetry = {},
            onProceedWithoutRestore = {},
        )
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewRetried() {
    AppThemeSurface {
        WalletRestoreErrorView(
            retryCount = 2,
            onRetry = {},
            onProceedWithoutRestore = {},
        )
    }
}
