package to.bitkit.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import to.bitkit.Tag.DEV
import to.bitkit.di.BgDispatcher
import javax.inject.Inject

@HiltViewModel
class SharedViewModel @Inject constructor(
    @BgDispatcher bgDispatcher: CoroutineDispatcher,
) : ViewModel() {
    fun warmupNode() {
        // TODO make it concurrent, and wait for all to finish before trying to access `lightningService.node`, etc…
        logInstanceHashCode()
        runBlocking { to.bitkit.services.warmupNode() }
    }

    fun logInstanceHashCode() {
        Log.d(DEV, "${this::class.java.simpleName} hashCode: ${hashCode()}")
    }
}
