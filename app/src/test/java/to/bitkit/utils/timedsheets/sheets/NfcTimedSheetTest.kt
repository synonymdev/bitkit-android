package to.bitkit.utils.timedsheets.sheets

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.test.BaseUnitTest
import to.bitkit.ui.components.TimedSheetType

class NfcTimedSheetTest : BaseUnitTest() {
    private val packageManager = mock<PackageManager>()
    private val context = mock<Context>()
    private val settingsStore = mock<SettingsStore>()
    private val settingsFlow = MutableStateFlow(SettingsData())
    private lateinit var sut: NfcTimedSheet

    @Before
    fun setUp() = runBlocking {
        whenever(context.packageManager).thenReturn(packageManager)
        whenever(packageManager.hasSystemFeature(PackageManager.FEATURE_NFC)).thenReturn(true)
        whenever(settingsStore.data).thenReturn(settingsFlow)
        whenever(settingsStore.update(any())).then {
            val transform = it.getArgument<(SettingsData) -> SettingsData>(0)
            settingsFlow.value = transform(settingsFlow.value)
            flowOf(settingsFlow.value)
        }
        sut = NfcTimedSheet(context, settingsStore)
    }

    @Test
    fun `type is NFC`() {
        assertEquals(TimedSheetType.NFC, sut.type)
    }

    @Test
    fun `shows once on devices with NFC`() = test {
        assertTrue(sut.shouldShow())
    }

    @Test
    fun `does not show without NFC`() = test {
        whenever(packageManager.hasSystemFeature(PackageManager.FEATURE_NFC)).thenReturn(false)

        assertFalse(sut.shouldShow())
    }

    @Test
    fun `does not show when already seen`() = test {
        settingsFlow.value = SettingsData(nfcIntroSeen = true)

        assertFalse(sut.shouldShow())
    }

    @Test
    fun `dismissing marks the NFC and QuickPay intros as seen`() = test {
        sut.onDismissed()

        assertTrue(settingsFlow.value.nfcIntroSeen)
        assertTrue(settingsFlow.value.quickPayIntroSeen)
        assertFalse(sut.shouldShow())
    }
}
