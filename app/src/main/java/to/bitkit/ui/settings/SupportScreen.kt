package to.bitkit.ui.settings

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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.components.BodyM
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
) {
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


            SettingsButtonRow(title = stringResource(R.string.settings__support__report), onClick = {})
            SettingsButtonRow(title = stringResource(R.string.settings__support__help), onClick = {})
            SettingsButtonRow(title = stringResource(R.string.settings__support__status), onClick = {})

            Image(
                painter = painterResource(R.drawable.question_mark),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )

            FlowRow (
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                FloatingActionButton(
                    onClick = {},
                    containerColor = Colors.White16,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_globe),
                        contentDescription = null,
                        tint = Colors.White
                    )
                }
                FloatingActionButton(
                    onClick = {},
                    containerColor = Colors.White16,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_medium),
                        contentDescription = null,
                        tint = Colors.White
                    )
                }
                FloatingActionButton(
                    onClick = {},
                    containerColor = Colors.White16,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_x_twitter),
                        contentDescription = null,
                        tint = Colors.White
                    )
                }
                FloatingActionButton(
                    onClick = {},
                    containerColor = Colors.White16,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_discord),
                        contentDescription = null,
                        tint = Colors.White
                    )
                }
                FloatingActionButton(
                    onClick = {},
                    containerColor = Colors.White16,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_telegram),
                        contentDescription = null,
                        tint = Colors.White
                    )
                }
                FloatingActionButton(
                    onClick = {},
                    containerColor = Colors.White16,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_github),
                        contentDescription = null,
                        tint = Colors.White
                    )
                }
            }

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
            onClose = {}
        )
    }
}
