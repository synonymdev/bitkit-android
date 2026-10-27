package to.bitkit.ui.sheets

import org.junit.Test
import to.bitkit.ui.components.SyncNodeVariant
import to.bitkit.viewmodels.SendMethod
import to.bitkit.viewmodels.SendUiState
import kotlin.test.assertEquals

class SendSyncNodeVariantTest {
    @Test
    fun `on-chain send without channels shows the bitcoin view`() {
        val uiState = SendUiState(payMethod = SendMethod.ONCHAIN)

        assertEquals(SyncNodeVariant.BITCOIN, uiState.syncNodeVariant(hasChannels = false))
    }

    @Test
    fun `wallet with channels keeps the spending view`() {
        val uiState = SendUiState(payMethod = SendMethod.ONCHAIN)

        assertEquals(SyncNodeVariant.LIGHTNING, uiState.syncNodeVariant(hasChannels = true))
    }

    @Test
    fun `lightning send keeps the spending view`() {
        val uiState = SendUiState(payMethod = SendMethod.LIGHTNING)

        assertEquals(SyncNodeVariant.LIGHTNING, uiState.syncNodeVariant(hasChannels = false))
    }
}
