package to.bitkit.models

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.res.stringResource

@Stable
sealed interface ToastText {
    @JvmInline
    value class Resource(@StringRes val resId: Int) : ToastText

    @JvmInline
    value class Literal(val value: String) : ToastText

    companion object {
        operator fun invoke(value: String): ToastText = Literal(value)
        operator fun invoke(@StringRes resId: Int): ToastText = Resource(resId)
    }
}

@Composable
fun ToastText.asString(): String = when (this) {
    is ToastText.Resource -> stringResource(resId)
    is ToastText.Literal -> value
}

fun ToastText.asString(context: Context): String = when (this) {
    is ToastText.Resource -> context.getString(resId)
    is ToastText.Literal -> value
}
