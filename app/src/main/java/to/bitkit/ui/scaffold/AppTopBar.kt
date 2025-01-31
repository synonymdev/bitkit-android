package to.bitkit.ui.scaffold

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.components.Title

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun AppTopBar(
    titleText: String,
    onBackClick: () -> Unit,
    navigationIcon: @Composable () -> Unit = backNavIcon(onBackClick),
    actions: @Composable (RowScope.() -> Unit) = {},
) {
    CenterAlignedTopAppBar(
        navigationIcon = navigationIcon,
        title = {
            Title(text = titleText)
        },
        actions = actions,
    )
}

private fun backNavIcon(onBackClick: () -> Unit) = @Composable {
    IconButton(onClick = onBackClick) {
        Icon(
            imageVector = Icons.AutoMirrored.Default.ArrowBack,
            contentDescription = stringResource(R.string.back),
            modifier = Modifier.size(24.dp)
        )
    }
}
