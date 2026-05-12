package to.bitkit.appwidget.ui.calculator

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.WidthModifier
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.Text
import androidx.glance.unit.Dimension
import to.bitkit.R
import to.bitkit.appwidget.ui.components.GlanceLayoutDimens
import to.bitkit.appwidget.ui.components.GlanceWidgetScaffold
import to.bitkit.appwidget.ui.components.HorizontalSpacer
import to.bitkit.appwidget.ui.components.VerticalSpacer
import to.bitkit.appwidget.ui.theme.GlanceColors
import to.bitkit.appwidget.ui.theme.GlanceTextStyles
import to.bitkit.models.BITCOIN_SYMBOL
import to.bitkit.models.widget.CalculatorValues
import to.bitkit.repositories.CurrencyState
import to.bitkit.ui.MainActivity
import to.bitkit.ui.screens.widgets.calculator.components.toCalculatorDisplaySymbol
import to.bitkit.ui.screens.widgets.calculator.formatBitcoinValue
import to.bitkit.ui.screens.widgets.calculator.formatFiatValue
import to.bitkit.ui.theme.Colors
import java.util.Locale

private val ROW_RADIUS = 8.dp
private val WIDE_ICON_SIZE = 32.dp
private val COMPACT_ICON_SIZE = 24.dp
private val RowBackground = ColorProvider(day = Colors.Black, night = Colors.Black)
private val IconBackground = ColorProvider(day = Colors.Gray6, night = Colors.Gray6)
private val BrandColor = ColorProvider(day = Colors.Brand, night = Colors.Brand)

@Suppress("RestrictedApi")
@Composable
fun CalculatorGlanceContent(
    values: CalculatorValues,
    currencyState: CurrencyState,
) {
    val context = LocalContext.current
    val btcValue = formatBitcoinValue(values.btcValue, currencyState.displayUnit)
    val fiatValue = formatFiatValue(values.fiatValue)
    val bitcoinLabel = context.getString(R.string.settings__general__unit_bitcoin).uppercase(Locale.getDefault())
    val openCalculatorIntent = Intent(context, MainActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }

    GlanceWidgetScaffold(onClick = actionStartActivity(openCalculatorIntent)) {
        if (LocalSize.current.width >= GlanceLayoutDimens.WIDE_LAYOUT_MIN_WIDTH) {
            WideContent(
                btcValue = btcValue,
                fiatValue = fiatValue,
                bitcoinLabel = bitcoinLabel,
                currencySymbol = currencyState.currencySymbol,
                selectedCurrency = currencyState.selectedCurrency,
            )
        } else {
            CompactContent(
                btcValue = btcValue,
                fiatValue = fiatValue,
                currencySymbol = currencyState.currencySymbol,
            )
        }
    }
}

@Composable
private fun WideContent(
    btcValue: String,
    fiatValue: String,
    bitcoinLabel: String,
    currencySymbol: String,
    selectedCurrency: String,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = GlanceModifier.fillMaxSize()
    ) {
        Column(modifier = GlanceModifier.fillMaxWidth()) {
            CalculatorRow(
                currencySymbol = BITCOIN_SYMBOL,
                value = btcValue,
                label = bitcoinLabel,
                iconSize = WIDE_ICON_SIZE,
                rowPadding = 16.dp,
                modifier = GlanceModifier.fillMaxWidth()
            )
            VerticalSpacer(16.dp)
            CalculatorRow(
                currencySymbol = currencySymbol,
                value = fiatValue,
                label = selectedCurrency.uppercase(Locale.ENGLISH),
                iconSize = WIDE_ICON_SIZE,
                rowPadding = 16.dp,
                modifier = GlanceModifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun CompactContent(
    btcValue: String,
    fiatValue: String,
    currencySymbol: String,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = GlanceModifier.fillMaxSize()
    ) {
        Column(modifier = GlanceModifier.fillMaxWidth()) {
            CalculatorRow(
                currencySymbol = BITCOIN_SYMBOL,
                value = btcValue,
                label = null,
                iconSize = COMPACT_ICON_SIZE,
                rowPadding = 12.dp,
                modifier = GlanceModifier.fillMaxWidth()
            )
            VerticalSpacer(16.dp)
            CalculatorRow(
                currencySymbol = currencySymbol,
                value = fiatValue,
                label = null,
                iconSize = COMPACT_ICON_SIZE,
                rowPadding = 12.dp,
                modifier = GlanceModifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun CalculatorRow(
    currencySymbol: String,
    value: String,
    label: String?,
    iconSize: Dp,
    rowPadding: Dp,
    modifier: GlanceModifier = GlanceModifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .background(RowBackground)
            .cornerRadius(ROW_RADIUS)
            .padding(rowPadding)
    ) {
        CalculatorIcon(
            currencySymbol = currencySymbol,
            size = iconSize,
        )
        HorizontalSpacer(8.dp)
        Text(
            text = value,
            style = GlanceTextStyles.bodySSB,
            maxLines = 1,
            modifier = GlanceModifier.then(WidthModifier(Dimension.Expand))
        )
        if (label != null) {
            HorizontalSpacer(8.dp)
            Text(
                text = label,
                style = GlanceTextStyles.captionB.copy(color = GlanceColors.textSecondary),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun CalculatorIcon(
    currencySymbol: String,
    size: Dp,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = GlanceModifier
            .size(size)
            .background(IconBackground)
            .cornerRadius(size / 2)
    ) {
        Text(
            text = currencySymbol.toCalculatorDisplaySymbol(),
            style = GlanceTextStyles.bodySSB.copy(color = BrandColor),
            maxLines = 1,
        )
    }
}
