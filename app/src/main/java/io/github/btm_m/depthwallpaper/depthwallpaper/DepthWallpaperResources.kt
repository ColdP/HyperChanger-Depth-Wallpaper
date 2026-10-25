// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package io.github.btm_m.depthwallpaper.depthwallpaper

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.FloatBuffer
import java.security.MessageDigest
import kotlin.math.exp
import kotlin.math.roundToInt

/** Downloadable extras that are not depth-estimation models. */
data class DepthResource(
    val id: String,
    val fileName: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
)

object DepthResources {
    val MATTING = DepthResource(
        id = "matting_u2netp",
        fileName = "u2netp.onnx",
        url = "https://coldp.github.io/hyperchanger-updateinfo/models/u2netp.onnx",
        sizeBytes = 4_574_861L,
        sha256 = "309c8469258dda742793dce0ebea8e6dd393174f89934733ecc8b14c76f4ddd8",
    )

    val MATTING_ISNET = DepthResource(
        id = "matting_isnet_int8",
        fileName = "isnet-general-int8.onnx",
        url = "https://coldp.github.io/hyperchanger-updateinfo/models/isnet-general-int8.onnx",
        sizeBytes = 44_229_662L,
        sha256 = "3b21a6706dc8d6e4ba9f5b31ebc6940f6c785b58862e27bb25daa9dd4424b87f",
    )

    val MATTING_BIREFNET = DepthResource(
        id = "matting_birefnet_lite_int8",
        fileName = "birefnet-lite-int8.onnx",
        url = "https://coldp.github.io/hyperchanger-updateinfo/models/birefnet-lite-int8.onnx",
        sizeBytes = 90_764_431L,
        sha256 = "2cffd9a8441691513a3d1d0990b5b154796f2351ba5ecb22f8ff802ac9dba1d1",
    )
}

enum class MattingModel(
    val displayName: String,
    val resource: DepthResource,
    val inputSize: Int,
    val outputLogits: Boolean = false,
) {
    U2NETP("U2Netp（轻量）", DepthResources.MATTING, 320),
    ISNET_INT8("ISNet General INT8（均衡）", DepthResources.MATTING_ISNET, 1024),
    BIREFNET_LITE_INT8("BiRefNet Lite INT8（精细，较慢）", DepthResources.MATTING_BIREFNET, 1024, true),
    ;

    val id: String get() = resource.id

    companion object {
        // Missing or unknown preferences keep the original model and its downloaded file.
        fun byId(id: String?): MattingModel = entries.firstOrNull { it.id == id } ?: U2NETP
    }
}

object DepthResourceStore {
    private const val BUFFER_SIZE = 256 * 1024

    fun directory(context: Context): File =
        File(context.filesDir, "depth_resources").apply { if (!isDirectory) mkdirs() }

    fun file(context: Context, resource: DepthResource): File =
        File(directory(context), resource.fileName)

    private fun markerFile(context: Context, resource: DepthResource): File =
        File(directory(context), "${resource.fileName}.verified")

    fun partialFile(context: Context, resource: DepthResource): File =
        File(file(context, resource).path + ".part")

    fun isReady(context: Context, resource: DepthResource): Boolean {
        val file = file(context, resource)
        if (!file.isFile || file.length() != resource.sizeBytes) return false
        return markerFile(context, resource).isFile
    }

    fun downloadedBytes(context: Context, resource: DepthResource): Long {
        if (isReady(context, resource)) return resource.sizeBytes
        val partial = partialFile(context, resource)
        return if (partial.isFile) partial.length().coerceAtMost(resource.sizeBytes) else 0L
    }

    fun delete(context: Context, resource: DepthResource) {
        file(context, resource).delete()
        partialFile(context, resource).delete()
        markerFile(context, resource).delete()
    }

    suspend fun download(
        context: Context,
        resource: DepthResource,
        onProgress: (downloaded: Long, total: Long, bytesPerSecond: Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val target = file(context, resource)
        val partial = partialFile(context, resource)
        var startOffset = if (partial.isFile) partial.length() else 0L
        if (startOffset >= resource.sizeBytes) {
            partial.delete()
            startOffset = 0L
        }
        val connection = (URL(resource.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "HyperChanger-Android")
            if (startOffset > 0L) setRequestProperty("Range", "bytes=$startOffset-")
        }
        try {
            connection.connect()
            val code = connection.responseCode
            if (code !in 200..299) error("HTTP $code")
            val append = code == 206 && startOffset > 0L
            var written = if (append) startOffset else 0L
            var windowStart = SystemClock.elapsedRealtime()
            var windowBytes = 0L
            var lastReport = 0L
            connection.inputStream.use { input ->
                FileOutputStream(partial, append).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        windowBytes += read
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastReport >= 140L) {
                            val speed = if (now > windowStart) {
                                windowBytes * 1000L / (now - windowStart).coerceAtLeast(1L)
                            } else 0L
                            onProgress(written, resource.sizeBytes, speed)
                            windowStart = now
                            windowBytes = 0L
                            lastReport = now
                        }
                    }
                    output.flush()
                    output.fd.sync()
                }
            }
            if (written != resource.sizeBytes) error("文件大小不匹配")
            if (!sha256(partial).equals(resource.sha256, ignoreCase = true)) {
                partial.delete()
                error("资源校验失败")
            }
            target.delete()
            if (!partial.renameTo(target)) error("无法保存资源文件")
            markerFile(context, resource).writeText(resource.sha256)
            onProgress(resource.sizeBytes, resource.sizeBytes, 0L)
            target
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/** On-demand matting with one resident session to limit memory when switching models. */
class MattingEstimator {
    private val lock = Any()
    private var session: OrtSession? = null
    private var loadedModel: MattingModel? = null

