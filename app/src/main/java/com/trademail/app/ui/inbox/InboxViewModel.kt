package com.trademail.app.ui.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trademail.app.TradeMailApp
import com.trademail.app.model.Account
import com.trademail.app.model.Email
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class InboxViewModel(private val app: TradeMailApp) : ViewModel() {

    private val _emails = MutableStateFlow<List<Email>>(emptyList())
    val emails: StateFlow<List<Email>> = _emails

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _account = MutableStateFlow(Account())
    val account: StateFlow<Account> = _account

    private val _translatingCount = MutableStateFlow(0)
    val translatingCount: StateFlow<Int> = _translatingCount

    init {
        viewModelScope.launch {
            app.accountManager.accountFlow.collect { acc ->
                _account.value = acc
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val acc = _account.value
            if (acc.email.isBlank()) {
                _error.value = "请先配置邮箱账户"
                return@launch
            }

            _isLoading.value = true
            _error.value = null

            val result = app.emailRepository.getInbox(acc)
            result.fold(
                onSuccess = { emails ->
                    _emails.value = emails
                    _isLoading.value = false
                },
                onFailure = { e ->
                    _error.value = "连接失败: ${e.message}"
                    _isLoading.value = false
                }
            )
        }
    }

    fun deleteAccount() {
        viewModelScope.launch {
            app.accountManager.clear()
            _emails.value = emptyList()
            _error.value = null
        }
    }
}
