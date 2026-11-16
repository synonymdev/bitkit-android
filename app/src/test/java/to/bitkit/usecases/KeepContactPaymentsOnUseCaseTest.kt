package to.bitkit.usecases

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.models.NodeLifecycleState
import to.bitkit.repositories.BackupRepo
import to.bitkit.repositories.ContactPaymentSettingsRepo
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.LightningState
import to.bitkit.repositories.PrivatePaykitError
import to.bitkit.repositories.PrivatePaykitRepo
import to.bitkit.repositories.PubkyRepo
import to.bitkit.repositories.PubkySignIn
import to.bitkit.repositories.WalletRepo
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals

class KeepContactPaymentsOnUseCaseTest : BaseUnitTest() {
    private val settingsStore = mock<SettingsStore>()
    private val contactPaymentSettingsRepo = mock<ContactPaymentSettingsRepo>()
    private val walletRepo = mock<WalletRepo>()
    private val lightningRepo = mock<LightningRepo>()
    private val pubkyRepo = mock<PubkyRepo>()
    private val privatePaykitRepo = mock<PrivatePaykitRepo>()
    private val backupRepo = mock<BackupRepo>()
    private val settings = MutableStateFlow(SettingsData())

    private val readyState = ContactPaymentsState(
        walletExists = true,
        isWiping = false,
        isDeletingProfile = false,
        isPaykitUiEnabled = true,
        isProfileSetupPending = false,
        isSignedIn = true,
        isPrivateOn = false,
        isPublicOn = false,
        isPrivateUnavailable = false,
    )

    private fun createSut() = KeepContactPaymentsOnUseCase(
        ioDispatcher = testDispatcher,
        settingsStore = settingsStore,
        contactPaymentSettingsRepo = contactPaymentSettingsRepo,
        walletRepo = walletRepo,
        lightningRepo = lightningRepo,
        pubkyRepo = pubkyRepo,
        privatePaykitRepo = privatePaykitRepo,
        backupRepo = backupRepo,
    )

    @Before
    fun setUp() {
        whenever(settingsStore.data).thenReturn(settings)
        whenever(settingsStore.isPaykitEnabled).thenReturn(MutableStateFlow(true))
        whenever(settingsStore.isPubkyProfileSetupPending).thenReturn(MutableStateFlow(false))
        whenever(walletRepo.walletExists()).thenReturn(true)
        whenever(lightningRepo.lightningState)
            .thenReturn(MutableStateFlow(LightningState(nodeLifecycleState = NodeLifecycleState.Running)))
        whenever(pubkyRepo.isRestoringSession).thenReturn(MutableStateFlow(false))
        whenever(pubkyRepo.currentSignIn()).thenReturn(PubkySignIn("pubky-owner", 1L))
        whenever(privatePaykitRepo.isProfileDeletionInProgress).thenReturn(MutableStateFlow(false))
        whenever(backupRepo.isWiping).thenReturn(MutableStateFlow(false))
        whenever { pubkyRepo.awaitInitialization() }.thenReturn(Unit)
        whenever { contactPaymentSettingsRepo.setEnabled(any()) }.thenReturn(Result.success(Unit))
        whenever { contactPaymentSettingsRepo.setPublicEnabled(any()) }.thenReturn(Result.success(Unit))
    }

    @Test
    fun `enables both modes when both are off`() {
        assertEquals(
            ContactPaymentsDecision(enablePrivate = true, enablePublic = true),
            decideContactPayments(readyState),
        )
    }

    @Test
    fun `enables only the mode that is off`() {
        assertEquals(
            ContactPaymentsDecision(enablePrivate = false, enablePublic = true),
            decideContactPayments(readyState.copy(isPrivateOn = true)),
        )
        assertEquals(
            ContactPaymentsDecision(enablePrivate = true, enablePublic = false),
            decideContactPayments(readyState.copy(isPublicOn = true)),
        )
    }

    @Test
    fun `skips private when the profile has no private access`() {
        assertEquals(
            ContactPaymentsDecision(enablePrivate = false, enablePublic = true),
            decideContactPayments(readyState.copy(isPrivateUnavailable = true)),
        )
    }

    @Test
    fun `does nothing unless every guard passes`() {
        val none = ContactPaymentsDecision(enablePrivate = false, enablePublic = false)
        listOf(
            readyState.copy(walletExists = false),
            readyState.copy(isWiping = true),
            readyState.copy(isDeletingProfile = true),
            readyState.copy(isPaykitUiEnabled = false),
            readyState.copy(isProfileSetupPending = true),
            readyState.copy(isSignedIn = false),
        ).forEach { assertEquals(none, decideContactPayments(it)) }
    }

    @Test
    fun `turns private on before public through the regular enable paths`() = test {
        createSut()()

        inOrder(contactPaymentSettingsRepo) {
            verify(contactPaymentSettingsRepo).setEnabled(true)
            verify(contactPaymentSettingsRepo).setPublicEnabled(true)
        }
    }

    @Test
    fun `leaves settings alone when both modes are on`() = test {
        settings.value = SettingsData(sharesPrivatePaykitEndpoints = true, sharesPublicPaykitEndpoints = true)

        createSut()()

        verify(contactPaymentSettingsRepo, never()).setEnabled(any())
        verify(contactPaymentSettingsRepo, never()).setPublicEnabled(any())
    }

    @Test
    fun `does not retry private in the same launch after private access is refused`() = test {
        whenever { contactPaymentSettingsRepo.setEnabled(any()) }
            .thenReturn(Result.failure(PrivatePaykitError.PrivateUnavailable))
        val sut = createSut()

        sut()
        sut()

        verify(contactPaymentSettingsRepo, times(1)).setEnabled(true)
        verify(contactPaymentSettingsRepo, times(2)).setPublicEnabled(true)
    }

    @Test
    fun `retries private on the next run after an ordinary failure`() = test {
        whenever { contactPaymentSettingsRepo.setEnabled(any()) }
            .thenReturn(Result.failure(IllegalStateException("sync failed")))
        val sut = createSut()

        sut()
        sut()

        verify(contactPaymentSettingsRepo, times(2)).setEnabled(true)
    }
}
