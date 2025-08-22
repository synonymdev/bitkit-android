package to.bitkit.ui.screens.wallets.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ext.ellipsisMiddle
import to.bitkit.models.Toast
import to.bitkit.ui.appViewModel
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.CloseNavIcon
import to.bitkit.ui.theme.Colors
import to.bitkit.viewmodels.ActivityDetailScreenState

@Composable
fun ActivityDetailPreviewScreen(
    uiState: ActivityDetailScreenState,
    onBackClick: () -> Unit,
    onCloseClick: () -> Unit,
) {
    val app = appViewModel ?: return
    val copyToastTitle = stringResource(R.string.common__copied)

    val title = when (uiState) {
        ActivityDetailScreenState.Loading -> R.string.wallet__activity
        is ActivityDetailScreenState.Success -> {
            val isSent = uiState.isSent

            var resId = when {
                isSent -> R.string.wallet__activity_bitcoin_sent
                else -> R.string.wallet__activity_bitcoin_received
            }

            if (uiState.isTransfer) {
                resId = when {
                    isSent -> R.string.wallet__activity_transfer_spending_done
                    else -> R.string.wallet__activity_transfer_savings_done
                }
            }

            resId
        }
    }

    Box(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.background(Colors.Black)
        ) {
            AppTopBar(
                titleText = stringResource(title),
                onBackClick = onBackClick,
                actions = { CloseNavIcon(onClick = onCloseClick) },
            )
            when (uiState) {
                ActivityDetailScreenState.Loading -> {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(32.dp)
                            .fillMaxSize()
                    )
                }

                is ActivityDetailScreenState.Success -> {
                    ActivityDetailContent(
                        uiState = uiState,
                        onRemoveTag = { /*detailViewModel.removeTag(it)*/ },
                        onAddTagClick = { /*showAddTagSheet = true */},
                        onExploreClick = {},
                        onCopy = { text ->
                            app.toast(
                                type = Toast.ToastType.SUCCESS,
                                title = copyToastTitle,
                                description = text.ellipsisMiddle(40)
                            )
                        },
                        onClickBoost = {}
                    )
                }
            }
            // if (showAddTagSheet) {
            //     ActivityAddTagSheet(
            //         listViewModel = {/*listViewModel*/ },
            //         activityViewModel = detailViewModel,
            //         onDismiss = { showAddTagSheet = false },
            //     )
            // }
        }
    }
}
