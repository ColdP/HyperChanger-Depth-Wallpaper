// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook.depthwallpaper

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Models that power the spatial wallpaper lab. Both are downloaded on demand from the project's
 * GitHub Pages endpoint so the APK stays small; inference itself is fully offline.
 */
enum class DepthModel(
    val id: String,
    val displayName: String,
    val fileName: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
    val inputSize: Int,
    val mean: FloatArray,
    val std: FloatArray,
    val bgr: Boolean,
    val invertDepth: Boolean,
    val rawUint8Input: Boolean,
) {
    DEPTH_ANYTHING_V2_SMALL(
        id = "depth_anything_v2_small",
        displayName = "Depth Anything V2 Small",
        fileName = "depth-anything-v2-small.onnx",
        url = "https://coldp.github.io/hyperchanger-updateinfo/models/depth-anything-v2-small.onnx",
        sizeBytes = 27_258_801L,
        sha256 = "fcf51f1b230362b28690bb9d1809bf0431f29cad20534e3f589bd7285547f20d",
        inputSize = 518,
        mean = floatArrayOf(.485f, .456f, .406f),
        std = floatArrayOf(.229f, .224f, .225f),
        bgr = false,
        invertDepth = false,
        rawUint8Input = false,
    ),
    MIDAS_MOBILE(
        id = "midas_mobile",
        displayName = "MiDaS Mobile",
        fileName = "midas-v2.1-small.onnx",
        url = "https://coldp.github.io/hyperchanger-updateinfo/models/midas-v2.1-small.onnx",
        sizeBytes = 66_389_153L,
        sha256 = "b0a5b3f12625137e626805167907fe0410665bec671685d59daaa2daab19f977",
        inputSize = 256,
        // MiDaS v2.1 (EfficientNet-Lite3) was trained on BGR images normalized with ImageNet stats.
        mean = floatArrayOf(.485f, .456f, .406f),
        std = floatArrayOf(.229f, .224f, .225f),
        bgr = true,
        invertDepth = false,
        rawUint8Input = false,
    ),
    DEPTH_ANYTHING_V3_BASE(
        id = "depth_anything_v3_base",
        displayName = "Depth Anything 3 Base",
        fileName = "da3-base-int8.onnx",
        url = "https://coldp.github.io/hyperchanger-updateinfo/models/da3-base-int8.onnx",
        sizeBytes = 103_943_002L,
        sha256 = "53cb20d8d0d1194c18de18259deffc46398b6ae7cd3f183e46d84af94b34007d",
        inputSize = 518,
        // DA3 ONNX is exported with the preprocessing folded in: uint8 RGB NHWC input.
        mean = floatArrayOf(.485f, .456f, .406f),
        std = floatArrayOf(.229f, .224f, .225f),
        bgr = false,
        invertDepth = true,
        rawUint8Input = true,
    ),
    ;

    companion object {
        fun byId(id: String?): DepthModel = entries.firstOrNull { it.id == id } ?: DEPTH_ANYTHING_V2_SMALL
    }
}

/** Downloads and verifies model files. A verified marker keeps repeated checks cheap. */
object DepthModelStore {
    private const val BUFFER_SIZE = 256 * 1024

    fun directory(context: Context): File =
        File(context.filesDir, "depth_models").apply { if (!isDirectory) mkdirs() }

    fun modelFile(context: Context, model: DepthModel): File =
        File(directory(context), model.fileName)

    private fun markerFile(context: Context, model: DepthModel): File =
        File(directory(context), "${model.fileName}.verified")

    fun partialFile(context: Context, model: DepthModel): File =
        File(modelFile(context, model).path + ".part")

    fun isReady(context: Context, model: DepthModel): Boolean {
        val file = modelFile(context, model)
        if (!file.isFile || file.length() != model.sizeBytes) return false
        return markerFile(context, model).isFile
    }

    fun downloadedBytes(context: Context, model: DepthModel): Long {
        if (isReady(context, model)) return model.sizeBytes
        val partial = partialFile(context, model)
        return if (partial.isFile) partial.length().coerceAtMost(model.sizeBytes) else 0L
    }

    fun delete(context: Context, model: DepthModel) {
        modelFile(context, model).delete()
        partialFile(context, model).delete()
        markerFile(context, model).delete()
    }

