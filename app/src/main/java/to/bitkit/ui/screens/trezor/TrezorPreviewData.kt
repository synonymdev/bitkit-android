package to.bitkit.ui.screens.trezor

import com.synonym.bitkitcore.AccountAddresses
import com.synonym.bitkitcore.AccountInfoResult
import com.synonym.bitkitcore.AccountType
import com.synonym.bitkitcore.AccountUtxo
import com.synonym.bitkitcore.ComposeAccount
import com.synonym.bitkitcore.ComposeResult
import com.synonym.bitkitcore.HistoryTransaction
import com.synonym.bitkitcore.SingleAddressInfoResult
import com.synonym.bitkitcore.TransactionHistoryResult
import com.synonym.bitkitcore.TrezorAddressResponse
import com.synonym.bitkitcore.TrezorDeviceInfo
import com.synonym.bitkitcore.TrezorFeatures
import com.synonym.bitkitcore.TrezorPublicKeyResponse
import com.synonym.bitkitcore.TrezorSignedTx
import com.synonym.bitkitcore.TrezorTransportType
import com.synonym.bitkitcore.TxDirection
import com.synonym.bitkitcore.WalletBalance
import kotlinx.collections.immutable.persistentListOf
import to.bitkit.models.KnownDevice
import to.bitkit.models.TransportType
import to.bitkit.repositories.ConnectedTrezorDevice
import to.bitkit.repositories.TrezorState
import com.synonym.bitkitcore.Network as BitkitCoreNetwork

internal object TrezorPreviewData {

    val sampleFeatures = TrezorFeatures(
        vendor = "trezor.io",
        model = "Safe 5",
        label = "My Trezor",
        deviceId = "trezor-abc123",
        majorVersion = 2u,
        minorVersion = 8u,
        patchVersion = 1u,
        pinProtection = true,
        unlocked = true,
        passphraseProtection = false,
        initialized = true,
        needsBackup = false,
        passphraseEntryCapable = true,
    )

    val sampleFeaturesMinimal = TrezorFeatures(
        vendor = null,
        model = null,
        label = null,
        deviceId = null,
        majorVersion = null,
        minorVersion = null,
        patchVersion = null,
        pinProtection = null,
        unlocked = null,
        passphraseProtection = null,
        initialized = null,
        needsBackup = null,
        passphraseEntryCapable = null,
    )

    val sampleKnownDevice = KnownDevice(
        id = "usb-1",
        name = "Trezor Safe 5",
        path = "/dev/usb/001",
        transportType = TransportType.USB,
        label = "My Savings",
        model = "Safe 5",
        lastConnectedAt = 1_700_000_000_000L,
    )

    val sampleKnownDeviceBle = KnownDevice(
        id = "ble-1",
        name = "Trezor Safe 7",
        path = "AA:BB:CC:DD:EE:FF",
        transportType = TransportType.BLUETOOTH,
        label = "Daily Wallet",
        model = "Safe 7",
        lastConnectedAt = 1_700_000_000_000L,
    )

    val sampleNearbyDevice = TrezorDeviceInfo(
        id = "ble-2",
        transportType = TrezorTransportType.BLUETOOTH,
        name = "Trezor Safe 7",
        path = "11:22:33:44:55:66",
        label = null,
        model = "Safe 7",
        isBootloader = false,
    )

    val sampleNearbyDeviceUsb = TrezorDeviceInfo(
        id = "usb-2",
        transportType = TrezorTransportType.USB,
        name = "Trezor Model T",
        path = "/dev/usb/002",
        label = "Backup Device",
        model = "Model T",
        isBootloader = false,
    )

    private const val SAMPLE_ADDRESS = "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4"
    private const val SAMPLE_TXID =
        "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2"
    private const val SAMPLE_XPUB =
        "xpub6CUGRUonZSQ4TWtTMmzXdrXDtypWKiKrhko4egpiMZbpiaQL2jkwSB1icqY" +
            "h2cfDfVxdx4df189oLKnC5fSwqPfgyP3hooxujYzAu3fDVmz"
    private const val SAMPLE_PUBKEY =
        "02a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2"
    private const val SAMPLE_CHAIN_CODE =
        "f1e2d3c4b5a6f1e2d3c4b5a6f1e2d3c4b5a6f1e2d3c4b5a6f1e2d3c4b5a6f1e2"

