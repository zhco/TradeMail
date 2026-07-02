package com.trademail.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.trademail.app.model.Account
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "account")

class AccountManager(private val context: Context) {

    companion object {
        private val KEY_EMAIL = stringPreferencesKey("email")
        private val KEY_PASSWORD = stringPreferencesKey("password")
        private val KEY_IMAP_HOST = stringPreferencesKey("imap_host")
        private val KEY_IMAP_PORT = stringPreferencesKey("imap_port")
        private val KEY_SMTP_HOST = stringPreferencesKey("smtp_host")
        private val KEY_SMTP_PORT = stringPreferencesKey("smtp_port")
        private val KEY_DISPLAY_NAME = stringPreferencesKey("display_name")
    }

    val accountFlow: Flow<Account> = context.dataStore.data.map { prefs ->
        Account(
            email = prefs[KEY_EMAIL] ?: "",
            password = prefs[KEY_PASSWORD] ?: "",
            imapHost = prefs[KEY_IMAP_HOST] ?: "",
            imapPort = prefs[KEY_IMAP_PORT]?.toIntOrNull() ?: 993,
            smtpHost = prefs[KEY_SMTP_HOST] ?: "",
            smtpPort = prefs[KEY_SMTP_PORT]?.toIntOrNull() ?: 465,
            displayName = prefs[KEY_DISPLAY_NAME] ?: ""
        )
    }

    suspend fun save(account: Account) {
        context.dataStore.edit { prefs ->
            prefs[KEY_EMAIL] = account.email
            prefs[KEY_PASSWORD] = account.password
            prefs[KEY_IMAP_HOST] = account.imapHost
            prefs[KEY_IMAP_PORT] = account.imapPort.toString()
            prefs[KEY_SMTP_HOST] = account.smtpHost
            prefs[KEY_SMTP_PORT] = account.smtpPort.toString()
            prefs[KEY_DISPLAY_NAME] = account.displayName
        }
    }

    suspend fun isConfigured(): Boolean {
        var configured = false
        context.dataStore.data.collect { prefs ->
            configured = prefs[KEY_EMAIL]?.isNotBlank() == true
            return@collect
        }
        return configured
    }

    suspend fun clear() {
        context.dataStore.edit { it.clear() }
    }
}
