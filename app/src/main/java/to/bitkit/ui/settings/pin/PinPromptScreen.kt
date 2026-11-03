package to.bitkit.ui.settings.pin

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
fun PinPromptScreen(
    onContinue: () -> Unit,
    onLater: () -> Unit,
    modifier: Modifier = Modifier,
    showLaterButton: Boolean = true,
) {
    SheetIntro(
        navTitle = stringResource(R.string.security__pin_security_header),
        title = stringResource(R.string.security__pin_security_title).withAccent(accentColor = Colors.Green),
        description = AnnotatedString(stringResource(R.string.security__pin_security_text)),
        image = R.drawable.shield,
        continueText = stringResource(R.string.security__pin_security_button),
        onContinue = onContinue,
        cancelText = stringResource(R.string.common__later).takeIf { showLaterButton },
        onCancel = onLater.takeIf { showLaterButton },
        testTag = "SecureWallet",
        modifier = modifier
    )
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        BottomSheetPreview {
            PinPromptScreen(
                showLaterButton = false,
                onContinue = {},
                onLater = {},
                modifier = Modifier.sheetHeight(SheetSize.MEDIUM)
            )
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewShowLater() {
    AppThemeSurface {
        BottomSheetPreview {
            PinPromptScreen(
                showLaterButton = true,
                onContinue = {},
                onLater = {},
                modifier = Modifier.sheetHeight(SheetSize.MEDIUM)
            )
        }
    }
}
