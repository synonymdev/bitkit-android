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
import to.bitkit.ui.components.SheetSize
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent

@Composable
fun UpdateSheet(
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    SheetIntro(
        navTitle = stringResource(R.string.other__update_nav_title),
        title = stringResource(R.string.other__update_title).withAccent(accentColor = Colors.Brand),
        description = AnnotatedString(stringResource(R.string.other__update_text)),
        image = R.drawable.wand,
        continueText = stringResource(R.string.other__update_button),
        onContinue = {
            context.startActivity(Intent(Intent.ACTION_VIEW, Env.PLAY_STORE_URL.toUri()))
        },
        cancelText = stringResource(R.string.common__cancel),
        onCancel = onCancel,
        testTag = "AppUpdateSheet",
        modifier = modifier.sheetHeight(SheetSize.LARGE)
    )
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        BottomSheetPreview {
            UpdateSheet(
                onCancel = {},
            )
        }
    }
}
