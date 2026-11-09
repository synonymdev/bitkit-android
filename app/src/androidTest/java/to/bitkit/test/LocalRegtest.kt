package to.bitkit.test

import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Funds and mines on the local regtest bitcoind from bitkit-docker. The wallet under test reaches
 * that chain through the stack's electrs in an `E2E=true` build (see `Env.electrumUrlForNetwork`).
 */
object LocalRegtest {
    private val rpcUrl: String
        get() = InstrumentationRegistry.getArguments().getString("bitcoinRpcUrl") ?: "http://10.0.2.2:43782"
    private val rpcAuth = Base64.encodeToString("polaruser:polarpass".toByteArray(), Base64.NO_WRAP)

    private suspend fun call(method: String, vararg params: Any): Any = withContext(Dispatchers.IO) {
        val connection = URL(rpcUrl).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 30_000
            connection.readTimeout = 30_000
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Basic $rpcAuth")
            connection.setRequestProperty("Content-Type", "application/json")
            val body = JSONObject()
                .put("jsonrpc", "1.0")
                .put("id", method)
                .put("method", method)
                .put("params", JSONArray(params.toList()))
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            val stream = if (connection.responseCode < 400) connection.inputStream else connection.errorStream
            val response = JSONObject(stream.bufferedReader().use { it.readText() })
            val error = response.optJSONObject("error")
            check(error == null) { "bitcoind $method failed: ${error?.optString("message")}" }
            response.get("result")
        } finally {
            connection.disconnect()
        }
    }

    /** Sends [amountSat] to [address] from the bitcoind wallet and returns the txid. */
    suspend fun deposit(address: String, amountSat: ULong): String =
        call("sendtoaddress", address, amountSat.toDouble() / 100_000_000) as String

    suspend fun mine(count: UInt = 1u) {
        val address = call("getnewaddress") as String
        call("generatetoaddress", count.toInt(), address)
    }
}
