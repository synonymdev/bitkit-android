package to.bitkit.ui.screens.contacts

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent

@Composable
fun ContactsIntroScreen(
    onContinue: () -> Unit,
    onBackClick: () -> Unit,
) {
    Content(
        onContinue = onContinue,
        onBackClick = onBackClick,
    )
}

@Composable
private fun Content(
    onContinue: () -> Unit,
    onBackClick: () -> Unit,
) {
    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.contacts__nav_title),
            onBackClick = onBackClick,
            actions = { DrawerNavIcon() },
        )

        Column(
            modifier = Modifier.padding(horizontal = 32.dp)
        ) {
            Image(
                painter = painterResource(R.drawable.contacts_intro),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )

            Display(
                text = stringResource(R.string.contacts__intro_title)
                    .withAccent(accentColor = Colors.PubkyGreen),
                color = Colors.White,
            )
            VerticalSpacer(8.dp)
            BodyM(text = stringResource(R.string.contacts__intro_description), color = Colors.White64)
            VerticalSpacer(32.dp)
            PrimaryButton(
                text = stringResource(R.string.contacts__intro_add_contact),
                onClick = onContinue,
            )
            VerticalSpacer(16.dp)
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        Content(
            onContinue = {},
            onBackClick = {},
        )
    }
}
