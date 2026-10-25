// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook.depthwallpaper

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import kotlin.math.max
import kotlin.math.min
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

/**
 * Lightweight U2Netp saliency matting. Used only when the user enables it and the image has no
 * alpha channel; the output is a soft 320x320 foreground mask.
 */
class MattingEstimator {
    private val lock = Any()
    private var session: OrtSession? = null

    fun ensureLoaded(context: Context) {
        synchronized(lock) {
            if (session != null) return
            if (!DepthResourceStore.isReady(context, DepthResources.MATTING)) return
            val env = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            val created = try {
                env.createSession(DepthResourceStore.file(context, DepthResources.MATTING).absolutePath, options)
            } finally {
                options.close()
            }
            session = created
        }
    }

    fun estimate(source: Bitmap): Bitmap? {
        synchronized(lock) {
            val currentSession = session ?: return null
            val size = 320
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
                data[index] = (((color shr 16) and 0xFF) / 255f - mean[0]) / std[0]
                data[plane + index] = (((color shr 8) and 0xFF) / 255f - mean[1]) / std[1]
                data[plane * 2 + index] = ((color and 0xFF) / 255f - mean[2]) / std[2]
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
                    buffer.rewind()
                    buffer.get(values)
                    return buildMask(values, size)
                }
            } finally {
                tensor.close()
            }
        }
    }

    fun close() = synchronized(lock) {
        session?.close()
        session = null
    }

    private fun buildMask(values: FloatArray, size: Int): Bitmap? {
        var low = Float.MAX_VALUE
        var high = -Float.MAX_VALUE
        for (value in values) {
            if (value < low) low = value
            if (value > high) high = value
        }
        val range = (high - low).takeIf { it > 1e-6f } ?: 1f
        val normalized = FloatArray(values.size) { ((values[it] - low) / range).coerceIn(0f, 1f) }
        // Threshold and keep the largest connected component: saliency models often leave
        // specks in the background.
        val binary = BooleanArray(normalized.size) { normalized[it] > .45f }
        val largest = largestComponent(binary, size, size)
        val mask = FloatArray(normalized.size) { index ->
            if (largest != null && !largest[index]) 0f else normalized[index]
        }
        var foreground = 0
        for (value in mask) if (value > .35f) foreground++
        if (foreground < normalized.size / 25) return null
        repeat(2) { boxBlur(mask, size, size) }
        val pixels = IntArray(mask.size)
        for (index in pixels.indices) {
            val value = (mask[index] * 255f).roundToInt().coerceIn(0, 255)
            pixels[index] = (0xFF shl 24) or (value shl 16) or (value shl 8) or value
        }
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
        return bitmap
    }

    private fun largestComponent(binary: BooleanArray, width: Int, height: Int): BooleanArray? {
        val visited = BooleanArray(binary.size)
        var best: BooleanArray? = null
        var bestSize = 0
        val queue = IntArray(binary.size)
        for (start in binary.indices) {
            if (!binary[start] || visited[start]) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = true
            var count = 0
            while (head < tail) {
                val index = queue[head++]
                count++
                val x = index % width
                val y = index / width
                for (dy in -1..1) {
                    val ny = y + dy
                    if (ny !in 0 until height) continue
                    for (dx in -1..1) {
                        val nx = x + dx
                        if (nx !in 0 until width) continue
                        val next = ny * width + nx
                        if (binary[next] && !visited[next]) {
                            visited[next] = true
                            queue[tail++] = next
                        }
                    }
                }
            }
            if (count > bestSize) {
                bestSize = count
                val component = BooleanArray(binary.size)
                for (position in 0 until tail) component[queue[position]] = true
                best = component
            }
        }
        return best
    }

    private fun boxBlur(values: FloatArray, width: Int, height: Int) {
        val temp = FloatArray(values.size)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val left = if (x > 0) values[row + x - 1] else values[row + x]
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

object DepthMatting {
    private val estimator = MattingEstimator()

    suspend fun estimate(context: Context, source: Bitmap): Bitmap? =
        withContext(Dispatchers.Default) {
            estimator.ensureLoaded(context)
            runCatching { estimator.estimate(source) }.getOrNull()
        }
}
