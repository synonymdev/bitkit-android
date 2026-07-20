package to.bitkit.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.data.SettingsStore
import to.bitkit.data.WidgetsStore
import to.bitkit.data.hasPaykitState
import to.bitkit.data.hasPublicPaykitPublicationState
import to.bitkit.data.paykitDisabled
import to.bitkit.flags.PaykitFeatureFlags
import to.bitkit.models.Toast
import to.bitkit.models.TransactionSpeed
import to.bitkit.repositories.ContactPaymentSettingsRepo
import to.bitkit.repositories.PrivatePaykitRepo
import to.bitkit.repositories.PubkyRepo
import to.bitkit.repositories.PublicPaykitError
import to.bitkit.repositories.PublicPaykitRepo
import to.bitkit.repositories.WidgetsRepo
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.utils.Logger
import javax.inject.Inject

@Suppress("LongParameterList", "TooManyFunctions")
@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsStore: SettingsStore,
    private val pubkyRepo: PubkyRepo,
    private val contactPaymentSettingsRepo: ContactPaymentSettingsRepo,
    private val publicPaykitRepo: PublicPaykitRepo,
    private val privatePaykitRepo: PrivatePaykitRepo,
    private val widgetsStore: WidgetsStore,
    private val widgetsRepo: WidgetsRepo,
) : ViewModel() {
    private companion object {
        const val TAG = "SettingsViewModel"
    }

    init {
        viewModelScope.launch {
            val settings = settingsStore.data.first()
            val isPaykitEnabled = PaykitFeatureFlags.isUiEnabled(settingsStore.isPaykitEnabled.first())
            if (!isPaykitEnabled && settings.hasPaykitState()) {
                updatePaykitEnabled(false)
            }
        }
    }

    fun reset() = viewModelScope.launch { settingsStore.reset() }

    val hasSeenSpendingIntro = settingsStore.data.map { it.hasSeenSpendingIntro }
        .asStateFlow(initialValue = false)

    val notificationsGranted = settingsStore.data.map { it.notificationsGranted }
        .asStateFlow(initialValue = false)

    fun setNotificationPreference(granted: Boolean) {
        viewModelScope.launch {
            settingsStore.update { it.copy(notificationsGranted = granted) }
        }
    }

    val keepBitkitActiveInBackground = settingsStore.data.map { it.keepBitkitActiveInBackground }
        .asStateFlow(initialValue = false)

    fun setKeepBitkitActiveInBackground(value: Boolean) {
        viewModelScope.launch {
            settingsStore.update { it.copy(keepBitkitActiveInBackground = value) }
        }
    }

    fun setHasSeenSpendingIntro(value: Boolean) {
        viewModelScope.launch {
            settingsStore.update { it.copy(hasSeenSpendingIntro = value) }
        }
    }

    val hasSeenWidgetsIntro = settingsStore.data.map { it.hasSeenWidgetsIntro }
        .asStateFlow(initialValue = false)

    fun setHasSeenWidgetsIntro(value: Boolean) {
        viewModelScope.launch {
            settingsStore.update { it.copy(hasSeenWidgetsIntro = value) }
        }
    }

    val hasSeenTransferIntro = settingsStore.data.map { it.hasSeenTransferIntro }
        .asStateFlow(initialValue = false)

    fun setHasSeenTransferIntro(value: Boolean) {
        viewModelScope.launch {
            settingsStore.update { it.copy(hasSeenTransferIntro = value) }
        }
    }

    val hasSeenSavingsIntro = settingsStore.data.map { it.hasSeenSavingsIntro }
        .asStateFlow(initialValue = false)

    fun setHasSeenSavingsIntro(value: Boolean) {
        viewModelScope.launch {
            settingsStore.update { it.copy(hasSeenSavingsIntro = value) }
        }
    }

    val hasSeenShopIntro = settingsStore.data.map { it.hasSeenShopIntro }
        .asStateFlow(initialValue = false)

    fun setHasSeenShopIntro(value: Boolean) {
        viewModelScope.launch {
            settingsStore.update { it.copy(hasSeenShopIntro = value) }
        }
    }

    val hasSeenProfileIntro = settingsStore.data.map { it.hasSeenProfileIntro }
        .asStateFlow(initialValue = false)

    fun setHasSeenProfileIntro(value: Boolean) {
        viewModelScope.launch {
            settingsStore.update { it.copy(hasSeenProfileIntro = value) }
        }
    }

    val hasSeenContactsIntro = settingsStore.data.map { it.hasSeenContactsIntro }
        .asStateFlow(initialValue = false)

    fun setHasSeenContactsIntro(value: Boolean) {
        viewModelScope.launch {
            settingsStore.update { it.copy(hasSeenContactsIntro = value) }
        }
    }

    val isPubkyAuthenticated = pubkyRepo.isAuthenticated
    val hasPubkyContacts = pubkyRepo.contacts.map { it.isNotEmpty() }
        .asStateFlow(initialValue = false)

    val quickPayIntroSeen = settingsStore.data.map { it.quickPayIntroSeen }
        .asStateFlow(initialValue = false)

    fun setQuickPayIntroSeen(value: Boolean) {
        viewModelScope.launch {
            settingsStore.update { it.copy(quickPayIntroSeen = value) }
        }
    }

    val bgPaymentsIntroSeen = settingsStore.data.map { it.bgPaymentsIntroSeen }
        .asStateFlow(initialValue = false)

    fun setBgPaymentsIntroSeen(value: Boolean) {
        viewModelScope.launch {
            settingsStore.update { it.copy(bgPaymentsIntroSeen = value) }
        }
    }

    val isPinForPaymentsEnabled = settingsStore.data.map { it.isPinForPaymentsEnabled }
        .asStateFlow(initialValue = false)

    fun setIsPinForPaymentsEnabled(value: Boolean) {
        viewModelScope.launch {
            settingsStore.update { it.copy(isPinForPaymentsEnabled = value) }
        }
    }

    val defaultTransactionSpeed = settingsStore.data.map { it.defaultTransactionSpeed }
        .asStateFlow(initialValue = TransactionSpeed.Medium)

    fun setDefaultTransactionSpeed(speed: TransactionSpeed) {
        viewModelScope.launch {
            settingsStore.update { it.copy(defaultTransactionSpeed = speed) }
        }
    }

    val isDevModeEnabled = settingsStore.data.map { it.isDevModeEnabled }
        .asStateFlow(initialValue = false)

    fun setIsDevModeEnabled(value: Boolean) {
        viewModelScope.launch {
            settingsStore.update { it.copy(isDevModeEnabled = value) }
        }
    }

    val isPaykitEnabled = settingsStore.isPaykitEnabled.map { PaykitFeatureFlags.isUiEnabled(it) }
        .asStateFlow(initialValue = false)

    val isPaykitStateLoaded = settingsStore.isPaykitEnabled.map { true }
        .asStateFlow(initialValue = false)

    val contactPaymentsEnabled = contactPaymentSettingsRepo.isEnabled
        .asStateFlow(initialValue = false)

    private val _isUpdatingContactPayments = MutableStateFlow(false)
    val isUpdatingContactPayments = _isUpdatingContactPayments.asStateFlow()

    fun setContactPaymentsEnabled(value: Boolean) {
        if (_isUpdatingContactPayments.value) return

        viewModelScope.launch {
            _isUpdatingContactPayments.update { true }
            contactPaymentSettingsRepo.setEnabled(value)
                .onFailure {
                    ToastEventBus.send(
                        type = Toast.ToastType.ERROR,
                        title = context.getString(R.string.common__error),
                        description = contactPaymentSyncErrorMessage(it),
                    )
                }
            _isUpdatingContactPayments.update { false }
        }
    }

    fun setIsPaykitEnabled(value: Boolean) {
        viewModelScope.launch {
            updatePaykitEnabled(value)
        }
    }

    private fun contactPaymentSyncErrorMessage(error: Throwable): String = when (error) {
        PublicPaykitError.InvalidPayload -> context.getString(R.string.profile__pay_contacts_error_invalid_payload)
        PublicPaykitError.NoSupportedEndpoint -> context.getString(R.string.profile__pay_contacts_error_no_endpoint)
        PublicPaykitError.SessionNotActive -> context.getString(R.string.profile__pay_contacts_error_session)
        PublicPaykitError.WalletNotReady -> context.getString(R.string.profile__pay_contacts_error_wallet)
        else -> context.getString(R.string.common__error_body)
    }

    private suspend fun updatePaykitEnabled(value: Boolean) {
        val shouldEnable = value && PaykitFeatureFlags.isUiAvailable
        val hadPublicPaykitState = settingsStore.data.first().hasPublicPaykitPublicationState()
        settingsStore.setIsPaykitEnabled(shouldEnable)

        if (!shouldEnable) {
            settingsStore.update {
                it.paykitDisabled(markPublicCleanupPending = it.hasPublicPaykitPublicationState())
            }
            removePaykitEndpoints(hadPublicPaykitState)
        }
    }

    private suspend fun removePaykitEndpoints(hadPublicPaykitState: Boolean) {
        val contacts = pubkyRepo.contacts.value.map { it.publicKey }

        if (hadPublicPaykitState) {
            publicPaykitRepo.syncPublishedEndpoints(publish = false)
                .onSuccess {
                    settingsStore.update { it.copy(publicPaykitCleanupPending = false) }
                }
                .onFailure {
                    settingsStore.update { it.copy(publicPaykitCleanupPending = true) }
                    Logger.warn("Failed to remove public Paykit endpoints after disabling Paykit UI", it, context = TAG)
                }
        }

        privatePaykitRepo.disableSharingAndPruneUnsavedContactState(contacts)
            .onFailure {
                Logger.warn("Failed to remove private Paykit endpoints after disabling Paykit UI", it, context = TAG)
            }
    }

    val isPinEnabled = settingsStore.data.map { it.isPinEnabled }
        .asStateFlow(SharingStarted.Eagerly, false)

    val isBiometricEnabled = settingsStore.data.map { it.isBiometricEnabled }
        .asStateFlow(SharingStarted.Eagerly, false)

    fun setIsBiometricEnabled(value: Boolean) {
        viewModelScope.launch {
            settingsStore.update { it.copy(isBiometricEnabled = value) }
        }
    }

    val showWidgets = settingsStore.data.map { it.showWidgets }
        .asStateFlow(initialValue = false)

    fun setShowWidgets(value: Boolean) {
        viewModelScope.launch {
            settingsStore.update { it.copy(showWidgets = value) }
        }
    }

    fun resetDismissedSuggestions() {
        viewModelScope.launch {
            settingsStore.update { it.copy(dismissedSuggestions = emptyList()) }
        }
    }

    fun resetWidgets() {
        viewModelScope.launch {
            widgetsStore.reset()
            widgetsRepo.refreshEnabledWidgets()
        }
    }

    val lastUsedTags = settingsStore.data.map { it.lastUsedTags.toImmutableList() }
        .asStateFlow(initialValue = persistentListOf())

    fun deleteLastUsedTag(tag: String) {
        viewModelScope.launch {
            settingsStore.deleteLastUsedTag(tag)
        }
    }

    val isQuickpayEnabled = settingsStore.data.map { it.isQuickPayEnabled }
        .asStateFlow(initialValue = false)

    fun setIsQuickPayEnabled(value: Boolean) {
        viewModelScope.launch {
            settingsStore.update { it.copy(isQuickPayEnabled = value) }
        }
    }

    val quickPayAmount = settingsStore.data.map { it.quickPayAmount }
        .asStateFlow(initialValue = 5)

    fun setQuickPayAmount(value: Int) {
        viewModelScope.launch {
            settingsStore.update { it.copy(quickPayAmount = value) }
        }
    }

    val enableSwipeToHideBalance = settingsStore.data.map { it.enableSwipeToHideBalance }
        .asStateFlow(initialValue = true)

    fun setEnableSwipeToHideBalance(value: Boolean) {
        viewModelScope.launch {
            settingsStore.update {
                it.copy(
                    enableSwipeToHideBalance = value,
                    hideBalance = if (!value) false else it.hideBalance,
                    hideBalanceOnOpen = if (!value) false else it.hideBalanceOnOpen,
                )
            }
        }
    }

    val hideBalance = settingsStore.data.map { it.hideBalance }
        .asStateFlow(initialValue = false)

    fun setHideBalance(value: Boolean) {
        viewModelScope.launch {
            settingsStore.update { it.copy(hideBalance = value) }
        }
    }

    val hideBalanceOnOpen = settingsStore.data.map { it.hideBalanceOnOpen }
        .asStateFlow(initialValue = false)

    fun setHideBalanceOnOpen(value: Boolean) {
        viewModelScope.launch {
            settingsStore.update { it.copy(hideBalanceOnOpen = value) }
        }
    }

    val enableAutoReadClipboard = settingsStore.data.map { it.enableAutoReadClipboard }
        .asStateFlow(initialValue = false)

    fun setEnableAutoReadClipboard(value: Boolean) {
        viewModelScope.launch {
            settingsStore.update { it.copy(enableAutoReadClipboard = value) }
        }
    }

    val enableSendAmountWarning = settingsStore.data.map { it.enableSendAmountWarning }
        .asStateFlow(initialValue = false)

    fun setEnableSendAmountWarning(value: Boolean) {
        viewModelScope.launch {
            settingsStore.update { it.copy(enableSendAmountWarning = value) }
        }
    }

    // utils
    private fun <T> Flow<T>.asStateFlow(
        started: SharingStarted = SharingStarted.WhileSubscribed(5000),
        initialValue: T,
    ): StateFlow<T> = stateIn(viewModelScope, started, initialValue)
}
