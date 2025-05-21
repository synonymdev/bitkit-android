package to.bitkit.ui.settings.support

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.CloseNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun ReportIssueResultScreen(
    isSuccess: Boolean,
    onBack: () -> Unit,
    onClose: () -> Unit,
) {

    ScreenColumn {
        AppTopBar(
            titleText = if (isSuccess) {
                stringResource(R.string.settings__support__title_success)
            } else {
                stringResource(R.string.settings__support__title_unsuccess)
            },
            onBackClick = onBack,
            actions = { CloseNavIcon(onClick = onClose) },
        )

        Column(
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(32.dp))

            BodyM(
                text = if (isSuccess) {
                    stringResource(R.string.settings__support__text_success)
                } else {
                    stringResource(R.string.settings__support__text_unsuccess)
                },
                color = Colors.White64
            )

            Image(
                painter = if (isSuccess) painterResource(R.drawable.email) else painterResource(R.drawable.cross),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )

            PrimaryButton(
                if (isSuccess) {
                    stringResource(R.string.settings__support__text_success_button)
                } else {
                    stringResource(R.string.settings__support__text_unsuccess_button)
                },
                onClick = { if (isSuccess) onClose() else onBack() }
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}


@Preview(showBackground = true, name = "Success")
@Composable
private fun Preview1() {
    AppThemeSurface {
        ReportIssueResultScreen(
            onBack = {},
            onClose = {},
            isSuccess = true,
        )
    }
}

@Preview(showBackground = true, name = "Failure")
@Composable
private fun Preview2() {
    AppThemeSurface {
        ReportIssueResultScreen(
            onBack = {},
            onClose = {},
            isSuccess = false,
        )
    }
}
