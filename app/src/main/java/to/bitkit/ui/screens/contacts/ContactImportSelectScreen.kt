package to.bitkit.ui.screens.contacts

import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.collections.immutable.toImmutableList
import to.bitkit.R
import to.bitkit.models.PubkyProfile
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodyMSB
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.BodySSB
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.HorizontalSpacer
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.PubkyImage
import to.bitkit.ui.components.TagButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.shared.modifiers.clickableAlpha
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent

@Composable
fun ContactImportSelectScreen(
    viewModel: ContactImportSelectViewModel,
    onBackClick: () -> Unit,
    onImportComplete: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.effects.collect {
            when (it) {
                ContactImportSelectEffect.ImportComplete -> onImportComplete()
                ContactImportSelectEffect.NavigateBack -> onBackClick()
            }
        }
    }

    LaunchedEffect(uiState.shouldRedirectToPayContacts) {
        if (uiState.shouldRedirectToPayContacts) {
            onImportComplete()
        }
    }

    BackHandler(onBack = viewModel::onBackClick)

    Content(
        uiState = uiState,
        onBackClick = viewModel::onBackClick,
        onToggleContact = { viewModel.toggleContact(it) },
        onSelectAll = { viewModel.selectAll() },
        onSelectNone = { viewModel.selectNone() },
        onClickContinue = { viewModel.importSelected() },
    )
}

@Composable
private fun Content(
    uiState: ContactImportSelectUiState,
    onBackClick: () -> Unit,
    onToggleContact: (String) -> Unit,
    onSelectAll: () -> Unit,
    onSelectNone: () -> Unit,
    onClickContinue: () -> Unit,
) {
    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.contacts__import_title),
            onBackClick = onBackClick,
            actions = { DrawerNavIcon() },
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp)
        ) {
            VerticalSpacer(24.dp)

            Display(
                text = stringResource(R.string.contacts__import_select_headline)
                    .withAccent(accentColor = Colors.PubkyGreen),
            )

            VerticalSpacer(8.dp)

            BodyM(
                text = stringResource(R.string.contacts__import_select_subtitle),
                color = Colors.White64,
            )

            VerticalSpacer(16.dp)

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                items(uiState.contacts, key = { it.profile.publicKey }) { contact ->
                    SelectableContactRow(
                        contact = contact,
                        onToggle = { onToggleContact(contact.profile.publicKey) },
                    )
                }
            }

            VerticalSpacer(16.dp)

            FooterBar(
                selectedCount = uiState.selectedCount,
                totalCount = uiState.contacts.size,
                onSelectAll = onSelectAll,
                onSelectNone = onSelectNone,
            )

            VerticalSpacer(16.dp)

            PrimaryButton(
                text = stringResource(R.string.common__continue),
                onClick = onClickContinue,
                isLoading = uiState.isImporting,
            )
            VerticalSpacer(16.dp)
        }
    }
}

@Composable
private fun SelectableContactRow(
    contact: SelectableContact,
    onToggle: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickableAlpha(onClick = onToggle)
            .padding(vertical = 12.dp)
    ) {
        ContactAvatar(profile = contact.profile)

        HorizontalSpacer(16.dp)

        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.weight(1f),
        ) {
            BodyS(
                text = contact.profile.truncatedPublicKey,
                color = Colors.White64,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            BodyMSB(
                text = contact.profile.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        HorizontalSpacer(16.dp)

        if (contact.isSelected) {
            Icon(
                painter = painterResource(R.drawable.ic_check),
                contentDescription = null,
                tint = Colors.PubkyGreen,
                modifier = Modifier.size(24.dp),
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
private fun FooterBar(
    selectedCount: Int,
    totalCount: Int,
    onSelectAll: () -> Unit,
    onSelectNone: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        BodyMSB(
            text = stringResource(R.string.contacts__import_selected_count, selectedCount),
        )

        HorizontalSpacer(16.dp)

        val allSelected = selectedCount == totalCount
        TagButton(
            text = if (allSelected) {
                stringResource(R.string.contacts__import_select_none)
            } else {
                stringResource(R.string.contacts__import_select_all)
            },
            onClick = if (allSelected) onSelectNone else onSelectAll,
        )
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    val contacts = listOf(
        SelectableContact(PubkyProfile("pk1", "Alex Stronghand", "", null, emptyList(), status = null), true),
        SelectableContact(PubkyProfile("pk2", "Anna Pleb", "", null, emptyList(), status = null), true),
        SelectableContact(PubkyProfile("pk3", "Craig Wrong", "", null, emptyList(), status = null), false),
        SelectableContact(PubkyProfile("pk4", "Satoshi Nakamoto", "", null, emptyList(), status = null), true),
        SelectableContact(PubkyProfile("pk5", "Hal Finney", "", null, emptyList(), status = null), false),
    ).toImmutableList()

    AppThemeSurface {
        Content(
            uiState = ContactImportSelectUiState(contacts = contacts),
            onBackClick = {},
            onToggleContact = {},
            onSelectAll = {},
            onSelectNone = {},
            onClickContinue = {},
        )
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewAllSelected() {
    val contacts = listOf(
        SelectableContact(PubkyProfile("pk1", "Alex Stronghand", "", null, emptyList(), status = null), true),
        SelectableContact(PubkyProfile("pk2", "Anna Pleb", "", null, emptyList(), status = null), true),
    ).toImmutableList()

    AppThemeSurface {
        Content(
            uiState = ContactImportSelectUiState(contacts = contacts),
            onBackClick = {},
            onToggleContact = {},
            onSelectAll = {},
            onSelectNone = {},
            onClickContinue = {},
        )
    }
}
