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
import to.bitkit.data.AppDb
import to.bitkit.data.keychain.Keychain
import to.bitkit.di.BgDispatcher
import to.bitkit.env.Tag.DEV
import to.bitkit.env.Tag.LDK
import to.bitkit.env.Tag.LSP
import to.bitkit.services.BlocktankService
import to.bitkit.services.LightningService
import to.bitkit.services.OnChainService
import javax.inject.Inject

@HiltViewModel
class SharedViewModel @Inject constructor(
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val db: AppDb,
    private val keychain: Keychain,
    private val blocktankService: BlocktankService,
    private val onChainService: OnChainService,
    private val firebaseMessaging: FirebaseMessaging,
) : ViewModel() {
    fun warmupNode() {
        // TODO make it concurrent, and wait for all to finish before trying to access `lightningService.node`, etc…
        runBlocking {
            runCatching {
                LightningService.shared.apply {
                    setup()
                    start()
                    sync()
                }
                OnChainService.shared.apply {
                    setup()
                    fullScan()
                }
            }.onFailure {
                Log.e(LDK, "Node warmup error", it)
            }
        }
    }

    fun registerForNotifications(fcmToken: String? = null) {
        viewModelScope.launch(bgDispatcher) {
            val token = fcmToken ?: firebaseMessaging.token.await()

            runCatching {
                blocktankService.registerDevice(token)
            }.onFailure {
                Log.e(LSP, "Failed to register device with LSP", it)
            }
        }
    }

    // region debug
    fun debugDb() {
        viewModelScope.launch {
            db.configDao().getAll().collect {
                Log.d(DEV, "${it.count()} entities in DB: $it")
            }
        }
    }

    fun debugKeychain() {
        viewModelScope.launch {
            val key = "test"
            if (keychain.exists(key)) {
                val value = keychain.loadString(key)
                Log.d(DEV, "Keychain entry: $key = $value")
                keychain.delete(key)
            }
            keychain.saveString(key, "testValue")
        }
    }

    fun debugWipeBdk() {
        onChainService.stop()
        onChainService.wipeStorage()
    }

    fun debugLspNotifications() {
        viewModelScope.launch(bgDispatcher) {
            val token = FirebaseMessaging.getInstance().token.await()
            blocktankService.testNotification(token)
        }
    }

    fun debugBlocktankInfo() {
        viewModelScope.launch(bgDispatcher) { blocktankService.getInfo() }
    }
    // endregion
}
