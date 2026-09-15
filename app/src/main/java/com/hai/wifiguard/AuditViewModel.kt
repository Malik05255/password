package com.hai.wifiguard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class AuditUiState(
    val isScanning: Boolean = false,
    val result: WifiAuditResult? = null,
    val error: String? = null,
)

internal class AuditViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AuditRepository(application)
    private val _state = MutableStateFlow(AuditUiState())
    val state: StateFlow<AuditUiState> = _state.asStateFlow()

    init {
        // Start immediately so the app identifies the currently connected Wi-Fi
        // without asking the user to type an SSID. On first launch Android may
        // still limit SSID/BSSID details until the Wi-Fi permission is granted.
        runAudit()
    }

    fun runAudit() {
        if (_state.value.isScanning) return
        viewModelScope.launch {
            _state.update { it.copy(isScanning = true, error = null) }
            runCatching { repository.audit() }
                .onSuccess { result ->
                    _state.value = AuditUiState(isScanning = false, result = result)
                }
                .onFailure { throwable ->
                    _state.value = AuditUiState(
                        isScanning = false,
                        result = _state.value.result,
                        error = throwable.message ?: "تعذر إكمال الفحص.",
                    )
                }
        }
    }
}
