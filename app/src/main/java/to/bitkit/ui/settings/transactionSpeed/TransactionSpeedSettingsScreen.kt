package to.bitkit.ui.settings.transactionSpeed

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import to.bitkit.R
import to.bitkit.models.TransactionSpeed
import to.bitkit.ui.appViewModel
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.settings.SettingsButtonRow
import to.bitkit.ui.components.settings.SettingsButtonValue
import to.bitkit.ui.navigateToHome
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.CloseNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun TransactionSpeedSettingsScreen(
    navController: NavController,
) {
    val app = appViewModel ?: return
    val defaultTransactionSpeed = app.defaultTransactionSpeed.collectAsStateWithLifecycle()

    TransactionSpeedSettingsContent(
        selectedSpeed = defaultTransactionSpeed.value,
        onSpeedSelected = { app.setDefaultTransactionSpeed(it) },
        onBackClick = { navController.popBackStack() },
        onCloseClick = { navController.navigateToHome() },
    )
}

@Composable
private fun TransactionSpeedSettingsContent(
    selectedSpeed: TransactionSpeed,
    onSpeedSelected: (TransactionSpeed) -> Unit = {},
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
            Spacer(modifier = Modifier.height(16.dp))
            Caption13Up(text = stringResource(R.string.settings__general__speed_default), color = Colors.White64)
            Spacer(modifier = Modifier.height(16.dp))

            SettingsButtonRow(
                title = stringResource(R.string.settings__fee__fast__label),
                subtitle = stringResource(R.string.settings__fee__fast__description),
                iconRes = R.drawable.ic_speed_fast,
                iconTint = Colors.Brand,
                value = SettingsButtonValue.BooleanValue(selectedSpeed is TransactionSpeed.Fast),
                onClick = { onSpeedSelected(TransactionSpeed.Fast) },
            )
            SettingsButtonRow(
                title = stringResource(R.string.settings__fee__normal__label),
                subtitle = stringResource(R.string.settings__fee__normal__description),
                iconRes = R.drawable.ic_speed_normal,
                iconTint = Colors.Brand,
                value = SettingsButtonValue.BooleanValue(selectedSpeed is TransactionSpeed.Medium),
                onClick = { onSpeedSelected(TransactionSpeed.Medium) },
            )
            SettingsButtonRow(
                title = stringResource(R.string.settings__fee__slow__label),
                subtitle = stringResource(R.string.settings__fee__slow__description),
                iconRes = R.drawable.ic_speed_slow,
                iconTint = Colors.Brand,
                value = SettingsButtonValue.BooleanValue(selectedSpeed is TransactionSpeed.Slow),
                onClick = { onSpeedSelected(TransactionSpeed.Slow) },
            )
        }
    }
}

@Preview
@Composable
private fun Preview() {
    AppThemeSurface {
        TransactionSpeedSettingsContent(
            selectedSpeed = TransactionSpeed.Medium,
        )
    }
}
