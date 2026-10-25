// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package io.github.btm_m.depthwallpaper.depthwallpaper

import android.graphics.Bitmap
import androidx.core.graphics.scale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Foreground/background decomposition used by the layered spatial renderer.
 *
 * The subject mask is soft, its depth is clamped around the subject's mean depth, and the
 * background is filled behind the subject so the foreground can move without revealing holes.
 */
class DepthLayers(
    val mask: Bitmap,
    val background: Bitmap,
    val backgroundDepth: Bitmap,
    val subjectDepth: Float,
    val fromAlpha: Boolean,
)

internal class BackgroundPlate(val image: Bitmap, val depth: Bitmap) {
    fun recycle() {
        image.recycle()
        depth.recycle()
    }
}

object DepthLayerBuilder {
    private const val LAYER_LONG_SIDE = 1440

    fun build(
        source: Bitmap,
        depth: Bitmap,
        mattingMask: Bitmap? = null,
        preserveMattingEdges: Boolean = false,
    ): DepthLayers {
        val aspect = source.width.toFloat() / source.height.coerceAtLeast(1)
        val width: Int
        val height: Int
        if (aspect >= 1f) {
            width = LAYER_LONG_SIDE
            height = max(1, (LAYER_LONG_SIDE / aspect).roundToInt())
        } else {
            width = max(1, (LAYER_LONG_SIDE * aspect).roundToInt())
            height = LAYER_LONG_SIDE
        }
        val sourceLayer = if (source.width == width && source.height == height) {
            source
        } else {
            source.scale(width, height)
        }
        val depthLayer = if (depth.width == width && depth.height == height) {
            depth
        } else {
            depth.scale(width, height)
        }
        val pixelCount = width * height
        val depthValues = FloatArray(pixelCount)
        val depthPixels = IntArray(pixelCount)
        depthLayer.getPixels(depthPixels, 0, width, 0, 0, width, height)
        for (index in 0 until pixelCount) {
            depthValues[index] = (depthPixels[index] and 0xFF) / 255f
        }
        val alphaMask = alphaMask(sourceLayer, width, height)
        val matting = if (alphaMask == null) {
            mattingMask?.let { scaleMatting(it, sourceLayer, width, height, preserveMattingEdges) }
        } else null
        val mask = alphaMask ?: matting ?: depthDerivedMask(depthValues, width, height)
        // Original alpha and refined model edges must not be blurred a second time.
        if (alphaMask == null && matting == null) softenMask(mask, width, height)
        var sum = 0f
        var count = 0
        for (index in 0 until pixelCount) {
            if (mask[index] > .5f) {
                sum += depthValues[index]
                count++
            }
        }
        val subjectDepth = if (count > 0) {
            (sum / count).coerceIn(.15f, .95f)
        } else {
            .65f
        }
        val background = buildBackground(sourceLayer, mask, depthValues, width, height)
        val maskBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val maskPixels = IntArray(pixelCount)
        for (index in 0 until pixelCount) {
            val value = (mask[index] * 255f).roundToInt().coerceIn(0, 255)
            maskPixels[index] = (0xFF shl 24) or (value shl 16) or (value shl 8) or value
        }
        maskBitmap.setPixels(maskPixels, 0, width, 0, 0, width, height)
        if (sourceLayer !== source) sourceLayer.recycle()
        if (depthLayer !== depth) depthLayer.recycle()
        return DepthLayers(maskBitmap, background.image, background.depth, subjectDepth, alphaMask != null)
    }

    private fun scaleMatting(source: Bitmap, image: Bitmap, width: Int, height: Int, preserveEdges: Boolean): FloatArray? {
        if (source.width < 2 || source.height < 2) return null
        val scaled = if (source.width == width && source.height == height) {
            source
        } else {
            source.scale(width, height)
        }
        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        if (scaled !== source) scaled.recycle()
        val values = FloatArray(width * height) { index -> (pixels[index] and 0xFF) / 255f }
        val imagePixels = IntArray(width * height)
        image.getPixels(imagePixels, 0, width, 0, 0, width, height)
        val radius = if (preserveEdges) 2 else (max(width, height) / 240).coerceIn(2, 8)
        return MattingEdges.refine(values, imagePixels, width, height, radius)
    }

    private fun alphaMask(sourceLayer: Bitmap, width: Int, height: Int): FloatArray? {
        if (!sourceLayer.hasAlpha()) return null
        val pixels = IntArray(width * height)
        sourceLayer.getPixels(pixels, 0, width, 0, 0, width, height)
        val mask = FloatArray(width * height)
        var transparent = false
        for (index in mask.indices) {
            val alpha = (pixels[index] ushr 24) and 0xFF
            mask[index] = alpha / 255f
            if (alpha < 246) transparent = true
        }
        return if (transparent) mask else null
    }

