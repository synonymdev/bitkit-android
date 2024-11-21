package to.bitkit.services

import uniffi.bitkitcore.OnChainInvoice
import uniffi.bitkitcore.Scanner
import javax.inject.Inject

class ScannerService @Inject constructor() {
    suspend fun decode(input: String): Scanner {
        return uniffi.bitkitcore.decode(input)
    }

    suspend fun decodeMock(input: String): Scanner {
        val inputMock = MockScannerInput.valueOf(input)
        return when (inputMock) {
            MockScannerInput.OnChain -> onChainMock()
            MockScannerInput.Lightning -> TODO()
        }
    }
}

enum class MockScannerInput {
    OnChain,
    Lightning,
}

// region mocks
private fun onChainMock() = Scanner.OnChain(
    invoice = OnChainInvoice(
        address = "btcAddress",
        amountSatoshis = 1234u,
        label = "label",
        message = "message",
        params = mapOf("param1" to "value1"),
    )
)
// endregion