    val sampleAddressResponse = TrezorAddressResponse(
        address = SAMPLE_ADDRESS,
        path = "m/84'/0'/0'/0/0",
    )

    val samplePublicKeyResponse = TrezorPublicKeyResponse(
        xpub = SAMPLE_XPUB,
        path = "m/84'/0'/0'",
        publicKey = SAMPLE_PUBKEY,
        chainCode = SAMPLE_CHAIN_CODE,
        fingerprint = 0x1234u,
        depth = 3u,
        rootFingerprint = 0x5678u,
    )

    val sampleAccountUtxo = AccountUtxo(
        txid = SAMPLE_TXID,
        vout = 0u,
        amount = 50_000uL,
        blockHeight = 849_990u,
        address = SAMPLE_ADDRESS,
        path = "m/84'/0'/0'/0/0",
        confirmations = 6u,
        coinbase = false,
        own = true,
        required = null,
    )

    private val sampleAccountUtxo2 = AccountUtxo(
        txid = "b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3",
        vout = 1u,
        amount = 100_000uL,
        blockHeight = 849_985u,
        address = "bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh",
        path = "m/84'/0'/0'/0/1",
        confirmations = 11u,
        coinbase = false,
        own = true,
        required = null,
    )

    val sampleAccountInfoResult = AccountInfoResult(
        account = ComposeAccount(
            path = "m/84'/0'/0'",
            addresses = AccountAddresses(
                used = emptyList(),
                unused = emptyList(),
                change = emptyList(),
            ),
            utxo = listOf(sampleAccountUtxo, sampleAccountUtxo2),
        ),
        balance = 150_000uL,
        utxoCount = 2u,
        accountType = AccountType.NATIVE_SEGWIT,
        blockHeight = 850_000u,
    )

    val sampleAddressInfoResult = SingleAddressInfoResult(
        address = SAMPLE_ADDRESS,
        balance = 50_000uL,
        utxos = listOf(sampleAccountUtxo),
        transfers = 3u,
        blockHeight = 850_000u,
    )

    val sampleComposeResult = ComposeResult.Success(
        psbt = "cHNidC8BAH0CAAAA...",
        fee = 1000uL,
        feeRate = 5.0f,
        totalSpent = 51000uL,
    )

    val sampleSignedTx = TrezorSignedTx(
        signatures = listOf("304402200a0b0c..."),
        serializedTx = "0200000001a1b2c3d4...deadbeef",
        txid = "c4d5e6f7a8b9c4d5e6f7a8b9c4d5e6f7a8b9c4d5e6f7a8b9c4d5e6f7a8b9c4d5",
    )

    val connectedState = TrezorState(
        connected = ConnectedTrezorDevice(
            id = "trezor-abc123",
            features = sampleFeatures,
        ),
    )

    val connectedStateWithResults = TrezorState(
        connected = ConnectedTrezorDevice(
            id = "trezor-abc123",
            features = sampleFeatures,
        ),
        lastAddress = sampleAddressResponse,
        lastPublicKey = samplePublicKeyResponse,
    )

    val uiStateWithSignature = TrezorUiState(
        network = TrezorNetworkState(selectedNetwork = BitkitCoreNetwork.REGTEST),
        message = TrezorMessageState(
            lastSignature = "H3bK9x...signature...base64==",
            lastSigningAddress = SAMPLE_ADDRESS,
        ),
    )

    val uiStateWithAccountInfo = TrezorUiState(
        network = TrezorNetworkState(selectedNetwork = BitkitCoreNetwork.REGTEST),
        lookup = TrezorLookupState(
            input = SAMPLE_XPUB,
            accountInfoResult = sampleAccountInfoResult,
        ),
    )

