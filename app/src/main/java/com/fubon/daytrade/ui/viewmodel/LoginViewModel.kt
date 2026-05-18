package com.fubon.daytrade.ui.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fubon.daytrade.data.repository.FubonRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LoginUiState(
    val personalId: String = "",
    val apiKey: String = "",
    val certPassword: String = "",
    val certPath: Uri? = null,
    val certFileName: String? = null,
    val isLoading: Boolean = false,
    val isLoginSuccess: Boolean = false,  // 登入成功事件（需在離開頁面後重置）
    val errorMessage: String? = null,
    val personalIdError: String? = null,
    val apiKeyError: String? = null
) {
    val isFormValid: Boolean
        get() = personalId.isNotBlank() &&
                apiKey.isNotBlank() &&
                certPath != null &&
                personalIdError == null &&
                apiKeyError == null
}

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val repository: FubonRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    fun updatePersonalId(value: String) {
        val error = validatePersonalId(value)
        _uiState.update { it.copy(personalId = value.uppercase(), personalIdError = error, errorMessage = null) }
    }

    fun updateApiKey(value: String) {
        val error = validateApiKey(value)
        _uiState.update { it.copy(apiKey = value, apiKeyError = error, errorMessage = null) }
    }

    fun updateCertPassword(value: String) {
        _uiState.update { it.copy(certPassword = value, errorMessage = null) }
    }

    fun selectCertFile(uri: Uri, fileName: String) {
        _uiState.update { it.copy(certPath = uri, certFileName = fileName, errorMessage = null) }
    }

    fun login(): Boolean {
        val state = _uiState.value

        // Final validation
        if (state.personalIdError != null || state.apiKeyError != null) {
            _uiState.update { it.copy(errorMessage = "請修正表單錯誤後再試") }
            return false
        }

        _uiState.update { it.copy(isLoading = true, errorMessage = null) }

        viewModelScope.launch {
            try {
                val result = repository.login(
                    personalId = state.personalId,
                    apiKey = state.apiKey,
                    certPath = state.certPath.toString(),
                    certPassword = state.certPassword.ifBlank { state.personalId }
                )

                result.fold(
                    onSuccess = { accounts ->
                        _uiState.update { it.copy(isLoading = false, isLoginSuccess = true) }
                        // Store accounts in repository for later use
                        repository.saveAccounts(accounts)
                    },
                    onFailure = { error ->
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                errorMessage = error.message ?: "登入失敗"
                            )
                        }
                    }
                )
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = e.message ?: "網路連線失敗，請檢查網路"
                    )
                }
            }
        }

        return true
    }

    /** 登出或離開登入頁後重置狀態，防止重新進入時 still isLoginSuccess=true */
    fun resetLoginState() {
        _uiState.update {
            it.copy(
                isLoginSuccess = false,
                isLoading = false,
                errorMessage = null
            )
        }
    }

    private fun validatePersonalId(value: String): String? {
        return when {
            value.isBlank() -> "請輸入身分證字號"
            !Regex("^[A-Z][0-9]{9}$").matches(value.uppercase()) -> "格式錯誤，應為 A123456789"
            else -> null
        }
    }

    private fun validateApiKey(value: String): String? {
        return when {
            value.isBlank() -> "請輸入 API Key"
            !Regex("^[0-9A-Fa-f]{64}$").matches(value) -> "API Key 應為 64 碼 HEX"
            else -> null
        }
    }
}