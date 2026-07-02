package com.trademail.app.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidz.lifecycle.viewModelScope
import com.trademail.app.TradeMailApp
import com.trademail.app.model.Account
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val app: TradeMailApp = application as TradeMailApp

    fun save(account: Account) {
        viewModelScope.launch {
            app.accountManager.save(account)
        }
    }
}
