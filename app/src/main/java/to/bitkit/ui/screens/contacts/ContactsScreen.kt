package to.bitkit.ui.screens.contacts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import to.bitkit.R
import to.bitkit.models.PubkyProfile
import to.bitkit.ui.components.ActionButton
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.BodySSB
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.GradientCircularProgressIndicator
import to.bitkit.ui.components.HorizontalSpacer
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.PubkyImage
import to.bitkit.ui.components.SearchInput
import to.bitkit.ui.components.Text13Up
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.shared.modifiers.clickableAlpha
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun ContactsScreen(
    viewModel: ContactsViewModel,
    onBackClick: () -> Unit,
    onClickMyProfile: () -> Unit,
    onClickContact: (String) -> Unit,
    onAddContact: (String) -> Unit = {},
    onScanQr: () -> Unit = {},
    openAddContactSheet: Boolean = false,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.refresh() }

    Content(
        uiState = uiState,
        onBackClick = onBackClick,
        onClickMyProfile = onClickMyProfile,
        onClickContact = onClickContact,
        onSearchTextChange = { viewModel.onSearchTextChange(it) },
        onAddContact = onAddContact,
        onScanQr = onScanQr,
        openAddContactSheet = openAddContactSheet,
    )
}

@Composable
private fun Content(
    uiState: ContactsUiState,
    onBackClick: () -> Unit,
    onClickMyProfile: () -> Unit,
    onClickContact: (String) -> Unit,
    onSearchTextChange: (String) -> Unit,
    onAddContact: (String) -> Unit,
    onScanQr: () -> Unit,
    openAddContactSheet: Boolean,
) {
    var showAddContactSheet by rememberSaveable { mutableStateOf(openAddContactSheet) }

    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.contacts__nav_title),
            onBackClick = onBackClick,
            actions = { DrawerNavIcon() },
        )

        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                SearchInput(
                    value = uiState.searchText,
                    onValueChange = onSearchTextChange,
                    modifier = Modifier.weight(1f)
                )
                HorizontalSpacer(8.dp)
                ActionButton(
                    onClick = { showAddContactSheet = true },
                    iconRes = R.drawable.ic_plus,
                    modifier = Modifier.testTag("ContactsAddButton")
                )
            }
            VerticalSpacer(8.dp)
        }

        when {
            uiState.isLoading && uiState.contacts.isEmpty() -> LoadingState()
            uiState.isEmpty && uiState.searchText.isBlank() -> EmptyState(
                myProfile = uiState.myProfile,
                onClickMyProfile = onClickMyProfile,
                onAddContact = { showAddContactSheet = true },
            )
            else -> ContactsList(
                contacts = uiState.contacts,
                myProfile = uiState.myProfile,
                showMyProfile = uiState.searchText.isBlank(),
                onClickMyProfile = onClickMyProfile,
                onClickContact = onClickContact,
            )
        }
    }

    if (showAddContactSheet) {
        AddContactSheet(
            currentPublicKey = uiState.myProfile?.publicKey,
            contacts = uiState.contacts,
            onDismiss = { showAddContactSheet = false },
            onSubmit = { publicKey ->
                showAddContactSheet = false
                onAddContact(publicKey)
            },
            onScanQr = {
                showAddContactSheet = false
                onScanQr()
            },
        )
    }
}

@Composable
private fun ContactsList(
    contacts: ImmutableList<PubkyProfile>,
    myProfile: PubkyProfile?,
    showMyProfile: Boolean,
    onClickMyProfile: () -> Unit,
    onClickContact: (String) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        if (showMyProfile && myProfile != null) {
            item {
                Text13Up(
                    text = stringResource(R.string.contacts__my_profile),
                    color = Colors.White64,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)
                )
                ContactRow(
                    profile = myProfile,
                    onClick = onClickMyProfile,
                    modifier = Modifier.testTag("ContactsMyProfile")
                )
                HorizontalDivider()
            }
        }

        if (contacts.isNotEmpty()) {
            item {
                Text13Up(
                    text = stringResource(R.string.contacts__contacts_header),
                    color = Colors.White64,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)
                )
                HorizontalDivider()
            }

            items(contacts, key = { it.publicKey }) { contact ->
                ContactRow(
                    profile = contact,
                    onClick = { onClickContact(contact.publicKey) },
                    modifier = Modifier.testTag("Contact_${contact.publicKey}")
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun ContactRow(
    profile: PubkyProfile,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier
            .fillMaxWidth()
            .clickableAlpha(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        ContactAvatar(profile = profile)

        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.weight(1f)
        ) {
            BodyS(
                text = profile.truncatedPublicKey,
                color = Colors.White64,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            BodySSB(
                text = profile.name,
                color = Colors.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ContactAvatar(profile: PubkyProfile) {
    if (profile.imageUrl != null) {
        PubkyImage(uri = profile.imageUrl, size = 48.dp)
    } else {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(Colors.White10)
        ) {
            BodySSB(
                text = profile.name.firstOrNull()?.uppercase().orEmpty(),
                color = Colors.White,
            )
        }
    }
}

@Composable
private fun LoadingState() {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize()
    ) {
        GradientCircularProgressIndicator(modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun EmptyState(
    myProfile: PubkyProfile?,
    onClickMyProfile: () -> Unit,
    onAddContact: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        myProfile?.let {
            VerticalSpacer(16.dp)
            Text13Up(
                text = stringResource(R.string.contacts__my_profile),
                color = Colors.White64,
                modifier = Modifier.fillMaxWidth()
            )
            ContactRow(
                profile = it,
                onClick = onClickMyProfile,
                modifier = Modifier.testTag("ContactsMyProfile")
            )
            HorizontalDivider()
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 48.dp)
        ) {
            PrimaryButton(
                text = stringResource(R.string.contacts__intro_add_contact),
                onClick = onAddContact,
                modifier = Modifier.testTag("ContactsEmptyAddButton")
            )
            BodyM(
                text = stringResource(R.string.contacts__empty_state),
                color = Colors.White64,
            )
        }

        FillHeight()
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        Content(
            uiState = ContactsUiState(
                contacts = persistentListOf(
                    PubkyProfile("pk1", "Alex Stronghand", "", null, emptyList(), status = null),
                    PubkyProfile("pk2", "Anna Pleb", "", null, emptyList(), status = null),
                    PubkyProfile("pk3", "Areem Holden", "", null, emptyList(), status = null),
                    PubkyProfile("pk4", "Craig Wrong", "", null, emptyList(), status = null),
                ),
                myProfile = PubkyProfile("pk0", "Satoshi Nakamoto", "", null, emptyList(), status = null),
            ),
            onBackClick = {},
            onClickMyProfile = {},
            onClickContact = {},
            onSearchTextChange = {},
            onAddContact = {},
            onScanQr = {},
            openAddContactSheet = false,
        )
    }
}
