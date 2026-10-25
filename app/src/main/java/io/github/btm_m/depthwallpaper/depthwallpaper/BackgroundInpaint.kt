// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.btm_m.depthwallpaper.depthwallpaper

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal class InpaintedBackground(val pixels: IntArray, val depth: FloatArray)

/** Coarse-to-fine harmonic extension from visible background only, for colour AND depth. */
internal object BackgroundInpaint {
    fun fill(pixels: IntArray, depth: FloatArray, removal: FloatArray, width: Int, height: Int): InpaintedBackground {
        require(width > 0 && height > 0 && pixels.size == width * height)
        require(depth.size == pixels.size && removal.size == pixels.size)
        val step = max(1, ceil(max(width, height) / 384.0).toInt())
        val smallWidth = (width + step - 1) / step
        val smallHeight = (height + step - 1) / step
        val channels = Array(4) { FloatArray(smallWidth * smallHeight) }
        val weights = FloatArray(smallWidth * smallHeight)
        var fallbackR = 0.0
        var fallbackG = 0.0
        var fallbackB = 0.0
        var fallbackWeight = 0.0
        for (y in 0 until height) for (x in 0 until width) {
            val i = y * width + x
            val color = pixels[i]
            val alpha = (color ushr 24) / 255f
            val r = ((color ushr 16) and 255) / 255f
            val g = ((color ushr 8) and 255) / 255f
            val b = (color and 255) / 255f
            fallbackR += r * alpha
            fallbackG += g * alpha
            fallbackB += b * alpha
            fallbackWeight += alpha
            // Never average foreground into a downsampled boundary cell. Transparent PNG
            // pixels are not observations of a black background either.
            if (removal[i] > .001f || alpha < .99f) continue
            val j = (y / step) * smallWidth + x / step
            weights[j] += 1f
            channels[0][j] += r
            channels[1][j] += g
            channels[2][j] += b
            channels[3][j] += depth[i]
        }
        val known = BooleanArray(weights.size) { weights[it] > 0f }
        for (i in weights.indices) if (known[i]) for (channel in channels) channel[i] /= weights[i]
        val fallback = if (fallbackWeight > 0.0) {
            floatArrayOf((fallbackR / fallbackWeight).toFloat(), (fallbackG / fallbackWeight).toFloat(), (fallbackB / fallbackWeight).toFloat(), .35f)
        } else floatArrayOf(.2f, .2f, .2f, .35f)
        solve(channels, known, smallWidth, smallHeight, fallback)
        val output = IntArray(pixels.size)
        val outputDepth = FloatArray(pixels.size)
        for (y in 0 until height) for (x in 0 until width) {
            val i = y * width + x
            val sx = (x + .5f) / step - .5f
            val sy = (y + .5f) / step - .5f
            val t = max(removal[i], 1f - (pixels[i] ushr 24) / 255f).coerceIn(0f, 1f)
            fun color(channel: Int, shift: Int): Int {
                val original = ((pixels[i] ushr shift) and 255) / 255f
                return ((original + (sample(channels[channel], smallWidth, smallHeight, sx, sy) - original) * t) * 255f)
                    .roundToInt().coerceIn(0, 255)
            }
            output[i] = (0xff shl 24) or (color(0, 16) shl 16) or (color(1, 8) shl 8) or color(2, 0)
            outputDepth[i] = (depth[i] + (sample(channels[3], smallWidth, smallHeight, sx, sy) - depth[i]) * t).coerceIn(0f, 1f)
        }
        return InpaintedBackground(output, outputDepth)
    }

    private fun solve(values: Array<FloatArray>, known: BooleanArray, width: Int, height: Int, fallback: FloatArray) {
        if (known.all { it }) return
        if (known.none { it }) {
            values.forEachIndexed { c, channel -> channel.fill(fallback[c]) }
            return
        }
        if (width > 2 || height > 2) {
            val cw = (width + 1) / 2
            val ch = (height + 1) / 2
            val coarse = Array(values.size) { FloatArray(cw * ch) }
            val coarseKnown = BooleanArray(cw * ch)
            for (y in 0 until ch) for (x in 0 until cw) {
                val i = y * cw + x
                var count = 0
                for (ny in y * 2 until min(height, y * 2 + 2)) for (nx in x * 2 until min(width, x * 2 + 2)) {
                    val j = ny * width + nx
                    if (!known[j]) continue
                    count++
                    for (c in values.indices) coarse[c][i] += values[c][j]
                }
                if (count > 0) {
                    coarseKnown[i] = true
                    for (channel in coarse) channel[i] /= count.toFloat()
                }
            }
            solve(coarse, coarseKnown, cw, ch, fallback)
            for (y in 0 until height) for (x in 0 until width) {
                val i = y * width + x
                if (!known[i]) for (c in values.indices) {
                    values[c][i] = sample(coarse[c], cw, ch, (x + .5f) / 2f - .5f, (y + .5f) / 2f - .5f)
                }
            }
        } else {
            for (c in values.indices) {
                var sum = 0f
                var count = 0
                for (i in known.indices) if (known[i]) { sum += values[c][i]; count++ }
                for (i in known.indices) if (!known[i]) values[c][i] = sum / count
            }
        }
        // Revisiting all unknown pixels avoids freezing the first colour that reaches a hole.
        // Alternating checkerboards removes the old top-left scan direction bias.
        repeat(64) {
            for (parity in 0..1) for (y in 0 until height) {
                for (x in ((y + parity) and 1) until width step 2) {
                    val i = y * width + x
                    if (known[i]) continue
                    for (channel in values) {
                        var sum = 0f
                        var count = 0
                        if (x > 0) { sum += channel[i - 1]; count++ }
                        if (x < width - 1) { sum += channel[i + 1]; count++ }
                        if (y > 0) { sum += channel[i - width]; count++ }
                        if (y < height - 1) { sum += channel[i + width]; count++ }
                        if (count > 0) channel[i] = sum / count
                    }
                }
            }
        }
    }

    private fun sample(values: FloatArray, width: Int, height: Int, x: Float, y: Float): Float {
        val sx = x.coerceIn(0f, width - 1f)
        val sy = y.coerceIn(0f, height - 1f)
        val x0 = sx.toInt()
        val y0 = sy.toInt()
        val x1 = min(x0 + 1, width - 1)
        val y1 = min(y0 + 1, height - 1)
        val fx = sx - x0
        val fy = sy - y0
        val top = values[y0 * width + x0] * (1f - fx) + values[y0 * width + x1] * fx
        val bottom = values[y1 * width + x0] * (1f - fx) + values[y1 * width + x1] * fx
        return top * (1f - fy) + bottom * fy
    }
}
