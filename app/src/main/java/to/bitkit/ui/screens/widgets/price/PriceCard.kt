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
import androidx.compose.material3.ShapeDefaults
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
import ir.ehsannarmani.compose_charts.models.DividerProperties
import ir.ehsannarmani.compose_charts.models.DrawStyle
import ir.ehsannarmani.compose_charts.models.GridProperties
import ir.ehsannarmani.compose_charts.models.HorizontalIndicatorProperties
import ir.ehsannarmani.compose_charts.models.LabelHelperProperties
import ir.ehsannarmani.compose_charts.models.LabelProperties
import ir.ehsannarmani.compose_charts.models.Line
import to.bitkit.R
import to.bitkit.data.dto.price.Change
import to.bitkit.data.dto.price.GraphPeriod
import to.bitkit.data.dto.price.PriceDTO
import to.bitkit.data.dto.price.PriceWidgetData
import to.bitkit.data.dto.price.TradingPair
import to.bitkit.models.widget.PricePreferences
import to.bitkit.ui.components.BodyMSB
import to.bitkit.ui.components.BodySB
import to.bitkit.ui.components.CaptionB
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
                        .testTag("price_card_widget_title_row")
                ) {
                    Icon(
                        painter = painterResource(R.drawable.widget_chart_line),
                        contentDescription = null,
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("price_card_widget_title_icon"),
                        tint = Color.Unspecified
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    BodyMSB(
                        text = stringResource(R.string.widgets__price__name),
                        modifier = Modifier.testTag("price_card_widget_title_text")
                    )
                }
            }

            val enabledPairs = remember(priceDTO.widgets) {
                priceDTO.widgets.filter { widgetData -> widgetData.pair in pricePreferences.enabledPairs }
            }

            enabledPairs.map { widgetData ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("price_card_pair_row_${widgetData.pair.displayName}"),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        BodySB(
                            text = widgetData.pair.displayName,
                            color = Colors.White64,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("price_card_pair_label_${widgetData.pair}")
                        )

                        BodySB(
                            text = widgetData.change.formatted,
                            color = if (widgetData.change.isPositive) Colors.Green else Colors.Red,
                            modifier = Modifier.testTag("price_card_pair_change_${widgetData.pair}")
                        )

                        Spacer(modifier = Modifier.width(16.dp))

                        BodySB(
                            text = widgetData.price,
                            color = Colors.White,
                            modifier = Modifier.testTag("price_card_pair_price_${widgetData.pair}")
                        )
                    }
                }

            val chartData = remember(enabledPairs) {
                if (enabledPairs.isNotEmpty()) enabledPairs.first() else priceDTO.widgets.firstOrNull()
            }

            chartData?.let { firstPriceData ->
                ChartComponent(
                    widgetData = firstPriceData,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.63.dp)
                        .testTag("price_card_chart")
                )
            }
        }
    }
}

@Composable
fun ChartComponent(
    widgetData: PriceWidgetData,
    modifier: Modifier = Modifier
) {
    val baseColor = if (widgetData.change.isPositive) Colors.Green else Colors.Red

    val minValue = remember(widgetData.pastValues) {
        widgetData.pastValues.minOrNull() ?: 0.0
    }
    val maxValue = remember(widgetData.pastValues) {
        widgetData.pastValues.maxOrNull() ?: 1.0
    }

    Box(
        modifier = modifier
            .height(96.dp)
            .clip(ShapeDefaults.Small)
    ) {
        LineChart(
            modifier = Modifier.fillMaxSize(),
            data = remember(widgetData.pastValues, baseColor) {
                listOf(
                    Line(
                        label = widgetData.pair.displayName,
                        values = widgetData.pastValues,
                        color = SolidColor(baseColor),
                        firstGradientFillColor = baseColor.copy(alpha = 0.8f),
                        secondGradientFillColor = baseColor.copy(alpha = 0.3f),
                        drawStyle = DrawStyle.Stroke(width = 1.dp),
                        curvedEdges = true
                    )
                )
            },
            labelProperties = LabelProperties(
                enabled = false
            ),
            labelHelperProperties = LabelHelperProperties(
                enabled = false
            ),
            gridProperties = GridProperties(
                enabled = false
            ),
            indicatorProperties = HorizontalIndicatorProperties(
                enabled = false
            ),
            dividerProperties = DividerProperties(
                enabled = false
            ),
            minValue = minValue,
            maxValue = maxValue
        )

        CaptionB(
            text = widgetData.period.value,
            color = baseColor,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(7.dp)
        )
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
                            pair = TradingPair.BTC_USD,
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
                            ),
                            period = GraphPeriod.ONE_DAY,
                        ),
                        PriceWidgetData(
                            pair = TradingPair.BTC_USD,
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
                            ),
                            period = GraphPeriod.ONE_DAY,
                        ),
                    ),
                )
            )
        }
    }
}
