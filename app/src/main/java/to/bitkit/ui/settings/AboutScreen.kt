package to.bitkit.ui.settings

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import to.bitkit.BuildConfig
import to.bitkit.R
import to.bitkit.env.Env
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.components.settings.Links
import to.bitkit.ui.components.settings.SettingsButtonRow
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.CloseNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.shared.util.shareText
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun AboutScreen(
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current

    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.settings__about__title),
            onBackClick = onBack,
            actions = { CloseNavIcon(onClick = onClose) },
        )

        Column(
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            VerticalSpacer(32.dp)

            BodyM(text = stringResource(R.string.settings__about__text), color = Colors.White64)

            VerticalSpacer(32.dp)

            SettingsButtonRow(title = stringResource(R.string.settings__about__legal), onClick = {

            })
            SettingsButtonRow(title = stringResource(R.string.settings__about__share), onClick = {
                shareText(
                    context,
                    context.getString(R.string.settings__about__shareText)
                        .replace("{appStoreUrl}", Env.APP_STORE_URL)
                        .replace("{playStoreUrl}", Env.PLAY_STORE_URL)
                )
            })

            VerticalSpacer(14.dp)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                BodyM(text = stringResource(R.string.settings__about__version))
                BodyM(text = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", color = Colors.White50)
            }

            VerticalSpacer(14.dp)

            HorizontalDivider()

            Image(
                painter = painterResource(R.drawable.bitkit_logo),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp)
                    .weight(1f)
            )

            Links(modifier = Modifier.fillMaxWidth())

            VerticalSpacer(16.dp)
        }
    }
}


@Preview
@Composable
private fun Preview() {
    AppThemeSurface {
        AboutScreen(
            onBack = {},
            onClose = {}
        )
    }
}
