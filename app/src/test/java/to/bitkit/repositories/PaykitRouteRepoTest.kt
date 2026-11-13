package to.bitkit.repositories

import com.synonym.bitkitcore.PaykitEndpoint
import com.synonym.bitkitcore.PaykitRoute
import com.synonym.bitkitcore.PaykitRouteRequest
import com.synonym.bitkitcore.ServiceRouteLeg
import com.synonym.bitkitcore.paykitRoutePayment
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.MockedStatic
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.services.SparkError
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PaykitRouteRepoTest : BaseUnitTest() {
    private val sparkRepo = mock<SparkRepo>()
    private val sut = PaykitRouteRepo(testDispatcher, sparkRepo)

    private val lightning = PaykitEndpoint(methodId = MethodId.Lnurl.rawValue, value = "lnurl1contact")
    private val onchain = PaykitEndpoint(methodId = MethodId.P2wpkh.rawValue, value = "bcrt1qcontact")
    private lateinit var core: MockedStatic<*>

    @Before
    fun setUp() {
        // Stands in for bitkit-core's router: Lightning pays directly when it covers the amount, before any leg.
        core = mockStatic(Class.forName("com.synonym.bitkitcore.Bitkitcore_androidKt"))
        core.`when`<PaykitRoute> { paykitRoutePayment(any(), any()) }.thenAnswer {
            val request = it.getArgument<PaykitRouteRequest>(0)
            when {
                request.lightningSpendableSat >= request.amountSat -> PaykitRoute.Direct(request.contactEndpoints[0])
                request.serviceLegs.isNotEmpty() -> PaykitRoute.Service(request.serviceLegs[0])
                else -> PaykitRoute.None("No balance can reach an endpoint this contact accepts.")
            }
        }
    }

    @After
    fun tearDown() = core.close()

    @Test
    fun `a contact lightning can pay routes direct without a spark quote`() = test {
        whenever(sparkRepo.withdrawalRouteLeg(any(), any())).thenReturn(Result.success(null))

        val route = sut.route(
            amountSats = 1_000uL,
            endpoints = listOf(lightning, onchain),
            lightningSpendableSats = 5_000uL,
            onchainSpendableSats = 0uL,
            quoteServiceLegs = true,
        ).getOrThrow()

        assertEquals(PaykitRoute.Direct(lightning), route)
        verify(sparkRepo, never()).withdrawalRouteLeg(any(), any())
    }

    @Test
    fun `an amount past lightning's limit takes the quoted spark leg`() = test {
        val leg = ServiceRouteLeg(
            service = SparkRepo.ROUTE_SERVICE,
            endpoint = onchain,
            deliveredSat = 10_000uL,
            debitSat = 10_300uL,
            atomicity = "trusted",
            validUntilUnix = 0uL,
        )
        whenever(sparkRepo.withdrawalRouteLeg(onchain, leg.deliveredSat)).thenReturn(Result.success(leg))

        val route = sut.route(
            amountSats = leg.deliveredSat,
            endpoints = listOf(lightning, onchain),
            lightningSpendableSats = 5_000uL,
            onchainSpendableSats = 0uL,
            quoteServiceLegs = true,
        ).getOrThrow()

        assertEquals(PaykitRoute.Service(leg), route)
    }

    @Test
    fun `a spark leg whose fee the balance can't cover routes as a short balance`() = test {
        whenever(sparkRepo.withdrawalRouteLeg(onchain, 9_000uL))
            .thenReturn(Result.failure(SparkError.WithdrawalOverBalance()))

        val route = sut.route(
            amountSats = 9_000uL,
            endpoints = listOf(onchain),
            // The stand-in router pays any endpoint directly from Lightning, so none is offered.
            lightningSpendableSats = 0uL,
            onchainSpendableSats = 0uL,
            quoteServiceLegs = true,
        )

        assertIs<SparkError.WithdrawalOverBalance>(route.exceptionOrNull())
    }

    @Test
    fun `a spark leg refused while an earlier withdrawal settles routes as settling`() = test {
        whenever(sparkRepo.withdrawalRouteLeg(onchain, 9_000uL))
            .thenReturn(Result.failure(SparkError.WithdrawalPending()))

        val route = sut.route(
            amountSats = 9_000uL,
            endpoints = listOf(onchain),
            lightningSpendableSats = 0uL,
            onchainSpendableSats = 0uL,
            quoteServiceLegs = true,
        )

        assertIs<SparkError.WithdrawalPending>(route.exceptionOrNull())
    }
}
