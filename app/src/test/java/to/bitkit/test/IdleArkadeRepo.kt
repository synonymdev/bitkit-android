package to.bitkit.test

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import to.bitkit.repositories.ArkadeRepo
import to.bitkit.repositories.ArkadeState

/** A Arkade wallet that is not connected, so every flow takes its Blocktank and channel path. */
fun idleArkadeRepo(): ArkadeRepo = mock {
    on { state } doReturn MutableStateFlow(ArkadeState())
    on { nodeEvents } doReturn MutableSharedFlow()
}
