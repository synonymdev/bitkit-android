package to.bitkit.ui.screens.widgets.headlines

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.models.widget.ArticleModel
import to.bitkit.models.widget.HeadlinePreferences
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodyMB
import to.bitkit.ui.components.CaptionB
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.TertiaryButton
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.CloseNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors


@Composable
fun HeadlinesSettingsScreen(
    headlinesViewModel: HeadlinesViewModel = hiltViewModel(),
    onClose: () -> Unit,
    onBack: () -> Unit,
) {
    val headlinePreferences by headlinesViewModel.headlinePreferences.collectAsStateWithLifecycle()
    val article by headlinesViewModel.currentArticle.collectAsStateWithLifecycle()
    HeadlinesSettingsContent(
        onClose = onClose,
        onBack = onBack,
        headlinePreferences = headlinePreferences,
        article = article
    )
}

@Composable
fun HeadlinesSettingsContent(
    onClose: () -> Unit,
    onBack: () -> Unit,
    headlinePreferences: HeadlinePreferences,
    article: ArticleModel
) {
    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.widgets__widget__edit),
            onBackClick = onBack,
            actions = { CloseNavIcon(onClick = onClose) },
        )

        Column(
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(26.dp))

            BodyM(
                text = stringResource(R.string.widgets__widget__edit_description).replace(
                    "{name}",
                    stringResource(R.string.widgets__news__name)
                ), color = Colors.White64
            )

            Spacer(modifier = Modifier.height(32.dp))

            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(vertical = 21.dp)
                    .fillMaxWidth()
            ) {
                BodyM(text = article.timeAgo)

                if (headlinePreferences.showTime) {
                    Icon(
                        painter = painterResource(R.drawable.ic_checkmark),
                        contentDescription = null,
                        tint = Colors.Brand,
                        modifier = Modifier.size(32.dp),
                    )
                }
            }

            HorizontalDivider()

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(vertical = 21.dp)
                    .fillMaxWidth()
            ) {
                BodyMB(text = article.title, modifier = Modifier.weight(1f))

                Icon(
                    painter = painterResource(R.drawable.ic_checkmark),
                    contentDescription = null,
                    tint = Colors.Brand,
                    modifier = Modifier.size(32.dp),
                )
            }

            HorizontalDivider()

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(vertical = 21.dp)
                    .fillMaxWidth()
            ) {
                CaptionB(
                    text = stringResource(R.string.widgets__widget__source),
                    color = Colors.White64,
                    modifier = Modifier.weight(1f)
                )

                CaptionB(text = article.publisher, color = Colors.White64)

                if (headlinePreferences.showSource) {
                    Icon(
                        painter = painterResource(R.drawable.ic_checkmark),
                        contentDescription = null,
                        tint = Colors.Brand,
                        modifier = Modifier.size(32.dp),
                    )
                }
            }

            HorizontalDivider()

            Spacer(modifier = Modifier.weight(1f))

            Row(
                modifier = Modifier
                    .padding(vertical = 21.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                TertiaryButton(
                    text = stringResource(R.string.common__reset),
                    modifier = Modifier.weight(1f),
                    enabled = false, //TODO UPDATE
                    fullWidth = false,
                    onClick = {}
                )

                PrimaryButton(
                    text = stringResource(R.string.common__preview),
                    modifier = Modifier.weight(1f),
                    fullWidth = false,
                    onClick = {}
                )
            }
        }
    }
}


@Preview(showBackground = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        HeadlinesSettingsContent(
            onClose = {},
            onBack = {},
            headlinePreferences = HeadlinePreferences(),
            article = ArticleModel(
                timeAgo = "21 minutes ago",
                title = "How Bitcoin changed El Salvador in more ways",
                publisher = "bitcoinmagazine.com",
                link = "bitcoinmagazine.com",
            )
        )
    }
}
