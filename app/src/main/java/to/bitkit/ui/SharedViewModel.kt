package to.bitkit.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.messaging.FirebaseMessaging
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import to.bitkit.Tag.DEV
import to.bitkit.Tag.LSP
import to.bitkit.di.BgDispatcher
import to.bitkit.services.BlocktankService
import javax.inject.Inject

@HiltViewModel
class SharedViewModel @Inject constructor(
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val blocktankService: BlocktankService,
) : ViewModel() {
    fun warmupNode() {
        // TODO make it concurrent, and wait for all to finish before trying to access `lightningService.node`, etc…
        logInstanceHashCode()
        runBlocking { to.bitkit.services.warmupNode() }
    }

    fun logInstanceHashCode() {
        Log.d(DEV, "${this::class.java.simpleName} hashCode: ${hashCode()}")
    }

    fun registerForNotifications(fcmToken: String? = null) {
        viewModelScope.launch(bgDispatcher) {
            val token = fcmToken ?: runCatching { FirebaseMessaging.getInstance().token.await() }.getOrNull()
            requireNotNull(token) { "FCM token read error" }

            runCatching {
                blocktankService.registerDevice(token)
            }.onFailure {
                Log.e(LSP, "Failed to register device with LSP", it)
            }
        }
    }
}
