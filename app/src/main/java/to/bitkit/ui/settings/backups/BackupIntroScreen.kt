package to.bitkit.ui.settings.backups

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import to.bitkit.R
import to.bitkit.ui.components.BottomSheetPreview
import to.bitkit.ui.components.SheetIntro
import to.bitkit.ui.components.SheetSize
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent

@Composable
fun BackupIntroScreen(
    hasFunds: Boolean,
    onClose: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SheetIntro(
        navTitle = stringResource(R.string.security__backup_wallet),
        title = stringResource(R.string.security__backup_title).withAccent(accentColor = Colors.Blue),
        description = AnnotatedString(
            when (hasFunds) {
                true -> stringResource(R.string.security__backup_funds)
                else -> stringResource(R.string.security__backup_funds_no)
            },
        ),
        image = R.drawable.safe,
        continueText = stringResource(R.string.security__backup_button),
        onContinue = onConfirm,
        cancelText = stringResource(R.string.common__later),
        onCancel = onClose,
        testTag = "BackupIntroView",
        modifier = modifier
    )
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        BottomSheetPreview {
            BackupIntroScreen(
                onClose = {},
                onConfirm = {},
                hasFunds = true,
                modifier = Modifier.sheetHeight(SheetSize.MEDIUM),
            )
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewHasFunds() {
    AppThemeSurface {
        BottomSheetPreview {
            BackupIntroScreen(
                onClose = {},
                onConfirm = {},
                hasFunds = false,
                modifier = Modifier.sheetHeight(SheetSize.MEDIUM),
            )
        }
    }
}
