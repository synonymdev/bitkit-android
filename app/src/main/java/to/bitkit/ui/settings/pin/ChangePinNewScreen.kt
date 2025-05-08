package to.bitkit.ui.settings.pin

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import to.bitkit.R
import to.bitkit.env.Env
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.KEY_DELETE
import to.bitkit.ui.components.PinDots
import to.bitkit.ui.components.NumberPadSimple
import to.bitkit.ui.navigateToChangePinConfirm
import to.bitkit.ui.navigateToHome
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.CloseNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun ChangePinNewScreen(
    navController: NavController,
) {
    var pin by remember { mutableStateOf("") }

    LaunchedEffect(pin) {
        if (pin.length == Env.PIN_LENGTH) {
            navController.navigateToChangePinConfirm(pin)
        }
    }

    ChangePinNewContent(
        pin = pin,
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
private fun ChangePinNewContent(
    pin: String,
    onKeyPress: (String) -> Unit,
    onBackClick: () -> Unit,
    onCloseClick: () -> Unit,
) {
    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.security__cp_setnew_title),
            onBackClick = onBackClick,
            actions = { CloseNavIcon(onClick = onCloseClick) },
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            BodyM(
                text = stringResource(R.string.security__cp_setnew_text),
                color = Colors.White64,
            )

            Spacer(modifier = Modifier.height(32.dp))

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
        ChangePinNewContent(
            pin = "12",
            onKeyPress = {},
            onBackClick = {},
            onCloseClick = {},
        )
    }
}
