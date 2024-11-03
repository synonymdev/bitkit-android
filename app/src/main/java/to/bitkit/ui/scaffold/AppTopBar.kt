package to.bitkit.ui.scaffold

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import to.bitkit.R

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun AppTopBar(
    navController: NavController,
    titleText: String,
    navigationIcon: @Composable () -> Unit = screenNavIcon(navController),
    actions: @Composable (RowScope.() -> Unit) = {},
) {
    CenterAlignedTopAppBar(
        navigationIcon = navigationIcon,
        title =  {
            Text(
                text = titleText,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold
            )
        },
        actions = actions,
    )
}

private fun screenNavIcon(navController: NavController) = @Composable {
    IconButton(onClick = navController::popBackStack) {
        Icon(
            imageVector = Icons.AutoMirrored.Default.ArrowBack,
            contentDescription = stringResource(R.string.back),
            modifier = Modifier.size(24.dp)
        )
    }
}
