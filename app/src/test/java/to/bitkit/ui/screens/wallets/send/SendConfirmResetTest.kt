package to.bitkit.ui.screens.wallets.send

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.launch
import org.junit.Test
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals

class SendConfirmResetTest : BaseUnitTest() {
    @Test
    fun `every reset of the confirm screen reaches its collector`() = test {
        val savedStateHandle = SavedStateHandle()
        var resets = 0
        backgroundScope.launch { savedStateHandle.collectConfirmResets { resets++ } }

        savedStateHandle[SEND_CONFIRM_RESET_RESULT_KEY] = true
        savedStateHandle[SEND_CONFIRM_RESET_RESULT_KEY] = true

        assertEquals(2, resets)
    }
}
