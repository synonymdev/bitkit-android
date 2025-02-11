package to.bitkit.ext

import kotlinx.coroutines.Job
import to.bitkit.utils.Logger

fun Job.logCompletion(name: String = "") = invokeOnCompletion { err ->
    if (err != null) {
        Logger.verbose("Coroutine '$name' error: ${err.message}")
    } else {
        Logger.verbose("Coroutine '$name' completed successfully")
    }
}
