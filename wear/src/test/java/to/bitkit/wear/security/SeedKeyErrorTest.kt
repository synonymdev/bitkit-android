package to.bitkit.wear.security

import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.UserNotAuthenticatedException
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import java.security.InvalidKeyException
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.IllegalBlockSizeException
import kotlin.test.assertIs
import kotlin.test.assertSame
import android.security.KeyStoreException as AndroidKeyStoreException

class SeedKeyErrorTest {
    @Test
    fun `a permanently invalidated key is a lost key, also as a cause`() {
        assertIs<SeedStoreError.KeyInvalidated>(AndroidKeystoreSeedKey.mapError(KeyPermanentlyInvalidatedException()))
        val wrapped = InvalidKeyException(KeyPermanentlyInvalidatedException())
        assertIs<SeedStoreError.KeyInvalidated>(AndroidKeystoreSeedKey.mapError(wrapped))
    }

    @Test
    fun `an unrecoverable key is a lost key only when the keystore says the key does not exist for good`() {
        val missing = keyStoreError(AndroidKeyStoreException.ERROR_KEY_DOES_NOT_EXIST)

        assertIs<SeedStoreError.KeyInvalidated>(AndroidKeystoreSeedKey.mapError(unrecoverable(missing)))
    }

    @Test
    fun `an unrecoverable key without a keystore cause is a failure to retry`() {
        assertIs<SeedStoreError.Failed>(AndroidKeystoreSeedKey.mapError(UnrecoverableKeyException()))
        val message = UnrecoverableKeyException("Failed to obtain information about key")
        assertIs<SeedStoreError.Failed>(AndroidKeystoreSeedKey.mapError(message))
        assertIs<SeedStoreError.Failed>(AndroidKeystoreSeedKey.mapError(unrecoverable(ProviderException())))
    }

    @Test
    fun `an unrecoverable key from any other keystore error is a failure to retry`() {
        val codes = listOf(
            AndroidKeyStoreException.ERROR_OTHER,
            AndroidKeyStoreException.ERROR_KEYSTORE_UNINITIALIZED,
            AndroidKeyStoreException.ERROR_INTERNAL_SYSTEM_ERROR,
            AndroidKeyStoreException.ERROR_PERMISSION_DENIED,
            AndroidKeyStoreException.ERROR_KEY_CORRUPTED,
            AndroidKeyStoreException.ERROR_KEYMINT_FAILURE,
            AndroidKeyStoreException.ERROR_KEYSTORE_FAILURE,
            AndroidKeyStoreException.ERROR_KEY_OPERATION_EXPIRED,
        )
        for (code in codes) {
            val error = AndroidKeystoreSeedKey.mapError(unrecoverable(keyStoreError(code)))
            assertIs<SeedStoreError.Failed>(error, "code $code")
        }
    }

    @Test
    fun `a transient keystore failure is never a lost key`() {
        val transient = keyStoreError(AndroidKeyStoreException.ERROR_KEY_DOES_NOT_EXIST, isTransient = true)

        assertIs<SeedStoreError.Failed>(AndroidKeystoreSeedKey.mapError(unrecoverable(transient)))
    }

    @Test
    fun `a missing key reported outside an unrecoverable key is a failure to retry`() {
        val missing = keyStoreError(AndroidKeyStoreException.ERROR_KEY_DOES_NOT_EXIST)

        assertIs<SeedStoreError.Failed>(AndroidKeystoreSeedKey.mapError(missing))
        assertIs<SeedStoreError.Failed>(AndroidKeystoreSeedKey.mapError(InvalidKeyException(missing)))
    }

    @Test
    fun `a key refused while the watch is locked reports the lock, also as a cause`() {
        assertIs<SeedStoreError.DeviceLocked>(AndroidKeystoreSeedKey.mapError(UserNotAuthenticatedException()))
        val wrapped = IllegalBlockSizeException().apply { initCause(UserNotAuthenticatedException()) }
        assertIs<SeedStoreError.DeviceLocked>(AndroidKeystoreSeedKey.mapError(wrapped))
        val locked = keyStoreError(AndroidKeyStoreException.ERROR_USER_AUTHENTICATION_REQUIRED)
        assertIs<SeedStoreError.DeviceLocked>(AndroidKeystoreSeedKey.mapError(unrecoverable(locked)))
    }

    @Test
    fun `a lock wins over a key that looks lost`() {
        val locked = keyStoreError(AndroidKeyStoreException.ERROR_USER_AUTHENTICATION_REQUIRED)
        val error = KeyPermanentlyInvalidatedException().apply { initCause(locked) }

        assertIs<SeedStoreError.DeviceLocked>(AndroidKeystoreSeedKey.mapError(error))
    }

    @Test
    fun `a failed tag is a corrupted seed and anything else a failure`() {
        assertIs<SeedStoreError.Corrupted>(AndroidKeystoreSeedKey.mapError(AEADBadTagException()))
        assertIs<SeedStoreError.Failed>(AndroidKeystoreSeedKey.mapError(InvalidKeyException()))
        assertIs<SeedStoreError.Failed>(AndroidKeystoreSeedKey.mapError(ProviderException()))
    }

    @Test
    fun `a store error passes through`() {
        val error = SeedStoreError.NotFound()
        assertSame(error, AndroidKeystoreSeedKey.mapError(error))
    }

    private fun unrecoverable(cause: Throwable) = UnrecoverableKeyException().apply { initCause(cause) }

    private fun keyStoreError(code: Int, isTransient: Boolean = false) = mock<AndroidKeyStoreException> {
        on { numericErrorCode } doReturn code
        on { isTransientFailure } doReturn isTransient
    }
}
