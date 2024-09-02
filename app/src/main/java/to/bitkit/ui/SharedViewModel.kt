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
import to.bitkit.data.AppDb
import to.bitkit.data.keychain.KeychainStore
import to.bitkit.di.BgDispatcher
import to.bitkit.services.BitcoinService
import to.bitkit.services.BlocktankService
import javax.inject.Inject

@HiltViewModel
class SharedViewModel @Inject constructor(
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val db: AppDb,
    private val keychain: KeychainStore,
    private val blocktankService: BlocktankService,
    private val bitcoinService: BitcoinService,
) : ViewModel() {
    fun warmupNode() {
        // TODO make it concurrent, and wait for all to finish before trying to access `lightningService.node`, etc…
        runBlocking { to.bitkit.services.warmupNode() }
    }

    fun registerForNotifications(fcmToken: String? = null) {
        viewModelScope.launch(bgDispatcher) {
            val token = fcmToken ?: FirebaseMessaging.getInstance().token.await()

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
                keychain.delete(key)
            }
            keychain.saveString(key, "testValue")
        }
    }

    fun debugWipeBdk() {
        bitcoinService.wipeStorage()
    }

    fun debugLspNotifications() {
        viewModelScope.launch(bgDispatcher) {
            val token = FirebaseMessaging.getInstance().token.await()
            blocktankService.testNotification(token)
        }
    }

    // endregion

}
