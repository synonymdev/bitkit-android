package to.bitkit.wear.security

import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.UserNotAuthenticatedException
import org.junit.Test
import java.security.InvalidKeyException
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.IllegalBlockSizeException
import kotlin.test.assertIs
import kotlin.test.assertSame

class SeedKeyErrorTest {
    @Test
    fun `a permanently invalidated or unrecoverable key is an invalidated key`() {
        assertIs<SeedStoreError.KeyInvalidated>(AndroidKeystoreSeedKey.mapError(KeyPermanentlyInvalidatedException()))
        assertIs<SeedStoreError.KeyInvalidated>(AndroidKeystoreSeedKey.mapError(UnrecoverableKeyException()))
    }

    @Test
    fun `a key refused while the watch is locked reports the lock, also as a cause`() {
        assertIs<SeedStoreError.DeviceLocked>(AndroidKeystoreSeedKey.mapError(UserNotAuthenticatedException()))
        val wrapped = IllegalBlockSizeException().apply { initCause(UserNotAuthenticatedException()) }
        assertIs<SeedStoreError.DeviceLocked>(AndroidKeystoreSeedKey.mapError(wrapped))
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
}
