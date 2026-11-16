package com.example.zuzuvpn.ui.account

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.zuzuvpn.ZuzuApplication
import com.example.zuzuvpn.delivery.AccountState
import com.example.zuzuvpn.delivery.RegisteredDevice
import com.example.zuzuvpn.delivery.SignInOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AccountFormState(
    val busy: Boolean = false,
    /** Backend error code (e.g. INVALID_CREDENTIALS) or NETWORK / KEYSTORE; mapped to text by the screen. */
    val error: String? = null,
    val limit: Int = 0,
    val limitDevices: List<RegisteredDevice> = emptyList(),
)

class AccountViewModel(application: Application) : AndroidViewModel(application) {
    private val account = (application as ZuzuApplication).container.account
    val state: StateFlow<AccountState> = account.state

    private val form = MutableStateFlow(AccountFormState())
    val formState: StateFlow<AccountFormState> = form.asStateFlow()

    fun signIn(email: String, password: String, register: Boolean) = run { account.signIn(email.trim(), password, register) }

    fun replace(device: RegisteredDevice) = run { account.replaceDevice(device.id) }

    fun cancelReplace() {
        account.abandonPendingSignIn()
        form.value = AccountFormState()
    }

    fun signOut() {
        viewModelScope.launch {
            form.update { it.copy(busy = true) }
            account.signOut()
            form.value = AccountFormState()
        }
    }

    /** Credentials remembered for a device-limit replacement never outlive this screen. */
    override fun onCleared() {
        account.abandonPendingSignIn()
    }

    private fun run(block: suspend () -> SignInOutcome) {
        if (form.value.busy) return
        viewModelScope.launch {
            form.update { it.copy(busy = true, error = null) }
            form.value = when (val outcome = block()) {
                SignInOutcome.Success -> AccountFormState()
                is SignInOutcome.DeviceLimit -> AccountFormState(limit = outcome.limit, limitDevices = outcome.devices)
                is SignInOutcome.Failed -> AccountFormState(error = outcome.code)
            }
        }
    }
}
