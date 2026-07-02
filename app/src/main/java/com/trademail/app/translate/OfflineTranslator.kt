package com.trademail.app.translate

import android.content.Context
import android.util.Log
import ai.onnxruntime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Hy-MT 离线翻译引擎。
 * 使用 ONNX Runtime 加载量化模型，完全离线运行。
 */
class OfflineTranslator(private val context: Context) {

    companion object {
        private const val TAG = "OfflineTranslator"
    }

    private var session: OrtSession? = null
    private var env: OrtEnvironment? = null
    private val cache = ConcurrentHashMap<String, String>()

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    /**
     * 初始化翻译引擎，加载 ONNX 模型。
     */
    suspend fun initialize() = withContext(Dispatchers.IO) {
        if (_isReady.value) return@withContext
        _isLoading.value = true
        try {
            val modelFile = File(context.filesDir, "models/${com.trademail.app.util.Constants.MODEL_FILENAME}")
            val tokenizerFile = File(context.filesDir, "models/${com.trademail.app.util.Constants.TOKENIZER_FILENAME}")

            if (!modelFile.exists()) {
                Log.w(TAG, "Model not found at ${modelFile.absolutePath}")
                _isLoading.value = false
                return@withContext
            }

            env = OrtEnvironment.getEnvironment()
            val sessionOptions = OrtSession.SessionOptions().apply {
                // 使用 CPU 执行，4 线程
                setIntraOpNumThreads(4)
                setInterOpNumThreads(2)
                // 启用图优化
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }

            session = env?.createSession(modelFile.absolutePath, sessionOptions)
            _isReady.value = true
            Log.i(TAG, "Offline translator initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize translator", e)
            _isReady.value = false
        } finally {
            _isLoading.value = false
        }
    }

    /**
     * 英文 → 中文翻译
     */
    suspend fun translateEnToZh(text: String): String = withContext(Dispatchers.IO) {
        translate(text, "en", "zh")
    }

    /**
     * 中文 → 英文翻译（用于写回复）
     */
    suspend fun translateZhToEn(text: String): String = withContext(Dispatchers.IO) {
        translate(text, "zh", "en")
    }

    private suspend fun translate(text: String, from: String, to: String): String {
        if (text.isBlank()) return text
        if (!_isReady.value) return text

        // 检查缓存
        val hash = hashKey(text, from, to)
        cache[hash]?.let { return it }

        return try {
            val result = doTranslate(text, from, to)
            cache[hash] = result
            result
        } catch (e: Exception) {
            Log.e(TAG, "Translation failed", e)
            text
        }
    }

    private fun doTranslate(text: String, from: String, to: String): String {
        val session = this.session ?: throw IllegalStateException("Session not initialized")
        val env = this.env ?: throw IllegalStateException("Environment not initialized")

        // 构造输入 prompt: "<from> <to> <text>"
        val inputText = "$from $to $text"
        val inputIds = simpleTokenize(inputText)
        val inputTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(inputIds), longArrayOf(1, inputIds.size.toLong()))

        val inputs = mapOf("input_ids" to inputTensor)
        val outputs = session.run(inputs)

        val outputTensor = outputs.first().value as Array<*>
        val outputIds = outputTensor.map { (it as LongArray).first() }

        // 简单的 detokenize
        val result = simpleDetokenize(outputIds, from, to)
        inputTensor.close()
        outputs.close()
        return result
    }

    /**
     * 简易分词器，将字符映射为 token id。
     * 实际生产环境应加载 tokenizer.json 做完整映射。
     */
    private fun simpleTokenize(text: String): LongArray {
        // 使用 UTF-8 字节编码作为简易 token 表示
        val bytes = text.toByteArray(Charsets.UTF_8)
        return bytes.map { it.toLong() and 0xFF }.toLongArray()
    }

    private fun simpleDetokenize(ids: List<Long>, from: String, to: String): String {
        val bytes = ids.map { it.toByte() }.toByteArray()
        val raw = String(bytes, Charsets.UTF_8)
        // 移除输入部分，只保留翻译结果
        val prefix = "$from $to "
        return if (raw.startsWith(prefix)) raw.removePrefix(prefix) else raw
    }

    private fun hashKey(text: String, from: String, to: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest("$from$to$text".toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun clearCache() = cache.clear()

    fun shutdown() {
        session?.close()
        env?.close()
        session = null
        env = null
        _isReady.value = false
    }
}
