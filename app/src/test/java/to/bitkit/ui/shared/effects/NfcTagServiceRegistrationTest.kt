package to.bitkit.ui.shared.effects

import android.app.Activity
import android.content.ComponentName
import android.nfc.cardemulation.CardEmulation
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NfcTagServiceRegistrationTest {
    private val activity = mock<Activity>()
    private val service = mock<ComponentName>()
    private val cardEmulation = mock<CardEmulation>()

    @Test
    fun `registered service is preferred and reported`() {
        whenever(cardEmulation.registerAidsForService(any(), any(), any())).thenReturn(true)
        whenever(cardEmulation.setPreferredService(activity, service)).thenReturn(true)

        assertTrue(registerTagService(cardEmulation, activity, service))
        verify(cardEmulation).setPreferredService(activity, service)
        verify(cardEmulation, never()).removeAidsForService(anyOrNull(), anyOrNull())
    }

    @Test
    fun `refused preference releases the aid and is reported`() {
        whenever(cardEmulation.registerAidsForService(any(), any(), any())).thenReturn(true)
        whenever(cardEmulation.setPreferredService(activity, service)).thenReturn(false)

        assertFalse(registerTagService(cardEmulation, activity, service))
        verify(cardEmulation).removeAidsForService(service, CardEmulation.CATEGORY_OTHER)
    }

    @Test
    fun `refused registration is reported`() {
        whenever(cardEmulation.registerAidsForService(any(), any(), any())).thenReturn(false)

        assertFalse(registerTagService(cardEmulation, activity, service))
        verify(cardEmulation, never()).setPreferredService(anyOrNull(), anyOrNull())
    }

    @Test
    fun `failed registration is reported`() {
        whenever(cardEmulation.registerAidsForService(any(), any(), any())).thenThrow(SecurityException("denied"))

        assertFalse(registerTagService(cardEmulation, activity, service))
    }
}
