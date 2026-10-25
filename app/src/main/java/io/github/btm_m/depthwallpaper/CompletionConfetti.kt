// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.btm_m.depthwallpaper

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import kotlin.math.sin
import kotlin.random.Random

private data class ConfettiPiece(
    val xFraction: Float,
    val delay: Float,
    val drift: Float,
    val frequency: Float,
    val phase: Float,
    val width: Float,
    val height: Float,
    val rotation: Float,
    val rotationTurns: Float,
    val color: Color,
    val round: Boolean,
    val restingProgress: Float?,
)

@Composable
internal fun CompletionConfetti(
    playKey: Int,
    modifier: Modifier = Modifier,
    pieceCount: Int = 54,
    restingCountRange: IntRange = 1..3,
    durationMillis: Int = 3_800,
) {
    val progress = remember { Animatable(0f) }
    val pieces = remember(playKey) {
        val random = Random(System.nanoTime())
        val count = pieceCount.coerceAtLeast(1)
        val restingCount = random.nextInt(
            restingCountRange.first.coerceAtLeast(0),
            restingCountRange.last.coerceAtLeast(restingCountRange.first) + 1,
        ).coerceAtMost(count)
        val palette = listOf(
            Color(0xFFFF3B30),
            Color(0xFFFFCC00),
            Color(0xFF34C759),
            Color(0xFF00A7FF),
            Color(0xFF5856D6),
            Color(0xFFFF2D8D),
        )
        List(count) { index ->
            ConfettiPiece(
                xFraction = random.nextFloat(),
                delay = random.nextFloat() * .3f,
                drift = 18f + random.nextFloat() * 54f,
                frequency = 1.5f + random.nextFloat() * 2.5f,
                phase = random.nextFloat() * 6.28318f,
                width = 5f + random.nextFloat() * 6f,
                height = 9f + random.nextFloat() * 9f,
                rotation = random.nextFloat() * 360f,
                rotationTurns = 1.5f + random.nextFloat() * 3.5f,
                color = palette[random.nextInt(palette.size)],
                round = random.nextInt(5) == 0,
                restingProgress = if (index < restingCount) .52f + random.nextFloat() * .2f else null,
            )
        }
    }
    LaunchedEffect(playKey) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(durationMillis, easing = LinearEasing))
    }
    Canvas(modifier) {
        val timeline = progress.value
        val overlayAlpha = when {
            timeline < .08f -> timeline / .08f
            timeline > .82f -> ((1f - timeline) / .18f).coerceIn(0f, 1f)
            else -> 1f
        }
        pieces.forEach { piece ->
            val localProgress = ((timeline - piece.delay) / (1f - piece.delay)).coerceIn(0f, 1f)
            if (localProgress <= 0f || overlayAlpha <= 0f) return@forEach
            val fallProgress = piece.restingProgress?.let { localProgress.coerceAtMost(it) } ?: localProgress
            val x = size.width * piece.xFraction +
                sin(fallProgress * piece.frequency * 6.28318f + piece.phase) * piece.drift.dp.toPx()
            val pieceHeight = piece.height.dp.toPx()
            val y = -pieceHeight + (size.height + pieceHeight * 2f) * fallProgress
            val alpha = overlayAlpha * (localProgress * 9f).coerceAtMost(1f)
            rotate(
                degrees = piece.rotation + localProgress * 360f * piece.rotationTurns,
                pivot = Offset(x, y),
            ) {
                if (piece.round) {
                    drawCircle(piece.color.copy(alpha = alpha), radius = piece.width.dp.toPx() * .58f, center = Offset(x, y))
                } else {
                    val width = piece.width.dp.toPx()
                    drawRect(
                        color = piece.color.copy(alpha = alpha),
                        topLeft = Offset(x - width / 2f, y - pieceHeight / 2f),
                        size = Size(width, pieceHeight),
                    )
                }
            }
        }
    }
}
