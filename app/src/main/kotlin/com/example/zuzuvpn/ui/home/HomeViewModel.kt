package com.example.zuzuvpn.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.zuzuvpn.ZuzuApplication
import com.example.zuzuvpn.data.servers.ServerCatalog
import com.example.zuzuvpn.vpn.ConnectionRequest
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One-off requests the screen must handle with Activity APIs or navigation. */
sealed interface HomeEvent {
    /** Ask for notification permission (Android 13+) if needed, then VPN permission, then call [HomeViewModel.onPermissionResult]. */
    data object RequestVpnPermission : HomeEvent
    data object OpenLocations : HomeEvent
}

/**
 * Drives the home screen: the selected location, the real [com.example.zuzuvpn.vpn.VpnController]
 * state, and the connect flow (location -> authorized configuration -> permission -> VPN service).
 */
class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as ZuzuApplication).container
    private val controller = container.vpnController
    private val servers = container.servers
    private val selection = container.serverSelection

    private val notice = MutableStateFlow<HomeNotice?>(null)
    private val events = Channel<HomeEvent>(Channel.BUFFERED)

    /** Resolved when the user pressed Connect; used once permissions are granted. Holds the private config in memory only. */
    private var pending: ConnectionRequest? = null

    val uiEvents: Flow<HomeEvent> = events.receiveAsFlow()

    val state: StateFlow<HomeUiState> = combine(
        controller.state,
        servers.directory,
        selection.selectedId,
        notice,
    ) { vpn, directory, selectedId, notice ->
        HomeUiState(vpn = vpn, directory = directory, location = ServerCatalog.selected(directory, selectedId), notice = notice)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState(vpn = controller.state.value))

    init {
        viewModelScope.launch { servers.refresh() }
    }

    fun onPrimaryAction() {
        val current = state.value
        when (val action = ConnectAction.decide(controller.state.value, current.directory, current.location)) {
            ConnectAction.Disconnect -> controller.disconnect()
            ConnectAction.Ignore -> Unit
            ConnectAction.LocationsUnavailable -> showNotice(HomeNotice.LocationsUnavailable)
            ConnectAction.DirectoryUnavailable -> {
                showNotice(HomeNotice.DirectoryUnreachable)
                viewModelScope.launch { servers.refresh() }
            }
            ConnectAction.NoLocation -> showNotice(HomeNotice.NoLocation)
            ConnectAction.ServerUnavailable -> showNotice(HomeNotice.LocationNotReady)
            is ConnectAction.Connect -> viewModelScope.launch {
                val server = current.location
                val config = servers.connectionConfig(action.serverId)
                if (server == null || config == null) {
                    showNotice(HomeNotice.LocationNotReady)
                } else {
                    showNotice(null)
                    pending = ConnectionRequest(server, config)
                    events.send(HomeEvent.RequestVpnPermission)
                }
            }
        }
    }

    fun onPermissionResult(granted: Boolean) {
        val request = pending
        pending = null
        if (!granted) {
            showNotice(HomeNotice.PermissionDenied)
        } else if (request != null) {
            controller.connect(request.server, request.config)
        }
    }

    fun onChangeLocation() {
        events.trySend(HomeEvent.OpenLocations)
    }

    private fun showNotice(value: HomeNotice?) {
        controller.acknowledgeFailure()
        notice.value = value
    }
}
