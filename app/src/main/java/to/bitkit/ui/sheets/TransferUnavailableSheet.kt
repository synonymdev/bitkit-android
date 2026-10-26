package to.bitkit.ui.sheets

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.net.toUri
import to.bitkit.R
import to.bitkit.env.Env
import to.bitkit.ui.components.BottomSheetPreview
import to.bitkit.ui.components.SheetIntro
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent

/** Shown instead of the Blocktank transfer to spending, which is no longer available. */
@Composable
fun TransferUnavailableSheet(
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TransferUnavailableContent(
        navTitle = stringResource(R.string.lightning__transfer_unavailable__nav_title),
        description = stringResource(R.string.lightning__transfer_unavailable__text),
        onContinue = onContinue,
        modifier = modifier.sheetHeight(isModal = true),
    )
}

@Composable
fun TransferUnavailableContent(
    navTitle: String,
    description: String,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    SheetIntro(
        navTitle = navTitle,
        title = stringResource(R.string.lightning__transfer_unavailable__title).withAccent(accentColor = Colors.Purple),
        description = AnnotatedString(description),
        image = R.drawable.lightning,
        continueText = stringResource(R.string.common__continue),
        onContinue = onContinue,
        cancelText = stringResource(R.string.common__learn_more),
        onCancel = {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Env.BLOCKTANK_SUNSET_URL.toUri())) }
        },
        onBack = onBack,
        testTag = "TransferUnavailable",
        cancelTestTag = "TransferUnavailableLearnMore",
        continueTestTag = "TransferUnavailableContinue",
        modifier = modifier,
    )
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        BottomSheetPreview {
            TransferUnavailableSheet(onContinue = {})
        }
    }
}