    private fun ensureLoaded(context: Context, model: MattingModel) {
        synchronized(lock) {
            check(DepthResourceStore.isReady(context, model.resource)) { "请先下载所选抠图模型" }
            if (session != null && loadedModel == model) return
            close()
            val env = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            val created = try {
                env.createSession(DepthResourceStore.file(context, model.resource).absolutePath, options)
            } finally {
                options.close()
            }
            session = created
            loadedModel = model
        }
    }

    fun estimate(context: Context, source: Bitmap, model: MattingModel): Bitmap? {
        synchronized(lock) {
            ensureLoaded(context, model)
            val currentSession = session ?: return null
            val size = model.inputSize
            val scaled = if (source.width == size && source.height == size) {
                source
            } else {
                Bitmap.createScaledBitmap(source, size, size, true)
            }
            val plane = size * size
            val pixels = IntArray(plane)
            scaled.getPixels(pixels, 0, size, 0, 0, size, size)
            if (scaled !== source) scaled.recycle()
            val data = FloatArray(plane * 3)
            val mean = floatArrayOf(.485f, .456f, .406f)
            val std = floatArrayOf(.229f, .224f, .225f)
            for (index in 0 until plane) {
                val color = pixels[index]
                if (model == MattingModel.ISNET_INT8) {
                    // This export expects raw RGB normalized by mean=128, std=256.
                    data[index] = (((color shr 16) and 0xFF) - 128f) / 256f
                    data[plane + index] = (((color shr 8) and 0xFF) - 128f) / 256f
                    data[plane * 2 + index] = ((color and 0xFF) - 128f) / 256f
                } else {
                    data[index] = (((color shr 16) and 0xFF) / 255f - mean[0]) / std[0]
                    data[plane + index] = (((color shr 8) and 0xFF) / 255f - mean[1]) / std[1]
                    data[plane * 2 + index] = ((color and 0xFF) / 255f - mean[2]) / std[2]
                }
            }
            val env = OrtEnvironment.getEnvironment()
            val inputName = currentSession.inputNames.first()
            val tensor = OnnxTensor.createTensor(
                env,
                FloatBuffer.wrap(data),
                longArrayOf(1L, 3L, size.toLong(), size.toLong()),
            )
            try {
                currentSession.run(mapOf(inputName to tensor)).use { outputs ->
                    val output = outputs.get(0) as OnnxTensor
                    val values = FloatArray(plane)
                    val buffer = output.floatBuffer
                    check(buffer.remaining() == plane) { "抠图模型输出尺寸不匹配" }
                    buffer.rewind()
                    buffer.get(values)
                    if (model.outputLogits) {
                        for (index in values.indices) values[index] = 1f / (1f + exp(-values[index]))
                    }
                    return buildMask(values, size, model)
                }
            } finally {
                tensor.close()
            }
        }
    }

    fun close() = synchronized(lock) {
        session?.close()
        session = null
        loadedModel = null
    }

    private fun buildMask(values: FloatArray, size: Int, model: MattingModel): Bitmap? {
        // U2Netp follows its original output normalization, but keeps continuous edges.
        // ISNet/BiRefNet already produce probabilities; avoid amplifying low-confidence noise.
        val mask = MattingEdges.probabilities(values, normalize = model == MattingModel.U2NETP)
        val pixels = IntArray(mask.size)
        for (index in pixels.indices) {
            val value = (mask[index] * 255f).roundToInt().coerceIn(0, 255)
            pixels[index] = (0xFF shl 24) or (value shl 16) or (value shl 8) or value
        }
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
        return bitmap
    }

}

object DepthMatting {
    private val estimator = MattingEstimator()

    fun release() = estimator.close()

    suspend fun estimate(context: Context, source: Bitmap, model: MattingModel): Bitmap? =
        withContext(Dispatchers.Default) {
            estimator.estimate(context, source, model)
        }
}
