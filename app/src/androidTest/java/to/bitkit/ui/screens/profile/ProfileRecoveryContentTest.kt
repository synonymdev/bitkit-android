package to.bitkit.ui.screens.profile

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso.pressBackUnconditionally
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import to.bitkit.R
import to.bitkit.test.annotations.ComposeUi
import to.bitkit.ui.theme.AppThemeSurface
import java.util.Locale
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@ComposeUi
class ProfileRecoveryContentTest {
    private companion object {
        val GREEK: Locale = Locale.forLanguageTag("el")
    }

    @get:Rule
    val compose = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun restartRecoveryWithoutCredentialsRequiresLocalConfirmation() {
        var state by mutableStateOf(
            ProfileUiState(recoveryPending = true, localDisconnect = true),
        )
        var confirmations = 0
        var back = 0
        compose.setContent {
            AppThemeSurface {
                CompositionLocalProvider(LocalInspectionMode provides true) {
                    ProfileContent(
                        uiState = state,
                        onBackClick = { back++ },
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
        compose.onNodeWithText(context.getString(R.string.profile__wallet_recovery_pending)).assertIsDisplayed()
        compose.onNodeWithTag("ProfileRecoveryDisconnect").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(recoveryCanDisconnect = true) }
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
        compose.onNodeWithTag("NavigationBack").performClick()
        compose.onNodeWithTag("HeaderMenu").assertDoesNotExist()
        pressBackUnconditionally()
        compose.onNodeWithTag("ProfileRecoveryDisconnect").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, back) }
    }

    @Test
    fun cleanupRecoveryContentKeepsStandardGutter() {
        var locale by mutableStateOf(Locale.ENGLISH)
        var width by mutableStateOf<Dp?>(null)
        var fontScale by mutableFloatStateOf(1f)
        compose.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply { setLocale(locale) }
            val localized = LocalContext.current.createConfigurationContext(configuration)
            CompositionLocalProvider(
                LocalContext provides localized,
                LocalConfiguration provides configuration,
                LocalDensity provides Density(LocalDensity.current.density, fontScale),
                LocalInspectionMode provides true,
            ) {
                AppThemeSurface {
                    Box(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier
                                .then(width?.let { Modifier.width(it) } ?: Modifier.fillMaxWidth())
                                .testTag("RecoveryFrame")
                        ) {
                            ProfileContent(
                                uiState = ProfileUiState(
                                    recoveryPending = true,
                                    recoveryCanDisconnect = true,
                                    recoveryCanRetry = true,
                                    isSigningOut = true,
                                ),
                                onBackClick = {},
                                onClickEdit = {},
                                onClickCopy = {},
                                onClickShare = {},
                                onClickSignOut = {},
                                onDismissSignOutDialog = {},
                                onConfirmSignOut = {},
                                onClickRetry = {},
                                onClickAddTag = {},
                                onRemoveTag = {},
                                onDismissAddTagSheet = {},
                                onSaveTag = {},
                                onDismissCopiedPopup = {},
                            )
                        }
                    }
                }
            }
        }
        assertGutter(cleanupText(Locale.ENGLISH))
        compose.runOnIdle {
            locale = GREEK
            width = 320.dp
            fontScale = 1.3f
        }
        assertGutter(cleanupText(GREEK))
    }

    private fun cleanupText(locale: Locale): String {
        val configuration = Configuration(context.resources.configuration).apply { setLocale(locale) }
        return context.createConfigurationContext(configuration).getString(R.string.profile__cleanup_pending)
    }

    private fun assertGutter(text: String) {
        val frame = compose.onNodeWithTag("RecoveryFrame").getUnclippedBoundsInRoot()
        val left = frame.left + 16.dp
        val right = frame.right - 16.dp
        compose.onNodeWithText(text).assertExists().assertLeftPositionInRootIsEqualTo(left)
        listOf("ProfileRecoveryRetry", "ProfileRecoveryDisconnect").forEach {
            val bounds = compose.onNodeWithTag(it).assertLeftPositionInRootIsEqualTo(left).getUnclippedBoundsInRoot()
            assertTrue(abs((right - bounds.right).value) < 0.5f, "'$it' right edge '${bounds.right}' is off the gutter")
        }
        val progress = compose.onNodeWithTag("ProfileRecoveryProgress").assertExists().getUnclippedBoundsInRoot()
        assertTrue(progress.left >= left && progress.right <= right, "progress '$progress' crosses the gutter")
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
                        onLocalDisconnect = { error("Fresh local disconnect is not part of this recovery scenario") },
                        onDismissAddLinkSheet = {}, onSaveLink = { _, _ -> }, onDismissAddTagSheet = {}, onSaveTag = {},
                    )
                }
            }
        }
    }
}
