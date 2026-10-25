// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.btm_m.depthwallpaper.depthwallpaper

import kotlin.math.max
import kotlin.math.min

/** Refines continuous model probabilities without thresholding away hair or separate subjects. */
internal object MattingEdges {
    fun probabilities(values: FloatArray, normalize: Boolean): FloatArray {
        var low = Float.POSITIVE_INFINITY
        var high = Float.NEGATIVE_INFINITY
        for (value in values) if (value.isFinite()) {
            low = min(low, value)
            high = max(high, value)
        }
        val range = high - low
        return FloatArray(values.size) { index ->
            val value = values[index]
            when {
                !value.isFinite() -> 0f
                normalize && range > 1e-6f -> ((value - low) / range).coerceIn(0f, 1f)
                else -> value.coerceIn(0f, 1f)
            }
        }
    }

    /** Grayscale guided filter: spatial smoothing follows the photograph instead of its silhouette. */
    fun refine(mask: FloatArray, pixels: IntArray, width: Int, height: Int, radius: Int): FloatArray {
        require(width > 0 && height > 0 && radius >= 0)
        require(mask.size == width * height && pixels.size == mask.size)
        val guide = FloatArray(mask.size) { i ->
            val color = pixels[i]
            (((color ushr 16) and 255) * .2126f + ((color ushr 8) and 255) * .7152f +
                (color and 255) * .0722f) / 255f
        }
        val meanI = mean(guide, width, height, radius)
        val meanP = mean(mask, width, height, radius)
        val a = mean(FloatArray(mask.size) { guide[it] * guide[it] }, width, height, radius)
        val b = mean(FloatArray(mask.size) { guide[it] * mask[it] }, width, height, radius)
        for (i in mask.indices) {
            val variance = (a[i] - meanI[i] * meanI[i]).coerceAtLeast(0f)
            a[i] = (b[i] - meanI[i] * meanP[i]) / (variance + .001f)
            b[i] = meanP[i] - a[i] * meanI[i]
        }
        val meanA = mean(a, width, height, radius)
        val meanB = mean(b, width, height, radius)
        for (i in mask.indices) {
            // Retain some of the model's soft detail, especially on low-contrast hair.
            meanP[i] = if (mask[i] <= .005f || mask[i] >= .995f) {
                // Never spread opacity into certain background, or punch holes in solid interiors.
                mask[i]
            } else {
                (.75f * (meanA[i] * guide[i] + meanB[i]) + .25f * mask[i]).coerceIn(0f, 1f)
            }
        }
        return meanP
    }

    /** The background must exclude the entire soft edge plus the resampling footprint. */
    fun backgroundRemovalMask(mask: FloatArray, width: Int, height: Int, radius: Int): FloatArray {
        require(width > 0 && height > 0 && radius >= 0 && mask.size == width * height)
        val horizontal = FloatArray(mask.size)
        val result = FloatArray(mask.size)
        for (y in 0 until height) for (x in 0 until width) {
            var value = 0f
            for (nx in max(0, x - radius)..min(width - 1, x + radius)) {
                value = max(value, mask[y * width + nx])
            }
            horizontal[y * width + x] = value
        }
        for (y in 0 until height) for (x in 0 until width) {
            var value = 0f
            for (ny in max(0, y - radius)..min(height - 1, y + radius)) {
                value = max(value, horizontal[ny * width + x])
            }
            // A narrow transition removes colour contamination even in low-opacity strands.
            result[y * width + x] = ((value - .005f) / .025f).coerceIn(0f, 1f)
        }
        return result
    }

    private fun mean(values: FloatArray, width: Int, height: Int, radius: Int): FloatArray {
        val temp = FloatArray(values.size)
        val result = FloatArray(values.size)
        for (y in 0 until height) {
            val row = y * width
            var sum = 0.0
            for (x in 0..min(radius, width - 1)) sum += values[row + x]
            for (x in 0 until width) {
                temp[row + x] = (sum / (min(width - 1, x + radius) - max(0, x - radius) + 1)).toFloat()
                if (x - radius >= 0) sum -= values[row + x - radius]
                if (x + radius + 1 < width) sum += values[row + x + radius + 1]
            }
        }
        for (x in 0 until width) {
            var sum = 0.0
            for (y in 0..min(radius, height - 1)) sum += temp[y * width + x]
            for (y in 0 until height) {
                result[y * width + x] = (sum / (min(height - 1, y + radius) - max(0, y - radius) + 1)).toFloat()
                if (y - radius >= 0) sum -= temp[(y - radius) * width + x]
                if (y + radius + 1 < height) sum += temp[(y + radius + 1) * width + x]
            }
        }
        return result
    }
}
