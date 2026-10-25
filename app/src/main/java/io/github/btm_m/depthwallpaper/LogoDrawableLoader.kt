// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package io.github.btm_m.depthwallpaper

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.Drawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.BitmapFactory
import android.util.Xml
import androidx.core.graphics.PathParser
import java.io.File
import kotlin.math.max

object LogoDrawableLoader {
    /** Loads a persisted local asset without relying on a document-provider URI grant. */
    fun loadFile(context: Context, file: File): Drawable? = runCatching {
        if (!file.isFile) return@runCatching null
        loadBytes(context, file.readBytes())
    }.getOrNull()

    fun loadBytes(context: Context, bytes: ByteArray): Drawable? {
        val text = bytes.toString(Charsets.UTF_8).trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        return when {
            text.startsWith("<svg", ignoreCase = true) || text.contains("<svg", ignoreCase = true) -> parseSvg(text)
            text.startsWith("<vector", ignoreCase = true) ||
                text.startsWith("<?xml", ignoreCase = true) && text.contains("<vector", ignoreCase = true) -> {
                val parser = Xml.newPullParser().apply { setInput(bytes.inputStream(), "UTF-8") }
                Drawable.createFromXml(context.resources, parser)
            }
            else -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { BitmapDrawable(context.resources, it) }
        }
    }

    private fun parseSvg(text: String): Drawable? {
        val paths = Regex("<path\\b([^>]*)>", RegexOption.IGNORE_CASE).findAll(text).mapNotNull { match ->
            val attrs = match.groupValues[1]
            val data = Regex("\\bd\\s*=\\s*[\\\"']([^\\\"']+)", RegexOption.IGNORE_CASE).find(attrs)?.groupValues?.get(1) ?: return@mapNotNull null
            // android.util.PathParser is hidden on recent Android versions; use AndroidX's public parser.
            val path = runCatching { PathParser.createPathFromPathData(data) }.getOrNull()
                ?: return@mapNotNull null
            val fill = Regex("\\bfill\\s*=\\s*[\\\"']([^\\\"']+)", RegexOption.IGNORE_CASE).find(attrs)?.groupValues?.get(1)
            path to parseColor(fill)
        }.toList()
        if (paths.isEmpty()) return null
        val viewBox = Regex("\\bviewBox\\s*=\\s*[\\\"']\\s*([-+0-9.eE]+)[, ]+([-+0-9.eE]+)[, ]+([-+0-9.eE]+)[, ]+([-+0-9.eE]+)", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.drop(1)?.mapNotNull { it.toFloatOrNull() }
        val width = Regex("\\bwidth\\s*=\\s*[\\\"']\\s*([-+0-9.eE]+)", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toFloatOrNull()
        val height = Regex("\\bheight\\s*=\\s*[\\\"']\\s*([-+0-9.eE]+)", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toFloatOrNull()
        val minX = viewBox?.getOrNull(0) ?: 0f
        val minY = viewBox?.getOrNull(1) ?: 0f
        val sourceWidth = max(1f, viewBox?.getOrNull(2) ?: width ?: 100f)
        val sourceHeight = max(1f, viewBox?.getOrNull(3) ?: height ?: sourceWidth)
        return SvgPathDrawable(paths, minX, minY, sourceWidth, sourceHeight)
    }

    private fun parseColor(value: String?): Int = when {
        value == null || value.equals("none", true) -> Color.WHITE
        value.startsWith("#") -> runCatching { Color.parseColor(value) }.getOrDefault(Color.WHITE)
        value.equals("black", true) -> Color.BLACK
        value.equals("white", true) -> Color.WHITE
        else -> Color.WHITE
    }
}

private class SvgPathDrawable(
    private val paths: List<Pair<Path, Int>>,
    private val sourceMinX: Float,
    private val sourceMinY: Float,
    private val sourceWidth: Float,
    private val sourceHeight: Float,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    override fun draw(canvas: Canvas) {
        val bounds = bounds
        if (bounds.isEmpty) return
        val scale = minOf(bounds.width() / sourceWidth, bounds.height() / sourceHeight)
        canvas.save()
        canvas.translate(
            bounds.left + (bounds.width() - sourceWidth * scale) / 2f - sourceMinX * scale,
            bounds.top + (bounds.height() - sourceHeight * scale) / 2f - sourceMinY * scale,
        )
        canvas.scale(scale, scale)
        paths.forEach { (path, color) -> paint.color = color; canvas.drawPath(path, paint) }
        canvas.restore()
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(filter: android.graphics.ColorFilter?) { paint.colorFilter = filter; invalidateSelf() }
    @Deprecated("Deprecated in Android API") override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth(): Int = sourceWidth.toInt()
    override fun getIntrinsicHeight(): Int = sourceHeight.toInt()
}
