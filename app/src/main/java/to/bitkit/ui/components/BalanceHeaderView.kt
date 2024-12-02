package to.bitkit.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import to.bitkit.models.ConvertedAmount
import to.bitkit.ui.currencyViewModel
import to.bitkit.viewmodels.PrimaryDisplay

@Composable
fun BalanceHeaderView(
    sats: Long,
    modifier: Modifier = Modifier,
    prefix: String? = null,
    showBitcoinSymbol: Boolean = true,
) {
    val currency = currencyViewModel ?: return
    val rates by currency.rates.collectAsState()
    val primaryDisplay by currency.primaryDisplay.collectAsState(PrimaryDisplay.BITCOIN)
    val converted: ConvertedAmount? = if (rates.isNotEmpty()) currency.convert(sats = sats) else null

    var isPressed by remember { mutableStateOf(false) }

    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.Start,
        modifier = modifier
            .graphicsLayer {
                alpha = if (isPressed) 0.5f else 1f
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        tryAwaitRelease()
                        isPressed = false
                    },
                    onTap = {
                        currency.togglePrimaryDisplay()
                    }
                )
            }
    ) {
        converted?.let { converted ->
            AnimatedContent(
                targetState = primaryDisplay,
                transitionSpec = {
                    val direction = if (targetState == PrimaryDisplay.BITCOIN)
                        AnimatedContentTransitionScope.SlideDirection.Up
                    else
                        AnimatedContentTransitionScope.SlideDirection.Down

                    slideIntoContainer(
                        towards = direction,
                        animationSpec = spring(
                            dampingRatio = 0.8f,
                            stiffness = Spring.StiffnessMedium
                        )
                    ) + fadeIn(
                        animationSpec = spring(
                            dampingRatio = 0.8f,
                            stiffness = Spring.StiffnessMedium
                        )
                    ) togetherWith
                        slideOutOfContainer(
                            towards = direction,
                            animationSpec = spring(
                                dampingRatio = 0.8f,
                                stiffness = Spring.StiffnessMedium
                            )
                        ) + fadeOut(
                        animationSpec = spring(
                            dampingRatio = 0.8f,
                            stiffness = Spring.StiffnessMedium
                        )
                    )
                },
                contentAlignment = Alignment.TopStart,
                label = "ConversionDisplay"
            ) { display ->
                if (display == PrimaryDisplay.BITCOIN) {
                    Column {
                        // Bitcoin small row
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .height(24.dp)
                                .fillMaxWidth()
                                .padding(bottom = 4.dp)
                        ) {
                            if (prefix != null) {
                                Text(
                                    text = prefix,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.alpha(0.6f)
                                )
                            }
                            Text(
                                text = "${converted.symbol} ${converted.formatted}",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.alpha(0.6f)
                            )
                        }

                        // Bitcoin large row
                        val btcComponents = converted.bitcoinDisplay(currency.displayUnit)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.height(62.dp)
                        ) {
                            if (prefix != null) {
                                Text(
                                    text = prefix,
                                    fontSize = 46.sp,
                                    fontWeight = FontWeight.Black,
                                    modifier = Modifier.alpha(0.6f)
                                )
                            }
                            if (showBitcoinSymbol) {
                                Text(
                                    text = btcComponents.symbol,
                                    fontSize = 46.sp,
                                    fontWeight = FontWeight.Black,
                                    modifier = Modifier
                                        .alpha(0.6f)
                                        .padding(end = 8.dp)
                                )
                            }
                            Text(
                                text = btcComponents.value,
                                fontSize = 46.sp,
                                fontWeight = FontWeight.Black,
                            )
                        }
                    }
                } else {  // FIAT
                    Column {
                        // Fiat small row
                        val btcComponents = converted.bitcoinDisplay(currency.displayUnit)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .height(24.dp)
                                .padding(bottom = 4.dp)
                        ) {
                            if (prefix != null) {
                                Text(
                                    text = prefix,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.alpha(0.6f)
                                )
                            }
                            Text(
                                text = "${btcComponents.symbol} ${btcComponents.value}",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.alpha(0.6f)
                            )
                        }

                        // Fiat large row
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.height(62.dp)
                        ) {
                            if (prefix != null) {
                                Text(
                                    text = prefix,
                                    fontSize = 46.sp,
                                    fontWeight = FontWeight.Black,
                                    modifier = Modifier.alpha(0.6f)
                                )
                            }
                            Text(
                                text = converted.symbol,
                                fontSize = 46.sp,
                                fontWeight = FontWeight.Black,
                                modifier = Modifier
                                    .alpha(0.6f)
                                    .padding(end = 8.dp)
                            )
                            Text(
                                text = converted.formatted,
                                fontSize = 46.sp,
                                fontWeight = FontWeight.Black,
                            )
                        }
                    }
                }
            }
        }
    }
}
