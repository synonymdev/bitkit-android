package to.bitkit.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Devices.NEXUS_5
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.models.BitcoinDisplayUnit
import to.bitkit.models.PrimaryDisplay
import to.bitkit.repositories.CurrencyState
import to.bitkit.ui.LocalCurrencies
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.shared.modifiers.clickableAlpha
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.viewmodels.AmountInputViewModel
import to.bitkit.viewmodels.previewAmountInputViewModel

const val KEY_DELETE = "delete"
const val KEY_000 = "000"
const val KEY_DECIMAL = "."
private val defaultHeight = 300.dp
private val idealButtonHeight = 75.dp
private val minButtonHeight = 50.dp
private const val ROWS = 4
private const val COLUMNS = 3
private const val ALPHA_PRESSED = 0.2f
private val pressHaptic = HapticFeedbackType.VirtualKey
private val errorHaptic = HapticFeedbackType.Reject

/**
 * Numeric keyboard.
 */
@Composable
fun NumberPad(
    onPress: (String) -> Unit,
    modifier: Modifier = Modifier,
    type: NumberPadType = NumberPadType.SIMPLE,
    availableHeight: Dp = defaultHeight,
    errorKey: String? = null,
) {
    BoxWithConstraints(modifier = modifier) {
        val buttonHeight = when {
            constraints.hasFixedHeight -> maxHeight / ROWS
            else -> (availableHeight / ROWS).coerceIn(minButtonHeight, idealButtonHeight)
        }

        val totalKeyboardHeight = buttonHeight * ROWS

        LazyVerticalGrid(
            columns = GridCells.Fixed(COLUMNS),
            userScrollEnabled = false,
            modifier = Modifier.height(totalKeyboardHeight),
        ) {
            items((1..9).map { "$it" }) { number ->
                NumberPadKeyButton(
                    text = number,
                    onPress = onPress,
                    height = buttonHeight,
                    hasError = errorKey == number,
                )
            }
            item {
                when (type) {
                    NumberPadType.SIMPLE -> Box(
                        modifier = Modifier
                            .height(buttonHeight)
                            .fillMaxWidth()
                    )

                    NumberPadType.INTEGER -> NumberPadKeyButton(
                        text = KEY_000,
                        onPress = onPress,
                        height = buttonHeight,
                        hasError = errorKey == KEY_000,
                        testTag = "N000",
                    )

                    NumberPadType.DECIMAL -> NumberPadKeyButton(
                        text = KEY_DECIMAL,
                        onPress = onPress,
                        height = buttonHeight,
                        hasError = errorKey == KEY_DECIMAL,
                        testTag = "NDecimal",
                    )
                }
            }
            item {
                NumberPadKeyButton(
                    text = "0",
                    onPress = onPress,
                    height = buttonHeight,
                    hasError = errorKey == "0",
                )
            }
            item {
                NumberPadDeleteButton(
                    onPress = { onPress(KEY_DELETE) },
                    height = buttonHeight,
                    modifier = Modifier.testTag("NRemove"),
                )
            }
        }
    }
}

/**
 * Numeric keyboard for amount input. Can be used together with [NumberPadTextField].
 */
@Composable
fun NumberPad(
    viewModel: AmountInputViewModel,
    modifier: Modifier = Modifier,
    currencies: CurrencyState = LocalCurrencies.current,
    type: NumberPadType = viewModel.getNumberPadType(currencies),
    availableHeight: Dp = defaultHeight,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    NumberPad(
        onPress = { key -> viewModel.handleNumberPadInput(key, currencies) },
        modifier = modifier,
        type = type,
        availableHeight = availableHeight,
        errorKey = uiState.errorKey,
    )
}

enum class NumberPadType { SIMPLE, INTEGER, DECIMAL }

