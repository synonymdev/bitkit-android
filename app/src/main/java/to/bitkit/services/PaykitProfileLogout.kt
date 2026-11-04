package to.bitkit.services

import com.synonym.paykit.PubkyGrantLogout
import com.synonym.paykit.PubkyGrantLogoutReceipt
import to.bitkit.data.keychain.ProfileCredentials
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** A live original-grant handle. A persisted removal UUID can never reconstruct it. */
internal class PaykitProfileLogout(
    internal val native: PubkyGrantLogout,
    internal val credentials: ProfileCredentials,
    private val validateOriginalSigner: () -> Unit = {},
    private val isOriginalLifecycle: () -> Boolean,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<PaykitProfileLogout>

    val binding: PubkyGrantLogoutReceipt = native.binding()
    private val admitted = AtomicBoolean(true)
    private val remoteAcknowledged = AtomicBoolean(false)
    internal val credentialsErased = AtomicBoolean(false)

    fun requireCurrent() {
        check(admitted.get() && isOriginalLifecycle()) { "The original profile logout is no longer available" }
    }

    fun isCurrent(): Boolean = admitted.get() && isOriginalLifecycle()

    fun validateSigner() = validateOriginalSigner()

    fun requireOriginalCredentials() {
        check(isOriginalLifecycle()) { "The original profile credentials are no longer current" }
    }

    fun revoke() {
        admitted.set(false)
        native.revokeAdmission()
    }

    fun retainAcknowledgement(receipt: PubkyGrantLogoutReceipt) {
        check(receipt == binding) { "The logout acknowledgement belongs to another grant" }
        remoteAcknowledged.set(true)
    }

    /** Inspect after native execution has drained, including when caller cancellation hid its return. */
    fun reconcileAcknowledgement(): Boolean {
        native.acknowledged()?.let(::retainAcknowledgement)
        return remoteAcknowledged.get()
    }
}
