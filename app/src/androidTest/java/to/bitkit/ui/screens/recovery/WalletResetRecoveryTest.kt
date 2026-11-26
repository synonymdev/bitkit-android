package to.bitkit.ui.screens.recovery

import android.os.SystemClock
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import to.bitkit.data.WalletResetRecovery
import to.bitkit.test.annotations.ComposeUi
import to.bitkit.ui.theme.AppThemeSurface
import kotlin.test.assertEquals

@ComposeUi
class WalletResetRecoveryTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun resetRequiresFreshConfirmationAndCancelDoesNothing() {
        val finished = mutableListOf<String>()
        rule.setContent {
            AppThemeSurface {
                WalletResetRecoveryContent(WalletResetRecovery.Pending("original"), false, {}, finished::add)
            }
        }
        rule.onNodeWithTag("WalletResetFinish").performClick()
        rule.runOnIdle { assertEquals(emptyList(), finished) }
        rule.onNodeWithTag("DialogCancel").performClick()
        rule.runOnIdle { assertEquals(emptyList(), finished) }
        val dismissedAt = SystemClock.uptimeMillis()
        rule.waitUntil(1_000) { SystemClock.uptimeMillis() - dismissedAt >= 600 }
        rule.onNodeWithTag("WalletResetFinish").performClick()
        rule.onNodeWithTag("DialogConfirm").performClick()
        rule.runOnIdle { assertEquals(listOf("original"), finished) }
    }

    @Test
    fun changedAttemptDiscardsTheOldConfirmation() {
        val recovery = mutableStateOf<WalletResetRecovery>(WalletResetRecovery.Pending("old"))
        val finished = mutableListOf<String>()
        rule.setContent {
            AppThemeSurface { WalletResetRecoveryContent(recovery.value, false, {}, finished::add) }
        }
        rule.onNodeWithTag("WalletResetFinish").performClick()
        rule.runOnIdle { recovery.value = WalletResetRecovery.Pending("new") }
        rule.onNodeWithTag("DialogConfirm").assertDoesNotExist()
        rule.runOnIdle { assertEquals(emptyList(), finished) }
    }

    @Test
    fun activeWipeShowsOnlyProgressUntilUnwind() {
        val recovery = mutableStateOf<WalletResetRecovery>(WalletResetRecovery.Unavailable)
        val busy = mutableStateOf(true)
        var reads = 0
        val finished = mutableListOf<String>()
        rule.setContent {
            AppThemeSurface { WalletResetRecoveryContent(recovery.value, busy.value, { reads++ }, finished::add) }
        }
        rule.onNodeWithTag("WalletResetFinish").assertDoesNotExist()
        rule.onNodeWithTag("WalletResetProgress").assertExists()
        rule.onNodeWithTag("WalletResetReadRetry").assertDoesNotExist()
        rule.onNodeWithTag("WalletResetShowPhrase").assertIsNotEnabled()
        rule.runOnIdle { busy.value = false }
        rule.onNodeWithTag("WalletResetReadRetry").performClick()
        rule.runOnIdle {
            assertEquals(1, reads)
            assertEquals(emptyList(), finished)
            recovery.value = WalletResetRecovery.Pending("pending")
            busy.value = true
        }
        rule.onNodeWithTag("WalletResetFinish").assertDoesNotExist()
        rule.onNodeWithTag("WalletResetProgress").assertExists()
        rule.runOnIdle { assertEquals(emptyList(), finished) }
    }

    @Test
    fun unknownMarkerOffersPhraseWithoutAdmittingReset() {
        var shown = 0
        var reads = 0
        val finished = mutableListOf<String>()
        rule.setContent {
            AppThemeSurface {
                WalletResetRecoveryContent(
                    WalletResetRecovery.Unreadable("unknown"), false, { reads++ }, finished::add,
                    onShowPhrase = { shown++ }, canShowPhrase = true,
                )
            }
        }
        rule.onNodeWithTag("WalletResetReadRetry").performClick()
        rule.runOnIdle {
            assertEquals(1, reads)
            assertEquals(0, shown)
            assertEquals(emptyList(), finished)
        }
        rule.onNodeWithTag("WalletResetShowPhrase").performClick()
        rule.runOnIdle {
            assertEquals(1, shown)
            assertEquals(emptyList(), finished)
        }
        rule.onNodeWithTag("WalletResetFinish").performClick()
        rule.onNodeWithTag("DialogCancel").performClick()
        rule.runOnIdle { assertEquals(emptyList(), finished) }
    }

    @Test
    fun phraseGrantIsRevokedByBackgroundAndLateAuthCannotReturn() {
        val lifecycle = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this).apply {
                currentState = Lifecycle.State.RESUMED
            }
        }
        val pinRequired = mutableStateOf<Boolean?>(null)
        var authenticate: (() -> Unit)? = null
        var phraseEntries = 0
        var phraseDisposals = 0
        rule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycle) {
                AppThemeSurface {
                    WalletResetRecoveryFlow(
                        recovery = WalletResetRecovery.Unreadable("original"), busy = false,
                        pinEnabled = pinRequired.value, isCurrent = { true }, onRetry = {}, onFinish = {},
                        authentication = { success, _ ->
                            authenticate = success
                            Text("auth", Modifier.testTag("RecoveryAuth"))
                        },
                        phrase = {
                            DisposableEffect(Unit) {
                                phraseEntries++
                                onDispose { phraseDisposals++ }
                            }
                            Text("read-only placeholder", Modifier.testTag("RecoveryPhrase"))
                        },
                    )
                }
            }
        }
        rule.onNodeWithTag("WalletResetShowPhrase").assertIsNotEnabled()
        rule.runOnIdle { pinRequired.value = true }
        rule.onNodeWithTag("WalletResetShowPhrase").performClick()
        rule.onNodeWithTag("RecoveryAuth").assertExists()
        rule.onNodeWithTag("RecoveryPhrase").assertDoesNotExist()
        rule.runOnIdle { requireNotNull(authenticate).invoke() }
        rule.onNodeWithTag("RecoveryPhrase").assertExists()
        rule.runOnIdle { lifecycle.lifecycle.currentState = Lifecycle.State.CREATED }
        rule.onNodeWithTag("RecoveryPhrase").assertDoesNotExist()
        rule.runOnIdle {
            assertEquals(1, phraseEntries)
            assertEquals(1, phraseDisposals)
            lifecycle.lifecycle.currentState = Lifecycle.State.RESUMED
            requireNotNull(authenticate).invoke()
        }
        rule.onNodeWithTag("RecoveryPhrase").assertDoesNotExist()
    }

    @Test
    fun rawAdmissionAndAttemptChangesRejectQueuedPhraseComposition() {
        val recovery = mutableStateOf<WalletResetRecovery>(WalletResetRecovery.Unreadable("original"))
        val busy = mutableStateOf(false)
        var admitted = true
        var authenticate: (() -> Unit)? = null
        var phraseEntries = 0
        rule.setContent {
            AppThemeSurface {
                WalletResetRecoveryFlow(
                    recovery = recovery.value, busy = busy.value, pinEnabled = true,
                    isCurrent = { admitted }, onRetry = {}, onFinish = {},
                    authentication = { success, _ ->
                        authenticate = success
                        Text("auth", Modifier.testTag("RecoveryAuth"))
                    },
                    phrase = {
                        DisposableEffect(Unit) { phraseEntries++; onDispose {} }
                        Text("read-only placeholder", Modifier.testTag("RecoveryPhrase"))
                    },
                )
            }
        }
        rule.onNodeWithTag("WalletResetShowPhrase").performClick()
        rule.onNodeWithTag("RecoveryAuth").assertExists()
        val oldAuth = requireNotNull(authenticate)
        rule.runOnIdle {
            oldAuth()
            admitted = false
        }
        rule.onNodeWithTag("RecoveryPhrase").assertDoesNotExist()
        rule.runOnIdle {
            assertEquals(0, phraseEntries)
            recovery.value = WalletResetRecovery.Unreadable("replacement")
            admitted = true
        }
        rule.runOnIdle { oldAuth() }
        rule.onNodeWithTag("RecoveryPhrase").assertDoesNotExist()
        rule.onNodeWithTag("WalletResetShowPhrase").performClick()
        rule.onNodeWithTag("RecoveryAuth").assertExists()
        rule.runOnIdle { busy.value = true }
        rule.runOnIdle { requireNotNull(authenticate).invoke() }
        rule.onNodeWithTag("RecoveryPhrase").assertDoesNotExist()
        rule.onNodeWithTag("WalletResetProgress").assertExists()
        rule.runOnIdle { assertEquals(0, phraseEntries) }
    }
}
