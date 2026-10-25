// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package io.github.btm_m.depthwallpaper.depthwallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

private const val MAX_TILT_ANGLE = .58f

private const val SPATIAL_SHADER = """
uniform shader img;
uniform shader dep;
uniform float2 size;
uniform float2 parallax;
uniform float warp;
uniform float2 tilt;

half4 main(float2 coord) {
    float d = clamp(float(dep.eval(coord).r), 0.0, 1.0);
    float2 center = size * 0.5;
    float2 p = coord - center;
    p.x += p.y * tilt.x * 0.035;
    p.y += p.x * tilt.y * 0.035;
    float zoom = 1.0 + warp * (d - 0.5);
    float2 uv = center + p / zoom;
    uv += parallax * (d - 0.5) * 2.0;
    return img.eval(uv);
}
"""

private const val BOKEH_SHADER = """
uniform shader img;
uniform shader dep;
uniform float2 size;
uniform float warp;

half4 main(float2 coord) {
    float d = clamp(float(dep.eval(coord).r), 0.0, 1.0);
    float2 center = size * 0.5;
    float zoom = 1.0 + warp * (d - 0.5);
    float2 uv = center + (coord - center) / zoom;
    return img.eval(uv);
}
"""

private const val LAYERED_SHADER = """
uniform shader img;
uniform shader bg;
uniform shader dep;
uniform shader msk;
uniform float2 size;
uniform float2 parallax;
uniform float2 tilt;
uniform float subjectDepth;

half4 main(float2 coord) {
    float d = clamp(float(dep.eval(coord).r), 0.0, 1.0);
    float2 center = size * 0.5;
    float2 p = coord - center;
    p.x += p.y * tilt.x * 0.03;
    p.y += p.x * tilt.y * 0.03;
    float2 bgUv = center + p / (1.0 + 0.08 * (d - 0.5));
    bgUv += parallax * (d - 0.5) * 0.7;
    // Use one transform for the entire foreground, including its soft silhouette.
    // Depth discontinuities at hair/limbs must not pull adjacent edge pixels apart.
    float dFg = clamp(subjectDepth, 0.0, 1.0);
    float2 fgUv = center + p / (1.0 + 0.14 * (dFg - 0.5));
    fgUv += parallax * (dFg - 0.5) * 1.5;
    half m = half(clamp(float(msk.eval(fgUv).r), 0.0, 1.0));
    half4 foreground = img.eval(fgUv);
    // Bitmap shaders return premultiplied colours. The mask already carries the PNG alpha;
    // multiplying it into premultiplied RGB again darkens semi-transparent edges.
    foreground = half4(foreground.rgb / max(foreground.a, half(0.0001)), 1.0);
    return mix(bg.eval(bgUv), foreground, m);
}
"""

@Stable
class SpatialTiltState {
    var tilt by mutableStateOf(Offset.Zero)
    var hasSensor by mutableStateOf(false)
    var resetSignal by mutableIntStateOf(0)

    fun reset() {
        resetSignal++
    }
}

/**
 * Tracks device rotation relative to the pose the preview was opened in. The delta is projected
 * on the screen plane and smoothed so the parallax follows the gyroscope without jitter.
 */
@Composable
fun rememberSpatialTilt(enabled: Boolean = true): SpatialTiltState {
    val context = LocalContext.current
    val state = remember { SpatialTiltState() }
    val resetSignal = state.resetSignal
    DisposableEffect(context, enabled, resetSignal) {
        if (!enabled) {
            state.hasSensor = false
            return@DisposableEffect onDispose { }
        }
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: manager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        if (manager == null || sensor == null) {
            state.hasSensor = false
            return@DisposableEffect onDispose { }
        }
        state.hasSensor = true
        val base = FloatArray(4)
        var hasBase = false
        var smoothX = 0f
        var smoothY = 0f
        val current = FloatArray(4)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getQuaternionFromVector(current, event.values)
                if (!hasBase) {
                    current.copyInto(base)
                    hasBase = true
                    return
                }
                val qw = base[0]
                val qx = -base[1]
                val qy = -base[2]
                val qz = -base[3]
                val w = (qw * current[0] - qx * current[1] - qy * current[2] - qz * current[3]).coerceIn(-1f, 1f)
                val x = qw * current[1] + qx * current[0] + qy * current[3] - qz * current[2]
                val y = qw * current[2] - qx * current[3] + qy * current[0] + qz * current[1]
                val angle = 2f * acos(w)
                val sinHalf = sqrt((1f - w * w).coerceAtLeast(0f))
                val axisY = if (sinHalf > 1e-4f) y / sinHalf else 0f
                val axisX = if (sinHalf > 1e-4f) x / sinHalf else 0f
                var dx = angle * axisY
                var dy = angle * axisX
                when (context.display?.rotation ?: Surface.ROTATION_0) {
                    Surface.ROTATION_90 -> {
                        val oldDx = dx
                        dx = angle * axisX
                        dy = -oldDx
                    }
                    Surface.ROTATION_180 -> {
                        dx = -dx
                        dy = -dy
                    }
                    Surface.ROTATION_270 -> {
                        val oldDx = dx
                        dx = -angle * axisX
                        dy = oldDx
                    }
                }
                val targetX = (dx / MAX_TILT_ANGLE).coerceIn(-1f, 1f)
                val targetY = (dy / MAX_TILT_ANGLE).coerceIn(-1f, 1f)
                smoothX += (targetX - smoothX) * .16f
                smoothY += (targetY - smoothY) * .16f
                state.tilt = Offset(smoothX, smoothY)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        onDispose {
            manager.unregisterListener(listener)
            state.tilt = Offset.Zero
        }
    }
    return state
}