    /**
     * Otsu threshold on a depth + centre-prior score. This keeps the typical "subject near the
     * centre and closer to the camera" composition intact without a segmentation model.
     */
    private fun depthDerivedMask(depth: FloatArray, width: Int, height: Int): FloatArray {
        val pixelCount = width * height
        val score = FloatArray(pixelCount)
        val halfWidth = width / 2f
        val halfHeight = height / 2f
        val norm = sqrt(halfWidth * halfWidth + halfHeight * halfHeight).coerceAtLeast(1f)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val dx = x - halfWidth
                val dy = y - halfHeight
                val center = (1f - sqrt(dx * dx + dy * dy) / norm).coerceIn(0f, 1f)
                score[row + x] = (depth[row + x] * .86f + center * .14f).coerceIn(0f, 1f)
            }
        }
        val threshold = otsu(score)
        val low = (threshold - .055f).coerceAtLeast(0f)
        val high = (threshold + .055f).coerceAtMost(1f)
        val range = (high - low).coerceAtLeast(.0001f)
        val mask = FloatArray(pixelCount)
        for (index in mask.indices) {
            mask[index] = ((score[index] - low) / range).coerceIn(0f, 1f)
        }
        morph(mask, width, height, dilate = true)
        morph(mask, width, height, dilate = false)
        var foreground = 0
        for (value in mask) if (value > .5f) foreground++
        val fraction = foreground.toFloat() / pixelCount
        if (fraction < .06f || fraction > .94f) {
            // Degenerate split: fall back to a depth percentile around the frame centre.
            val sorted = mask.copyOf()
            sorted.sort()
            val cut = sorted[(sorted.size * .7f).toInt().coerceIn(0, sorted.lastIndex)]
            for (index in mask.indices) {
                mask[index] = if (mask[index] >= cut) 1f else (mask[index] / cut.coerceAtLeast(.0001f)) * .5f
            }
        }
        return mask
    }

    private fun otsu(values: FloatArray): Float {
        val histogram = IntArray(256)
        for (value in values) {
            histogram[(value * 255f).roundToInt().coerceIn(0, 255)]++
        }
        val total = values.size
        var sumAll = 0.0
        for (index in 0..255) sumAll += index.toDouble() * histogram[index]
        var weightBackground = 0
        var sumBackground = 0.0
        var bestVariance = -1.0
        var bestThreshold = 0
        for (index in 0..255) {
            weightBackground += histogram[index]
            if (weightBackground == 0) continue
            val weightForeground = total - weightBackground
            if (weightForeground == 0) break
            sumBackground += index.toDouble() * histogram[index]
            val meanBackground = sumBackground / weightBackground
            val meanForeground = (sumAll - sumBackground) / weightForeground
            val delta = meanBackground - meanForeground
            val variance = weightBackground.toDouble() * weightForeground * delta * delta
            if (variance > bestVariance) {
                bestVariance = variance
                bestThreshold = index
            }
        }
        return (bestThreshold + .5f) / 255f
    }

    private fun morph(values: FloatArray, width: Int, height: Int, dilate: Boolean) {
        val temp = FloatArray(values.size)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                var value = values[row + x]
                for (dy in -1..1) {
                    val ny = y + dy
                    if (ny !in 0 until height) continue
                    val nRow = ny * width
                    for (dx in -1..1) {
                        val nx = x + dx
                        if (nx !in 0 until width) continue
                        val other = values[nRow + nx]
                        value = if (dilate) max(value, other) else min(value, other)
                    }
                }
                temp[row + x] = value
            }
        }
        temp.copyInto(values)
    }

    private fun softenMask(values: FloatArray, width: Int, height: Int) {
        repeat(3) { boxBlur(values, width, height) }
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

    /** Repairs stored projects using their existing masks; model inference is not needed. */
    internal fun rebuildBackground(source: Bitmap, mask: Bitmap, depth: Bitmap): BackgroundPlate {
        val width = mask.width
        val height = mask.height
        val image = if (source.width == width && source.height == height) source else source.scale(width, height)
        val scaledDepth = if (depth.width == width && depth.height == height) depth else depth.scale(width, height)
        try {
            val pixels = IntArray(width * height)
            mask.getPixels(pixels, 0, width, 0, 0, width, height)
            val values = FloatArray(pixels.size) { (pixels[it] and 255) / 255f }
            scaledDepth.getPixels(pixels, 0, width, 0, 0, width, height)
            val depths = FloatArray(pixels.size) { (pixels[it] and 255) / 255f }
            return buildBackground(image, values, depths, width, height)
        } finally {
            if (image !== source) image.recycle()
            if (scaledDepth !== depth) scaledDepth.recycle()
        }
    }

    private fun buildBackground(sourceLayer: Bitmap, mask: FloatArray, depth: FloatArray, width: Int, height: Int): BackgroundPlate {
        val removalMask = MattingEdges.backgroundRemovalMask(
            mask, width, height, (max(width, height) / 240).coerceIn(4, 8),
        )
        val pixels = IntArray(width * height)
        sourceLayer.getPixels(pixels, 0, width, 0, 0, width, height)
        val filled = BackgroundInpaint.fill(pixels, depth, removalMask, width, height)
        val background = Bitmap.createBitmap(filled.pixels, width, height, Bitmap.Config.ARGB_8888)
        for (i in pixels.indices) {
            val value = (filled.depth[i] * 255f).roundToInt().coerceIn(0, 255)
            pixels[i] = (0xff shl 24) or (value shl 16) or (value shl 8) or value
        }
        val backgroundDepth = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        return BackgroundPlate(background, backgroundDepth)
    }
}
