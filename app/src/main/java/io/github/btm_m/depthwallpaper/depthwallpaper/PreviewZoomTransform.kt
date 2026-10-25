// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.btm_m.depthwallpaper.depthwallpaper

/** Viewport transform shared by all preview render modes. Translation is in screen pixels. */
internal data class PreviewZoomTransform(
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
) {
    fun transform(
        zoom: Float,
        panX: Float,
        panY: Float,
        centroidX: Float,
        centroidY: Float,
        width: Float,
        height: Float,
    ): PreviewZoomTransform {
        if (width <= 0f || height <= 0f || !zoom.isFinite() || zoom <= 0f) return this
        val nextScale = (scale * zoom).coerceIn(1f, 4f)
        val ratio = nextScale / scale
        val centerX = centroidX - width / 2f
        val centerY = centroidY - height / 2f
        // Keep the content beneath the pinch center stationary while scaling.
        val nextX = (offsetX - centerX) * ratio + centerX + panX
        val nextY = (offsetY - centerY) * ratio + centerY + panY
        val limitX = width * (nextScale - 1f) / 2f
        val limitY = height * (nextScale - 1f) / 2f
        return PreviewZoomTransform(
            scale = nextScale,
            offsetX = nextX.coerceIn(-limitX, limitX),
            offsetY = nextY.coerceIn(-limitY, limitY),
        )
    }
}
