package to.bitkit.ui.settings.profile

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
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.CloseNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent


@Composable
fun ProfileIntroScreen(
    onClose: () -> Unit,
    onContinue: () -> Unit,
) {
    ScreenColumn {

        AppTopBar(
            titleText = stringResource(R.string.slashtags__profile),
            onBackClick = null,
            actions = { CloseNavIcon(onClick = onClose) },
        )

        Column(
            modifier = Modifier.padding(horizontal = 32.dp)
        ) {
            Image(
                painter = painterResource(R.drawable.crown),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )

            Display(
                text = stringResource(R.string.slashtags__onboarding_profile1_header).withAccent(accentColor = Colors.Brand),
                color = Colors.White
            )
            Spacer(Modifier.height(8.dp))
            BodyM(text = stringResource(R.string.slashtags__onboarding_profile1_text), color = Colors.White64)
            Spacer(Modifier.height(32.dp))
            PrimaryButton(
                text = stringResource(R.string.common__continue),
                onClick = onContinue
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        ProfileIntroScreen(
            onClose = {},
            onContinue = {}
        )
    }
}
