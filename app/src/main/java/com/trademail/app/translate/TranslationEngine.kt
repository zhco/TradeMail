package com.trademail.app.translate

import kotlinx.coroutines.flow.StateFlow

/**
 * 翻译引擎统一接口。
 * 默认使用离线 Hy-MT 引擎，可扩展在线引擎。
 */
class TranslationEngine(
    private val offlineTranslator: OfflineTranslator
) {
    val isReady: StateFlow<Boolean> get() = offlineTranslator.isReady
    val isLoading: StateFlow<Boolean> get() = offlineTranslator.isLoading

    /**
     * 英文 → 中文（收件翻译）
     */
    suspend fun translateIncoming(text: String): String {
        if (text.isBlank()) return text
        if (!isReady.value) return text
        return offlineTranslator.translateEnToZh(text)
    }

    /**
     * 中文 → 英文（回复翻译）
     */
    suspend fun translateOutgoing(text: String): String {
        if (text.isBlank()) return text
        if (!isReady.value) return text
        return offlineTranslator.translateZhToEn(text)
    }

    fun clearCache() = offlineTranslator.clearCache()

    fun shutdown() = offlineTranslator.shutdown()
}
