package to.bitkit.ui.screens.widgets.price

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ir.ehsannarmani.compose_charts.LineChart
import ir.ehsannarmani.compose_charts.models.DrawStyle
import ir.ehsannarmani.compose_charts.models.Line
import to.bitkit.R
import to.bitkit.data.dto.price.Change
import to.bitkit.data.dto.price.PriceDTO
import to.bitkit.data.dto.price.PriceWidgetData
import to.bitkit.models.widget.PricePreferences
import to.bitkit.ui.components.BodyMSB
import to.bitkit.ui.components.BodySB
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun PriceCard(
    modifier: Modifier = Modifier,
    showWidgetTitle: Boolean,
    pricePreferences: PricePreferences,
    priceDTO: PriceDTO
) {
    Box(
        modifier = modifier
            .clip(shape = MaterialTheme.shapes.medium)
            .background(Colors.White10)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (showWidgetTitle) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .padding(bottom = 8.dp)
                        .testTag("block_card_widget_title_row")
                ) {
                    Icon(
                        painter = painterResource(R.drawable.widget_chart_line),
                        contentDescription = null,
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("block_card_widget_title_icon"),
                        tint = Color.Unspecified
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    BodyMSB(
                        text = stringResource(R.string.widgets__price__name),
                        modifier = Modifier.testTag("block_card_widget_title_text")
                    )
                }
            }

            priceDTO.widgets.map { widgetData ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("block_card_block_row"),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    BodySB(
                        text = widgetData.name,
                        color = Colors.White64,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("block_card_block_label")
                    )

                    BodySB(
                        text = widgetData.change.formatted,
                        color = if (widgetData.change.isPositive) Colors.Green else Colors.Red,
                        modifier = Modifier.testTag("block_card_block_text")
                    )

                    Spacer(modifier = Modifier.width(16.dp))

                    BodySB(
                        text = widgetData.price,
                        color = Colors.White,
                        modifier = Modifier.testTag("block_card_block_text")
                    )
                }
            }

            priceDTO.widgets.firstOrNull()?.let { firstPriceData ->
                LineChart(
                    modifier = Modifier.fillMaxWidth().height(96.dp),
                    data = remember {
                        listOf(
                            Line(
                                label = firstPriceData.name,
                                values = firstPriceData.pastValues,
                                color = SolidColor(
                                    if (firstPriceData.change.isPositive) Colors.Green else Colors.Red,
                                ),
                                firstGradientFillColor = if (firstPriceData.change.isPositive) Colors.Green else Colors.Red,
                                secondGradientFillColor = Color.Transparent,
                                drawStyle = DrawStyle.Stroke(width = 2.dp),
                            )
                        )
                    },
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun FullBlockCardPreview() {
    AppThemeSurface {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PriceCard(
                modifier = Modifier.fillMaxWidth(),
                showWidgetTitle = true,
                pricePreferences = PricePreferences(),
                priceDTO = PriceDTO(
                    widgets = listOf(
                        PriceWidgetData(
                            name = "BTC/USD",
                            change = Change(
                                isPositive = true,
                                formatted = "$ 20,326"
                            ),
                            price = "$20,326",
                            pastValues = listOf(
                                1.0,
                                2.0,
                                3.0,
                                4.0,
                            )
                        ),
                        PriceWidgetData(
                            name = "BTC/EUR",
                            change = Change(
                                isPositive = false,
                                formatted = "€ 20,326"
                            ),
                            price = "€ 20,326",
                            pastValues = listOf(
                                1.0,
                                2.0,
                                3.0,
                                4.0,
                            )
                        ),
                    ),
                )
            )
        }
    }
}
