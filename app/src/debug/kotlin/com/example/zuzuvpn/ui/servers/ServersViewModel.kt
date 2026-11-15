package com.example.zuzuvpn.ui.servers

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.zuzuvpn.ZuzuApplication
import com.example.zuzuvpn.data.ServerStoreState
import com.example.zuzuvpn.data.ServerListCodec
import com.example.zuzuvpn.data.servers.ConnectionConfig
import com.example.zuzuvpn.data.servers.ServerEntry
import com.zuzuvpn.shadowsocks.ShadowsocksProfile
import com.example.zuzuvpn.vpn.VpnState
import com.zuzuvpn.shadowsocks.ProfileProblem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EditorUiState(
    /** Null when adding a new server. */
    val editingId: String? = null,
    val form: ServerForm = ServerForm(),
    /** Shown after the first save attempt so the user is not greeted with errors. */
    val errors: Map<FormField, ProfileProblem> = emptyMap(),
    val keyVisible: Boolean = false,
    val busy: Boolean = false,
    val loadFailed: Boolean = false,
    /** Saving or deleting failed (storage/Keystore error). Nothing was changed. */
    val saveFailed: Boolean = false,
)

data class ImportUiState(
    val text: String = "",
    val outcome: ServerImport.Outcome? = null,
    /** ss:// links do not say whether the server relays UDP, so it starts off. */
    val udpEnabled: Boolean = false,
    val busy: Boolean = false,
    val saveFailed: Boolean = false,
) {
    override fun toString(): String = "ImportUiState(outcome=${outcome?.javaClass?.simpleName}, udpEnabled=$udpEnabled, text=<redacted>)"
}

sealed interface ServersEvent {
    /** A development server was saved and selected as the current location: go back to Home. */
    data object SavedAndSelected : ServersEvent
    /** Editor/import finished without saving a new selection (e.g. delete): go to the list. */
    data object BackToList : ServersEvent
}

/**
 * Shared by the server list, editor and import screens. Holds the key only in memory while a
 * form is open and clears it when the form closes; it is never put in saved instance state.
 */
class ServersViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as ZuzuApplication).container
    private val store = container.shadowsocksProfiles
    private val selection = container.serverSelection

    val servers: StateFlow<ServerStoreState> = store.state

    /** The server currently selected app-wide (Home, Locations, VPN service). */
    val selectedServerId: StateFlow<String?> = selection.selectedId
    val vpnState: StateFlow<VpnState> = container.vpnController.state

    private val editorState = MutableStateFlow(EditorUiState())
    val editor: StateFlow<EditorUiState> = editorState.asStateFlow()

    private val importState = MutableStateFlow(ImportUiState())
    val importUi: StateFlow<ImportUiState> = importState.asStateFlow()

    private val events = Channel<ServersEvent>(Channel.BUFFERED)
    val uiEvents: Flow<ServersEvent> = events.receiveAsFlow()

    init {
        viewModelScope.launch { store.refresh() }
    }

    fun select(id: String) {
        selection.select(id)
    }

    // ---- editor ----

    fun beginAdd() {
        editorState.value = EditorUiState()
    }

    fun beginEdit(id: String) {
        editorState.value = EditorUiState(editingId = id, busy = true)
        viewModelScope.launch {
            val profile = (store.load(id)?.config as? ConnectionConfig.Shadowsocks)?.profile
            editorState.value = if (profile == null) {
                EditorUiState(editingId = id, loadFailed = true)
            } else {
                EditorUiState(editingId = id, form = ServerForm.from(profile))
            }
        }
    }

    fun updateForm(transform: (ServerForm) -> ServerForm) {
        editorState.update { state ->
            val form = transform(state.form)
            // Re-validate live only once errors are being shown.
            val errors = if (state.errors.isEmpty()) emptyMap() else (form.validate() as? ServerForm.Result.Invalid)?.errors.orEmpty()
            state.copy(form = form, errors = errors)
        }
    }

    fun toggleKeyVisible() = editorState.update { it.copy(keyVisible = !it.keyVisible) }

    fun save() {
        val state = editorState.value
        if (state.busy) return
        when (val result = state.form.validate()) {
            is ServerForm.Result.Invalid -> editorState.value = state.copy(errors = result.errors)
            is ServerForm.Result.Valid -> {
                editorState.value = state.copy(busy = true, errors = emptyMap(), saveFailed = false)
                viewModelScope.launch {
                    if (storageSucceeded { saveAndSelect(state.editingId, result.profile) }) {
                        closeEditor()
                        events.send(ServersEvent.SavedAndSelected)
                    } else {
                        editorState.update { it.copy(busy = false, saveFailed = true) }
                    }
                }
            }
        }
    }

    fun delete() {
        val id = editorState.value.editingId ?: return
        editorState.update { it.copy(busy = true, saveFailed = false) }
        viewModelScope.launch {
            if (storageSucceeded { store.delete(id) }) {
                if (selectedServerId.value == id) selection.select(null)
                closeEditor()
                events.send(ServersEvent.BackToList)
            } else {
                editorState.update { it.copy(busy = false, saveFailed = true) }
            }
        }
    }

    /** Drops the in-memory form (including the key). */
    fun closeEditor() {
        editorState.value = EditorUiState()
    }

    // ---- import ----

    fun beginImport() {
        importState.value = ImportUiState()
    }

    fun setImportText(text: String) = importState.update { ImportUiState(text = text, udpEnabled = it.udpEnabled) }

    fun checkImport() = importState.update { it.copy(outcome = ServerImport.parse(it.text)) }

    fun setImportUdp(enabled: Boolean) = importState.update { it.copy(udpEnabled = enabled) }

    fun saveImport() {
        val state = importState.value
        val success = state.outcome as? ServerImport.Outcome.Success ?: return
        if (state.busy) return
        importState.value = state.copy(busy = true, saveFailed = false)
        viewModelScope.launch {
            if (storageSucceeded { saveAndSelect(null, success.preview.profile.copy(udpEnabled = state.udpEnabled)) }) {
                closeImport()
                events.send(ServersEvent.SavedAndSelected)
            } else {
                importState.update { it.copy(busy = false, saveFailed = true) }
            }
        }
    }

    /** Moves the imported values into the editor (as a new server) for changes before saving. */
    fun editImport() {
        val state = importState.value
        val success = state.outcome as? ServerImport.Outcome.Success ?: return
        editorState.value = EditorUiState(form = ServerForm.from(success.preview.profile.copy(udpEnabled = state.udpEnabled)))
        closeImport()
    }

    /** Drops the pasted link (it contains the key). */
    fun closeImport() {
        importState.value = ImportUiState()
    }

    /**
     * Saves a manually entered server. Editing keeps the existing public metadata (country, flag,
     * recommendation) and only updates the name and the connection configuration.
     */
    private suspend fun saveAndSelect(id: String?, profile: ShadowsocksProfile) {
        val existing = id?.let { store.load(it) }
        val entry = if (existing != null) {
            ServerEntry(existing.info.copy(name = profile.name), ConnectionConfig.Shadowsocks(profile))
        } else {
            ServerListCodec.developmentEntry(store.newId(), profile)
        }
        store.save(entry)
        selection.select(entry.info.id)
    }

    /**
     * Runs a store write; on failure logs only the exception class (never its message, which could
     * describe the data) and returns false so the UI can show an error instead of crashing.
     */
    private suspend fun storageSucceeded(block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Server storage failed (${e.javaClass.simpleName})")
        false
    }

    override fun onCleared() {
        closeEditor()
        closeImport()
    }

    private companion object {
        const val TAG = "ZuzuServers"
    }
}
