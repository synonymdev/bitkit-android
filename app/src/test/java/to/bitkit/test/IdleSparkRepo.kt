package to.bitkit.test

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import to.bitkit.repositories.SparkRepo
import to.bitkit.repositories.SparkState

/** A Spark wallet that is not connected, so every flow takes its Blocktank and channel path. */
fun idleSparkRepo(): SparkRepo = mock {
    on { state } doReturn MutableStateFlow(SparkState())
    on { nodeEvents } doReturn MutableSharedFlow()
}
