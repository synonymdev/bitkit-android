package to.bitkit.ui.settings.support

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import to.bitkit.R
import to.bitkit.env.Env
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.settings.Links
import to.bitkit.ui.components.settings.SettingsButtonRow
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.CloseNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun SupportScreen(
    onBack: () -> Unit,
    onClose: () -> Unit,
    navigateReportIssue: () -> Unit
) {
    val context = LocalContext.current

    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.settings__support_title),
            onBackClick = onBack,
            actions = { CloseNavIcon(onClick = onClose) },
        )

        Column(
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(32.dp))

            BodyM(text = stringResource(R.string.settings__support__text), color = Colors.White64)

            Spacer(modifier = Modifier.height(32.dp))

            SettingsButtonRow(title = stringResource(R.string.settings__support__report), onClick = navigateReportIssue)
            SettingsButtonRow(title = stringResource(R.string.settings__support__help), onClick = {
                val intent = Intent(Intent.ACTION_VIEW, Env.BITKIT_HELP_CENTER.toUri())
                context.startActivity(intent)
            })
            SettingsButtonRow(title = stringResource(R.string.settings__support__status), onClick = {
                //TODO NOT IMPLEMENTED
            })

            Image(
                painter = painterResource(R.drawable.question_mark),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )

            Links(modifier = Modifier.fillMaxWidth())

            Spacer(modifier = Modifier.height(16.dp))
        }

    }
}


@Preview(showBackground = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        SupportScreen(
            onBack = {},
            onClose = {},
            navigateReportIssue = {}
        )
    }
}
