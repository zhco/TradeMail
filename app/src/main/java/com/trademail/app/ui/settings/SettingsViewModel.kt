package com.trademail.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trademail.app.TradeMailApp
import com.trademail.app.model.Account
import kotlinx.coroutines.launch

class SettingsViewModel : ViewModel() {

    fun save(account: Account) {
        // 从 Application 获取 AccountManager
        // 简化实现：直接通过 TradeMailApp 单例
        viewModelScope.launch {
            // 此处实际使用时通过依赖注入获取
        }
    }
}
