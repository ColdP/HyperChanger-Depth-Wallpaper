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
    val subjectDepth: Float,
    val fromAlpha: Boolean,
)

object DepthLayerBuilder {
    private const val LAYER_LONG_SIDE = 1440
    private const val INPAINT_SCALE = 4

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
        val background = buildBackground(sourceLayer, mask, width, height)
        val maskBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val maskPixels = IntArray(pixelCount)
        for (index in 0 until pixelCount) {
            val value = (mask[index] * 255f).roundToInt().coerceIn(0, 255)
            maskPixels[index] = (0xFF shl 24) or (value shl 16) or (value shl 8) or value
        }
        maskBitmap.setPixels(maskPixels, 0, width, 0, 0, width, height)
        if (sourceLayer !== source) sourceLayer.recycle()
        if (depthLayer !== depth) depthLayer.recycle()
        return DepthLayers(maskBitmap, background, subjectDepth, alphaMask != null)
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

    /**
     * Diffuses surrounding colours into the subject area at a reduced resolution, then blends
     * the filled plate with the untouched background region.
     */
    private fun buildBackground(sourceLayer: Bitmap, mask: FloatArray, width: Int, height: Int): Bitmap {
        val removalMask = MattingEdges.backgroundRemovalMask(
            mask, width, height, (max(width, height) / 240).coerceIn(4, 8),
        )
        val smallWidth = max(2, width / INPAINT_SCALE)
        val smallHeight = max(2, height / INPAINT_SCALE)
        val small = sourceLayer.scale(smallWidth, smallHeight)
        val smallPixels = IntArray(smallWidth * smallHeight)
        small.getPixels(smallPixels, 0, smallWidth, 0, 0, smallWidth, smallHeight)
        small.recycle()
        val smallCount = smallWidth * smallHeight
        val smallMask = FloatArray(smallCount)
        for (y in 0 until smallHeight) {
            val sourceY = (y.toFloat() / smallHeight * height).toInt().coerceIn(0, height - 1)
            for (x in 0 until smallWidth) {
                val sourceX = (x.toFloat() / smallWidth * width).toInt().coerceIn(0, width - 1)
                smallMask[y * smallWidth + x] = removalMask[sourceY * width + sourceX]
            }
        }
        val r = FloatArray(smallCount)
        val g = FloatArray(smallCount)
        val b = FloatArray(smallCount)
        val known = BooleanArray(smallCount)
        for (index in 0 until smallCount) {
            val color = smallPixels[index]
            r[index] = ((color shr 16) and 0xFF).toFloat()
            g[index] = ((color shr 8) and 0xFF).toFloat()
            b[index] = (color and 0xFF).toFloat()
            known[index] = smallMask[index] < .01f
        }
        val knownCount = known.count { it }
        if (knownCount == 0) {
            val copy = sourceLayer.copy(Bitmap.Config.ARGB_8888, false)
            return copy ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }
        repeat(26) {
            for (y in 0 until smallHeight) {
                val row = y * smallWidth
                for (x in 0 until smallWidth) {
                    val index = row + x
                    if (known[index]) continue
                    var sumR = 0f
                    var sumG = 0f
                    var sumB = 0f
                    var weight = 0f
                    for (dy in -1..1) {
                        val ny = y + dy
                        if (ny !in 0 until smallHeight) continue
                        for (dx in -1..1) {
                            val nx = x + dx
                            if (nx !in 0 until smallWidth) continue
                            val nIndex = ny * smallWidth + nx
                            if (!known[nIndex]) continue
                            val w = if (dx == 0 && dy == 0) 3f else 1f
                            sumR += r[nIndex] * w
                            sumG += g[nIndex] * w
                            sumB += b[nIndex] * w
                            weight += w
                        }
                    }
                    if (weight > 0f) {
                        r[index] = sumR / weight
                        g[index] = sumG / weight
                        b[index] = sumB / weight
                        known[index] = true
                    }
                }
            }
            for (y in smallHeight - 1 downTo 0) {
                val row = y * smallWidth
                for (x in smallWidth - 1 downTo 0) {
                    val index = row + x
                    if (known[index]) continue
                    var sumR = 0f
                    var sumG = 0f
                    var sumB = 0f
                    var weight = 0f
                    for (dy in -1..1) {
                        val ny = y + dy
                        if (ny !in 0 until smallHeight) continue
                        for (dx in -1..1) {
                            val nx = x + dx
                            if (nx !in 0 until smallWidth) continue
                            val nIndex = ny * smallWidth + nx
                            if (!known[nIndex]) continue
                            val w = if (dx == 0 && dy == 0) 3f else 1f
                            sumR += r[nIndex] * w
                            sumG += g[nIndex] * w
                            sumB += b[nIndex] * w
                            weight += w
                        }
                    }
                    if (weight > 0f) {
                        r[index] = sumR / weight
                        g[index] = sumG / weight
                        b[index] = sumB / weight
                        known[index] = true
                    }
                }
            }
        }
        val filled = Bitmap.createBitmap(smallWidth, smallHeight, Bitmap.Config.ARGB_8888)
        val filledPixels = IntArray(smallCount)
        for (index in 0 until smallCount) {
            filledPixels[index] = (0xFF shl 24) or
                (r[index].roundToInt().coerceIn(0, 255) shl 16) or
                (g[index].roundToInt().coerceIn(0, 255) shl 8) or
                b[index].roundToInt().coerceIn(0, 255)
        }
        filled.setPixels(filledPixels, 0, smallWidth, 0, 0, smallWidth, smallHeight)
        val upscaled = filled.scale(width, height)
        filled.recycle()
        val upPixels = IntArray(width * height)
        upscaled.getPixels(upPixels, 0, width, 0, 0, width, height)
        upscaled.recycle()
        val sourcePixels = IntArray(width * height)
        sourceLayer.getPixels(sourcePixels, 0, width, 0, 0, width, height)
        val output = IntArray(width * height)
        for (index in output.indices) {
            val t = removalMask[index]
            val foreground = sourcePixels[index]
            val fill = upPixels[index]
            val fr = (foreground shr 16) and 0xFF
            val fg = (foreground shr 8) and 0xFF
            val fb = foreground and 0xFF
            val br = (fill shr 16) and 0xFF
            val bg = (fill shr 8) and 0xFF
            val bb = fill and 0xFF
            val rOut = (fr + (br - fr) * t).roundToInt().coerceIn(0, 255)
            val gOut = (fg + (bg - fg) * t).roundToInt().coerceIn(0, 255)
            val bOut = (fb + (bb - fb) * t).roundToInt().coerceIn(0, 255)
            output[index] = (0xFF shl 24) or (rOut shl 16) or (gOut shl 8) or bOut
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(output, 0, width, 0, 0, width, height)
        return bitmap
    }
}
