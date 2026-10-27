package to.bitkit.repositories

import breez_sdk_spark.BitcoinNetwork
import breez_sdk_spark.Conversion
import breez_sdk_spark.ConversionAsset
import breez_sdk_spark.ConversionChain
import breez_sdk_spark.ConversionDetails
import breez_sdk_spark.ConversionProvider
import breez_sdk_spark.ConversionSide
import breez_sdk_spark.ConversionStatus
import breez_sdk_spark.GetInfoResponse
import breez_sdk_spark.ImportUnilateralExitStateResponse
import breez_sdk_spark.InputType
import breez_sdk_spark.Payment
import breez_sdk_spark.PaymentDetails
import breez_sdk_spark.PaymentMethod
import breez_sdk_spark.PaymentStatus
import breez_sdk_spark.PaymentType
import breez_sdk_spark.SdkEvent
import breez_sdk_spark.SparkHtlcDetails
import breez_sdk_spark.SparkHtlcStatus
import breez_sdk_spark.SparkInvoiceDetails
import breez_sdk_spark.TokenBalance
import breez_sdk_spark.TokenMetadata
import breez_sdk_spark.TokenTransactionType
import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.OnchainActivity
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.env.Env
import to.bitkit.ext.create
import to.bitkit.models.SparkExitBackupV1
import to.bitkit.services.SparkService
import to.bitkit.test.BaseUnitTest
import java.io.File
import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.synonym.bitkitcore.PaymentType as ActivityPaymentType

class SparkRepoTest : BaseUnitTest() {
    private val sparkService = mock<SparkService>()
    private val activityRepo = mock<ActivityRepo>()
    private val sparkEvents = MutableSharedFlow<SdkEvent>()

    private fun createSut() = SparkRepo(
        bgDispatcher = testDispatcher,
        sparkService = sparkService,
        activityRepo = { activityRepo },
        settingsStore = mock(),
    )

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Before
    fun setUp() {
        Env.initAppStoragePath(tempFolder.root.absolutePath)
        whenever { sparkService.getInfo() }.thenReturn(GetInfoResponse("pubkey", 0uL, emptyMap(), 0uL))
        whenever { sparkService.listUnclaimedDeposits() }.thenReturn(emptyList())
        whenever { sparkService.listPayments() }.thenReturn(emptyList())
        whenever(sparkService.events).thenReturn(sparkEvents)
        whenever { activityRepo.getActivity(any(), any()) }.thenReturn(Result.success(null))
        whenever { activityRepo.upsertActivity(any()) }.thenReturn(Result.success(Unit))
        whenever(sparkService.isSupported).thenReturn(true)
        whenever(sparkService.networkName).thenReturn("regtest")
    }

    @Test
    fun `imports a restored exit backup once Spark connects`() = test {
        whenever { sparkService.importExitState(any()) }.thenReturn(ImportUnilateralExitStateResponse(2u, 0u, 0u, 0u))
        val sut = createSut()

        sut.restoreExitBackup(SparkExitBackupV1(createdAt = 1, network = "regtest", exitState = "state"), 0)
            .getOrThrow()
        verify(sparkService, never()).importExitState(any())

        whenever(sparkService.isConnected).thenReturn(true)
        sut.start(0).getOrThrow()

        verify(sparkService).importExitState("state")
        assertFalse(File(Env.sparkExitRestorePath(0)).exists())
    }

    @Test
    fun `records a send's fee once a sync reports it`() = test {
        whenever(sparkService.isConnected).thenReturn(true)
        val sut = createSut()
        sut.start(0).getOrThrow()

        sparkEvents.emit(SdkEvent.PaymentSucceeded(sentPayment(fees = 0)))
        whenever { sparkService.listPayments() }.thenReturn(listOf(sentPayment(fees = 2)))
        sparkEvents.emit(SdkEvent.Synced)

        val activities = argumentCaptor<Activity>()
        verify(activityRepo, times(2)).upsertActivity(activities.capture())
        assertEquals(listOf(0uL, 2uL), activities.allValues.map { (it as Activity.Lightning).v1.fee })
    }