    suspend fun download(
        context: Context,
        model: DepthModel,
        onProgress: (downloaded: Long, total: Long, bytesPerSecond: Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val target = modelFile(context, model)
        val partial = partialFile(context, model)
        var startOffset = if (partial.isFile) partial.length() else 0L
        if (startOffset >= model.sizeBytes) {
            partial.delete()
            startOffset = 0L
        }
        val connection = (URL(model.url).openConnection() as HttpURLConnection).apply {
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
                            onProgress(written, model.sizeBytes, speed)
                            windowStart = now
                            windowBytes = 0L
                            lastReport = now
                        }
                    }
                    output.flush()
                    output.fd.sync()
                }
            }
            if (written != model.sizeBytes) error("文件大小不匹配")
            if (!sha256(partial).equals(model.sha256, ignoreCase = true)) {
                partial.delete()
                error("模型校验失败")
            }
            target.delete()
            if (!partial.renameTo(target)) error("无法保存模型文件")
            markerFile(context, model).writeText(model.sha256)
            onProgress(model.sizeBytes, model.sizeBytes, 0L)
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

/** Raw relative inverse-depth map returned by the models (larger value = closer). */
class DepthRaw(val width: Int, val height: Int, val values: FloatArray)

/**
 * Single-session ONNX Runtime wrapper. Only one model is kept in memory at a time because the
 * quantized Depth Anything graph and MiDaS together are larger than the budget of low-end devices.
 */
class DepthEstimator {
    private val lock = Any()
    private var session: OrtSession? = null
    private var loadedId: String? = null
    private var loadedModel: DepthModel? = null

    fun ensureLoaded(context: Context, model: DepthModel) {
        synchronized(lock) {
            if (loadedId == model.id && session != null) return
            if (!DepthModelStore.isReady(context, model)) error("Model is not downloaded")
            val env = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            val created = try {
                env.createSession(DepthModelStore.modelFile(context, model).absolutePath, options)
            } finally {
                options.close()
            }
            session?.close()
            session = created
            loadedId = model.id
            loadedModel = model
        }
    }

    fun run(bitmap: Bitmap): DepthRaw {
        synchronized(lock) {
            val currentSession = session ?: error("Model is not loaded")
            val model = loadedModel ?: error("Model is not loaded")
            val size = model.inputSize
            val scaled = if (bitmap.width == size && bitmap.height == size) {
                bitmap
            } else {
                Bitmap.createScaledBitmap(bitmap, size, size, true)
            }
            val plane = size * size
            val pixels = IntArray(plane)
            scaled.getPixels(pixels, 0, size, 0, 0, size, size)
            if (scaled !== bitmap) scaled.recycle()
            val data = FloatArray(plane * 3)
            val mean = model.mean
            val std = model.std
            if (!model.rawUint8Input) {
                for (index in 0 until plane) {
                    val color = pixels[index]
                    val r = ((color shr 16) and 0xFF) / 255f
                    val g = ((color shr 8) and 0xFF) / 255f
                    val b = (color and 0xFF) / 255f
                    val c0 = if (model.bgr) b else r
                    val c1 = g
                    val c2 = if (model.bgr) r else b
                    data[index] = (c0 - mean[0]) / std[0]
                    data[plane + index] = (c1 - mean[1]) / std[1]
                    data[plane * 2 + index] = (c2 - mean[2]) / std[2]
                }
            }
            val env = OrtEnvironment.getEnvironment()
            val inputName = currentSession.inputNames.first()
            val tensor = if (model.rawUint8Input) {
                // DA3 ONNX has preprocessing folded into the graph: uint8 RGB, NHWC.
                val bytes = ByteArray(plane * 3)
                var cursor = 0
                for (index in 0 until plane) {
                    val color = pixels[index]
                    bytes[cursor++] = ((color shr 16) and 0xFF).toByte()
                    bytes[cursor++] = ((color shr 8) and 0xFF).toByte()
                    bytes[cursor++] = (color and 0xFF).toByte()
                }
                OnnxTensor.createTensor(
                    env,
                    ByteBuffer.wrap(bytes),
                    longArrayOf(1L, size.toLong(), size.toLong(), 3L),
                    OnnxJavaType.UINT8,
                )
            } else {
                OnnxTensor.createTensor(
                    env,
                    FloatBuffer.wrap(data),
                    longArrayOf(1L, 3L, size.toLong(), size.toLong()),
                )
            }
            try {
                currentSession.run(mapOf(inputName to tensor)).use { outputs ->
                    val output = outputs.get(0) as OnnxTensor
                    val shape = output.info.shape
                    val height = shape[shape.size - 2].toInt()
                    val width = shape[shape.size - 1].toInt()
                    val values = FloatArray(width * height)
                    val buffer = output.floatBuffer
                    buffer.rewind()
                    buffer.get(values)
                    val normalized = normalizeAndSmooth(values, width, height)
                    if (model.invertDepth) {
                        for (index in normalized.indices) normalized[index] = 1f - normalized[index]
                    }
                    return DepthRaw(width, height, normalized)
                }
            } finally {
                tensor.close()
            }
        }
    }

