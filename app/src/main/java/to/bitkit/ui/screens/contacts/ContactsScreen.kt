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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.models.PubkyProfile
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.BodySSB
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
    onClickContact: (String) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.refresh() }

    Content(
        uiState = uiState,
        onBackClick = onBackClick,
        onClickContact = onClickContact,
        onSearchTextChange = { viewModel.onSearchTextChange(it) },
    )
}

@Composable
private fun Content(
    uiState: ContactsUiState,
    onBackClick: () -> Unit,
    onClickContact: (String) -> Unit,
    onSearchTextChange: (String) -> Unit,
) {
    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.contacts__nav_title),
            onBackClick = onBackClick,
            actions = { DrawerNavIcon() },
        )

        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            SearchInput(
                value = uiState.searchText,
                onValueChange = onSearchTextChange,
            )
            VerticalSpacer(8.dp)
        }

        when {
            uiState.isLoading && uiState.groupedContacts.isEmpty() -> LoadingState()
            uiState.isEmpty && uiState.searchText.isBlank() -> EmptyState()
            else -> ContactsList(
                groupedContacts = uiState.groupedContacts,
                myProfile = uiState.myProfile,
                showMyProfile = uiState.searchText.isBlank(),
                onClickContact = onClickContact,
            )
        }
    }
}

@Composable
private fun ContactsList(
    groupedContacts: Map<Char, List<PubkyProfile>>,
    myProfile: PubkyProfile?,
    showMyProfile: Boolean,
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
                    onClick = { onClickContact(myProfile.publicKey) },
                )
                HorizontalDivider()
            }
        }

        groupedContacts.forEach { (letter, contacts) ->
            item {
                Text13Up(
                    text = letter.toString(),
                    color = Colors.White64,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)
                )
                HorizontalDivider()
            }

            items(contacts, key = { it.publicKey }) { contact ->
                ContactRow(
                    profile = contact,
                    onClick = { onClickContact(contact.publicKey) },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun ContactRow(
    profile: PubkyProfile,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickableAlpha(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        ContactAvatar(profile = profile)

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            BodySSB(
                text = profile.name,
                color = Colors.White,
            )
            BodyS(
                text = profile.truncatedPublicKey,
                color = Colors.White64,
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
        CircularProgressIndicator(color = Colors.White32)
    }
}

@Composable
private fun EmptyState() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp)
    ) {
        BodyM(text = stringResource(R.string.contacts__empty_state), color = Colors.White64)
    }
}

@Preview(showBackground = true)
@Composable
private fun Preview() {
    val contacts = listOf(
        PubkyProfile("pk1", "Alex Stronghand", "", null, emptyList(), null),
        PubkyProfile("pk2", "Anna Pleb", "", null, emptyList(), null),
        PubkyProfile("pk3", "Areem Holden", "", null, emptyList(), null),
        PubkyProfile("pk4", "Craig Wrong", "", null, emptyList(), null),
    )
    AppThemeSurface {
        Content(
            uiState = ContactsUiState(
                groupedContacts = contacts.groupBy { it.name.first() }.toSortedMap(),
                myProfile = PubkyProfile("pk0", "Satoshi Nakamoto", "", null, emptyList(), null),
            ),
            onBackClick = {},
            onClickContact = {},
            onSearchTextChange = {},
        )
    }
}
