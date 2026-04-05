package to.bitkit.ui.screens.contacts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import to.bitkit.R
import to.bitkit.models.PubkyProfile
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodyMSB
import to.bitkit.ui.components.BodySSB
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.HorizontalSpacer
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.PubkyImage
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent

@Composable
fun ContactImportOverviewScreen(
    viewModel: ContactImportOverviewViewModel,
    onBackClick: () -> Unit,
    onImportComplete: () -> Unit,
    onNavigateToSelect: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.effects.collect {
            when (it) {
                ContactImportOverviewEffect.ImportComplete -> onImportComplete()
                ContactImportOverviewEffect.NavigateToSelect -> onNavigateToSelect()
            }
        }
    }

    Content(
        uiState = uiState,
        onBackClick = onBackClick,
        onClickSelect = { viewModel.navigateToSelect() },
        onClickImportAll = { viewModel.importAll() },
    )
}

@Composable
private fun Content(
    uiState: ContactImportOverviewUiState,
    onBackClick: () -> Unit,
    onClickSelect: () -> Unit,
    onClickImportAll: () -> Unit,
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
                text = stringResource(R.string.contacts__import_overview_headline)
                    .withAccent(accentColor = Colors.PubkyGreen),
            )

            VerticalSpacer(8.dp)

            val truncatedKey = uiState.profile?.truncatedPublicKey.orEmpty()
            BodyM(
                text = stringResource(R.string.contacts__import_overview_subtitle, truncatedKey),
                color = Colors.White64,
            )

            VerticalSpacer(32.dp)

            if (uiState.profile != null) {
                ProfileRow(profile = uiState.profile)
                VerticalSpacer(24.dp)
            }

            if (uiState.contacts.isNotEmpty()) {
                ContactCountRow(contacts = uiState.contacts)
            }

            FillHeight()

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                SecondaryButton(
                    text = stringResource(R.string.contacts__import_select),
                    onClick = onClickSelect,
                    modifier = Modifier.weight(1f),
                )
                PrimaryButton(
                    text = stringResource(R.string.contacts__import_all),
                    onClick = onClickImportAll,
                    isLoading = uiState.isImporting,
                    modifier = Modifier.weight(1f),
                )
            }
            VerticalSpacer(16.dp)
        }
    }
}

@Composable
private fun ProfileRow(profile: PubkyProfile) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Display(
            text = profile.name,
            modifier = Modifier.weight(1f),
        )

        HorizontalSpacer(16.dp)

        if (profile.imageUrl != null) {
            PubkyImage(uri = profile.imageUrl, size = 64.dp)
        } else {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(64.dp)
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
}

@Composable
private fun ContactCountRow(contacts: ImmutableList<PubkyProfile>) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth(),
    ) {
        BodyMSB(
            text = stringResource(R.string.contacts__import_friends_count, contacts.size),
        )

        AvatarStack(contacts = contacts)
    }
}

@Composable
private fun AvatarStack(contacts: ImmutableList<PubkyProfile>) {
    val visibleCount = minOf(contacts.size, 4)
    val overflow = contacts.size - visibleCount

    Box {
        contacts.take(visibleCount).forEachIndexed { index, contact ->
            Box(modifier = Modifier.offset(x = (index * 24).dp)) {
                if (contact.imageUrl != null) {
                    PubkyImage(uri = contact.imageUrl, size = 36.dp)
                } else {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Colors.White10)
                    ) {
                        BodySSB(
                            text = contact.name.firstOrNull()?.uppercase().orEmpty(),
                            color = Colors.White,
                        )
                    }
                }
            }
        }

        if (overflow > 0) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .offset(x = (visibleCount * 24).dp)
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Colors.Gray4)
            ) {
                BodySSB(text = "+$overflow", color = Colors.White)
            }
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    val contacts = listOf(
        PubkyProfile("pk1", "Alex Stronghand", "", null, emptyList(), status = null),
        PubkyProfile("pk2", "Anna Pleb", "", null, emptyList(), status = null),
        PubkyProfile("pk3", "Craig Wrong", "", null, emptyList(), status = null),
        PubkyProfile("pk4", "Satoshi Nakamoto", "", null, emptyList(), status = null),
        PubkyProfile("pk5", "Hal Finney", "", null, emptyList(), status = null),
        PubkyProfile("pk6", "Nick Szabo", "", null, emptyList(), status = null),
    ).toImmutableList()

    AppThemeSurface {
        Content(
            uiState = ContactImportOverviewUiState(
                profile = PubkyProfile(
                    publicKey = "pubky3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg",
                    name = "John Carvalho",
                    bio = "CEO at @synonym_to",
                    imageUrl = null,
                    links = emptyList(),
                    status = null,
                ),
                contacts = contacts,
            ),
            onBackClick = {},
            onClickSelect = {},
            onClickImportAll = {},
        )
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewLoading() {
    AppThemeSurface {
        Content(
            uiState = ContactImportOverviewUiState(
                profile = PubkyProfile(
                    publicKey = "pubky3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg",
                    name = "John Carvalho",
                    bio = "",
                    imageUrl = null,
                    links = emptyList(),
                    status = null,
                ),
                contacts = persistentListOf(),
                isImporting = true,
            ),
            onBackClick = {},
            onClickSelect = {},
            onClickImportAll = {},
        )
    }
}
