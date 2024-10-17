package to.bitkit.ui.shared

import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.withStyle
import to.bitkit.R
import java.text.NumberFormat

@Composable
internal fun moneyString(
    value: String,
    currency: String = stringResource(R.string.sat),
) = buildAnnotatedString {
    append(NumberFormat.getNumberInstance(Locale.current.platformLocale).format(value.toLong()))
    append(" ")
    withStyle(SpanStyle(color = colorScheme.onBackground.copy(0.5f))) {
        append(currency)
    }
}
