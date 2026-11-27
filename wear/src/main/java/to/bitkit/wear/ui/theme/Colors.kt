// Tokens copied from app/src/main/java/to/bitkit/ui/theme/Colors.kt; keep both in sync until a shared module exists.
package to.bitkit.wear.ui.theme

import androidx.compose.ui.graphics.Color

object Colors {
    val Brand = Color(0xFFFF4400)
    val Red = Color(0xFFE95164)

    val Black = Color(0xFF000000)
    val White = Color(0xFFFFFFFF)

    val Gray7 = Color(0xFF101010)
    val Gray6 = Color(0xFF1C1C1C)
    val Gray5 = Color(0xFF2A2A2A)
    val Gray4 = Color(0xFF3A3A3A)
    val Gray3 = Color(0xFF48484A)
    val Gray1 = Color(0xFF8E8E8E)

    val White64 = Color.White.copy(alpha = 0.64f)
    val Brand24 = Brand.copy(alpha = 0.24f)
    val Red24 = Red.copy(alpha = 0.24f)
}
