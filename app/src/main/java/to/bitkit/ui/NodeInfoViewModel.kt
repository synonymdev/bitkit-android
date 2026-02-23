package to.bitkit.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.lightningdevkit.ldknode.PeerDetails
import to.bitkit.R
import to.bitkit.env.Peers
import to.bitkit.models.NodePeer
import to.bitkit.models.Toast
import to.bitkit.repositories.BlocktankRepo
import to.bitkit.repositories.LightningRepo
import to.bitkit.ui.shared.toast.ToastEventBus
import javax.inject.Inject

@HiltViewModel
class NodeInfoViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val lightningRepo: LightningRepo,
    private val blocktankRepo: BlocktankRepo,
) : ViewModel() {
    val peers: StateFlow<List<NodePeer>> = combine(
        lightningRepo.lightningState.map { it.peers },
        blocktankRepo.blocktankState.map { it.info?.nodes },
    ) { peers, lspNodes ->
        peers.map { peer ->
            NodePeer(
                peerDetails = peer,
                lspNode = lspNodes?.firstOrNull { it.pubkey == peer.nodeId },
                name = Peers.Known.find(peer)?.name,
            )
        }
    }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun disconnectPeer(peer: PeerDetails) {
        viewModelScope.launch {
            lightningRepo.disconnectPeer(peer)
                .onSuccess {
                    ToastEventBus.send(
                        type = Toast.ToastType.INFO,
                        title = context.getString(R.string.common__success),
                        description = context.getString(R.string.wallet__peer_disconnected),
                    )
                }
                .onFailure {
                    ToastEventBus.send(
                        type = Toast.ToastType.ERROR,
                        title = context.getString(R.string.common__error),
                        description = it.message ?: context.getString(R.string.common__error_body),
                    )
                }
        }
    }
}
