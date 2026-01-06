package to.bitkit.ui.sheets

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
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
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.Sheet
import to.bitkit.ui.components.SheetSize
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.viewmodels.AppViewModel

@Composable
fun LnurlAuthSheet(
    sheet: Sheet.LnurlAuth,
    app: AppViewModel,
) {
    Content(
        domain = sheet.domain,
        onContinue = {
            app.requestLnurlAuth(
                callback = sheet.lnurl,
                k1 = sheet.k1,
                domain = sheet.domain,
            )
        },
        onCancel = { app.hideSheet() },
    )
}

@Composable
private fun Content(
    domain: String,
    modifier: Modifier = Modifier,
    onCancel: () -> Unit = {},
    onContinue: () -> Unit = {},
) {
    Column(
        modifier = modifier
            .sheetHeight(SheetSize.MEDIUM)
            .gradientBackground()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
    ) {
        SheetTopBar(titleText = stringResource(R.string.other__lnurl_auth_login_title))
        VerticalSpacer(16.dp)

        BodyM(
            text = stringResource(R.string.other__lnurl_auth_login_prompt).replace("{domain}", domain),
            color = Colors.White64,
        )

        Image(
            painter = painterResource(R.drawable.keyring),
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp)
                .aspectRatio(1.0f)
                .weight(1f)
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SecondaryButton(
                text = stringResource(R.string.common__cancel),
                onClick = onCancel,
                fullWidth = false,
                modifier = Modifier
                    .weight(1f)
                    .testTag("LnurlAuthCancel")
            )
            PrimaryButton(
                text = stringResource(R.string.other__lnurl_auth_login_button),
                onClick = onContinue,
                fullWidth = false,
                modifier = Modifier
                    .weight(1f)
                    .testTag("LnurlAuthContinue")
            )
        }
        VerticalSpacer(16.dp)
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        BottomSheetPreview {
            Content(
                domain = "LNMarkets.com",
            )
        }
    }
}
