package com.trademail.app.model

data class Account(
    val email: String = "",
    val password: String = "",
    val imapHost: String = "",
    val imapPort: Int = 993,
    val smtpHost: String = "",
    val smtpPort: Int = 465,
    val displayName: String = ""
)

data class Email(
    val uid: Long = 0,
    val from: String = "",
    val subject: String = "",
    val bodyPlain: String = "",
    val bodyHtml: String = "",
    val receivedDate: Long = 0,
    val isRead: Boolean = false,
    val hasAttachments: Boolean = false,
    // 翻译结果缓存
    val translatedSubject: String = "",
    val translatedBody: String = ""
)

data class TranslationCache(
    val sourceHash: String,
    val translatedText: String,
    val timestamp: Long = System.currentTimeMillis()
)
