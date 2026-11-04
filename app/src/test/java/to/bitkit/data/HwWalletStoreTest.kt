package to.bitkit.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import to.bitkit.models.HwWalletVendor
import to.bitkit.models.KnownDevice
import to.bitkit.models.TransportType
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals

@Config(application = Application::class, sdk = [34])
@RunWith(RobolectricTestRunner::class)
class HwWalletStoreTest : BaseUnitTest() {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val sut = HwWalletStore(context, testDispatcher)

    private val trezor = KnownDevice(
        id = "trezor",
        name = "trezor",
        path = "trezor",
        transportType = TransportType.USB,
        label = null,
        model = null,
        lastConnectedAt = 1L,
        xpubs = mapOf("nativeSegwit" to "xpub-trezor"),
        walletId = "wallet-trezor",
        vendor = HwWalletVendor.TREZOR,
    )

    @Before
    fun setUp() = runBlocking { sut.reset() }

    @After
    fun tearDown() = runBlocking { sut.reset() }

    @Test
    fun `a held send is kept apart from the devices until its transaction is released`() = test {
        val held = HeldHwSend(
            walletId = "wallet-trezor",
            txId = "AB",
            address = "address",
            amountSats = 25_000uL,
            satsPerVByte = 2uL,
            serializedTx = "00",
            miningFeeSats = 200uL,
            feeRate = 2uL,
            totalSpent = 25_200uL,
        )
        sut.holdSend(held)
        sut.holdSend(held.copy(tags = listOf("rent")))
        sut.saveKnownDevices(listOf(trezor))

        assertEquals(listOf(held.copy(tags = listOf("rent"))), sut.loadHeldSends())

        sut.releaseHeldSends(setOf("ab"))

        assertEquals(emptyList(), sut.loadHeldSends())
        assertEquals(listOf(trezor), sut.loadKnownDevices())
    }
}
