package to.bitkit.ui.screens.profile

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun CreateProfileScreen(
    onBack: () -> Unit,
) { // TODO IMPLEMENT
    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.slashtags__profile_create),
            onBackClick = onBack,
            actions = { DrawerNavIcon() },
        )

        Column(
            modifier = Modifier.padding(horizontal = 32.dp)
        ) {
            FillHeight()

            Display(
                text = stringResource(R.string.other__coming_soon),
                color = Colors.White
            )
            FillHeight()
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        CreateProfileScreen(
            onBack = {},
        )
    }
}