private fun depthShaderSupported(shader: String = SPATIAL_SHADER): Boolean =
    runCatching { RuntimeShader(shader) }.isSuccess

/**
 * Draws the source photo warped by the depth map. The shader path samples both textures on the
 * GPU; a plain translated image is used if RuntimeShader is unavailable.
 */
@Composable
fun SpatialDepthCanvas(
    image: Bitmap,
    depth: Bitmap,
    tiltProvider: () -> Offset,
    strength: Float,
    modifier: Modifier = Modifier,
    tilt3d: Boolean = true,
) {
    val supported = remember { depthShaderSupported() }
    if (!supported) {
        Image(
            bitmap = image.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            colorFilter = null,
            modifier = modifier
                .clipToBounds()
                .graphicsLayer {
                    val tilt = tiltProvider()
                    val shift = min(size.width, size.height) * .035f * strength
                    translationX = tilt.x * shift
                    translationY = tilt.y * shift
                    scaleX = 1.08f
                    scaleY = 1.08f
                }
                .fillMaxSize(),
        )
        return
    }
    val drawModifier = Modifier
        .clipToBounds()
        .graphicsLayer {
            // A subtle 3D tilt keeps the edges covered by the overscan in the shader.
            if (tilt3d) {
                val tilt = tiltProvider()
                rotationX = -tilt.y * 2.6f
                rotationY = tilt.x * 2.6f
            }
        }
        .drawWithCache {
            val imageShader = BitmapShader(image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val depthShader = BitmapShader(depth, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val shader = RuntimeShader(SPATIAL_SHADER)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            val matrix = Matrix()
            onDrawBehind {
                val width = size.width
                val height = size.height
                if (width <= 0f || height <= 0f) return@onDrawBehind
                val imageWidth = image.width.toFloat().coerceAtLeast(1f)
                val imageHeight = image.height.toFloat().coerceAtLeast(1f)
                val cover = max(width / imageWidth, height / imageHeight)
                val overscan = 1.1f
                val drawWidth = imageWidth * cover * overscan
                val drawHeight = imageHeight * cover * overscan
                val left = (width - drawWidth) / 2f
                val top = (height - drawHeight) / 2f
                val tilt = tiltProvider()
                val shift = min(width, height) * .05f * strength
                matrix.reset()
                matrix.setScale(drawWidth / imageWidth, drawHeight / imageHeight)
                imageShader.setLocalMatrix(matrix)
                depthShader.setLocalMatrix(matrix)
                shader.setFloatUniform("size", drawWidth, drawHeight)
                shader.setFloatUniform("parallax", tilt.x * shift, tilt.y * shift)
                shader.setFloatUniform("warp", .2f)
                shader.setFloatUniform("tilt", tilt.x, tilt.y)
                shader.setInputShader("img", imageShader)
                shader.setInputShader("dep", depthShader)
                paint.shader = shader
                val canvas = drawContext.canvas.nativeCanvas
                canvas.save()
                canvas.clipRect(0f, 0f, width, height)
                canvas.translate(left, top)
                canvas.drawRect(0f, 0f, drawWidth, drawHeight, paint)
                canvas.restore()
            }
        }
    androidx.compose.foundation.layout.Box(modifier.then(drawModifier))
}

/** Pure depth-of-field: the frame is only scaled by depth, no positional parallax. */
@Composable
fun BokehDepthCanvas(
    image: Bitmap,
    depth: Bitmap,
    modifier: Modifier = Modifier,
) {
    val supported = remember { depthShaderSupported(BOKEH_SHADER) }
    if (!supported) {
        Image(
            bitmap = image.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.fillMaxSize(),
        )
        return
    }
    val drawModifier = Modifier
        .clipToBounds()
        .drawWithCache {
            val imageShader = BitmapShader(image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val depthShader = BitmapShader(depth, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val shader = RuntimeShader(BOKEH_SHADER)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            val matrix = Matrix()
            onDrawBehind {
                val width = size.width
                val height = size.height
                if (width <= 0f || height <= 0f) return@onDrawBehind
                val imageWidth = image.width.toFloat().coerceAtLeast(1f)
                val imageHeight = image.height.toFloat().coerceAtLeast(1f)
                val cover = max(width / imageWidth, height / imageHeight)
                val overscan = 1.12f
                val drawWidth = imageWidth * cover * overscan
                val drawHeight = imageHeight * cover * overscan
                val left = (width - drawWidth) / 2f
                val top = (height - drawHeight) / 2f
                matrix.reset()
                matrix.setScale(drawWidth / imageWidth, drawHeight / imageHeight)
                imageShader.setLocalMatrix(matrix)
                matrix.setScale(
                    drawWidth / depth.width.toFloat().coerceAtLeast(1f),
                    drawHeight / depth.height.toFloat().coerceAtLeast(1f),
                )
                depthShader.setLocalMatrix(matrix)
                shader.setFloatUniform("size", drawWidth, drawHeight)
                shader.setFloatUniform("warp", .34f)
                shader.setInputShader("img", imageShader)
                shader.setInputShader("dep", depthShader)
                paint.shader = shader
                val canvas = drawContext.canvas.nativeCanvas
                canvas.save()
                canvas.clipRect(0f, 0f, width, height)
                canvas.translate(left, top)
                canvas.drawRect(0f, 0f, drawWidth, drawHeight, paint)
                canvas.restore()
            }
        }
    androidx.compose.foundation.layout.Box(modifier.then(drawModifier))
}

/**
 * Layered renderer: the background plate moves with a weak depth parallax while the subject is
 * composited back with a clamped depth around its mean depth. This keeps the subject coherent and
 * prevents depth discontinuities on its silhouette.
 */
@Composable
fun LayeredSpatialCanvas(
    image: Bitmap,
    background: Bitmap,
    backgroundDepth: Bitmap,
    mask: Bitmap,
    subjectDepth: Float,
    tiltProvider: () -> Offset,
    strength: Float,
    modifier: Modifier = Modifier,
) {
    val supported = remember { depthShaderSupported(LAYERED_SHADER) }
    if (!supported) {
        Image(
            bitmap = image.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.fillMaxSize(),
        )
        return
    }
    val drawModifier = Modifier
        .clipToBounds()
        .graphicsLayer {
            val tilt = tiltProvider()
            rotationX = -tilt.y * 2.2f
            rotationY = tilt.x * 2.2f
        }
        .drawWithCache {
            val imageShader = BitmapShader(image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val backgroundShader = BitmapShader(background, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val depthShader = BitmapShader(backgroundDepth, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val maskShader = BitmapShader(mask, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val shader = RuntimeShader(LAYERED_SHADER)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            val matrix = Matrix()
            onDrawBehind {
                val width = size.width
                val height = size.height
                if (width <= 0f || height <= 0f) return@onDrawBehind
                val imageWidth = image.width.toFloat().coerceAtLeast(1f)
                val imageHeight = image.height.toFloat().coerceAtLeast(1f)
                val cover = max(width / imageWidth, height / imageHeight)
                val overscan = 1.14f
                val drawWidth = imageWidth * cover * overscan
                val drawHeight = imageHeight * cover * overscan
                val left = (width - drawWidth) / 2f
                val top = (height - drawHeight) / 2f
                val tilt = tiltProvider()
                val shift = min(width, height) * .05f * strength
                matrix.reset()
                matrix.setScale(drawWidth / imageWidth, drawHeight / imageHeight)
                imageShader.setLocalMatrix(matrix)
                matrix.setScale(
                    drawWidth / background.width.toFloat().coerceAtLeast(1f),
                    drawHeight / background.height.toFloat().coerceAtLeast(1f),
                )
                backgroundShader.setLocalMatrix(matrix)
                depthShader.setLocalMatrix(matrix)
                maskShader.setLocalMatrix(matrix)
                shader.setFloatUniform("size", drawWidth, drawHeight)
                shader.setFloatUniform("parallax", tilt.x * shift, tilt.y * shift)
                shader.setFloatUniform("tilt", tilt.x, tilt.y)
                shader.setFloatUniform("subjectDepth", subjectDepth.coerceIn(.05f, .98f))
                shader.setInputShader("img", imageShader)
                shader.setInputShader("bg", backgroundShader)
                shader.setInputShader("dep", depthShader)
                shader.setInputShader("msk", maskShader)
                paint.shader = shader
                val canvas = drawContext.canvas.nativeCanvas
                canvas.save()
                canvas.clipRect(0f, 0f, width, height)
                canvas.translate(left, top)
                canvas.drawRect(0f, 0f, drawWidth, drawHeight, paint)
                canvas.restore()
            }
        }
    androidx.compose.foundation.layout.Box(modifier.then(drawModifier))
}
