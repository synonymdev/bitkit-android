package to.bitkit.models

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import to.bitkit.R

enum class WidgetType(
    @DrawableRes val iconRes: Int,
    @StringRes val title: Int,
) {
    BLOCK(
        iconRes = R.drawable.widget_cube,
        title = R.string.widgets__blocks__name
    ),
    CALCULATOR(
        iconRes = R.drawable.widget_math_operation,
        title = R.string.widgets__calculator__name
    ),
    FACTS(
        iconRes = R.drawable.widget_lightbulb,
        title = R.string.widgets__facts__name
    ),
    NEWS(
        iconRes = R.drawable.widget_newspaper,
        title = R.string.widgets__news__name
    ),
    PRICE(
        iconRes = R.drawable.widget_chart_line,
        title = R.string.widgets__price__name
    ),
    WEATHER(
        iconRes = R.drawable.widget_cloud,
        title = R.string.widgets__blocks__name
    )
}
