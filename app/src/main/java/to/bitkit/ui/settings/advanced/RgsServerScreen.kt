package to.bitkit.ui.settings.advanced

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.navigation.NavController
import to.bitkit.R
import to.bitkit.ui.navigateToHome
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.CloseNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface

@Composable
fun RgsServerScreen(
    navController: NavController,
) {
    Content(
        onBack = { navController.popBackStack() },
        onClose = { navController.navigateToHome() },
    )
}

@Composable
private fun Content(
    onBack: () -> Unit = {},
    onClose: () -> Unit = {},
) {
    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.settings__adv__rgs_server),
            onBackClick = onBack,
            actions = { CloseNavIcon(onClose) },
        )
        Box(modifier = Modifier.fillMaxSize()) {
            Text("TODO: RGS Server Screen", modifier = Modifier.align(Alignment.Center))
        }
    }
}

@Preview
@Composable
private fun Preview() {
    AppThemeSurface {
        Content()
    }
}
