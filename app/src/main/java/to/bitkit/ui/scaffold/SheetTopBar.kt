package to.bitkit.ui.scaffold

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import to.bitkit.R
import to.bitkit.ui.theme.AppThemeSurface

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun SheetTopBar(
    titleText: String,
    actions: @Composable (RowScope.() -> Unit) = {},
    onBack: (() -> Unit)? = null,
) {
    CenterAlignedTopAppBar(
        navigationIcon = {
            onBack?.let { callback ->
                IconButton(onClick = callback) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Default.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        },
        title =  {
            Text(
                text = titleText,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
            )
        },
        actions = actions,
        windowInsets = WindowInsets.statusBars.only(WindowInsetsSides.Horizontal),
    )
}

@Preview(showBackground = true)
@Composable
private fun SheetTopBarPreview() {
    AppThemeSurface {
        SheetTopBar(
            titleText = "Sheet Top Bar",
            onBack = {},
        )
    }
}
