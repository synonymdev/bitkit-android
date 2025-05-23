package to.bitkit.ui.settings

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
fun AboutScreen(
    navController: NavController,
) {
    AboutContent(
        onBackClick = { navController.popBackStack() },
        onCloseClick = { navController.navigateToHome() },
    )
}

@Composable
private fun AboutContent(
    onBackClick: () -> Unit = {},
    onCloseClick: () -> Unit = {},
) {
    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.settings__about_title),
            onBackClick = onBackClick,
            actions = { CloseNavIcon(onCloseClick) },
        )
        Box(modifier = Modifier.fillMaxSize()) {
            Text("TODO: About Screen", modifier = Modifier.align(Alignment.Center))
        }
    }
}

@Preview
@Composable
private fun Preview() {
    AppThemeSurface {
        AboutContent()
    }
}
