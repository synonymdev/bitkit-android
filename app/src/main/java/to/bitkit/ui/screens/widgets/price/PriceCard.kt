package to.bitkit.ui.screens.widgets.price

import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShapeDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
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
import to.bitkit.ui.components.BodySSB
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.Display34
import to.bitkit.ui.components.HorizontalSpacer
import to.bitkit.ui.components.Title
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun PriceCard(
    modifier: Modifier = Modifier,
    pricePreferences: PricePreferences,
    priceDTO: PriceDTO,
) {
    val widgetData = remember(pricePreferences.enabledPairs, priceDTO.widgets) {
        priceDTO.resolveWidget(pricePreferences)
    } ?: return

    Box(
        modifier = modifier
            .clip(shape = MaterialTheme.shapes.medium)
            .background(Colors.White10)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("price_card_pair_row_${widgetData.pair.displayName}")
            ) {
                Caption13Up(
                    text = "${widgetData.pair.displayName}  ${widgetData.period.value}",
                    color = Colors.White64,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("PriceWidgetRow-${widgetData.pair.displayName}")
                )
                HorizontalSpacer(16.dp)
                Title(
                    text = widgetData.change.formatted,
                    color = if (widgetData.change.isPositive) Colors.Green else Colors.Red,
                    modifier = Modifier.testTag("price_card_pair_change_${widgetData.pair}")
                )
            }

            Display34(
                text = "${widgetData.pair.symbol} ${widgetData.price}",
                color = Colors.White,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("price_card_pair_price_${widgetData.pair}")
            )

            ChartComponent(
                widgetData = widgetData,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("price_card_chart")
            )
        }
    }
}

@Composable
fun PriceCardSmall(
    modifier: Modifier = Modifier,
    pricePreferences: PricePreferences,
    priceDTO: PriceDTO,
) {
    val widgetData = remember(pricePreferences.enabledPairs, priceDTO.widgets) {
        priceDTO.resolveWidget(pricePreferences)
    } ?: return

    Box(
        modifier = modifier
            .clip(shape = MaterialTheme.shapes.medium)
            .background(Colors.White10)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("price_card_small_pair_row_${widgetData.pair.displayName}")
                ) {
                    Caption13Up(
                        text = widgetData.pair.displayName,
                        color = Colors.White64,
                    )
                    Caption13Up(
                        text = widgetData.period.value,
                        color = Colors.White64,
                    )
                }
                Title(
                    text = "${widgetData.pair.symbol} ${widgetData.price}",
                    color = Colors.White,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("price_card_small_pair_price_${widgetData.pair}")
                )
                BodySSB(
                    text = widgetData.change.formatted,
                    color = if (widgetData.change.isPositive) Colors.Green else Colors.Red,
                    modifier = Modifier.testTag("price_card_small_pair_change_${widgetData.pair}")
                )
            }

            ChartComponent(
                widgetData = widgetData,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .testTag("price_card_small_chart")
            )
        }
    }
}

private fun PriceDTO.resolveWidget(prefs: PricePreferences): PriceWidgetData? =
    widgets.firstOrNull { it.pair in prefs.enabledPairs } ?: widgets.firstOrNull()

@Composable
fun ChartComponent(
    widgetData: PriceWidgetData,
    modifier: Modifier = Modifier,
) {
    val baseColor = if (widgetData.change.isPositive) Colors.Green else Colors.Red

    val minValue = remember(widgetData.pastValues) {
        widgetData.pastValues.minOrNull() ?: 0.0
    }
    val maxValue = remember(widgetData.pastValues) {
        widgetData.pastValues.maxOrNull() ?: 1.0
    }

    Box(
        modifier = modifier.clip(ShapeDefaults.Small)
    ) {
        if (LocalInspectionMode.current) {
            Image(
                painter = painterResource(R.drawable.chart_preview),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize()
            )
            return@Box
        }

        LineChart(
            data = remember(widgetData.pastValues, baseColor) {
                listOf(
                    Line(
                        label = widgetData.pair.displayName,
                        values = widgetData.pastValues,
                        color = SolidColor(baseColor),
                        strokeAnimationSpec = tween(1000, easing = EaseInOutCubic),
                        gradientAnimationDelay = 1000,
                        drawStyle = DrawStyle.Stroke(width = 1.dp),
                        curvedEdges = true,
                    ),
                )
            },
            labelProperties = LabelProperties(
                enabled = false,
            ),
            labelHelperProperties = LabelHelperProperties(
                enabled = false,
            ),
            gridProperties = GridProperties(
                enabled = false,
            ),
            indicatorProperties = HorizontalIndicatorProperties(
                enabled = false,
            ),
            dividerProperties = DividerProperties(
                enabled = false,
            ),
            minValue = minValue,
            maxValue = maxValue,
            modifier = Modifier.fillMaxSize()
        )
    }
}

private val SAMPLE_PAST_VALUES = listOf(1.0, 2.0, 1.5, 3.0, 2.5, 4.0)

@Preview(showBackground = true)
@Composable
private fun FullBlockCardPreview() {
    AppThemeSurface {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            PriceCard(
                pricePreferences = PricePreferences(),
                priceDTO = PriceDTO(
                    widgets = listOf(
                        PriceWidgetData(
                            pair = TradingPair.BTC_USD,
                            change = Change(
                                isPositive = true,
                                formatted = "+1.24%",
                            ),
                            price = "75,326",
                            pastValues = SAMPLE_PAST_VALUES,
                            period = GraphPeriod.ONE_DAY,
                        ),
                    ),
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
