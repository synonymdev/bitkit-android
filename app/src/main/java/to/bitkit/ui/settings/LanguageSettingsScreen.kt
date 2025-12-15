package to.bitkit.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.models.Language
import to.bitkit.ui.components.Text13Up
import to.bitkit.ui.components.settings.SettingsButtonRow
import to.bitkit.ui.components.settings.SettingsButtonValue
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.shared.util.screen
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.viewmodels.LanguageUiState
import to.bitkit.viewmodels.LanguageViewModel

@Composable
fun LanguageSettingsScreen(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewmodel: LanguageViewModel = hiltViewModel(),
) {
    val uiState by viewmodel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewmodel.fetchLanguageInfo() }

    Content(
        uiState = uiState,
        onBackClick = onBackClick,
        onClickLanguage = { selectedLanguage -> viewmodel.selectLanguage(selectedLanguage) },
        modifier = modifier,
    )
}

@Composable
private fun Content(
    uiState: LanguageUiState,
    onBackClick: () -> Unit,
    onClickLanguage: (Language) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.screen()
    ) {
        AppTopBar(
            titleText = "Language", // TODO Transifex
            onBackClick = onBackClick,
            actions = { DrawerNavIcon() }
        )

        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
        ) {
            Text13Up("Interface Language", color = Colors.White64, modifier = Modifier.padding(vertical = 16.dp))

            LazyColumn {
                items(uiState.languages, { item -> item.displayName }) { item ->
                    SettingsButtonRow(
                        title = item.displayName,
                        value = SettingsButtonValue.BooleanValue(item == uiState.selectedLanguage),
                        onClick = { onClickLanguage(item) }
                    )
                }
            }
        }
    }
}

@Preview
@Composable
private fun Preview() {
    AppThemeSurface {
        Content(
            uiState = LanguageUiState(
                selectedLanguage = Language.SPANISH,
                languages = Language.entries
            ),
            onBackClick = {},
            onClickLanguage = {},
        )
    }
}
