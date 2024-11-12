package to.bitkit.ext

import android.util.Log
import kotlinx.coroutines.Job
import to.bitkit.env.Tag.APP

fun Job.logCompletion(name: String = "") = invokeOnCompletion {
    if (it != null) {
        Log.v(APP, "Coroutine '$name' error: ${it.message}", it)
    } else {
        Log.v(APP, "Coroutine '$name' completed successfully")
    }
}
