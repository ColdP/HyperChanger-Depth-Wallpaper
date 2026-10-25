// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.btm_m.depthwallpaper.depthwallpaper

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

private fun assertNear(actual: Float, expected: Float) {
    check(abs(actual - expected) < .0001f) { "Expected $expected, got $actual" }
}

fun main(args: Array<String>) {
    // A constant low-confidence prediction must not turn into foreground by min/max stretching.
    MattingEdges.probabilities(floatArrayOf(.02f, .03f, .04f), false).forEachIndexed { i, value ->
        assertNear(value, .02f + i * .01f)
    }
    check(MattingEdges.probabilities(floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY), true).all { it == 0f })
    check(MattingEdges.probabilities(FloatArray(9), true).all { it == 0f })

    val width = 80
    val height = 48
    // Deliberately broad model transition around a sharp photograph edge.
    val guide = IntArray(width * height) { i -> if (i % width < width / 2) 0xff202020.toInt() else 0xffe0e0e0.toInt() }
    val mask = FloatArray(width * height) { i -> ((i % width - 34f) / 12f).coerceIn(0f, 1f) }
    val refined = MattingEdges.refine(mask, guide, width, height, 5)
    val errorBefore = mask.indices.sumOf { abs(mask[it] - if (it % width < 40) 0f else 1f).toDouble() }
    val errorAfter = refined.indices.sumOf { abs(refined[it] - if (it % width < 40) 0f else 1f).toDouble() }
    check(errorAfter < errorBefore * .9) { "Edge refinement did not improve the synthetic boundary: $errorAfter vs $errorBefore" }
    check(refined.all { it.isFinite() && it in 0f..1f })
    check(refined.any { it > .05f && it < .95f }) // Soft alpha must survive.
    mask.indices.filter { mask[it] == 0f || mask[it] == 1f }.forEach { i -> assertNear(refined[i], mask[i]) }

    // Separate small components and holes must remain, rather than selecting one largest blob.
    val details = FloatArray(width * height)
    for (y in 8..39) for (x in 20..34) details[y * width + x] = 1f
    for (y in 12..35) for (x in 50..52) details[y * width + x] = .7f
    for (y in 20..26) for (x in 25..29) details[y * width + x] = 0f
    val detailGuide = IntArray(details.size) { if (details[it] > 0f) 0xffeeeeee.toInt() else 0xff111111.toInt() }
    val preserved = MattingEdges.refine(details, detailGuide, width, height, 3)
    check(preserved[24 * width + 51] > .5f) // Thin disconnected strand.
    check(preserved[23 * width + 27] < .05f) // Hole within foreground.

    // Uniform semitransparency, full foreground/background, and degenerate dimensions.
    for ((w, h) in listOf(1 to 1, 1 to 11, 13 to 1, 9 to 7)) for (alpha in listOf(0f, .37f, 1f)) {
        val actual = MattingEdges.refine(FloatArray(w * h) { alpha }, IntArray(w * h) { 0xff777777.toInt() }, w, h, 4)
        actual.forEach { assertNear(it, alpha) }
    }

    // Inpainting sources cannot include the uncertain silhouette or its resampling neighbours.
    val removal = MattingEdges.backgroundRemovalMask(details, width, height, 4)
    for (i in details.indices) if (details[i] >= .03f) {
        val x = i % width
        val y = i / width
        for (ny in max(0, y - 4)..min(height - 1, y + 4)) {
            for (nx in max(0, x - 4)..min(width - 1, x + 4)) assertNear(removal[ny * width + nx], 1f)
        }
    }
    assertNear(removal[0], 0f)
    check(MattingEdges.backgroundRemovalMask(FloatArray(25), 5, 5, 4).all { it == 0f })

    if (args.isNotEmpty()) {
        val image = BufferedImage(width * 3, height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until height) for (x in 0 until width) {
            val i = y * width + x
            image.setRGB(x, y, guide[i])
            for ((panel, values) in listOf(1 to mask, 2 to refined)) {
                val gray = (values[i] * 255).toInt()
                image.setRGB(panel * width + x, y, (gray shl 16) or (gray shl 8) or gray)
            }
        }
        ImageIO.write(image, "png", File(args[0]))
    }
    println("Matting edge checks passed: edge error ${"%.2f".format(errorBefore)} -> ${"%.2f".format(errorAfter)}, soft alpha, strands, holes, empty masks and background margin")
}
