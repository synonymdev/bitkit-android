package to.bitkit.ui.settings.transactionSpeed

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import to.bitkit.R
import to.bitkit.ui.navigateToHome
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.CloseNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface

@Composable
fun TransactionSpeedSettingsScreen(
    navController: NavController,
) {
    TransactionSpeedSettingsContent(
        onBackClick = { navController.popBackStack() },
        onCloseClick = { navController.navigateToHome() },
    )
}

@Composable
private fun TransactionSpeedSettingsContent(
    onBackClick: () -> Unit = {},
    onCloseClick: () -> Unit = {},
) {
    ScreenColumn(
        modifier = Modifier.verticalScroll(rememberScrollState())
    ) {
        AppTopBar(
            titleText = stringResource(R.string.settings__general__speed_title),
            onBackClick = onBackClick,
            actions = { CloseNavIcon(onClick = onCloseClick) },
        )
        Column(
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            // TODO: Add content here
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        TransactionSpeedSettingsContent()
    }
}
