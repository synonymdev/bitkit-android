package to.bitkit.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.shared.util.screen
import to.bitkit.ui.theme.AppThemeSurface

@Composable
fun MigrationRecoveryScreen(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    BackHandler {}
    Column(modifier = modifier.screen().padding(horizontal = 16.dp).testTag("MigrationRecovery")) {
        AppTopBar(titleText = stringResource(R.string.migration__nav_title), onBackClick = null)
        VerticalSpacer(16.dp)
        BodyM(text = stringResource(R.string.migration__status_error))
        VerticalSpacer(32.dp)
        PrimaryButton(
            text = stringResource(R.string.common__retry),
            onClick = onRetry,
            modifier = Modifier.testTag("MigrationReadRetry")
        )
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface { MigrationRecoveryScreen(onRetry = {}) }
}
