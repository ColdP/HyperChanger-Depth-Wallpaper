// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.btm_m.depthwallpaper.depthwallpaper

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs

private fun backgroundColor(x: Int, y: Int, width: Int, height: Int): Int {
    val r = 40 + x * 140 / (width - 1)
    val g = 60 + y * 100 / (height - 1)
    return (0xff shl 24) or (r shl 16) or (g shl 8) or 110
}

fun main(args: Array<String>) {
    val width = 128
    val height = 96
    val expected = IntArray(width * height) { backgroundColor(it % width, it / width, width, height) }
    val removal = FloatArray(expected.size) { i -> if (i % width in 30..97 && i / width in 18..77) 1f else 0f }
    val corrupted = IntArray(expected.size) { if (removal[it] > 0f) 0xffff00ff.toInt() else expected[it] }
    val depth = FloatArray(expected.size) { if (removal[it] > 0f) .95f else .3f }
    val result = BackgroundInpaint.fill(corrupted, depth, removal, width, height)
    var maxError = 0
    for (i in expected.indices) {
        check(result.pixels[i] ushr 24 == 255)
        check(abs(result.depth[i] - .3f) < .001f) { "Foreground depth leaked into background" }
        if (removal[i] == 0f) check(result.pixels[i] == expected[i])
        for (shift in listOf(0, 8, 16)) {
            maxError = maxOf(maxError, abs(((result.pixels[i] ushr shift) and 255) - ((expected[i] ushr shift) and 255)))
        }
    }
    check(maxError <= 5) { "Gradient completion error: $maxError" }

    // Mirroring the image must not move the fill seam to the opposite corner.
    fun mirror(values: IntArray) = IntArray(values.size) { values[it / width * width + width - 1 - it % width] }
    fun mirror(values: FloatArray) = FloatArray(values.size) { values[it / width * width + width - 1 - it % width] }
    val mirrored = BackgroundInpaint.fill(mirror(corrupted), mirror(depth), mirror(removal), width, height)
    val flippedBack = mirror(mirrored.pixels)
    check(result.pixels.indices.all { i -> listOf(0, 8, 16).all { shift ->
        abs(((result.pixels[i] ushr shift) and 255) - ((flippedBack[i] ushr shift) and 255)) <= 2
    } })

    // A single observed pixel fills arbitrarily large holes without black or foreground seeds.
    val almostAll = FloatArray(35 * 29) { 1f }.apply { this[0] = 0f }
    val oneKnown = IntArray(almostAll.size) { 0xffff00ff.toInt() }.apply { this[0] = 0xff4682b4.toInt() }
    val oneResult = BackgroundInpaint.fill(oneKnown, FloatArray(oneKnown.size) { .4f }, almostAll, 35, 29)
    check(oneResult.pixels.all { it == 0xff4682b4.toInt() })
    val transparent = BackgroundInpaint.fill(IntArray(49), FloatArray(49), FloatArray(49) { 1f }, 7, 7)
    check(transparent.pixels.all { it ushr 24 == 255 && (it and 0xffffff) != 0 })
    check(transparent.depth.all { it == .35f })
    for ((w, h) in listOf(1 to 1, 1 to 31, 37 to 1, 385 to 67)) {
        val color = 0xff5886a4.toInt()
        val mask = FloatArray(w * h) { if (it == 0) 0f else 1f }
        val fill = BackgroundInpaint.fill(IntArray(w * h) { color }, FloatArray(w * h) { .4f }, mask, w, h)
        check(fill.pixels.all { it == color })
    }
    if (args.isNotEmpty()) {
        val image = BufferedImage(width * 3, height, BufferedImage.TYPE_INT_RGB)
        for ((panel, pixels) in listOf(0 to expected, 1 to corrupted, 2 to result.pixels)) {
            for (y in 0 until height) for (x in 0 until width) image.setRGB(panel * width + x, y, pixels[y * width + x])
        }
        ImageIO.write(image, "png", File(args[0]))
    }
    println("Background checks passed: large-hole fill (max gradient error $maxError/255), mirror symmetry, preserved surroundings, depth removal, transparent and thin images")
}