@Composable
fun NumberPadKeyButton(
    text: String,
    onPress: (String) -> Unit,
    height: Dp,
    modifier: Modifier = Modifier,
    hasError: Boolean = false,
    testTag: String = "N$text",
) {
    NumberPadKey(
        onClick = { onPress(text) },
        height = height,
        haptic = if (hasError) errorHaptic else pressHaptic,
        modifier = modifier.testTag(testTag),
    ) {
        Text(
            text = text,
            fontSize = when {
                height < 60.dp -> 20.sp
                height < 70.dp -> 22.sp
                else -> 24.sp
            },
            textAlign = TextAlign.Center,
            color = if (hasError) Colors.Red else Colors.White,
        )
    }
}

@Composable
internal fun NumberPadDeleteButton(
    onPress: () -> Unit,
    height: Dp,
    modifier: Modifier = Modifier,
) {
    NumberPadKeyIcon(
        icon = R.drawable.ic_backspace,
        contentDescription = stringResource(R.string.common__delete),
        onClick = onPress,
        height = height,
        modifier = modifier,
    )
}

@Composable
fun NumberPadKeyIcon(
    @DrawableRes icon: Int,
    contentDescription: String?,
    onClick: () -> Unit,
    height: Dp,
    modifier: Modifier = Modifier,
) {
    NumberPadKey(
        onClick = onClick,
        height = height,
        modifier = modifier,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = contentDescription,
        )
    }
}

@Composable
fun NumberPadKey(
    onClick: () -> Unit,
    height: Dp,
    modifier: Modifier = Modifier,
    haptic: HapticFeedbackType = pressHaptic,
    content: @Composable (BoxScope.() -> Unit),
) {
    val haptics = LocalHapticFeedback.current
    Box(
        content = content,
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(height)
            .fillMaxWidth()
            .clickableAlpha(ALPHA_PRESSED) {
                haptics.performHapticFeedback(haptic)
                onClick()
            },
    )
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        ScreenColumn {
            FillHeight()
            NumberPad(
                viewModel = previewAmountInputViewModel(),
            )
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewClassic() {
    AppThemeSurface {
        ScreenColumn {
            FillHeight()
            NumberPad(
                viewModel = previewAmountInputViewModel(),
                currencies = CurrencyState(
                    displayUnit = BitcoinDisplayUnit.CLASSIC,
                ),
            )
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewFiat() {
    AppThemeSurface {
        ScreenColumn {
            FillHeight()
            NumberPad(
                viewModel = previewAmountInputViewModel(),
                currencies = CurrencyState(
                    primaryDisplay = PrimaryDisplay.FIAT,
                ),
            )
        }
    }
}

@Preview(showSystemUi = true, device = NEXUS_5)
@Composable
private fun PreviewSmall() {
    AppThemeSurface {
        ScreenColumn {
            FillHeight()
            NumberPad(
                viewModel = previewAmountInputViewModel(),
            )
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewSimple() {
    AppThemeSurface {
        ScreenColumn {
            FillHeight()
            NumberPad(
                onPress = {},
                type = NumberPadType.SIMPLE,
            )
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewHeight() {
    AppThemeSurface {
        ScreenColumn {
            FillHeight()
            NumberPad(
                onPress = {},
                type = NumberPadType.SIMPLE,
                modifier = Modifier.height(350.dp),
            )
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewMaxHeight() {
    AppThemeSurface {
        ScreenColumn {
            FillHeight()
            NumberPad(
                onPress = {},
                type = NumberPadType.SIMPLE,
                availableHeight = 350.dp,
            )
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewHeightXs() {
    AppThemeSurface {
        ScreenColumn {
            FillHeight()
            NumberPad(
                onPress = {},
                type = NumberPadType.SIMPLE,
                modifier = Modifier.height(100.dp),
            )
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewMaxHeightXs() {
    AppThemeSurface {
        ScreenColumn {
            FillHeight()
            NumberPad(
                onPress = {},
                type = NumberPadType.SIMPLE,
                availableHeight = 100.dp,
            )
        }
    }
}
