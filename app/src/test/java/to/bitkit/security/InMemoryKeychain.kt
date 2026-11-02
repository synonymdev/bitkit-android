package to.bitkit.security

import kotlinx.coroutines.flow.MutableStateFlow
import org.mockito.kotlin.any
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import to.bitkit.data.keychain.Keychain

/** Backs a mocked [Keychain] with in-memory entries, so writes are visible to later reads. */
internal class InMemoryKeychain {
    companion object {
        const val WIPE_PENDING = "WIPE_PENDING"
    }

    val keychain = mock<Keychain>()
    val entries = mutableMapOf<String, String>()
    var wipePending: String? = null
    private val attemptsRemaining = MutableStateFlow<Int?>(null)

    /** Runs before each write is applied, with the written key; throwing from it fails the write. */
    var beforeWrite: suspend (String) -> Unit = {}

    var pin: String?
        get() = entries[Keychain.Key.PIN.name]
        set(value) = set(Keychain.Key.PIN.name, value)

    var attempts: String?
        get() = entries[Keychain.Key.PIN_ATTEMPTS_REMAINING.name]
        set(value) = set(Keychain.Key.PIN_ATTEMPTS_REMAINING.name, value)

    init {
        whenever(keychain.loadWipePending()).thenAnswer { wipePending }
        whenever { keychain.saveWipePending(any()) }.doSuspendableAnswer {
            beforeWrite(WIPE_PENDING)
            wipePending = it.getArgument(0)
        }
        whenever { keychain.deleteWipePending() }.doSuspendableAnswer {
            beforeWrite(WIPE_PENDING)
            wipePending = null
        }
        whenever(keychain.loadString(any())).thenAnswer { entries[it.getArgument(0)] }
        whenever(keychain.exists(any())).thenAnswer { it.getArgument<String>(0) in entries }
        whenever { keychain.upsertString(any(), any()) }.doSuspendableAnswer {
            beforeWrite(it.getArgument(0))
            set(it.getArgument(0), it.getArgument(1))
        }
        whenever(keychain.pinAttemptsRemaining()).thenReturn(attemptsRemaining)
    }

    private fun set(key: String, value: String?) {
        if (value == null) entries.remove(key) else entries[key] = value
        if (key == Keychain.Key.PIN_ATTEMPTS_REMAINING.name) attemptsRemaining.value = value?.toIntOrNull()
    }
}
