package to.bitkit.repositories

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.utils.io.errors.IOException
import org.junit.Test
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SparkExitChainTest : BaseUnitTest() {
    private val hostA = "https://a.example/api"
    private val hostB = "https://b.example/api"

    private class Route(val url: String, val status: Int, val body: String)

    private val requests = mutableListOf<HttpRequestData>()
    private val bodies = mutableListOf<String>()

    /** Answers each request with the first route whose URL it ends with; an unknown URL fails like a dead host. */
    private fun chain(vararg routes: Route, hosts: List<String> = listOf(hostA)): EsploraSparkExitChain =
        chain(*routes, hosts = SparkExitHosts.of(*hosts.toTypedArray()))

    private fun chain(vararg routes: Route, hosts: SparkExitHosts): EsploraSparkExitChain {
        val pending = routes.toMutableList()
        val engine = MockEngine { request -> answer(request, pending) }
        // Like the app's shared client, which defaults every request to JSON.
        val client = HttpClient(engine) { defaultRequest { contentType(ContentType.Application.Json) } }
        return EsploraSparkExitChain(hosts, client)
    }

    private suspend fun MockRequestHandleScope.answer(
        request: HttpRequestData,
        pending: MutableList<Route>,
    ): HttpResponseData {
        requests += request
        bodies += String(request.body.toByteArray())
        val url = request.url.toString()
        val route = pending.firstOrNull { url.endsWith(it.url) } ?: throw IOException("unreachable '$url'")
        pending.remove(route)
        return respond(route.body, HttpStatusCode.fromValue(route.status))
    }

    @Test
    fun `sends a package as a raw json array of hex`() = test {
        val sut = chain(Route("a.example/api/v1/txs/package", 200, """{"package_msg":"success","tx-results":{}}"""))

        val outcome = sut.broadcast(SparkExitFixtures.tx("n1", cpfp = "03child"))

        assertEquals(SparkExitBroadcastOutcome.Accepted, outcome)
        assertEquals(HttpMethod.Post, requests.single().method)
        assertEquals("""["02n1","03child"]""", bodies.single())
        assertEquals(ContentType.Application.Json, requests.single().body.contentType?.withoutParameters())
    }

    @Test
    fun `sends a step without cpfp as plain hex`() = test {
        val sut = chain(Route("a.example/api/tx", 200, "s1"))

        val outcome = sut.broadcast(SparkExitFixtures.tx("s1", kind = breez_sdk_spark.RecoveryTxKind.SWEEP))

        assertEquals(SparkExitBroadcastOutcome.Accepted, outcome)
        assertEquals("02s1", bodies.single())
        assertEquals(ContentType.Text.Plain, requests.single().body.contentType?.withoutParameters())
        assertEquals(null, requests.single().headers["Content-Type"])
    }

    private val bareRefusal = """{"error":"sendrawtransaction RPC error: {\"code\":-1}"}"""

    @Test
    fun `a lone transaction refused with a bare rpc error is sent again as a one-item package`() = test {
        val sut = chain(
            Route("a.example/api/tx", 400, bareRefusal),
            Route("a.example/api/v1/txs/package", 200, """{"package_msg":"success","tx-results":{}}"""),
        )

        assertEquals(SparkExitBroadcastOutcome.Accepted, sut.broadcastHex("02sweep"))
        assertEquals(listOf("02sweep", """["02sweep"]"""), bodies)
    }

    @Test
    fun `a one-item package's own error is classified`() = test {
        val sut = chain(
            Route("a.example/api/tx", 400, bareRefusal),
            Route(
                "a.example/api/v1/txs/package",
                200,
                """{"package_msg":"transaction failed","tx-results":{"w":{"txid":"x","error":"non-BIP68-final"}}}""",
            ),
        )

        val outcome = sut.broadcast(SparkExitFixtures.tx("s1", kind = breez_sdk_spark.RecoveryTxKind.SWEEP))

        assertIs<SparkExitBroadcastOutcome.NotMature>(outcome)
    }

    @Test
    fun `a lone transaction a host takes or refuses with a reason never goes out as a package`() = test {
        assertEquals(
            SparkExitBroadcastOutcome.Accepted,
            chain(Route("a.example/api/tx", 200, "s1")).broadcastHex("02s1")
        )
        assertEquals(1, requests.size)
        val refused = chain(
            Route("a.example/api/tx", 400, "sendrawtransaction RPC error: min relay fee not met, 1 < 2")
        )
        assertIs<SparkExitBroadcastOutcome.Rejected>(refused.broadcastHex("02s1"))
        assertEquals(2, requests.size)
        assertFalse(SparkExitBroadcastClassifier.isUnplaceable("min relay fee not met"))
        assertTrue(SparkExitBroadcastClassifier.isUnplaceable(bareRefusal))
    }

    @Test
    fun `fee estimates fall back to the next target, then to the min relay rate, and never fail`() = test {
        val empty = chain(
            Route("a.example/api/fee-estimates", 200, "{}"),
            Route("a.example/api/fee-estimates", 200, "{}")
        )
        assertEquals(1uL, empty.nextBlockFeeRate())
        assertEquals(SparkExitNetworkFees(fastest = 1uL, halfHour = 1uL), empty.networkFees())
        assertEquals(1uL, chain(Route("a.example/api/fee-estimates", 200, "{}")).economyFeeRate())

        val sparse = mapOf(6 to 3.2, 144 to 1.1)
        assertEquals(4uL, SparkExitFeeEstimates.at(sparse, 1))
        assertEquals(2uL, SparkExitFeeEstimates.at(sparse, 1_008))
        assertEquals(2uL, SparkExitFeeEstimates.farthest(sparse))
        assertEquals(sparse, SparkExitFeeEstimates.parse("""{"6":3.2,"144":1.1,"x":2}"""))
    }

    @Test
    fun `falls back to the unversioned package path then the next host`() = test {
        val sut = chain(
            Route("a.example/api/v1/txs/package", 404, "Not Found"),
            Route("a.example/api/txs/package", 404, "Not Found"),
            Route("b.example/api/v1/txs/package", 200, """{"package_msg":"success"}"""),
            hosts = listOf(hostA, hostB),
        )

        val outcome = sut.broadcast(SparkExitFixtures.tx("n1", cpfp = "03c"))

        assertEquals(SparkExitBroadcastOutcome.Accepted, outcome)
        assertEquals(listOf("a.example", "a.example", "b.example"), requests.map { it.url.host })
    }

    @Test
    fun `tries the next host after a rejection or an unavailable host`() = test {
        val rejected = chain(
            Route(
                "a.example/api/tx",
                400,
                """sendrawtransaction RPC error: {"code":-26,"message":"min relay fee not met"}"""
            ),
            Route("b.example/api/tx", 200, "s1"),
            hosts = listOf(hostA, hostB),
        )
        assertEquals(SparkExitBroadcastOutcome.Accepted, rejected.broadcast(SparkExitFixtures.tx("s1")))

        val limited = chain(
            Route("a.example/api/tx", 429, "Too Many Requests"),
            Route("b.example/api/tx", 200, "s1"),
            hosts = listOf(hostA, hostB),
        )
        assertEquals(SparkExitBroadcastOutcome.Accepted, limited.broadcast(SparkExitFixtures.tx("s1")))
    }

    @Test
    fun `a node refusal wins over an unavailable or unreachable host`() = test {
        val sut = chain(
            Route(
                "a.example/api/tx",
                400,
                """sendrawtransaction RPC error: {"code":-26,"message":"min relay fee not met"}"""
            ),
            Route("b.example/api/tx", 503, ""),
            hosts = listOf(hostA, hostB, "https://c.example/api"),
        )

        assertIs<SparkExitBroadcastOutcome.Rejected>(sut.broadcast(SparkExitFixtures.tx("s1")))
    }

    @Test
    fun `no reachable host is retried later`() = test {
        val outcome = chain(hosts = listOf(hostA)).broadcast(SparkExitFixtures.tx("s1"))

        assertEquals(SparkExitBroadcastOutcome.RetryLater("no chain host reachable"), outcome)
    }

    @Test
    fun `reads the tip, funding utxos and a transaction status`() = test {
        val sut = chain(
            Route("a.example/api/blocks/tip/height", 200, "3681536\n"),
            Route(
                "a.example/api/address/bcrt1qf/utxo",
                200,
                """[{"txid":"t","vout":1,"value":5000,""" +
                    """"status":{"confirmed":true,"block_height":7,"block_hash":"h"}}]""",
            ),
            Route("a.example/api/tx/t/status", 200, """{"confirmed":false}"""),
        )

        assertEquals(3_681_536u, sut.tipHeight())
        assertEquals(listOf(EsploraUtxo("t", 1u, 5_000uL, EsploraTxStatus(true, 7u))), sut.utxos("bcrt1qf"))
        assertEquals(EsploraTxStatus(false, null), sut.txStatus("t"))
    }

    @Test
    fun `a refusal keeps only a short reason, never a host's page`() {
        val classifier = SparkExitBroadcastClassifier
        assertEquals(
            SparkExitBroadcastOutcome.Rejected("min relay fee not met, 0 < 17"),
            classifier.single(
                400,
                """{"error":"sendrawtransaction RPC error: """ +
                    """{\"code\":-26,\"message\":\"min relay fee not met, 0 < 17\"}"}"""
            ),
        )
        val page = "<html><body><h1>502 Bad Gateway</h1>" + "x".repeat(500) + "</body></html>"
        val reason = (classifier.single(400, page) as SparkExitBroadcastOutcome.Rejected).reason
        assertFalse("<" in reason)
        assertTrue(reason.length <= 200)
    }

    @Test
    fun `classifies node answers`() {
        val failedPackage = """{"package_msg":"transaction failed","tx-results":"""
        val classifier = SparkExitBroadcastClassifier
        assertEquals(
            SparkExitBroadcastOutcome.Accepted,
            classifier.single(400, """sendrawtransaction RPC error: {"code":-27}"""),
        )
        assertEquals(
            SparkExitBroadcastOutcome.InputsMissing("RPC error -25"),
            classifier.single(400, """sendrawtransaction RPC error: {"code":-25}"""),
        )
        assertEquals(SparkExitBroadcastOutcome.RetryLater("HTTP 503"), classifier.single(503, ""))
        assertIs<SparkExitBroadcastOutcome.Rejected>(
            classifier.single(400, """{"code":-26,"message":"min relay fee not met"}""")
        )

        val missing = failedPackage + """{"w1":{"txid":"a","error":"bad-txns-inputs-missingorspent"},""" +
            """"w2":{"txid":"b","error":"bad-txns-inputs-missingorspent"}},"replaced-transactions":[]}"""
        assertIs<SparkExitBroadcastOutcome.InputsMissing>(classifier.pkg(200, missing))
        val known = failedPackage + """{"w1":{"txid":"a","error":"txn-already-in-mempool"},""" +
            """"w2":{"txid":"b"}}}"""
        assertEquals(SparkExitBroadcastOutcome.Accepted, classifier.pkg(200, known))
        val nonFinal = failedPackage + """{"w1":{"txid":"a","error":"non-BIP68-final"}}}"""
        assertIs<SparkExitBroadcastOutcome.NotMature>(classifier.pkg(200, nonFinal))
        // A child of an immature parent can fail with any error; the parent's timelock decides.
        val nonFinalParent = failedPackage + """{"w1":{"txid":"a","error":"non-BIP68-final"},""" +
            """"w2":{"txid":"b","error":"min relay fee not met"}}}"""
        assertIs<SparkExitBroadcastOutcome.NotMature>(classifier.pkg(200, nonFinalParent))
        val escaped = """{"error":"submitpackage RPC error: {\"code\":-25}"}"""
        assertEquals(SparkExitBroadcastOutcome.InputsMissing("RPC error -25"), classifier.pkg(400, escaped))
        // Missing inputs on the CPFP child alone say nothing about the step.
        val childMissing = failedPackage + """{"w1":{"txid":"parent"},""" +
            """"w2":{"txid":"child","error":"bad-txns-inputs-missingorspent"}}}"""
        assertIs<SparkExitBroadcastOutcome.RetryLater>(classifier.pkg(200, childMissing, parentTxid = "parent"))
        val parentMissing = failedPackage + """{"w1":{"txid":"parent","error":"bad-txns-inputs-missingorspent"}}}"""
        assertIs<SparkExitBroadcastOutcome.InputsMissing>(classifier.pkg(200, parentMissing, parentTxid = "parent"))
        // The step's own missing inputs decide over its child's error, in either order.
        val parentFirst = failedPackage + """{"w1":{"txid":"parent","error":"bad-txns-inputs-missingorspent"},""" +
            """"w2":{"txid":"child","error":"package-not-validated"}}}"""
        assertIs<SparkExitBroadcastOutcome.InputsMissing>(classifier.pkg(200, parentFirst, parentTxid = "parent"))
        val childFirst = failedPackage + """{"w2":{"txid":"child","error":"package-not-validated"},""" +
            """"w1":{"txid":"parent","error":"bad-txns-inputs-missingorspent"}}}"""
        assertIs<SparkExitBroadcastOutcome.InputsMissing>(classifier.pkg(200, childFirst, parentTxid = "parent"))
        val lowFee = failedPackage + """{"w1":{"txid":"a","error":"min relay fee not met"}}}"""
        assertIs<SparkExitBroadcastOutcome.Rejected>(classifier.pkg(200, lowFee))
    }

    @Test
    fun `only a step that paid no fee at all is unpaid`() {
        val classifier = SparkExitBroadcastClassifier
        // The qa-03 shared-ancestor race: the node pays nothing, and its child's chained change is gone.
        val lostChain = """{"package_msg":"transaction failed","tx-results":""" +
            """{"w1":{"txid":"parent","error":"min relay fee not met, 0 < 17"},""" +
            """"w2":{"txid":"child","error":"bad-txns-inputs-missingorspent"}}}"""
        assertTrue(classifier.isUnpaid(classifier.pkg(200, lostChain, parentTxid = "parent")))
        assertFalse(classifier.isUnpaid(SparkExitBroadcastOutcome.Rejected("min relay fee not met, 100 < 110")))
        assertFalse(classifier.isUnpaid(SparkExitBroadcastOutcome.RetryLater("min relay fee not met, 0 < 17")))
    }

    @Test
    fun `only a nonzero fee under a node's floor reads as a fee too low`() {
        val classifier = SparkExitBroadcastClassifier
        assertTrue(classifier.isFeeTooLow(SparkExitBroadcastOutcome.Rejected("min relay fee not met, 5 < 17")))
        assertTrue(classifier.isFeeTooLow(SparkExitBroadcastOutcome.Rejected("mempool min fee not met, 300 < 400")))
        // A dead parent or child, and the lost replacement race, stay on the broken-chain path.
        assertFalse(classifier.isFeeTooLow(SparkExitBroadcastOutcome.Rejected("min relay fee not met, 0 < 17")))
        assertFalse(
            classifier.isFeeTooLow(SparkExitBroadcastOutcome.Rejected("insufficient fee, rejecting replacement")),
        )
        assertFalse(classifier.isFeeTooLow(SparkExitBroadcastOutcome.RetryLater("min relay fee not met, 5 < 17")))
    }

    @Test
    fun `a transaction is unknown only when every host says so`() = test {
        val unknown = Route("a.example/api/tx/t/hex", 404, "Transaction not found")
        assertFalse(chain(unknown).isTxKnown("t"))

        val known = Route("b.example/api/tx/t/hex", 200, "02aa")
        assertTrue(chain(unknown, known, hosts = listOf(hostA, hostB)).isTxKnown("t"))

        val down = Route("b.example/api/tx/t/hex", 503, "")
        assertFailsWith<SparkExitChainError> { chain(unknown, down, hosts = listOf(hostA, hostB)).isTxKnown("t") }
        assertFailsWith<Exception> { chain(hosts = listOf(hostA)).isTxKnown("t") }
    }

    @Test
    fun `a single read host decides whether a transaction is known`() = test {
        assertFalse(chain(Route("a.example/api/tx/t/hex", 404, "Transaction not found")).isTxKnown("t"))
        assertFailsWith<SparkExitChainError> { chain(Route("a.example/api/tx/t/hex", 503, "")).isTxKnown("t") }
        assertFailsWith<IOException> { chain().isTxKnown("t") }
    }

    private val ownHosts = SparkExitHosts(
        reads = listOf(hostA),
        broadcasts = listOf(
            SparkExitBroadcastHost(hostA, packagePaths = listOf("/txs/package")),
            SparkExitBroadcastHost(hostB),
        ),
    )

    @Test
    fun `reads stay on the read hosts`() = test {
        val sut = chain(
            Route("a.example/api/blocks/tip/height", 503, ""),
            Route("b.example/api/blocks/tip/height", 200, "1"),
            Route("b.example/api/tx/t/hex", 200, "02aa"),
            hosts = ownHosts,
        )

        assertFailsWith<SparkExitChainError> { sut.tipHeight() }
        assertFailsWith<IOException> { sut.isTxKnown("t") }
        assertEquals(listOf("a.example", "a.example"), requests.map { it.url.host })
    }

    @Test
    fun `a host with only the unversioned package path is asked once`() = test {
        val sut = chain(
            Route("a.example/api/txs/package", 200, """{"package_msg":"success"}"""),
            hosts = ownHosts,
        )

        assertEquals(SparkExitBroadcastOutcome.Accepted, sut.broadcast(SparkExitFixtures.tx("n1", cpfp = "03c")))
        assertEquals("/api/txs/package", requests.single().url.encodedPath)
    }

    @Test
    fun `a broadcast falls back from the first host`() = test {
        val sut = chain(
            Route("a.example/api/txs/package", 503, ""),
            Route("b.example/api/v1/txs/package", 200, """{"package_msg":"success"}"""),
            Route("a.example/api/tx", 503, ""),
            Route("b.example/api/tx", 200, "s1"),
            hosts = ownHosts,
        )

        assertEquals(SparkExitBroadcastOutcome.Accepted, sut.broadcast(SparkExitFixtures.tx("n1", cpfp = "03c")))
        assertEquals(SparkExitBroadcastOutcome.Accepted, sut.broadcastHex("02s1"))
        val paths = requests.map { "${it.url.host}${it.url.encodedPath}" }
        assertEquals(
            listOf("a.example/api/txs/package", "b.example/api/v1/txs/package", "a.example/api/tx", "b.example/api/tx"),
            paths,
        )
    }

    @Test
    fun `mainnet reads bitkit electrs and broadcasts there first`() {
        val hosts = checkNotNull(EsploraSparkExitChain.defaultHosts("mainnet"))

        assertEquals(listOf("https://bitkit.to/electrs"), hosts.reads)
        assertEquals(
            listOf(
                SparkExitBroadcastHost("https://bitkit.to/electrs", listOf("/txs/package")),
                SparkExitBroadcastHost("https://mempool.space/api", listOf("/v1/txs/package", "/txs/package")),
                SparkExitBroadcastHost("https://blockstream.info/api", listOf("/v1/txs/package", "/txs/package")),
            ),
            hosts.broadcasts,
        )
    }

    @Test
    fun `regtest reads and broadcasts on the sdk host`() {
        val regtest = "https://regtest-mempool.us-west-2.sparkinfra.net/api"

        assertEquals(SparkExitHosts.of(regtest), EsploraSparkExitChain.defaultHosts("regtest"))
        assertEquals(null, EsploraSparkExitChain.defaultHosts("signet"))
    }
}
