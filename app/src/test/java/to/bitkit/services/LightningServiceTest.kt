package to.bitkit.services

import org.junit.Before
import org.junit.Test
import org.lightningdevkit.ldknode.Node
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import to.bitkit.data.SettingsStore
import to.bitkit.data.backup.VssStoreIdProvider
import to.bitkit.data.keychain.Keychain
import to.bitkit.ext.createChannelDetails
import to.bitkit.test.BaseUnitTest
import to.bitkit.utils.LoggerLdk
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LightningServiceTest : BaseUnitTest() {
    private val keychain = mock<Keychain>()
    private val vssStoreIdProvider = mock<VssStoreIdProvider>()
    private val settingsStore = mock<SettingsStore>()
    private val loggerLdk = mock<LoggerLdk>()
    private val node = mock<Node>()

    private lateinit var sut: LightningService

    @Before
    fun setUp() {
        sut = LightningService(
            bgDispatcher = testDispatcher,
            keychain = keychain,
            vssStoreIdProvider = vssStoreIdProvider,
            settingsStore = settingsStore,
            loggerLdk = loggerLdk,
        )
        sut.node = node
    }

    @Test
    fun `canReceive returns false when channel is ready but not usable`() {
        val readyButNotUsable = createChannelDetails().copy(
            isChannelReady = true,
            isUsable = false,
        )
        whenever(node.listChannels()).thenReturn(listOf(readyButNotUsable))

        assertFalse(sut.canReceive())
    }

    @Test
    fun `canReceive returns true when channel is usable`() {
        val usableChannel = createChannelDetails().copy(
            isChannelReady = true,
            isUsable = true,
        )
        whenever(node.listChannels()).thenReturn(listOf(usableChannel))

        assertTrue(sut.canReceive())
    }
}
