package to.bitkit.ui.settings.pin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.env.Env
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.KEY_DELETE
import to.bitkit.ui.components.PinDots
import to.bitkit.ui.components.NumberPadSimple
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun ChoosePinScreen(
    onPinChosen: (String) -> Unit,
    onBack: () -> Unit,
) {
    var pin by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .gradientBackground()
            .navigationBarsPadding()

    ) {
        SheetTopBar(titleText = stringResource(R.string.security__pin_choose_header), onBack = onBack)

        Spacer(modifier = Modifier.height(16.dp))

        BodyM(
            text = stringResource(R.string.security__pin_choose_text),
            color = Colors.White64,
            modifier = Modifier.padding(horizontal = 32.dp),
        )

        Spacer(modifier = Modifier.height(32.dp))
        Spacer(modifier = Modifier.weight(1f))

        PinDots(pin = pin)

        Spacer(modifier = Modifier.height(32.dp))

        NumberPadSimple(
            onPress = { key ->
                if (key == KEY_DELETE) {
                    if (pin.isNotEmpty()) {
                        pin = pin.dropLast(1)
                    }
                } else if (pin.length < Env.PIN_LENGTH) {
                    pin += key
                    if (pin.length == Env.PIN_LENGTH) {
                        onPinChosen(pin)
                    }
                }
            },
            modifier = Modifier
                .height(350.dp)
                .background(Colors.Black)
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        ChoosePinScreen(
            onPinChosen = {},
            onBack = {},
        )
    }
}
