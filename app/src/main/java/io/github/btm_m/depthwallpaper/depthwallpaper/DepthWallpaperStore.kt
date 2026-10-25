// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package io.github.btm_m.depthwallpaper.depthwallpaper

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import androidx.core.graphics.scale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class DepthWallpaperProject(
    val id: String,
    val createdAt: Long,
    val modelId: String,
    val width: Int,
    val height: Int,
    val sourceName: String,
    val subjectDepth: Float = .7f,
    val hasLayers: Boolean = false,
) {
    val aspectRatio: Float
        get() = if (height > 0) width.toFloat() / height else 2f / 3f

    val model: DepthModel
        get() = DepthModel.byId(modelId)
}

/**
 * Disk backed history for generated spatial wallpapers. Each project owns a folder with the
 * original image, the depth map and a small list thumbnail.
 */
class DepthWallpaperStore(context: Context) {
    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, "depth_wallpapers")
    private val indexFile = File(root, "index.json")
    private val lock = Any()

    private fun ensureRoot() {
        if (!root.isDirectory) root.mkdirs()
    }

    fun projects(): List<DepthWallpaperProject> = synchronized(lock) {
        readProjects().sortedByDescending { it.createdAt }
    }

    fun project(id: String): DepthWallpaperProject? = projects().firstOrNull { it.id == id }

    fun originalFile(id: String): File {
        val folder = File(root, id)
        val png = File(folder, "original.png")
        return if (png.isFile) png else File(folder, "original.jpg")
    }

    fun depthFile(id: String): File = File(File(root, id), "depth.png")

    fun maskFile(id: String): File = File(File(root, id), "mask.png")

    fun backgroundFile(id: String): File = File(File(root, id), "background.jpg")

    fun thumbnailFile(id: String): File = File(File(root, id), "thumb.jpg")

    suspend fun save(
        source: Bitmap,
        depth: Bitmap,
        layers: DepthLayers,
        model: DepthModel,
        sourceName: String,
    ): DepthWallpaperProject = withContext(Dispatchers.IO) {
        ensureRoot()
        val id = UUID.randomUUID().toString().replace("-", "")
        val folder = File(root, id).apply { mkdirs() }
        if (layers.fromAlpha) {
            FileOutputStream(File(folder, "original.png")).use { output ->
                source.compress(Bitmap.CompressFormat.PNG, 100, output)
            }
        } else {
            FileOutputStream(File(folder, "original.jpg")).use { output ->
                source.compress(Bitmap.CompressFormat.JPEG, 92, output)
            }
        }
        FileOutputStream(File(folder, "depth.png")).use { output ->
            depth.compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        FileOutputStream(File(folder, "mask.png")).use { output ->
            layers.mask.compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        FileOutputStream(File(folder, "background.jpg")).use { output ->
            layers.background.compress(Bitmap.CompressFormat.JPEG, 90, output)
        }
        writeThumbnail(source, File(folder, "thumb.jpg"), layers.fromAlpha)
        val project = DepthWallpaperProject(
            id = id,
            createdAt = System.currentTimeMillis(),
            modelId = model.id,
            width = source.width,
            height = source.height,
            sourceName = sourceName,
            subjectDepth = layers.subjectDepth,
            hasLayers = true,
        )
        synchronized(lock) {
            val projects = readProjects().toMutableList()
            projects.add(0, project)
            writeProjects(projects)
        }
        project
    }

    fun delete(id: String) = synchronized(lock) {
        val projects = readProjects().filterNot { it.id == id }
        writeProjects(projects)
        File(root, id).deleteRecursively()
    }

    fun clearAll() = synchronized(lock) {
        val projects = readProjects()
        writeProjects(emptyList())
        projects.forEach { File(root, it.id).deleteRecursively() }
    }

    fun loadOriginal(id: String): Bitmap? = BitmapFactory.decodeFile(originalFile(id).absolutePath)

    fun loadDepth(id: String): Bitmap? = BitmapFactory.decodeFile(depthFile(id).absolutePath)

    fun loadMask(id: String): Bitmap? = BitmapFactory.decodeFile(maskFile(id).absolutePath)

    fun loadBackground(id: String): Bitmap? = BitmapFactory.decodeFile(backgroundFile(id).absolutePath)

    fun loadThumbnail(id: String): Bitmap? {
        val thumbnail = thumbnailFile(id)
        if (thumbnail.isFile) return BitmapFactory.decodeFile(thumbnail.absolutePath)
        val original = loadOriginal(id) ?: return null
        val width = minOf(360, original.width.coerceAtLeast(1))
        val height = (original.height.toFloat() / original.width * width).toInt().coerceAtLeast(1)
        return original.scale(width, height)
    }

    private fun writeThumbnail(source: Bitmap, target: File, flattenAlpha: Boolean) {
        val base = if (flattenAlpha) flattenOnWhite(source) else source
        val width = minOf(480, base.width.coerceAtLeast(1))
        val height = (base.height.toFloat() / base.width * width).toInt().coerceAtLeast(1)
        val scaled = base.scale(width, height)
        FileOutputStream(target).use { output -> scaled.compress(Bitmap.CompressFormat.JPEG, 84, output) }
        if (scaled !== base) scaled.recycle()
        if (base !== source) base.recycle()
    }

    private fun flattenOnWhite(source: Bitmap): Bitmap {
        val flattened = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(flattened)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(source, 0f, 0f, null)
        return flattened
    }

    private fun readProjects(): List<DepthWallpaperProject> {
        if (!indexFile.isFile) return emptyList()
        return runCatching {
            val array = JSONArray(indexFile.readText())
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val project = item.toProject() ?: continue
                    if (File(root, project.id).isDirectory) add(project)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun writeProjects(projects: List<DepthWallpaperProject>) = synchronized(lock) {
        ensureRoot()
        val array = JSONArray()
        projects.forEach { array.put(it.toJson()) }
        val temp = File(root, "index.json.tmp")
        temp.writeText(array.toString())
        if (indexFile.exists()) indexFile.delete()
        temp.renameTo(indexFile)
        Unit
    }

    private fun DepthWallpaperProject.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("createdAt", createdAt)
        put("modelId", modelId)
        put("width", width)
        put("height", height)
        put("sourceName", sourceName)
        put("subjectDepth", subjectDepth.toDouble())
        put("hasLayers", hasLayers)
    }

    private fun JSONObject.toProject(): DepthWallpaperProject? = runCatching {
        val projectId = getString("id")
        DepthWallpaperProject(
            id = projectId,
            createdAt = optLong("createdAt", 0L),
            modelId = optString("modelId", DepthModel.DEPTH_ANYTHING_V2_SMALL.id),
            width = optInt("width", 2).coerceAtLeast(1),
            height = optInt("height", 3).coerceAtLeast(1),
            sourceName = optString("sourceName", ""),
            subjectDepth = optDouble("subjectDepth", .7).toFloat().coerceIn(.1f, .95f),
            hasLayers = optBoolean("hasLayers", false) &&
                File(File(root, projectId), "mask.png").isFile &&
                File(File(root, projectId), "background.jpg").isFile,
        )
    }.getOrNull()
}

enum class DepthRenderMode {
    ORIGINAL,
    DEPTH,
    PARALLAX,
    BOKEH,
    FINAL,
    ;

    companion object {
        fun byOrdinal(value: Int): DepthRenderMode =
            entries.getOrElse(value) { FINAL }
    }
}

/**
 * Dedicated preference file for the lab. Kept out of HookSettings on purpose: these values only
 * affect the app UI and must never be pushed to the hooked system processes.
 */
class DepthWallpaperSettingsStore(context: Context) {
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences("depth_wallpaper_settings", Context.MODE_PRIVATE)

    var settings: DepthWallpaperSettings
        get() = DepthWallpaperSettings(
            modelId = DepthModel.byId(preferences.getString(KEY_MODEL, null)).id,
            parallaxStrength = preferences.getInt(KEY_PARALLAX, 60).coerceIn(20, 100),
            renderMode = DepthRenderMode.byOrdinal(preferences.getInt(KEY_RENDER_MODE, DepthRenderMode.FINAL.ordinal)).ordinal,
            mattingModelId = MattingModel.byId(preferences.getString(KEY_MATTING_MODEL, null)).id,
        )
        private set(value) {
            preferences.edit()
                .putString(KEY_MODEL, value.modelId)
                .putInt(KEY_PARALLAX, value.parallaxStrength.coerceIn(20, 100))
                .putInt(KEY_RENDER_MODE, value.renderMode)
                .putString(KEY_MATTING_MODEL, value.mattingModelId)
                .apply()
        }

    fun update(transform: (DepthWallpaperSettings) -> DepthWallpaperSettings) {
        settings = transform(settings)
    }

    private companion object {
        const val KEY_MODEL = "model_id"
        const val KEY_PARALLAX = "parallax_strength"
        const val KEY_RENDER_MODE = "render_mode"
        const val KEY_MATTING_MODEL = "matting_model_id"
    }
}

data class DepthWallpaperSettings(
    val modelId: String,
    val parallaxStrength: Int,
    val renderMode: Int = DepthRenderMode.FINAL.ordinal,
    val mattingModelId: String = MattingModel.U2NETP.id,
) {
    val mode: DepthRenderMode
        get() = DepthRenderMode.byOrdinal(renderMode)
}

/** Decodes a picked image, applying EXIF orientation and limiting the stored resolution. */
suspend fun decodeDepthSourceBitmap(context: Context, uri: Uri, maxLongSide: Int = 2560): Bitmap? =
    withContext(Dispatchers.IO) {
        runCatching {
            val source = android.graphics.ImageDecoder.createSource(context.contentResolver, uri)
            android.graphics.ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = false
                val size = info.size
                val longSide = maxOf(size.width, size.height)
                if (longSide > maxLongSide) {
                    val scale = maxLongSide.toFloat() / longSide
                    decoder.setTargetSize(
                        (size.width * scale).toInt().coerceAtLeast(1),
                        (size.height * scale).toInt().coerceAtLeast(1),
                    )
                }
            }
        }.getOrNull()
    }

fun queryDisplayName(context: Context, uri: Uri): String = runCatching {
    context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
}.getOrNull().orEmpty()

fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> String.format(java.util.Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}
