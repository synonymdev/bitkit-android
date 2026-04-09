package to.bitkit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import to.bitkit.R
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileEditForm(
    name: String,
    onNameChange: (String) -> Unit,
    publicKey: String,
    bio: String,
    onBioChange: (String) -> Unit,
    links: ImmutableList<ProfileEditLink>,
    onRemoveLink: (Int) -> Unit,
    onAddLink: () -> Unit,
    tags: ImmutableList<String>,
    onRemoveTag: (Int) -> Unit,
    onAddTag: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    isSaveEnabled: Boolean,
    modifier: Modifier = Modifier,
    avatarContent: @Composable () -> Unit = {},
    onDelete: (() -> Unit)? = null,
    deleteLabel: String = "",
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp)
    ) {
        VerticalSpacer(16.dp)
        avatarContent()
        VerticalSpacer(12.dp)

        TextInput(
            value = name,
            onValueChange = onNameChange,
            placeholder = stringResource(R.string.profile__edit_name_placeholder),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        HorizontalDivider()
        VerticalSpacer(12.dp)

        Text13Up(
            text = stringResource(R.string.profile__your_pubky),
            color = Colors.White64,
        )
        VerticalSpacer(4.dp)
        BodyS(
            text = publicKey,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        HorizontalDivider(modifier = Modifier.padding(top = 12.dp))

        VerticalSpacer(16.dp)
        Text13Up(
            text = stringResource(R.string.profile__edit_bio),
            color = Colors.White64,
            modifier = Modifier.fillMaxWidth()
        )
        VerticalSpacer(8.dp)
        TextInput(
            value = bio,
            onValueChange = onBioChange,
            placeholder = stringResource(R.string.profile__edit_bio_placeholder),
            minLines = 2,
            maxLines = 4,
            modifier = Modifier.fillMaxWidth()
        )

        VerticalSpacer(16.dp)
        links.forEachIndexed { index, link ->
            HorizontalDivider(color = Colors.White10)
            VerticalSpacer(8.dp)
            Text13Up(
                text = link.label,
                color = Colors.White64,
                modifier = Modifier.fillMaxWidth()
            )
            VerticalSpacer(8.dp)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Colors.White10, RoundedCornerShape(8.dp))
                    .padding(16.dp),
            ) {
                BodySSB(text = link.url, modifier = Modifier.weight(1f))
                HorizontalSpacer(8.dp)
                Icon(
                    painter = painterResource(R.drawable.ic_trash),
                    contentDescription = null,
                    tint = Colors.White64,
                    modifier = Modifier
                        .size(16.dp)
                        .clickable { onRemoveLink(index) },
                )
            }
            VerticalSpacer(8.dp)
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            PrimaryButton(
                text = stringResource(R.string.profile__add_link),
                onClick = onAddLink,
                size = ButtonSize.Small,
                fullWidth = false,
                icon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_link),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                },
            )
        }

        VerticalSpacer(16.dp)
        Text13Up(
            text = stringResource(R.string.profile__edit_tags),
            color = Colors.White64,
            modifier = Modifier.fillMaxWidth()
        )
        VerticalSpacer(8.dp)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            tags.forEachIndexed { index, tag ->
                TagButton(
                    text = tag,
                    onClick = { onRemoveTag(index) },
                    displayIconClose = true,
                )
            }
        }
        VerticalSpacer(8.dp)
        Row(modifier = Modifier.fillMaxWidth()) {
            PrimaryButton(
                text = stringResource(R.string.profile__add_tag),
                onClick = onAddTag,
                size = ButtonSize.Small,
                fullWidth = false,
                icon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_tag),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                },
            )
        }

        VerticalSpacer(16.dp)
        BodyS(
            text = stringResource(R.string.profile__edit_public_note),
            color = Colors.White64,
        )

        if (onDelete != null) {
            VerticalSpacer(16.dp)
            HorizontalDivider()
            VerticalSpacer(16.dp)
            Text13Up(
                text = stringResource(R.string.profile__edit_delete_section),
                color = Colors.White64,
                modifier = Modifier.fillMaxWidth()
            )
            VerticalSpacer(8.dp)
            Row(modifier = Modifier.fillMaxWidth()) {
                PrimaryButton(
                    text = deleteLabel,
                    onClick = onDelete,
                    size = ButtonSize.Small,
                    fullWidth = false,
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_trash),
                            contentDescription = null,
                            tint = Colors.Red,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                )
            }
        }

        FillHeight()
        VerticalSpacer(16.dp)
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            SecondaryButton(
                text = stringResource(R.string.common__cancel),
                onClick = onCancel,
                modifier = Modifier.weight(1f)
            )
            PrimaryButton(
                text = stringResource(R.string.common__save),
                onClick = onSave,
                enabled = isSaveEnabled,
                modifier = Modifier.weight(1f)
            )
        }
        VerticalSpacer(16.dp)
    }
}

data class ProfileEditLink(val label: String, val url: String)

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        var name by remember { mutableStateOf("Satoshi") }
        var bio by remember { mutableStateOf("Authored the Bitcoin white paper") }
        ProfileEditForm(
            name = name,
            onNameChange = { name = it },
            publicKey = "pubky3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg",
            bio = bio,
            onBioChange = { bio = it },
            links = persistentListOf(ProfileEditLink("X", "https://x.com/satoshinakamoto")),
            onRemoveLink = {},
            onAddLink = {},
            tags = persistentListOf("Founder"),
            onRemoveTag = {},
            onAddTag = {},
            onSave = {},
            onCancel = {},
            isSaveEnabled = true,
            onDelete = {},
            deleteLabel = "Delete Profile",
        )
    }
}
