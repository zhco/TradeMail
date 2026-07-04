package com.trademail.app

import android.app.Application
import com.trademail.app.data.AccountManager
import com.trademail.app.data.EmailRepository
import com.trademail.app.data.ImapClient

import com.trademail.app.translate.ModelDownloader
import com.trademail.app.translate.OfflineTranslator
import com.trademail.app.translate.TranslationEngine
import kotlinx.coroutines.*

class TradeMailApp : Application() {

    lateinit var accountManager: AccountManager
    lateinit var emailRepository: EmailRepository
    lateinit var translationEngine: TranslationEngine
    lateinit var modelDownloader: ModelDownloader
    private lateinit var offlineTranslator: OfflineTranslator

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()

        accountManager = AccountManager(this)
        offlineTranslator = OfflineTranslator(this)
        translationEngine = TranslationEngine(offlineTranslator)
        modelDownloader = ModelDownloader(this)
        emailRepository = EmailRepository(
            ImapClient(),
            translationEngine
        )

        // 如果模型已下载，自动初始化
        if (modelDownloader.isModelDownloaded()) {
            appScope.launch {
                translationEngine.let { offlineTranslator.initialize() }
            }
        }
    }
}
