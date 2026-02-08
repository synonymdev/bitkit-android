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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.delay
import to.bitkit.R
import to.bitkit.domain.models.secretOf
import to.bitkit.env.Env
import to.bitkit.ui.appViewModel
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.KEY_DELETE
import to.bitkit.ui.components.NumberPad
import to.bitkit.ui.components.NumberPadType
import to.bitkit.ui.components.PinDots
import to.bitkit.ui.components.mutableSecretOf
import to.bitkit.ui.navigateToChangePinResult
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun ChangePinConfirmScreen(
    navController: NavController,
) {
    val app = appViewModel ?: return
    var pin by remember { mutableSecretOf() }
    var showError by remember { mutableStateOf(false) }

    LaunchedEffect(pin) {
        if (pin.size == Env.PIN_LENGTH) {
            val matches = app.consumePendingPin()?.let { pending ->
                val result = pending.peek { it.contentEquals(pin) }
                if (result) {
                    app.editPin(secretOf(pin.copyOf()))
                    pending.wipe()
                }
                result
            } ?: false
            if (matches) {
                navController.navigateToChangePinResult()
            } else {
                showError = true
                delay(500)
                pin = charArrayOf()
            }
        }
    }

    ChangePinConfirmContent(
        pinLength = pin.size,
        showError = showError,
        onKeyPress = { key ->
            if (key == KEY_DELETE) {
                if (pin.isNotEmpty()) {
                    pin = pin.sliceArray(0 until pin.size - 1)
                }
            } else if (pin.size < Env.PIN_LENGTH) {
                pin = pin + key[0]
            }
        },
        onBackClick = { navController.popBackStack() },
    )
}

@Composable
private fun ChangePinConfirmContent(
    pinLength: Int,
    showError: Boolean,
    onKeyPress: (String) -> Unit,
    onBackClick: () -> Unit,
) {
    ScreenColumn(
        modifier = Modifier.testTag("ChangePIN2")
    ) {
        AppTopBar(
            titleText = stringResource(R.string.security__cp_retype_title),
            onBackClick = onBackClick,
            actions = { DrawerNavIcon() },
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
                    text = stringResource(R.string.security__cp_try_again),
                    textAlign = TextAlign.Center,
                    color = Colors.Brand,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("WrongPIN")
                )
            }

            PinDots(
                pinLength = pinLength,
                modifier = Modifier.padding(vertical = 16.dp),
            )

            Spacer(modifier = Modifier.weight(1f))

            NumberPad(
                onPress = onKeyPress,
                type = NumberPadType.SIMPLE,
                modifier = Modifier.height(350.dp),
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        ChangePinConfirmContent(
            pinLength = 2,
            showError = false,
            onKeyPress = {},
            onBackClick = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewRetry() {
    AppThemeSurface {
        ChangePinConfirmContent(
            pinLength = 0,
            showError = true,
            onKeyPress = {},
            onBackClick = {},
        )
    }
}
