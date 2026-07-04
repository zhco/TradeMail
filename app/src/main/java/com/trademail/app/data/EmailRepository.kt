package com.trademail.app.data

import com.trademail.app.model.Account
import com.trademail.app.model.Email
import com.trademail.app.translate.TranslationEngine
import kotlinx.coroutines.*

/**
 * 邮件仓库，协调 IMAP 拉取 + 自动翻译。
 */
class EmailRepository(
    private val imapClient: ImapClient,
    private val translationEngine: TranslationEngine
) {
    suspend fun getInbox(account: Account, page: Int = 0): Result<List<Email>> {
        val result = imapClient.fetchInbox(account, page)
        return result.map { emails ->
            // 自动翻译所有英文邮件
            if (translationEngine.isReady.value) {
                emails.map { email ->
                    email.copy(
                        translatedSubject = if (needsTranslation(email.subject)) {
                            translationEngine.translateIncoming(email.subject)
                        } else email.subject,
                        translatedBody = if (needsTranslation(email.bodyPlain)) {
                            translationEngine.translateIncoming(email.bodyPlain)
                        } else email.bodyPlain
                    )
                }
            } else {
                emails
            }
        }
    }

    suspend fun sendReply(
        account: Account,
        to: String,
        subject: String,
        chineseBody: String
    ): Result<Unit> {
        // 中文自动转英文
        val englishBody = if (translationEngine.isReady.value) {
            translationEngine.translateOutgoing(chineseBody)
        } else {
            chineseBody
        }
    }

    suspend fun sendNew(
        account: Account,
        to: String,
        subject: String,
        chineseBody: String
    ): Result<Unit> {
        val englishBody = if (translationEngine.isReady.value) {
            translationEngine.translateOutgoing(chineseBody)
        } else {
            chineseBody
        }
        // 主题也翻译
        val englishSubject = if (translationEngine.isReady.value) {
            translationEngine.translateOutgoing(subject)
        } else {
            subject
        }
    }

    /**
     * 判断文本是否包含英文（需要翻译）。
     */
    private fun needsTranslation(text: String): Boolean {
        if (text.isBlank()) return false
        val englishChars = text.count { it in 'a'..'z' || it in 'A'..'Z' }
        return englishChars > text.length * 0.3 // 英文占比超过 30%
    }
}