    @Test
    fun `lists a withdrawal with its fee and marks its arrival in Savings as a transfer`() = test {
        val arrival = OnchainActivity.create(
            id = "arrival",
            txType = ActivityPaymentType.RECEIVED,
            txId = "withdrawal",
            value = 4_917uL,
            fee = 0uL,
            address = "bc1q",
            timestamp = 1uL,
        )
        whenever { activityRepo.getOnchainActivityByTxId(eq("withdrawal"), any()) }.thenReturn(arrival)
        whenever { activityRepo.updateActivity(any(), any(), any()) }.thenReturn(Result.success(Unit))
        val withdrawal = sentPayment(fees = 1_470).copy(
            method = PaymentMethod.WITHDRAW,
            details = PaymentDetails.Withdraw("withdrawal"),
        )
        whenever { sparkService.listPayments() }.thenReturn(listOf(withdrawal))
        whenever(sparkService.isConnected).thenReturn(true)

        val sut = createSut()
        sut.start(0).getOrThrow()

        val updated = argumentCaptor<Activity>()
        verify(activityRepo).updateActivity(eq("arrival"), updated.capture(), any())
        assertTrue((updated.firstValue as Activity.Onchain).v1.isTransfer)
        assertEquals(listOf(SparkWithdrawal("withdrawal", 128uL, 1_470uL)), sut.state.value.withdrawals)
    }

    @Test
    fun `skips an exit backup from another network`() = test {
        val sut = createSut()

        sut.restoreExitBackup(SparkExitBackupV1(createdAt = 1, network = "mainnet", exitState = "state"), 0)
            .getOrThrow()

        assertFalse(File(Env.sparkExitRestorePath(0)).exists())
    }

    @Test
    fun `lists a broadcast deposit as pending until Spark lists it`() = test {
        val sut = createSut()

        sut.onDepositBroadcast("txid", 2_500uL)

        val deposit = sut.state.value.deposits.single()
        assertEquals("txid", deposit.txid)
        assertEquals(false, deposit.isClaimed)
        assertEquals(2_500uL, sut.state.value.pendingDepositSats)
    }

    private fun sentPayment(fees: Int) = Payment(
        id = "send",
        paymentType = PaymentType.SEND,
        status = PaymentStatus.COMPLETED,
        amount = BigInteger.valueOf(128),
        fees = BigInteger.valueOf(fees.toLong()),
        timestamp = 1uL,
        method = PaymentMethod.LIGHTNING,
        details = PaymentDetails.Lightning(
            description = null,
            invoice = "lnbc1280n1send",
            destinationPubkey = "pubkey",
            htlcDetails = SparkHtlcDetails("hash", "preimage", 1uL, SparkHtlcStatus.PREIMAGE_SHARED),
            lnurlPayInfo = null,
            lnurlWithdrawInfo = null,
            lnurlReceiveMetadata = null,
            conversionInfo = null,
        ),
        conversionDetails = null,
    )

    @Test
    fun `recognizes spark addresses and invoices before parsing`() {
        assertTrue(SparkRepo.isSparkInput("sparkrt1pgssyuuuhnrrdjswal5c3s3rafw9w3y5dd4cuy3whr3f0zhs8hu"))
        assertTrue(SparkRepo.isSparkInput(" SPARK1abc"))
        assertFalse(SparkRepo.isSparkInput("lnbcrt1abc"))
        assertFalse(SparkRepo.isSparkInput("0x2d8a9f6c4c37a2f8b7f3a3b1d6c3c8f8d1a2b3c4"))
    }

    @Test
    fun `scales token units to dollar units and back`() {
        assertEquals(1_500_000uL, SparkRepo.toUsdUnits(BigInteger.valueOf(1_500_000), 6))
        assertEquals(1_500_000uL, SparkRepo.toUsdUnits(BigInteger.valueOf(150_000_000), 8))
        assertEquals(BigInteger.valueOf(150_000_000), SparkRepo.fromUsdUnits(1_500_000uL, 8))
        assertEquals(BigInteger.valueOf(1_500_000), SparkRepo.fromUsdUnits(1_500_000uL, 6))
    }

    @Test
    fun `loads the dollar token balance and transfers`() = test {
        val tokenId = requireNotNull(Env.sparkUsdTokenId)
        val metadata = usdMetadata(tokenId)
        whenever { sparkService.getInfo() }.thenReturn(
            GetInfoResponse(
                "pubkey",
                0uL,
                mapOf(tokenId to TokenBalance(BigInteger.valueOf(7_000_000), metadata)),
                0uL
            ),
        )
        whenever { sparkService.tokenMetadata(tokenId) }.thenReturn(metadata)
        whenever { sparkService.listPayments() }.thenReturn(
            listOf(
                tokenPayment("mint", metadata, TokenTransactionType.MINT, 1uL),
                tokenPayment("in", metadata, TokenTransactionType.TRANSFER, 2uL),
            ),
        )
        whenever(sparkService.isConnected).thenReturn(true)
        val sut = createSut()

        sut.start(0).getOrThrow()

        val usd = requireNotNull(sut.state.value.usd)
        assertEquals("USDT", usd.ticker)
        assertEquals(7_000_000uL, usd.balance)
        val transfer = usd.transfers.single()
        assertEquals("in", transfer.id)
        assertEquals(SparkUsdTransfer.Kind.RECEIVED, transfer.kind)
        assertTrue(transfer.isIncoming)
        assertEquals(2_000_000uL, transfer.amount)
    }

