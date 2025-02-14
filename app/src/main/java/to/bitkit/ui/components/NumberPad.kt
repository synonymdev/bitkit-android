package to.bitkit.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.sp
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

private val matrix = listOf(
    listOf("1", "2", "3"),
    listOf("4", "5", "6"),
    listOf("7", "8", "9")
)

@Composable
private fun NumberButton(
    text: String,
    hasError: Boolean = false,
    onPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onPress,
        color = Color.Transparent,
        modifier = modifier.fillMaxSize()
    ) {
        Box(
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                fontSize = 24.sp,
                color = if (hasError) Colors.Brand else Color.Unspecified,
                textAlign = TextAlign.Center
            )
        }
    }
}

enum class NumberPadType {
    SIMPLE, INTEGER, DECIMAL
}

@Composable
fun NumberPad(
    type: NumberPadType,
    onPress: (String) -> Unit,
    errorKey: String? = null,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.SpaceEvenly
    ) {
        matrix.forEach { row ->
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                row.forEach { number ->
                    NumberButton(
                        text = number,
                        hasError = errorKey == number,
                        onPress = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onPress(number)
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        Row(
            horizontalArrangement = Arrangement.SpaceEvenly,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            when (type) {
                NumberPadType.SIMPLE -> {
                    Spacer(modifier = Modifier.weight(1f))
                }
                NumberPadType.INTEGER -> {
                    NumberButton(
                        text = "000",
                        hasError = errorKey == "000",
                        onPress = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onPress("000")
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
                NumberPadType.DECIMAL -> {
                    NumberButton(
                        text = ".",
                        hasError = errorKey == ".",
                        onPress = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onPress(".")
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            NumberButton(
                text = "0",
                hasError = errorKey == "0",
                onPress = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onPress("0")
                },
                modifier = Modifier.weight(1f)
            )

            Surface(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onPress("delete")
                },
                color = Color.Transparent,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.Backspace,
                        contentDescription = "Delete"
                    )
                }
            }
        }
    }
}

@Preview
@Composable
private fun NumberPadPreview() {
    AppThemeSurface {
        NumberPad(type = NumberPadType.INTEGER, onPress = {})
    }
}

fun handleNumberPadPress(
    key: String,
    current: String,
    maxLength: Int = 20,
    maxDecimals: Int = 2,
): String {
    val parts = current.split(".")
    val integer = parts[0]
    val decimals = parts.getOrNull(1) ?: ""

    return when {
        key == "delete" -> {
            when {
                current.endsWith("0.") -> ""
                decimals.length >= maxDecimals -> "$integer.${decimals.substring(0, maxDecimals - 1)}"
                else -> current.dropLast(1)
            }
        }
        current == "0" && key != "." && key != "delete" -> key // No leading zeros
        current.length == maxLength -> current // Limit max length
        decimals.length >= maxDecimals -> current // Limit max decimals
        key == "." -> when {
            current.contains(".") -> current // No multiple decimals
            current.isEmpty() -> "0$key" // Add leading zero
            else -> "$current$key"
        }
        else -> "$current$key"
    }
}
