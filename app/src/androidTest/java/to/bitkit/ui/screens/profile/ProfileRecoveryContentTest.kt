package to.bitkit.ui.screens.profile

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import to.bitkit.R
import to.bitkit.test.annotations.ComposeUi
import to.bitkit.ui.theme.AppThemeSurface
import kotlin.test.assertEquals

@ComposeUi
class ProfileRecoveryContentTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun restartRecoveryWithoutCredentialsRequiresLocalConfirmation() {
        var state by mutableStateOf(ProfileUiState(recoveryPending = true, localDisconnect = true))
        var confirmations = 0
        compose.setContent {
            AppThemeSurface {
                CompositionLocalProvider(LocalInspectionMode provides true) {
                    ProfileContent(
                        uiState = state,
                        onBackClick = {},
                        onClickEdit = { error("Pending recovery must not offer editing") },
                        onClickCopy = {},
                        onClickShare = {},
                        onClickSignOut = { state = state.copy(showSignOutDialog = true) },
                        onDismissSignOutDialog = { state = state.copy(showSignOutDialog = false) },
                        onConfirmSignOut = {
                            confirmations++
                            state = state.copy(showSignOutDialog = false, isSigningOut = true)
                        },
                        onClickRetry = { error("Pending recovery must not restore credentials") },
                        onClickAddTag = {},
                        onRemoveTag = {},
                        onDismissAddTagSheet = {},
                        onSaveTag = {},
                        onDismissCopiedPopup = {},
                    )
                }
            }
        }
        compose.onNodeWithText(context.getString(R.string.profile__cleanup_pending)).assertIsDisplayed()
        compose.onNodeWithTag("ProfileRecoveryDisconnect").assertIsDisplayed().performClick()
        compose.onNodeWithText(context.getString(R.string.profile__disconnect_local_description)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, confirmations) }
        compose.onNodeWithTag("DialogCancel").performClick()
        val dismissedAt = System.currentTimeMillis()
        compose.runOnIdle { assertEquals(0, confirmations) }
        compose.waitUntil(1_000) { System.currentTimeMillis() - dismissedAt > 600 }
        compose.onNodeWithTag("ProfileRecoveryDisconnect").performClick()
        compose.onNodeWithTag("DialogConfirm").performClick()
        compose.runOnIdle { assertEquals(1, confirmations) }
        compose.onNodeWithTag("ProfileRecoveryDisconnect").assertIsNotEnabled()
    }

    @Test
    fun uncertainDeletionOffersReviewThenSeparateConfirmation() {
        var state by mutableStateOf(EditProfileUiState(showDeleteFailureDialog = true, canReviewRemoval = true))
        var reviews = 0
        var deletes = 0
        editContent({ state }, onReview = {
            reviews++
            state = state.copy(showDeleteFailureDialog = false, showDeleteDialog = true)
        }, onConfirm = { deletes++ })
        compose.onNodeWithText(context.getString(R.string.profile__review_removal)).assertIsDisplayed()
        compose.onNodeWithTag("DialogConfirm").performClick()
        compose.runOnIdle {
            assertEquals(1, reviews)
            assertEquals(0, deletes)
        }
        compose.onNodeWithTag("DialogConfirm").performClick()
        compose.runOnIdle { assertEquals(1, deletes) }
    }

    @Test
    fun localCompletionFailureOnlyOffersDisconnectAndClose() {
        var disconnects = 0
        editContent(
            state = { EditProfileUiState(showDeleteFailureDialog = true, canReviewRemoval = false) },
            onReview = { error("Local completion cannot repeat remote deletion") },
            onDisconnect = { disconnects++ },
        )
        compose.onNodeWithText(context.getString(R.string.profile__review_removal)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.common__close)).assertIsDisplayed()
        compose.onNodeWithTag("DialogConfirm").performClick()
        compose.runOnIdle { assertEquals(1, disconnects) }
    }

    private fun editContent(
        state: () -> EditProfileUiState,
        onReview: () -> Unit,
        onDisconnect: () -> Unit = {},
        onConfirm: () -> Unit = {},
    ) {
        compose.setContent {
            AppThemeSurface {
                CompositionLocalProvider(LocalInspectionMode provides true) {
                    EditProfileContent(
                        uiState = state(),
                        onBackClick = {}, onNameChange = {}, onBioChange = {}, onLinkUrlChange = { _, _ -> },
                        onAvatarSelected = {}, onAddLink = {}, onRemoveLink = {}, onAddTag = {}, onRemoveTag = {},
                        onSave = {}, onDelete = {}, onDismissDeleteDialog = {}, onConfirmDelete = onConfirm,
                        onRetryDelete = onReview, onDismissDeleteFailureDialog = {}, onDisconnectProfile = onDisconnect,
                        onDismissAddLinkSheet = {}, onSaveLink = { _, _ -> }, onDismissAddTagSheet = {}, onSaveTag = {},
                    )
                }
            }
        }
    }
}
