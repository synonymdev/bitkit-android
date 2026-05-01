package to.bitkit.ext

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import to.bitkit.R
import to.bitkit.data.dto.price.GraphPeriod

@StringRes
fun GraphPeriod.labelRes(): Int = when (this) {
    GraphPeriod.ONE_DAY -> R.string.appwidget__price__day
    GraphPeriod.ONE_WEEK -> R.string.appwidget__price__week
    GraphPeriod.ONE_MONTH -> R.string.appwidget__price__month
    GraphPeriod.ONE_YEAR -> R.string.appwidget__price__year
}

@Composable
fun GraphPeriod.label(): String = stringResource(labelRes())
