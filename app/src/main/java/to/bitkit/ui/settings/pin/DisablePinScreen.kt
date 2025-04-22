package to.bitkit.ui.settings.pin

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.navigation.NavController
import kotlinx.coroutines.flow.filterNotNull
import to.bitkit.R
import to.bitkit.ui.appViewModel
import to.bitkit.ui.components.AuthCheckAction
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.navigateToAuthCheck
import to.bitkit.ui.navigateToHome
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.CloseNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun DisablePinScreen(
    navController: NavController,
    savedStateHandle: SavedStateHandle,
) {
    val app = appViewModel ?: return

    LaunchedEffect(savedStateHandle) {
        savedStateHandle.getStateFlow<String?>(AuthCheckAction.KEY, null)
            .filterNotNull()
            .collect { actionId ->
                when (actionId) {
                    AuthCheckAction.Id.DISABLE_PIN -> {
                        app.removePin()
                        navController.popBackStack()
                    }
                }
                savedStateHandle.remove<String>(AuthCheckAction.KEY)
            }
    }

    DisablePinContent(
        onDisableClick = {
            navController.navigateToAuthCheck(
                requirePin = true,
                onSuccessActionId = AuthCheckAction.Id.DISABLE_PIN,
            )
        },
        onBackClick = { navController.popBackStack() },
        onCloseClick = { navController.navigateToHome() },
    )
}

@Composable
private fun DisablePinContent(
    onDisableClick: () -> Unit = {},
    onBackClick: () -> Unit = {},
    onCloseClick: () -> Unit = {},
) {
    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.security__pin_disable_title),
            onBackClick = onBackClick,
            actions = { CloseNavIcon(onClick = onCloseClick) },
        )
        Column(
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {

            BodyM(
                text = stringResource(R.string.security__pin_disable_text),
                color = Colors.White64,
            )

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                Image(
                    painter = painterResource(R.drawable.shield),
                    contentDescription = null,
                    modifier = Modifier.size(256.dp)
                )
            }

            PrimaryButton(
                text = stringResource(R.string.security__pin_disable_button),
                onClick = onDisableClick,
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Preview
@Composable
private fun Preview() {
    AppThemeSurface {
        DisablePinContent()
    }
}
