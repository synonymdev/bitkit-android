package to.bitkit.ui.screens.widgets.calculator.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import to.bitkit.ui.components.BodyMSB
import to.bitkit.ui.components.CaptionB
import to.bitkit.ui.shared.modifiers.clickableAlpha
import to.bitkit.ui.theme.AppTextStyles
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

val inputShape @Composable get() = MaterialTheme.shapes.small
private val BRING_INPUT_INTO_VIEW_DELAY = 120.milliseconds
private val CURSOR_WIDTH = 2.dp
private val CURSOR_HEIGHT = 22.dp
private val CURSOR_BLINK_INTERVAL = 500.milliseconds

@Composable
fun CalculatorInput(
    value: String,
    currencySymbol: String,
    currencyName: String,
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
) {
    val displayCurrencySymbol = currencySymbol.toCalculatorDisplaySymbol()
    val bringIntoViewRequester = remember { BringIntoViewRequester() }

    LaunchedEffect(isActive) {
        if (!isActive) return@LaunchedEffect
        delay(BRING_INPUT_INTO_VIEW_DELAY)
        bringIntoViewRequester.bringIntoView()
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .bringIntoViewRequester(bringIntoViewRequester)
            .clip(inputShape)
            .background(Colors.Black)
            .clickableAlpha(onClick = onClick)
            .padding(16.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .background(color = Colors.Gray6, shape = CircleShape)
                .size(32.dp)
        ) {
            BodyMSB(displayCurrencySymbol, color = Colors.Brand)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            InputValue(
                value = value,
                placeholder = placeholder,
                isActive = isActive,
                modifier = Modifier.weight(weight = 1f, fill = false)
            )
        }
        CaptionB(
            text = currencyName.uppercase(),
            color = Colors.Gray1,
            maxLines = 1,
        )
    }
}

@Composable
private fun InputValue(
    value: String,
    placeholder: String,
    isActive: Boolean,
    modifier: Modifier = Modifier,
) {
    var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val text = remember(value, placeholder) {
        buildAnnotatedString {
            append(value)
            withStyle(SpanStyle(color = Colors.White50)) {
                append(placeholder)
            }
        }
    }

    Box(modifier = modifier) {
        Text(
            text = text,
            style = AppTextStyles.BodyMSB.merge(
                color = MaterialTheme.colorScheme.primary,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { textLayout = it },
        )
        if (isActive) {
            Cursor(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset {
                        IntOffset(
                            x = textLayout.cursorOffset(value).roundToInt(),
                            y = 0,
                        )
                    }
            )
        }
    }
}

private fun TextLayoutResult?.cursorOffset(value: String): Float {
    if (this == null || value.isEmpty()) return 0f
    return getCursorRect(value.length).left
}

@Composable
private fun Cursor(modifier: Modifier = Modifier) {
    var isVisible by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(CURSOR_BLINK_INTERVAL)
            isVisible = !isVisible
        }
    }

    Box(
        modifier = modifier
            .width(CURSOR_WIDTH)
            .height(CURSOR_HEIGHT)
            .background(if (isVisible) Colors.Brand else Color.Transparent)
    )
}

internal fun String.toCalculatorDisplaySymbol(): String {
    val symbol = trim()
    return if (symbol.length >= 3) {
        symbol.take(1)
    } else {
        symbol
    }
}

@Preview
@Composable
private fun Preview() {
    AppThemeSurface {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(16.dp)
        ) {
            CalculatorInput(
                value = "123 456 789",
                currencySymbol = "₿",
                currencyName = "BITCOIN",
                isActive = true,
                onClick = {},
                modifier = Modifier.fillMaxWidth()
            )
            CalculatorInput(
                value = "82,209.8",
                currencySymbol = "$",
                currencyName = "USD",
                isActive = true,
                onClick = {},
                placeholder = "0",
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
