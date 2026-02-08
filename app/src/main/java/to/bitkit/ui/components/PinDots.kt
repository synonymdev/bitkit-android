package to.bitkit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import to.bitkit.env.Env
import to.bitkit.ui.theme.Colors

fun mutableSecretOf(): MutableState<CharArray> = mutableStateOf(charArrayOf())

@Composable
fun PinDots(
    pinLength: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        repeat(Env.PIN_LENGTH) { index ->
            Box(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .border(1.dp, Colors.Brand, CircleShape)
                    .background(if (index < pinLength) Colors.Brand else Colors.Brand08)
            )
        }
    }
}
