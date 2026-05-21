package to.bitkit.ui.screens.wallets.activity

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.di.BgDispatcher
import to.bitkit.models.PubkyProfile
import to.bitkit.models.Toast
import to.bitkit.repositories.ActivityRepo
import to.bitkit.repositories.PubkyRepo
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.BodySSB
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.GradientCircularProgressIndicator
import to.bitkit.ui.components.PubkyContactAvatar
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.shared.modifiers.clickableAlpha
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.utils.Logger
import javax.inject.Inject

@HiltViewModel
class ActivityAssignContactViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val activityRepo: ActivityRepo,
    private val pubkyRepo: PubkyRepo,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ActivityAssignContactUiState())
    val uiState = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<ActivityAssignContactEffect>(extraBufferCapacity = 1)
    val effects = _effects.asSharedFlow()

    init {
        viewModelScope.launch {
            pubkyRepo.contacts.collect { contacts ->
                _uiState.update {
                    it.copy(
                        contacts = contacts.sortedBy { contact -> contact.name.lowercase() }.toImmutableList(),
                        isLoading = pubkyRepo.isLoadingContacts.value,
                    )
                }
            }
        }
        viewModelScope.launch {
            pubkyRepo.isLoadingContacts.collect { isLoading ->
                _uiState.update { it.copy(isLoading = isLoading) }
            }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { pubkyRepo.loadContacts() }
    }

    fun assignContact(activityId: String, publicKey: String) {
        if (_uiState.value.selectedContactKey != null) return

        viewModelScope.launch(bgDispatcher) {
            _uiState.update { it.copy(selectedContactKey = publicKey) }
            activityRepo.setContact(
                contactPublicKey = publicKey,
                forPaymentId = activityId,
                syncLdkPayments = false,
            ).onSuccess {
                _effects.emit(ActivityAssignContactEffect.Close)
            }.onFailure {
                Logger.error("Failed to assign contact for activity '$activityId'", it, context = TAG)
                _uiState.update { state -> state.copy(selectedContactKey = null) }
                ToastEventBus.send(
                    type = Toast.ToastType.ERROR,
                    title = context.getString(R.string.common__error),
                    description = context.getString(R.string.common__error_body),
                )
            }
        }
    }

    private companion object {
        const val TAG = "ActivityAssignContactViewModel"
    }
}

@Stable
data class ActivityAssignContactUiState(
    val contacts: ImmutableList<PubkyProfile> = persistentListOf(),
    val isLoading: Boolean = false,
    val selectedContactKey: String? = null,
)

sealed interface ActivityAssignContactEffect {
    data object Close : ActivityAssignContactEffect
}

@Composable
fun ActivityAssignContactScreen(
    activityId: String,
    onBackClick: () -> Unit,
    viewModel: ActivityAssignContactViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(activityId) {
        viewModel.effects.collect {
            when (it) {
                ActivityAssignContactEffect.Close -> onBackClick()
            }
        }
    }

    ActivityAssignContactContent(
        uiState = uiState,
        onBackClick = onBackClick,
        onContactClick = { viewModel.assignContact(activityId, it.publicKey) },
    )
}

@Composable
private fun ActivityAssignContactContent(
    uiState: ActivityAssignContactUiState,
    onBackClick: () -> Unit = {},
    onContactClick: (PubkyProfile) -> Unit = {},
) {
    ScreenColumn(modifier = Modifier.testTag("AssignActivityContactScreen")) {
        AppTopBar(
            titleText = stringResource(R.string.wallet__activity_assign_contact_title),
            onBackClick = onBackClick,
            actions = { DrawerNavIcon() },
        )

        when {
            uiState.isLoading && uiState.contacts.isEmpty() -> {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    GradientCircularProgressIndicator()
                }
            }

            uiState.contacts.isEmpty() -> {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp)
                ) {
                    BodyM(
                        text = stringResource(R.string.wallet__activity_assign_contact_empty),
                        color = Colors.White64,
                    )
                }
            }

            else -> AssignContactList(
                contacts = uiState.contacts,
                selectedContactKey = uiState.selectedContactKey,
                onContactClick = onContactClick,
            )
        }
    }
}

@Composable
private fun AssignContactList(
    contacts: ImmutableList<PubkyProfile>,
    selectedContactKey: String?,
    onContactClick: (PubkyProfile) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        item {
            Caption13Up(
                text = stringResource(R.string.contacts__nav_title),
                color = Colors.White64,
                modifier = Modifier.padding(top = 16.dp, bottom = 16.dp)
            )
            HorizontalDivider(color = Colors.White10)
        }

        items(contacts, key = { it.publicKey }) { contact ->
            AssignActivityContactRow(
                contact = contact,
                enabled = selectedContactKey == null,
                onClickContact = { onContactClick(contact) },
                modifier = Modifier.testTag("AssignActivityContact_${contact.publicKey}")
            )
            HorizontalDivider(color = Colors.White10)
        }
    }
}

@Composable
private fun AssignActivityContactRow(
    contact: PubkyProfile,
    enabled: Boolean,
    onClickContact: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier
            .fillMaxWidth()
            .clickableAlpha(enabled = enabled) { onClickContact() }
            .padding(vertical = 24.dp)
    ) {
        PubkyContactAvatar(profile = contact)
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.weight(1f)
        ) {
            BodyS(
                text = contact.truncatedPublicKey,
                color = Colors.White64,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            BodySSB(
                text = contact.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        ActivityAssignContactContent(
            uiState = ActivityAssignContactUiState(
                contacts = persistentListOf(PubkyProfile.placeholder("pubky1")),
            ),
        )
    }
}
