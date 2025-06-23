package to.bitkit.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import to.bitkit.R
import to.bitkit.ui.components.settings.SectionHeader
import to.bitkit.ui.navigateToTransferFunding
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.ScreenColumn

@Composable
fun LightningSettingsScreen(
    navController: NavController,
) {
    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.lightning__connections),
            onBackClick = { navController.popBackStack() },
            actions = {
                IconButton(onClick = { navController.navigateToTransferFunding() }) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = stringResource(R.string.lightning__conn_button_add),
                    )
                }
            }
        )
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            SectionHeader(stringResource(R.string.lightning__conn_open))
        }
    }
}
