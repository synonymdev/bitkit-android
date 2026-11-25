package to.bitkit.ui.sheets

import androidx.annotation.StringRes
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
import java.net.URI
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
        action = LnurlAuthAction.fromUri(sheet.lnurl),
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
    action: LnurlAuthAction,
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
        SheetTopBar(titleText = stringResource(action.title))
        VerticalSpacer(16.dp)

        BodyM(
            text = stringResource(action.prompt).replace("{domain}", domain),
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
                text = stringResource(action.button),
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
                action = LnurlAuthAction.LOGIN,
            )
        }
    }
}

/** LUD-04 `action` of an LNURL-auth request; bitkit-core reports every request with tag `login`. */
enum class LnurlAuthAction(
    @StringRes val title: Int,
    @StringRes val prompt: Int,
    @StringRes val button: Int = title,
) {
    LOGIN(
        R.string.other__lnurl_auth_login_title,
        R.string.other__lnurl_auth_login_prompt,
        R.string.other__lnurl_auth_login_button,
    ),
    REGISTER(R.string.other__lnurl_auth_register_title, R.string.other__lnurl_auth_register_prompt),
    LINK(R.string.other__lnurl_auth_link_title, R.string.other__lnurl_auth_link_prompt),
    AUTH(R.string.other__lnurl_auth_auth_title, R.string.other__lnurl_auth_auth_prompt),
    ;

    companion object {
        fun fromUri(uri: String): LnurlAuthAction {
            val action = runCatching { URI(uri).rawQuery }.getOrNull()
                ?.split('&')
                ?.firstNotNullOfOrNull { it.split('=', limit = 2).takeIf { kv -> kv[0] == "action" }?.getOrNull(1) }
            return when (action?.lowercase()) {
                "login" -> LOGIN
                "register" -> REGISTER
                "link" -> LINK
                else -> AUTH
            }
        }
    }
}