    fun close() = synchronized(lock) {
        session?.close()
        session = null
        loadedId = null
        loadedModel = null
    }

    private fun normalizeAndSmooth(values: FloatArray, width: Int, height: Int): FloatArray {
        // 1% / 99% percentiles avoid outliers dominating the contrast stretch.
        val sorted = values.copyOf()
        sorted.sort()
        val low = sorted[(sorted.size * .01f).toInt().coerceIn(0, sorted.lastIndex)]
        val high = sorted[(sorted.size * .99f).toInt().coerceIn(0, sorted.lastIndex)]
        val range = (high - low).takeIf { it > 1e-6f } ?: 1f
        for (index in values.indices) {
            values[index] = ((values[index] - low) / range).coerceIn(0f, 1f)
        }
        boxBlur(values, width, height)
        boxBlur(values, width, height)
        return values
    }

    /** Separable 3x3 box blur; depth edges are softened so parallax does not tear. */
    private fun boxBlur(values: FloatArray, width: Int, height: Int) {
        if (width < 3 || height < 3) return
        val temp = FloatArray(values.size)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val left = if (x > 0) values[row + x - 1] else values[row]
                val center = values[row + x]
                val right = if (x < width - 1) values[row + x + 1] else values[row + width - 1]
                temp[row + x] = (left + center + right) / 3f
            }
        }
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val up = if (y > 0) temp[row - width + x] else temp[x]
                val center = temp[row + x]
                val down = if (y < height - 1) temp[row + width + x] else temp[(height - 1) * width + x]
                values[row + x] = (up + center + down) / 3f
            }
        }
    }
}

class DepthGeneration(
    val depth: Bitmap,
    val layers: DepthLayers,
)

object DepthWallpaperGenerator {
    private val estimator = DepthEstimator()

    /**
     * Runs depth estimation, derives the subject mask and background plate, and keeps everything
     * needed by the layered renderer.
     */
    suspend fun generate(
        context: Context,
        source: Bitmap,
        model: DepthModel,
        useMatting: Boolean,
        onProgress: (Float) -> Unit,
    ): DepthGeneration = withContext(Dispatchers.Default) {
        onProgress(.02f)
        val loadTicker = launchTicker(onProgress, .04f, .22f, .006f)
        try {
            estimator.ensureLoaded(context, model)
        } finally {
            loadTicker.cancel()
        }
        onProgress(.25f)
        onProgress(.3f)
        var raw: DepthRaw? = null
        val inferenceStep = if (model.rawUint8Input) .004f else .012f
        val inferenceTicker = launchTicker(onProgress, .32f, .84f, inferenceStep)
        try {
            raw = estimator.run(source)
        } finally {
            inferenceTicker.cancel()
        }
        onProgress(.86f)
        val depth = buildDepthBitmap(raw ?: error("Depth estimation failed"), source)
        onProgress(.88f)
        val mattingMask = if (useMatting) DepthMatting.estimate(context, source) else null
        onProgress(.92f)
        val layers = DepthLayerBuilder.build(source, depth, mattingMask)
        mattingMask?.recycle()
        onProgress(1f)
        DepthGeneration(depth, layers)
    }

    fun release() = estimator.close()

    private fun CoroutineScope.launchTicker(
        onProgress: (Float) -> Unit,
        from: Float,
        to: Float,
        step: Float,
    ): Job = launch {
        var value = from
        while (isActive) {
            delay(150)
            value = (value + step).coerceAtMost(to)
            onProgress(value)
        }
    }

    private fun buildDepthBitmap(raw: DepthRaw, source: Bitmap): Bitmap {
        val aspect = source.width.toFloat() / source.height.coerceAtLeast(1)
        val longSide = 1440
        val width: Int
        val height: Int
        if (aspect >= 1f) {
            width = longSide
            height = max(1, (longSide / aspect).roundToInt())
        } else {
            width = max(1, (longSide * aspect).roundToInt())
            height = longSide
        }
        val small = Bitmap.createBitmap(raw.width, raw.height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(raw.width * raw.height)
        for (index in pixels.indices) {
            val value = (raw.values[index] * 255f).roundToInt().coerceIn(0, 255)
            pixels[index] = (0xFF shl 24) or (value shl 16) or (value shl 8) or value
        }
        small.setPixels(pixels, 0, raw.width, 0, 0, raw.width, raw.height)
        val scaled = Bitmap.createScaledBitmap(small, width, height, true)
        if (scaled !== small) small.recycle()
        return scaled
    }
}
