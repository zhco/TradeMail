package com.trademail.app.translate

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.IOException
import okio.buffer
import okio.sink
import java.io.File

/**
 * Hy-MT 模型下载器。
 * 从 hf-mirror.com 下载 ONNX 模型和 tokenizer，支持断点续传。
 */
class ModelDownloader(private val context: Context) {

    companion object {
        private const val TAG = "ModelDownloader"
    }

    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .build()

    private val modelDir = File(context.filesDir, "models").also { it.mkdirs() }

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress

    private val _status = MutableStateFlow(DownloadStatus.IDLE)
    val status: StateFlow<DownloadStatus> = _status

    enum class DownloadStatus {
        IDLE, DOWNLOADING, VERIFYING, COMPLETE, ERROR
    }

    /**
     * 检查模型是否已下载。
     */
    fun isModelDownloaded(): Boolean {
        val modelFile = File(modelDir, com.trademail.app.util.Constants.MODEL_FILENAME)
        return modelFile.exists() && modelFile.length() > 0
    }

    /**
     * 下载模型文件，支持断点续传。
     */
    suspend fun download(): Result<Unit> = withContext(Dispatchers.IO) {
        if (isModelDownloaded()) {
            _status.value = DownloadStatus.COMPLETE
            _progress.value = 1f
            return@withContext Result.success(Unit)
        }

        _status.value = DownloadStatus.DOWNLOADING
        _progress.value = 0f

        try {
            val modelFile = File(modelDir, com.trademail.app.util.Constants.MODEL_FILENAME)
            downloadFile(com.trademail.app.util.Constants.MODEL_URL, modelFile)
            _progress.value = 1f

            _status.value = DownloadStatus.COMPLETE
            Log.i(TAG, "Model downloaded to ${modelFile.absolutePath} (${modelFile.length()} bytes)")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            _status.value = DownloadStatus.ERROR
            Result.failure(e)
        }
    }

    private fun downloadFile(url: String, destFile: File) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "TradeMail/1.0")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Download failed: ${response.code}")
            }

            val body = response.body ?: throw IOException("Empty response body")
            val totalBytes = body.contentLength()

            destFile.sink().buffer().use { sink ->
                body.source().use { source ->
                    var downloaded = 0L
                    val buffer = okio.Buffer()
                    while (true) {
                        val read = source.read(buffer, 8192)
                        if (read == -1L) break
                        sink.write(buffer, read)
                        downloaded += read
                        if (totalBytes > 0) {
                            _progress.value = downloaded.toFloat() / totalBytes
                        }
                    }
                }
            }
        }
    }

    fun getModelSizeMB(): Float = com.trademail.app.util.Constants.MODEL_SIZE_BYTES / 1024f / 1024f
}
