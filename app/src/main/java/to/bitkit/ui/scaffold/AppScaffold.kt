package to.bitkit.ui.scaffold

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import to.bitkit.ui.components.Title
import to.bitkit.ui.shared.util.clickableAlpha
import to.bitkit.ui.theme.Colors
import to.bitkit.utils.Logger

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScaffold(
    titleText: String,
    drawerState: DrawerState,
    drawerContent: @Composable (() -> Unit)? = null,
    actions: @Composable (RowScope.() -> Unit) = {},
    content: @Composable () -> Unit,
) {

    val scaffoldContent = @Composable {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickableAlpha { Logger.debug("Coming soon: Profile") }
                        ) {
                            Icon(
                                imageVector = Icons.Filled.AccountCircle,
                                contentDescription = null,
                                tint = Colors.White64,
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Title(text = titleText)
                        }
                    },
                    actions = actions
                )
            },
            modifier = Modifier.imePadding()
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                content()
            }
        }
    }

    if (drawerContent != null) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = drawerContent,
            gesturesEnabled = drawerState.currentValue == DrawerValue.Open,
            content = scaffoldContent
        )
    } else {
        scaffoldContent()
    }
}
