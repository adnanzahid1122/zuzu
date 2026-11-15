package com.example.zuzuvpn.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.example.zuzuvpn.R
import com.example.zuzuvpn.ui.theme.ZuzuColors

/** Zuzu's expression. Mapped from the connection phase by the home screen. */
enum class MascotMood { Sleepy, Alert, Happy }

/**
 * PLACEHOLDER mascot: Zuzu the rabbit, drawn in code with soft gradients so the app has an
 * original character before final 3D artwork exists. Replace the Canvas with the final asset
 * (for example a Lottie or WebP sequence) without changing callers.
 *
 * Original design cues: round head, tall upright ears, and an electric-blue scarf with a "Z" mark.
 */
@Composable
fun RabbitMascot(
    mood: MascotMood,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "mascot")
    val bob by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (mood == MascotMood.Alert) 420 else 1600,
                easing = FastOutSlowInEasing,
            ),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "bob",
    )
    val description = stringResource(R.string.mascot_cd)
    Canvas(
        modifier = modifier
            .aspectRatio(1f)
            .semantics { contentDescription = description },
    ) {
        drawRabbit(mood = mood, bob = bob)
    }
}

private fun DrawScope.drawRabbit(mood: MascotMood, bob: Float) {
    val w = size.minDimension
    val cx = size.width / 2f
    val lift = bob * when (mood) {
        MascotMood.Sleepy -> w * 0.012f
        MascotMood.Alert -> w * 0.04f
        MascotMood.Happy -> w * 0.025f
    }
    val headR = w * 0.24f
    val headC = Offset(cx, size.height * 0.58f - lift)

    // Body (drawn first, partly hidden behind the head)
    val bodyW = w * 0.46f
    val bodyH = w * 0.26f
    val bodyTop = headC.y + headR * 0.55f
    drawOval(
        brush = Brush.radialGradient(
            colors = listOf(ZuzuColors.Fur, ZuzuColors.FurShade),
            center = Offset(cx - bodyW * 0.15f, bodyTop + bodyH * 0.3f),
            radius = bodyW * 0.8f,
        ),
        topLeft = Offset(cx - bodyW / 2f, bodyTop),
        size = Size(bodyW, bodyH),
    )

    // Ears
    val earTilt = when (mood) {
        MascotMood.Sleepy -> 24f
        MascotMood.Alert -> 6f
        MascotMood.Happy -> 12f
    }
    val earW = headR * 0.42f
    val earH = headR * 1.25f
    for (side in listOf(-1f, 1f)) {
        val base = Offset(headC.x + side * headR * 0.42f, headC.y - headR * 0.72f)
        rotate(degrees = side * earTilt, pivot = base) {
            drawOval(
                brush = Brush.verticalGradient(
                    colors = listOf(ZuzuColors.Fur, ZuzuColors.FurShade),
                    startY = base.y - earH,
                    endY = base.y,
                ),
                topLeft = Offset(base.x - earW / 2f, base.y - earH),
                size = Size(earW, earH + earW * 0.3f),
            )
            drawOval(
                color = ZuzuColors.EarInner,
                topLeft = Offset(base.x - earW * 0.22f, base.y - earH * 0.85f),
                size = Size(earW * 0.44f, earH * 0.75f),
            )
        }
    }

    // Head with soft top-left light for a rounded, 3D feel
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(ZuzuColors.Fur, ZuzuColors.FurShade),
            center = headC + Offset(-headR * 0.35f, -headR * 0.4f),
            radius = headR * 1.6f,
        ),
        radius = headR,
        center = headC,
    )
    // Muzzle highlight
    drawOval(
        color = Color.White.copy(alpha = 0.7f),
        topLeft = Offset(cx - headR * 0.45f, headC.y + headR * 0.05f),
        size = Size(headR * 0.9f, headR * 0.55f),
    )
    // Cheeks
    for (side in listOf(-1f, 1f)) {
        drawCircle(
            color = ZuzuColors.EarInner.copy(alpha = 0.45f),
            radius = headR * 0.14f,
            center = Offset(cx + side * headR * 0.56f, headC.y + headR * 0.22f),
        )
    }

    // Eyes
    val eyeW = headR * 0.2f
    val eyeH = headR * 0.26f
    val lineStroke = Stroke(width = headR * 0.06f, cap = StrokeCap.Round)
    for (side in listOf(-1f, 1f)) {
        val c = Offset(cx + side * headR * 0.36f, headC.y - headR * 0.05f)
        when (mood) {
            MascotMood.Alert -> {
                drawOval(
                    color = ZuzuColors.Ink,
                    topLeft = Offset(c.x - eyeW / 2f, c.y - eyeH / 2f),
                    size = Size(eyeW, eyeH),
                )
                drawCircle(
                    color = Color.White,
                    radius = eyeW * 0.22f,
                    center = Offset(c.x - eyeW * 0.15f, c.y - eyeH * 0.18f),
                )
            }
            MascotMood.Happy -> drawArc(
                color = ZuzuColors.Ink,
                startAngle = 200f,
                sweepAngle = 140f,
                useCenter = false,
                topLeft = Offset(c.x - eyeW * 0.7f, c.y - eyeH * 0.2f),
                size = Size(eyeW * 1.4f, eyeH * 0.9f),
                style = lineStroke,
            )
            MascotMood.Sleepy -> drawArc(
                color = ZuzuColors.Ink,
                startAngle = 20f,
                sweepAngle = 140f,
                useCenter = false,
                topLeft = Offset(c.x - eyeW * 0.7f, c.y - eyeH * 0.6f),
                size = Size(eyeW * 1.4f, eyeH * 0.9f),
                style = lineStroke,
            )
        }
    }

    // Nose
    drawOval(
        color = ZuzuColors.Nose,
        topLeft = Offset(cx - headR * 0.09f, headC.y + headR * 0.18f),
        size = Size(headR * 0.18f, headR * 0.12f),
    )

    // Mouth
    val mouthY = headC.y + headR * 0.3f
    when (mood) {
        MascotMood.Happy -> drawArc(
            color = ZuzuColors.Ink,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = true,
            topLeft = Offset(cx - headR * 0.16f, mouthY),
            size = Size(headR * 0.32f, headR * 0.26f),
        )
        MascotMood.Alert -> {
            val small = Stroke(width = headR * 0.045f, cap = StrokeCap.Round)
            drawArc(ZuzuColors.Ink, 0f, 180f, false, Offset(cx - headR * 0.14f, mouthY - headR * 0.06f), Size(headR * 0.14f, headR * 0.12f), style = small)
            drawArc(ZuzuColors.Ink, 0f, 180f, false, Offset(cx, mouthY - headR * 0.06f), Size(headR * 0.14f, headR * 0.12f), style = small)
        }
        MascotMood.Sleepy -> drawLine(
            color = ZuzuColors.Ink,
            start = Offset(cx - headR * 0.06f, mouthY + headR * 0.02f),
            end = Offset(cx + headR * 0.06f, mouthY + headR * 0.02f),
            strokeWidth = headR * 0.045f,
            cap = StrokeCap.Round,
        )
    }

    // Electric-blue scarf with a "Z" mark (Zuzu's signature)
    val scarfW = headR * 1.4f
    val scarfH = headR * 0.26f
    val scarfTop = headC.y + headR * 0.86f
    drawRoundRect(
        brush = Brush.horizontalGradient(
            colors = listOf(ZuzuColors.ElectricBlue, ZuzuColors.Cyan),
            startX = cx - scarfW / 2f,
            endX = cx + scarfW / 2f,
        ),
        topLeft = Offset(cx - scarfW / 2f, scarfTop),
        size = Size(scarfW, scarfH),
        cornerRadius = CornerRadius(scarfH / 2f, scarfH / 2f),
    )
    rotate(degrees = 14f, pivot = Offset(cx + headR * 0.38f, scarfTop + scarfH)) {
        drawRoundRect(
            color = ZuzuColors.ElectricBlue,
            topLeft = Offset(cx + headR * 0.3f, scarfTop + scarfH * 0.6f),
            size = Size(scarfH * 0.7f, scarfH * 1.3f),
            cornerRadius = CornerRadius(scarfH * 0.3f, scarfH * 0.3f),
        )
    }
    val zs = scarfH * 0.55f
    drawZ(
        topLeft = Offset(cx - zs / 2f, scarfTop + (scarfH - zs) / 2f),
        size = zs,
        color = Color.White,
        stroke = headR * 0.045f,
    )

    // Mood extras
    when (mood) {
        MascotMood.Sleepy -> {
            val a = 0.35f + 0.65f * bob
            drawZ(Offset(cx + headR * 0.95f, headC.y - headR * 1.15f), headR * 0.24f, ZuzuColors.ElectricBlueBright.copy(alpha = a), headR * 0.05f)
            drawZ(Offset(cx + headR * 1.3f, headC.y - headR * 1.5f), headR * 0.16f, ZuzuColors.ElectricBlueBright.copy(alpha = a * 0.7f), headR * 0.04f)
        }
        MascotMood.Happy -> {
            val a = 0.6f + 0.4f * bob
            drawSparkle(Offset(cx - headR * 1.15f, headC.y - headR * 0.6f), headR * 0.16f, ZuzuColors.Gold.copy(alpha = a))
            drawSparkle(Offset(cx + headR * 1.1f, headC.y - headR * 0.9f), headR * 0.12f, ZuzuColors.Cyan.copy(alpha = a))
        }
        MascotMood.Alert -> Unit
    }
}

private fun DrawScope.drawZ(topLeft: Offset, size: Float, color: Color, stroke: Float) {
    val path = Path().apply {
        moveTo(topLeft.x, topLeft.y)
        lineTo(topLeft.x + size, topLeft.y)
        lineTo(topLeft.x, topLeft.y + size)
        lineTo(topLeft.x + size, topLeft.y + size)
    }
    drawPath(path, color, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun DrawScope.drawSparkle(center: Offset, radius: Float, color: Color) {
    val path = Path().apply {
        moveTo(center.x, center.y - radius)
        quadraticTo(center.x, center.y, center.x + radius, center.y)
        quadraticTo(center.x, center.y, center.x, center.y + radius)
        quadraticTo(center.x, center.y, center.x - radius, center.y)
        quadraticTo(center.x, center.y, center.x, center.y - radius)
        close()
    }
    drawPath(path, color)
}
