package to.bitkit.ui.screens.profile

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.components.Display
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.CloseNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors


@Composable
fun CreateProfileScreen(
    onClose: () -> Unit,
    onBack: () -> Unit,
) { //TODO IMPLEMENT
    ScreenColumn {

        AppTopBar(
            titleText = stringResource(R.string.slashtags__profile_create),
            onBackClick = onBack,
            actions = { CloseNavIcon(onClick = onClose) },
        )

        Column(
            modifier = Modifier.padding(horizontal = 32.dp)
        ) {
            Spacer(Modifier.weight(1f))

            Display(
                text = "Comming soon",
                color = Colors.White
            )
            Spacer(Modifier.weight(1f))

        }
    }
}

@Preview(showBackground = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        CreateProfileScreen(
            onClose = {},
            onBack = {},
        )
    }
}
