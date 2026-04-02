package to.bitkit.ui.utils

import androidx.compose.ui.unit.dp
import to.bitkit.env.Env
import to.bitkit.ui.components.KEY_DELETE

val NumberPadHeight = 350.dp

fun handlePinKeyPress(currentPin: String, key: String): String = when {
    key == KEY_DELETE -> currentPin.dropLast(1)
    currentPin.length < Env.PIN_LENGTH -> currentPin + key
    else -> currentPin
}
