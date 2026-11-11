package to.bitkit.services

import org.junit.Test
import org.lightningdevkit.ldknode.LogRecord
import to.bitkit.utils.LdkLogWriter
import to.bitkit.utils.LogSaver
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.lightningdevkit.ldknode.LogLevel as LdkLogLevel

class NodeRuntimeMonitorTest {
    private val sut = NodeRuntimeMonitor()

    @Test
    fun `flags each detaching message ldk-node logs after a timed-out stop`() {
        listOf(
            "Detaching background tasks after cancellation timed out.",
            "Detaching cancellable background tasks after cancellation timed out.",
            "Detaching event processor after cancellation timed out: deadline has elapsed",
        ).forEach {
            val monitor = NodeRuntimeMonitor()

            monitor.onLog(record(it))

            assertTrue(monitor.hasDetachedWork, it)
        }
    }

    @Test
    fun `ignores a stop that timed out but did not detach`() {
        sut.onLog(record("Stopping background tasks timed out."))
        sut.onLog(record("Stopping event handling timed out: deadline has elapsed"))

        assertFalse(sut.hasDetachedWork)
    }

    @Test
    fun `ignores detaching messages from other modules or levels`() {
        sut.onLog(record("Detaching background tasks after cancellation timed out.", module = "ldk_node::chain"))
        sut.onLog(record("Detaching background tasks after cancellation timed out.", level = LdkLogLevel.WARN))

        assertFalse(sut.hasDetachedWork)
    }

    @Test
    fun `log writer forwards records to the monitor`() {
        val writer = LdkLogWriter(
            maxLogLevel = LdkLogLevel.ERROR,
            saver = object : LogSaver {
                override fun save(message: String) = Unit
            },
            onRecord = sut::onLog,
        )

        writer.log(record("Detaching background tasks after cancellation timed out."))

        assertTrue(sut.hasDetachedWork)
    }

    private fun record(
        args: String,
        module: String = "ldk_node::runtime",
        level: LdkLogLevel = LdkLogLevel.ERROR,
    ) = LogRecord(level, args, module, 1u)
}
