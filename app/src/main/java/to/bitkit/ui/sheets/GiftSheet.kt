package to.bitkit.ui.sheets

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import to.bitkit.R
import to.bitkit.models.NewTransactionSheetDetails
import to.bitkit.ui.components.Sheet
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.utils.composableWithDefaultTransitions
import to.bitkit.viewmodels.AppViewModel

@Composable
fun GiftSheet(
    sheet: Sheet.Gift,
    appViewModel: AppViewModel,
    modifier: Modifier = Modifier,
    viewModel: GiftViewModel = hiltViewModel(),
) {
    val navController = rememberNavController()

    LaunchedEffect(sheet.code, sheet.amount) {
        viewModel.initialize(sheet.code, sheet.amount)
    }

    val onSuccessState = rememberUpdatedState { details: NewTransactionSheetDetails ->
        appViewModel.hideSheet()
        appViewModel.showTransactionSheet(details)
    }

    LaunchedEffect(Unit) {
        viewModel.successEvent.collect { details ->
            onSuccessState.value.invoke(details)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.navigationEvent.collect { route ->
            when (route) {
                is GiftRoute.Success -> appViewModel.hideSheet()
                else -> navController.navigate(route) {
                    popUpTo(GiftRoute.Loading) { inclusive = false }
                }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .sheetHeight()
            .imePadding()
            .testTag("GiftSheet")
    ) {
        NavHost(
            navController = navController,
            startDestination = GiftRoute.Loading,
        ) {
            composableWithDefaultTransitions<GiftRoute.Loading> {
                GiftLoading(
                    viewModel = viewModel,
                )
            }
            composableWithDefaultTransitions<GiftRoute.Used> {
                GiftErrorSheet(
                    titleRes = R.string.other__gift__used__title,
                    textRes = R.string.other__gift__used__text,
                    testTag = "GiftUsed",
                    onDismiss = { appViewModel.hideSheet() },
                )
            }
            composableWithDefaultTransitions<GiftRoute.UsedUp> {
                GiftErrorSheet(
                    titleRes = R.string.other__gift__used_up__title,
                    textRes = R.string.other__gift__used_up__text,
                    testTag = "GiftUsedUp",
                    onDismiss = { appViewModel.hideSheet() },
                )
            }
            composableWithDefaultTransitions<GiftRoute.Error> {
                GiftErrorSheet(
                    titleRes = R.string.other__gift__error__title,
                    textRes = R.string.other__gift__error__text,
                    testTag = "GiftError",
                    onDismiss = { appViewModel.hideSheet() },
                )
            }
        }
    }
}
