package to.bitkit.ui.settings.general

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import to.bitkit.R
import to.bitkit.ui.components.TagButton
import to.bitkit.ui.components.settings.SectionHeader
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.settingsViewModel
import to.bitkit.ui.theme.AppThemeSurface

@Composable
fun TagsSettingsScreen(
    navController: NavController,
) {
    val settings = settingsViewModel ?: return

    val tags by settings.lastUsedTags.collectAsStateWithLifecycle()

    TagsSettingsContent(
        tags = tags,
        onClickTag = { tag ->
            settings.deleteLastUsedTag(tag)
            if (tags.size == 1) {
                navController.popBackStack()
            }
        },
        onBackClick = { navController.popBackStack() },
    )
}

@Composable
private fun TagsSettingsContent(
    tags: ImmutableList<String>,
    onClickTag: (String) -> Unit = {},
    onBackClick: () -> Unit = {},
) {
    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.settings__general__tags),
            onBackClick = onBackClick,
            actions = { DrawerNavIcon() },
        )
        Column(
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            tags.takeIf { it.isNotEmpty() }?.let {
                SectionHeader(stringResource(R.string.settings__general__tags_previously))

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                ) {
                    tags.map { tagText ->
                        TagButton(
                            text = tagText,
                            onClick = { onClickTag(tagText) },
                            displayIconClose = true,
                            icon = painterResource(R.drawable.ic_trash),
                        )
                    }
                }
            }
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        TagsSettingsContent(
            tags = persistentListOf("tag1", "tag2", "tag3"),
        )
    }
}
