package to.bitkit.ui.screens.wallets.sheets

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BottomSheetType
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.SheetSize
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.viewmodels.AppViewModel

@Composable
fun LnurlAuthSheet(
    sheet: BottomSheetType.LnurlAuth,
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
    onCancel: () -> Unit = {},
    onContinue: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(SheetSize.MEDIUM)
            .gradientBackground()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
    ) {
        // TODO add missing localized text
        SheetTopBar(titleText = "Log In")
        VerticalSpacer(16.dp)

        BodyM(
            // TODO add missing localized text
            text = "Log in to {domain}?".replace("{domain}", domain),
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
                    .testTag("cancel_button")
            )
            // TODO add missing localized text
            PrimaryButton(
                text = "Log In",
                onClick = onContinue,
                fullWidth = false,
                modifier = Modifier
                    .weight(1f)
                    .testTag("continue_button")
            )
        }
        VerticalSpacer(16.dp)
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        Box(
            contentAlignment = Alignment.BottomCenter,
            modifier = Modifier.fillMaxSize()
        ) {
            Content(
                domain = "LNMarkets.com",
            )
        }
    }
}
