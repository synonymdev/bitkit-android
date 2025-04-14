package to.bitkit.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val Shapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(4.dp), // // TODO set to 8.dp & replace all current .shapes.small with extraSmall
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(32.dp),
    extraLarge = RoundedCornerShape(64.dp),
)

object AppShapes {
    val sheet = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    val small = RoundedCornerShape(8.dp)
    val smallButton = small
    val smallInput = small
}
