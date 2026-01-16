package to.bitkit.models

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Stable

@Stable
sealed interface ToastText {
    @JvmInline
    @Stable
    value class Resource(@StringRes val resId: Int) : ToastText

    @JvmInline
    @Stable
    value class Literal(val value: String) : ToastText
}

fun ToastText.asString(context: Context): String = when (this) {
    is ToastText.Resource -> context.getString(resId)
    is ToastText.Literal -> value
}
