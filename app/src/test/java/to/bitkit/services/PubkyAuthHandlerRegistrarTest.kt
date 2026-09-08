package to.bitkit.services

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.doNothing
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import to.bitkit.data.SettingsStore
import to.bitkit.repositories.PubkyRepo
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS")
@OptIn(ExperimentalCoroutinesApi::class)
class PubkyAuthHandlerRegistrarTest : BaseUnitTest() {
    private companion object {
        const val PACKAGE_NAME = "to.bitkit"
    }

    private val context: Context = mock()
    private val packageManager: PackageManager = mock()
    private val pubkyRepo: PubkyRepo = mock()
    private val settingsStore: SettingsStore = mock()
    private val isPaykitEnabled = MutableStateFlow(false)
    private val publicKey = MutableStateFlow<String?>(null)

    @Before
    fun setUp() {
        whenever(context.packageName).thenReturn(PACKAGE_NAME)
        whenever(context.packageManager).thenReturn(packageManager)
        whenever(settingsStore.isPaykitEnabled).thenReturn(isPaykitEnabled)
        whenever(pubkyRepo.publicKey).thenReturn(publicKey)
    }

    @Test
    fun `handler is enabled for an available locally managed identity`() = test {
        isPaykitEnabled.value = true
        publicKey.value = "pubkylocal"
        whenever(pubkyRepo.hasSecretKey()).thenReturn(true)

        createSut().start(backgroundScope)
        runCurrent()

        verifyComponentStates(authEnabled = true, signupEnabled = false)
        assertTrue(
            canHandlePubkyAuth(
                isPaykitUiEnabled = true,
                hasIdentity = true,
                hasSecretKey = true,
            ),
        )
    }

    @Test
    fun `handler is disabled with an unavailable Paykit UI`() {
        assertFalse(
            canHandlePubkyAuth(
                isPaykitUiEnabled = false,
                hasIdentity = true,
                hasSecretKey = true,
            ),
        )
    }

    @Test
    fun `only signup handler is enabled without an identity`() = test {
        isPaykitEnabled.value = true

        createSut().start(backgroundScope)
        runCurrent()

        verifyComponentStates(authEnabled = false, signupEnabled = true)
        verify(pubkyRepo, never()).hasSecretKey()

        clearInvocations(packageManager)
        whenever(pubkyRepo.hasSecretKey()).thenReturn(true)
        publicKey.value = "pubkylocal"
        runCurrent()

        verifyComponentStates(authEnabled = true, signupEnabled = false)
    }

    @Test
    fun `handler is disabled for a Ring managed identity`() = test {
        isPaykitEnabled.value = true
        publicKey.value = "pubkyring"
        whenever(pubkyRepo.hasSecretKey()).thenReturn(false)

        createSut().start(backgroundScope)
        runCurrent()

        verifyComponentStates(authEnabled = false, signupEnabled = false)
    }

    @Test
    fun `authorization handler switches to signup when the local identity is removed`() = test {
        isPaykitEnabled.value = true
        publicKey.value = "pubkylocal"
        whenever(pubkyRepo.hasSecretKey()).thenReturn(true)
        createSut().start(backgroundScope)
        runCurrent()
        clearInvocations(packageManager)

        publicKey.value = null
        runCurrent()

        verifyComponentStates(authEnabled = false, signupEnabled = true)
    }

    @Test
    fun `handler is disabled when the Paykit UI is turned off`() = test {
        isPaykitEnabled.value = true
        publicKey.value = "pubkylocal"
        whenever(pubkyRepo.hasSecretKey()).thenReturn(true)
        createSut().start(backgroundScope)
        runCurrent()
        clearInvocations(packageManager)

        isPaykitEnabled.value = false
        runCurrent()

        verifyComponentStates(authEnabled = false, signupEnabled = false)
    }

    @Test
    fun `handler collection starts once`() = test {
        val sut = createSut()

        sut.start(backgroundScope)
        sut.start(backgroundScope)
        runCurrent()

        verifyComponentStates(authEnabled = false, signupEnabled = false)
    }

    @Test
    fun `handler keeps observing state after a package manager failure`() = test {
        isPaykitEnabled.value = true
        publicKey.value = "pubkylocal"
        whenever(pubkyRepo.hasSecretKey()).thenReturn(true)
        doThrow(IllegalStateException("component update failed"))
            .doNothing()
            .whenever(packageManager)
            .setComponentEnabledSetting(any(), any(), any())

        createSut().start(backgroundScope)
        runCurrent()
        clearInvocations(packageManager)

        isPaykitEnabled.value = false
        runCurrent()

        verifyComponentStates(authEnabled = false, signupEnabled = false)
    }

    private fun createSut() = PubkyAuthHandlerRegistrar(
        context = context,
        pubkyRepo = pubkyRepo,
        settingsStore = settingsStore,
        ioDispatcher = testDispatcher,
    )

    private fun verifyComponentStates(authEnabled: Boolean, signupEnabled: Boolean) {
        mapOf(
            "to.bitkit.ui.MainActivityPubkyAuth" to authEnabled,
            "to.bitkit.ui.MainActivityPubkySignup" to signupEnabled,
        ).forEach { (className, enabled) ->
            val state = if (enabled) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            verify(packageManager).setComponentEnabledSetting(
                eq(ComponentName(PACKAGE_NAME, className)),
                eq(state),
                eq(PackageManager.DONT_KILL_APP),
            )
        }
    }
}
