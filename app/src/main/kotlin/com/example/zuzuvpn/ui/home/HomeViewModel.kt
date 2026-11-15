package com.example.zuzuvpn.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.zuzuvpn.data.DemoLocationRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Drives the home screen with DEMONSTRATION states only.
 *
 * No VPN permission is requested and no tunnel is created here. Phase 2 replaces [onPrimaryAction]
 * with a VpnController that calls GoBackend.setState(...) and derives the phase from real tunnel
 * state plus handshake freshness.
 */
class HomeViewModel : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private var demoJob: Job? = null

    init {
        // The selected location (chosen on the Locations tab) is still SAMPLE data.
        viewModelScope.launch {
            DemoLocationRepository.selected.collect { server ->
                _state.update { it.copy(server = server) }
            }
        }
    }

    fun onPrimaryAction() {
        when (_state.value.phase) {
            ConnectionPhase.Disconnected -> startDemoConnection()
            ConnectionPhase.Connecting, ConnectionPhase.Connected -> stopDemo()
        }
    }

    private fun startDemoConnection() {
        demoJob?.cancel()
        _state.update { it.copy(phase = ConnectionPhase.Connecting, demoElapsedSeconds = 0) }
        demoJob = viewModelScope.launch {
            delay(DEMO_CONNECT_DELAY_MS)
            _state.update { it.copy(phase = ConnectionPhase.Connected) }
            while (isActive) {
                delay(1_000)
                _state.update { it.copy(demoElapsedSeconds = it.demoElapsedSeconds + 1) }
            }
        }
    }

    private fun stopDemo() {
        demoJob?.cancel()
        demoJob = null
        _state.update { it.copy(phase = ConnectionPhase.Disconnected, demoElapsedSeconds = 0) }
    }

    private companion object {
        const val DEMO_CONNECT_DELAY_MS = 2_400L
    }
}
