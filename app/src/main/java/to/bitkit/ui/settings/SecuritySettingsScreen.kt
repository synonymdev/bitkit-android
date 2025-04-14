package to.bitkit.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import to.bitkit.R
import to.bitkit.ui.appViewModel
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.SettingsToggleRow
import to.bitkit.ui.navigateToHome
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.CloseNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.rememberBiometricAuthSupported

@Composable
fun SecuritySettingsScreen(
    navController: NavController,
) {
    val app = appViewModel ?: return
    val isPinEnabled = true // TODO add actual logic
    val isPinOnLaunchRequired by app.isPinOnLaunchRequired.collectAsStateWithLifecycle()
    val isBiometricEnabled by app.isBiometricEnabled.collectAsStateWithLifecycle()

    SecuritySettingsContent(
        isPinEnabled = isPinEnabled,
        isPinOnLaunchRequired = isPinOnLaunchRequired,
        isBiometricEnabled = isBiometricEnabled,
        isBiometrySupported = rememberBiometricAuthSupported(),
        onPinOnLaunchClick = { app.setIsPinOnLaunchRequired(!isPinOnLaunchRequired) }, // TODO auth check
        onUseBiometricsClick = { app.setIsBiometricEnabled(!isBiometricEnabled) }, // TODO auth check
        onBackClick = { navController.popBackStack() },
        onCloseClick = { navController.navigateToHome() },
    )
}

@Composable
private fun SecuritySettingsContent(
    isPinEnabled: Boolean,
    isPinOnLaunchRequired: Boolean,
    isBiometricEnabled: Boolean,
    isBiometrySupported: Boolean,
    onPinOnLaunchClick: () -> Unit = {},
    onUseBiometricsClick: () -> Unit = {},
    onBackClick: () -> Unit = {},
    onCloseClick: () -> Unit = {},
) {
    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.settings__security_title),
            onBackClick = onBackClick,
            actions = { CloseNavIcon(onClick = onCloseClick) },
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            if (isPinEnabled) {
                SettingsToggleRow(
                    label = stringResource(R.string.settings__security__pin_launch),
                    isChecked = isPinOnLaunchRequired,
                    onClick = onPinOnLaunchClick,
                )
            }
            if (isPinEnabled && isBiometrySupported) {
                SettingsToggleRow(
                    label = let {
                        val bioTypeName = stringResource(R.string.security__bio)
                        stringResource(R.string.settings__security__use_bio).replace("{biometryTypeName}", bioTypeName)
                    },
                    isChecked = isBiometricEnabled,
                    onClick = onUseBiometricsClick,
                )
            }
            if (isPinEnabled && isBiometrySupported) {
                BodyS(
                    text = let {
                        val bioTypeName = stringResource(R.string.security__bio)
                        stringResource(R.string.settings__security__footer).replace("{biometryTypeName}", bioTypeName)
                    },
                    color = Colors.White64,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            }
        }
    }
}

@Preview
@Composable
fun Preview() {
    AppThemeSurface {
        SecuritySettingsContent(
            isPinEnabled = true,
            isPinOnLaunchRequired = true,
            isBiometricEnabled = true,
            isBiometrySupported = true,
        )
    }
}
