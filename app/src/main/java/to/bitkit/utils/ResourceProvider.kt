package to.bitkit.utils

import android.annotation.SuppressLint
import android.content.Context
import androidx.annotation.StringRes

// TODO find a better way ?!
@SuppressLint("StaticFieldLeak")
object ResourceProvider {
    private lateinit var context: Context

    fun init(appContext: Context) {
        context = appContext.applicationContext
    }

    fun getString(@StringRes resId: Int): String {
        return context.getString(resId)
    }
}
