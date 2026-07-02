package com.trademail.app.util

object Constants {
    // Hy-MT 离线模型
    const val MODEL_URL = "https://hf-mirror.com/AngelSlim/Hy-MT1.5-1.8B-1.25bit/resolve/main/model.onnx"
    const val TOKENIZER_URL = "https://hf-mirror.com/AngelSlim/Hy-MT1.5-1.8B-1.25bit/resolve/main/tokenizer.json"
    const val MODEL_FILENAME = "hy_mt_model.onnx"
    const val TOKENIZER_FILENAME = "hy_mt_tokenizer.json"
    const val MODEL_SIZE_BYTES = 461_373_440L // ~440MB

    // 翻译
    const val MAX_TRANSLATE_CHARS = 3000
    const val TRANSLATE_LANG_FROM = "en"
    const val TRANSLATE_LANG_TO = "zh"
    const val TRANSLATE_REPLY_FROM = "zh"
    const val TRANSLATE_REPLY_TO = "en"

    // 邮件
    const val INBOX_PAGE_SIZE = 20
}
