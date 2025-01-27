package to.bitkit.services

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.lightningdevkit.ldknode.Event
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LdkNodeEventBus @Inject constructor() {
    private val _events = MutableSharedFlow<Event>(replay = 0, extraBufferCapacity = 1)
    val events = _events.asSharedFlow()

    suspend fun emit(event: Event) {
        _events.emit(event)
    }
}