    @Test
    fun `lists a conversion to dollars with its bitcoin side and fee`() = test {
        val tokenId = requireNotNull(Env.sparkUsdTokenId)
        val metadata = usdMetadata(tokenId)
        whenever { sparkService.tokenMetadata(tokenId) }.thenReturn(metadata)
        val conversion = Conversion(
            provider = ConversionProvider.AMM,
            status = ConversionStatus.COMPLETED,
            from = ConversionSide(
                chain = ConversionChain.Spark,
                asset = ConversionAsset("BTC", "btc", 8u),
                amount = BigInteger.valueOf(2_504),
                fee = BigInteger.valueOf(801),
            ),
            to = ConversionSide(
                chain = ConversionChain.Spark,
                asset = ConversionAsset("USDB", tokenId, 6u),
                amount = BigInteger.valueOf(2_000_000),
                fee = BigInteger.ZERO,
            ),
            amountAdjustment = null,
        )
        whenever { sparkService.listPayments() }.thenReturn(
            listOf(
                tokenPayment("convert", metadata, TokenTransactionType.TRANSFER, 2uL)
                    .copy(conversionDetails = ConversionDetails(ConversionStatus.COMPLETED, listOf(conversion))),
            ),
        )
        whenever(sparkService.isConnected).thenReturn(true)
        val sut = createSut()

        sut.start(0).getOrThrow()

        val transfer = requireNotNull(sut.state.value.usd).transfers.single()
        assertEquals(SparkUsdTransfer.Kind.TO_USD, transfer.kind)
        assertEquals(2_000_000uL, transfer.amount)
        assertEquals(2_504uL, transfer.sats)
        assertEquals(801uL, transfer.fee)
    }

    @Test
    fun `rejects a spark invoice for another token`() = test {
        whenever { sparkService.parse("sparkrt1invoice") }.thenReturn(
            InputType.SparkInvoice(invoice(tokenId = "btknrt1other", amount = null)),
        )
        val sut = createSut()

        val result = sut.parseUsdRecipient("sparkrt1invoice")

        assertIs<SparkUsdError.UnsupportedRecipient>(result.exceptionOrNull())
    }

    @Test
    fun `reads the amount a dollar invoice fixes`() = test {
        val tokenId = requireNotNull(Env.sparkUsdTokenId)
        whenever { sparkService.tokenMetadata(tokenId) }.thenReturn(usdMetadata(tokenId))
        whenever { sparkService.parse("sparkrt1invoice") }.thenReturn(
            InputType.SparkInvoice(invoice(tokenId = tokenId, amount = BigInteger.valueOf(2_500_000))),
        )
        val sut = createSut()

        val recipient = sut.parseUsdRecipient("sparkrt1invoice").getOrThrow()

        assertEquals("sparkrt1invoice", recipient.request)
        assertEquals(2_500_000uL, recipient.amount)
        assertNull(sut.parseUsdRecipient("sparkrt1invoice").exceptionOrNull())
    }

    private fun usdMetadata(tokenId: String) = TokenMetadata(
        identifier = tokenId,
        issuerPublicKey = "issuer",
        name = "Test Tether USD",
        ticker = "USDT",
        decimals = 6u,
        maxSupply = BigInteger.valueOf(Long.MAX_VALUE),
        isFreezable = true,
    )

    private fun tokenPayment(id: String, metadata: TokenMetadata, txType: TokenTransactionType, dollars: ULong) =
        Payment(
            id = id,
            paymentType = PaymentType.RECEIVE,
            status = PaymentStatus.COMPLETED,
            amount = BigInteger.valueOf((dollars * 1_000_000uL).toLong()),
            fees = BigInteger.ZERO,
            timestamp = dollars,
            method = PaymentMethod.TOKEN,
            details = PaymentDetails.Token(metadata, "hash-$id", txType, null, null),
            conversionDetails = null,
        )

    private fun invoice(tokenId: String, amount: BigInteger?) = SparkInvoiceDetails(
        invoice = "sparkrt1invoice",
        identityPublicKey = "pubkey",
        network = BitcoinNetwork.REGTEST,
        amount = amount,
        tokenIdentifier = tokenId,
        expiryTime = null,
        description = null,
        senderPublicKey = null,
    )
}
