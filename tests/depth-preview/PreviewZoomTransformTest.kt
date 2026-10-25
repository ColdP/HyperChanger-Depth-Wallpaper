// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.btm_m.depthwallpaper.depthwallpaper

import kotlin.math.abs
import kotlin.random.Random

private fun near(actual: Float, expected: Float) = check(abs(actual - expected) < .001f) {
    "Expected $expected, got $actual"
}

fun main() {
    val initial = PreviewZoomTransform()
    val centered = initial.transform(2f, 0f, 0f, 500f, 1000f, 1000f, 2000f)
    near(centered.scale, 2f)
    near(centered.offsetX, 0f)
    near(centered.offsetY, 0f)

    // The same source point must remain under a moving pinch center.
    val before = PreviewZoomTransform(2f, 100f, -200f)
    val anchorX = 650f
    val anchorY = 850f
    val sourceX = (anchorX - 500f - before.offsetX) / before.scale
    val sourceY = (anchorY - 1000f - before.offsetY) / before.scale
    val after = before.transform(1.25f, 15f, -20f, anchorX, anchorY, 1000f, 2000f)
    near(500f + after.offsetX + sourceX * after.scale, anchorX + 15f)
    near(1000f + after.offsetY + sourceY * after.scale, anchorY - 20f)

    val capped = initial.transform(100f, 100000f, -100000f, 500f, 1000f, 1000f, 2000f)
    near(capped.scale, 4f)
    val reset = capped.transform(.001f, 0f, 0f, 100f, 100f, 1000f, 2000f)
    near(reset.scale, 1f)
    near(reset.offsetX, 0f)
    near(reset.offsetY, 0f)
    check(before.transform(Float.NaN, 0f, 0f, 0f, 0f, 1000f, 2000f) == before)
    check(before.transform(2f, 0f, 0f, 0f, 0f, 0f, 2000f) == before)

    // Repeated gestures in portrait and landscape must always cover the viewport.
    val random = Random(7129)
    for ((width, height) in listOf(1080f to 2400f, 2400f to 1080f)) {
        var state = initial
        repeat(3000) {
            state = state.transform(
                random.nextFloat() * 2f + .2f,
                random.nextFloat() * 4000f - 2000f,
                random.nextFloat() * 4000f - 2000f,
                random.nextFloat() * width,
                random.nextFloat() * height,
                width,
                height,
            )
            check(state.scale in 1f..4f)
            val left = (width - width * state.scale) / 2f + state.offsetX
            val top = (height - height * state.scale) / 2f + state.offsetY
            check(left <= .001f && top <= .001f)
            check(left + width * state.scale >= width - .001f)
            check(top + height * state.scale >= height - .001f)
        }
    }
    println("Preview zoom checks passed: pinch anchor, panning, limits, reset and 6000 viewport coverage gestures")
}
