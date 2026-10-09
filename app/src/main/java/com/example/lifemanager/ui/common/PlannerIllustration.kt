package com.example.lifemanager.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.clearAndSetSemantics

/** Decorative, density-independent artwork. Business content always remains native text/UI. */
@Composable
fun PlannerIllustration(modifier: Modifier = Modifier) {
    Canvas(modifier.clearAndSetSemantics {}) {
        val factor = minOf(size.width, size.height) / 200f
        val ink = Color(0xFF203C30)
        val paper = Color(0xFFFFFCF0)
        val mint = Color(0xFF85B89A)
        val peach = Color(0xFFFFB69F)
        val yellow = Color(0xFFF6D878)
        // Fixed 200-unit artboard, centered in the caller's adaptive bounds.
        scale(factor, factor, pivot = Offset.Zero) {
            drawCircle(peach.copy(alpha = 0.85f), 65f, Offset(80f, 91f))
            drawOval(mint.copy(alpha = 0.35f), Offset(18f, 175f), Size(166f, 12f))
            rotate(8f, Offset(97f, 110f)) {
                drawRoundRect(mint.copy(alpha = 0.45f), Offset(49f, 46f), Size(102f, 133f), CornerRadius(11f))
                drawRoundRect(paper, Offset(43f, 40f), Size(102f, 133f), CornerRadius(11f))
                drawRoundRect(ink, Offset(43f, 40f), Size(102f, 133f), CornerRadius(11f), style = Stroke(3.5f))
                drawCircle(ink, 3f, Offset(79f, 72f))
                drawCircle(ink, 3f, Offset(108f, 72f))
                drawCircle(peach, 5f, Offset(68f, 82f))
                drawCircle(peach, 5f, Offset(119f, 82f))
                drawArc(ink, 0f, 180f, false, Offset(87f, 76f), Size(14f, 12f), style = Stroke(3f, cap = StrokeCap.Round))
                listOf(105f, 132f).forEach { y ->
                    drawRoundRect(ink, Offset(60f, y), Size(12f, 12f), CornerRadius(2f), style = Stroke(2.5f))
                    drawLine(mint, Offset(85f, y + 6f), Offset(127f, y + 6f), 4f, StrokeCap.Round)
                }
            }
            rotate(27f, Offset(158f, 131f)) {
                drawRoundRect(yellow, Offset(149f, 81f), Size(17f, 83f), CornerRadius(7f))
                drawRoundRect(ink, Offset(149f, 81f), Size(17f, 83f), CornerRadius(7f), style = Stroke(3f))
                drawLine(paper, Offset(154f, 101f), Offset(154f, 150f), 3f, StrokeCap.Round)
                val tip = Path().apply { moveTo(149f, 161f); lineTo(157.5f, 180f); lineTo(166f, 161f); close() }
                drawPath(tip, paper)
                drawPath(tip, ink, style = Stroke(3f))
                drawLine(ink, Offset(157.5f, 174f), Offset(157.5f, 180f), 3f, StrokeCap.Round)
            }
            drawLine(mint, Offset(165f, 34f), Offset(165f, 48f), 3.5f, StrokeCap.Round)
            drawLine(mint, Offset(158f, 41f), Offset(172f, 41f), 3.5f, StrokeCap.Round)
            drawLine(ink, Offset(27f, 51f), Offset(21f, 43f), 3f, StrokeCap.Round)
            drawLine(mint, Offset(39f, 37f), Offset(36f, 25f), 3f, StrokeCap.Round)
        }
    }
}
