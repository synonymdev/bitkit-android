package to.bitkit.ui.utils

import to.bitkit.ui.components.KEY_DECIMAL
import to.bitkit.ui.components.KEY_DELETE

object NumberPadInputHandler {
    fun handleInput(
        key: String,
        current: String,
        maxLength: Int,
        maxDecimals: Int,
    ): String {
        return if (maxDecimals == 0) {
            handleIntegerInput(key, current, maxLength)
        } else {
            handleDecimalInput(key, current, maxLength, maxDecimals)
        }
    }

    private fun handleIntegerInput(key: String, current: String, maxLength: Int): String {
        if (key == KEY_DELETE) return current.dropLast(1)

        if (current == "0") return key
        if (current.length >= maxLength) return current

        return current + key
    }

    @Suppress("ReturnCount")
    private fun handleDecimalInput(
        key: String,
        current: String,
        maxLength: Int,
        maxDecimals: Int,
    ): String {
        val parts = current.split(".", limit = 2)
        val decimalPart = if (parts.size > 1) parts[1] else ""

        if (key == KEY_DELETE) {
            if (current == "0.") return ""
            return current.dropLast(1)
        }

        if (current == "0" && key != ".") return key

        if (current.length >= maxLength) return current

        if (decimalPart.length >= maxDecimals) return current

        if (key == KEY_DECIMAL) {
            if (current.contains(".")) return current
            if (current.isEmpty()) return "0."
        }

        return current + key
    }
}
