package to.bitkit.ui.settings.pin

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.delay
import to.bitkit.R
import to.bitkit.env.Env
import to.bitkit.ui.appViewModel
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.KEY_DELETE
import to.bitkit.ui.components.PinDots
import to.bitkit.ui.components.NumberPadSimple
import to.bitkit.ui.navigateToHome
import to.bitkit.ui.navigateToChangePinResult
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.CloseNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun ChangePinConfirmScreen(
    newPin: String,
    navController: NavController,
) {
    val app = appViewModel ?: return
    var pin by remember { mutableStateOf("") }
    var showError by remember { mutableStateOf(false) }

    LaunchedEffect(pin) {
        if (pin.length == Env.PIN_LENGTH) {
            if (pin == newPin) {
                app.editPin(newPin)
                navController.navigateToChangePinResult()
            } else {
                showError = true
                delay(500)
                pin = ""
            }
        }
    }

    ChangePinConfirmContent(
        pin = pin,
        showError = showError,
        onKeyPress = { key ->
            if (key == KEY_DELETE) {
                if (pin.isNotEmpty()) {
                    pin = pin.dropLast(1)
                }
            } else if (pin.length < Env.PIN_LENGTH) {
                pin += key
            }
        },
        onBackClick = { navController.popBackStack() },
        onCloseClick = { navController.navigateToHome() },
    )
}

@Composable
private fun ChangePinConfirmContent(
    pin: String,
    showError: Boolean,
    onKeyPress: (String) -> Unit,
    onBackClick: () -> Unit,
    onCloseClick: () -> Unit,
) {
    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.security__cp_retype_title),
            onBackClick = onBackClick,
            actions = { CloseNavIcon(onClick = onCloseClick) },
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            BodyM(
                text = stringResource(R.string.security__cp_retype_text),
                color = Colors.White64,
            )

            Spacer(modifier = Modifier.height(32.dp))

            AnimatedVisibility(visible = showError) {
                BodyS(
                    text = stringResource(R.string.security__pin_not_match),
                    textAlign = TextAlign.Center,
                    color = Colors.Brand,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            PinDots(
                pin = pin,
                modifier = Modifier.padding(vertical = 16.dp),
            )

            Spacer(modifier = Modifier.weight(1f))

            NumberPadSimple(
                modifier = Modifier.height(350.dp),
                onPress = onKeyPress,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        ChangePinConfirmContent(
            pin = "12",
            showError = false,
            onKeyPress = {},
            onBackClick = {},
            onCloseClick = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewRetry() {
    AppThemeSurface {
        ChangePinConfirmContent(
            pin = "",
            showError = true,
            onKeyPress = {},
            onBackClick = {},
            onCloseClick = {},
        )
    }
}
