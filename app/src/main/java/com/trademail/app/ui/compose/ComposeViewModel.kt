package com.trademail.app.ui.compose

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trademail.app.TradeMailApp
import com.trademail.app.model.Account
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ComposeViewModel(private val app: TradeMailApp) : ViewModel() {

    private val _account = MutableStateFlow(Account())
    val account: StateFlow<Account> = _account

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending

    private val _sendResult = MutableStateFlow<String?>(null)
    val sendResult: StateFlow<String?> = _sendResult

    init {
        viewModelScope.launch {
            app.accountManager.accountFlow.collect { acc ->
                _account.value = acc
            }
        }
    }

    fun sendReply(to: String, subject: String, chineseBody: String) {
        viewModelScope.launch {
            _isSending.value = true
            _sendResult.value = null
            val result = app.emailRepository.sendReply(_account.value, to, subject, chineseBody)
            result.fold(
                onSuccess = { _sendResult.value = "发送成功" },
                onFailure = { _sendResult.value = "发送失败: ${it.message}" }
            )
            _isSending.value = false
        }
    }

    fun sendNew(to: String, subject: String, chineseBody: String) {
        viewModelScope.launch {
            _isSending.value = true
            _sendResult.value = null
            val result = app.emailRepository.sendNew(_account.value, to, subject, chineseBody)
            result.fold(
                onSuccess = { _sendResult.value = "发送成功" },
                onFailure = { _sendResult.value = "发送失败: ${it.message}" }
            )
            _isSending.value = false
        }
    }
}
