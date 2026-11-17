package to.bitkit.ui.screens.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PayContactsViewModel @Inject constructor() : ViewModel() {
    private val _effects = MutableSharedFlow<PayContactsEffect>(extraBufferCapacity = 1)
    val effects = _effects.asSharedFlow()

    fun continueToProfile() {
        viewModelScope.launch { _effects.emit(PayContactsEffect.Continue) }
    }
}

sealed interface PayContactsEffect {
    data object Continue : PayContactsEffect
}