    val uiStateWithAddressInfo = TrezorUiState(
        network = TrezorNetworkState(selectedNetwork = BitkitCoreNetwork.REGTEST),
        lookup = TrezorLookupState(
            input = SAMPLE_ADDRESS,
            addressInfoResult = sampleAddressInfoResult,
        ),
    )

    val uiStateReview = TrezorUiState(
        network = TrezorNetworkState(selectedNetwork = BitkitCoreNetwork.REGTEST),
        send = TrezorSendState(
            address = "bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh",
            amountSats = "45000",
            feeRate = "5",
            step = SendStep.Review(sampleComposeResult),
        ),
    )

    val uiStateSigned = TrezorUiState(
        network = TrezorNetworkState(selectedNetwork = BitkitCoreNetwork.REGTEST),
        send = TrezorSendState(step = SendStep.Signed(sampleSignedTx)),
    )

    val sampleWalletBalance = WalletBalance(
        confirmed = 150_000uL,
        immature = 0uL,
        trustedPending = 5_000uL,
        untrustedPending = 0uL,
        spendable = 155_000uL,
        total = 155_000uL,
    )

    val sampleHistoryTransactions = listOf(
        HistoryTransaction(
            txid = SAMPLE_TXID,
            received = 100_000uL,
            sent = 0uL,
            net = 100_000L,
            fee = null,
            feeRate = null,
            amount = 100_000uL,
            direction = TxDirection.RECEIVED,
            blockHeight = 849_990u,
            timestamp = 1_700_000_000uL,
            confirmations = 10u,
        ),
        HistoryTransaction(
            txid = "b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3",
            received = 0uL,
            sent = 50_000uL,
            net = -50_000L,
            fee = 1_200uL,
            feeRate = 5.0,
            amount = 48_800uL,
            direction = TxDirection.SENT,
            blockHeight = 849_995u,
            timestamp = 1_700_100_000uL,
            confirmations = 5u,
        ),
        HistoryTransaction(
            txid = "c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4",
            received = 5_000uL,
            sent = 5_000uL,
            net = 0L,
            fee = 500uL,
            feeRate = 2.0,
            amount = 500uL,
            direction = TxDirection.SELF_TRANSFER,
            blockHeight = null,
            timestamp = null,
            confirmations = 0u,
        ),
    )

    val sampleTransactionHistoryResult = TransactionHistoryResult(
        transactions = sampleHistoryTransactions,
        balance = sampleWalletBalance,
        txCount = 3u,
        blockHeight = 850_000u,
        accountType = AccountType.NATIVE_SEGWIT,
    )

    val uiStateWithTxHistory = TrezorUiState(
        network = TrezorNetworkState(selectedNetwork = BitkitCoreNetwork.REGTEST),
        txHistory = TrezorTxHistoryState(
            input = SAMPLE_XPUB,
            result = sampleTransactionHistoryResult,
        ),
    )

    val uiStateWithActiveWatcher = TrezorUiState(
        network = TrezorNetworkState(selectedNetwork = BitkitCoreNetwork.REGTEST),
        watcher = TrezorWatcherState(
            extendedKey = SAMPLE_XPUB,
            activeWatcherId = "watcher-abc-123",
            connectionStatus = WatcherConnectionStatus.CONNECTED,
            balance = sampleWalletBalance,
            transactionCount = 2u,
            blockHeight = 850_000u,
            accountType = AccountType.NATIVE_SEGWIT,
            events = persistentListOf(
                "Watcher started: watcher-abc-123",
                "TX update: 2 txs, balance=155000 sats",
            ),
        ),
    )

    val uiStateBroadcast = TrezorUiState(
        network = TrezorNetworkState(selectedNetwork = BitkitCoreNetwork.REGTEST),
        send = TrezorSendState(
            step = SendStep.Signed(
                signedTx = sampleSignedTx,
                broadcastTxid = "c4d5e6f7a8b9c4d5e6f7a8b9c4d5e6f7a8b9c4d5e6f7a8b9c4d5e6f7a8b9c4d5",
            ),
        ),
    )
}
