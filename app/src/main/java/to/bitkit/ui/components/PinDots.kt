package to.bitkit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import to.bitkit.env.Env
import to.bitkit.ui.theme.Colors

@Composable
fun PinDots(
    pin: String,
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
                    .background(if (index < pin.length) Colors.Brand else Colors.Brand08)
            )
        }
    }
}
