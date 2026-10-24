package com.kmusic.apkhd.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kmusic.apkhd.ui.theme.moyinColors
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * 黑胶转盘（网易云那种）。
 *
 * 几何只有一套，盘永远居中：
 *   - 盘心 = 容器中心，盘径 = 0.86 × 最短边（唱针不再占位，不用把盘推到左边）；
 *   - 唱针按盘心坐标定位：支点在盘外右上方 1.38R / -48°，针尖落 0.86R 的外圈沟。
 *   旧版把盘放 CenterStart 给唱针让位，盘心比屏幕中线偏左 8%，切歌词就像跳了一下。
 *
 * 转动：逐帧累加角度（withFrameNanos），不靠 animate 循环，每圈衔接不丢帧；
 * 暂停时角速度 700ms 线性收到 0（唱片惯性）。旋转只写 graphicsLayer，不触发重组。
 */
@Composable
fun VinylCover(
    coverUrl: String?,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    /** 小于 150dp 时不画唱针：针臂会糊成一坨黑线，反而脏 */
    showTonearm: Boolean = true,
    /** 交给 CoverArt 的圆角（当前是正圆裁切，一般传 0） */
    coverCornerRadius: Dp = 0.dp
) {
    val c = moyinColors()
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth, maxHeight)
        val withArm = showTonearm && side >= 150.dp
        // 盘径 0.76：参考图的 1.58R 支点在方形容器里只能配 0.62 的盘，盘太小。
        // 折中方案（用户选 A）：盘放到 0.76，支点收到 1.22R —— 支点仍在容器上沿内侧
        // （845px 容器 → 支点 y≈30px），臂角 52°（参考图 65°，略平但形态不变）。
        val disc = if (withArm) side * 0.76f else side * 0.96f

        // ── 转动 ─────────────────────────────────────────────
        var angle by remember { mutableFloatStateOf(0f) }
        // 角速度倍率：1 = 正常转，0 = 停。用倍率而不是直接起停，才有慢慢停下的惯性
        var omega by remember { mutableFloatStateOf(if (isPlaying) 1f else 0f) }
        LaunchedEffect(isPlaying) {
            animate(
                initialValue = omega,
                targetValue = if (isPlaying) 1f else 0f,
                animationSpec = tween(700, easing = LinearEasing)
            ) { v, _ -> omega = v }
        }
        LaunchedEffect(Unit) {
            var last = 0L
            while (true) {
                withFrameNanos { now ->
                    if (last != 0L && omega > 0.001f) {
                        // 20 秒一圈，和网易云一致
                        val dt = (now - last) / 1_000_000_000f
                        angle = (angle + dt * (360f / 20f) * omega) % 360f
                    }
                    last = now
                }
            }
        }

        // ── 盘下淡阴影 ───────────────────────────────────────
        // 单独一层、不跟盘转：圆形渐变一旦随 rotationZ 转起来，影子的偏移会绕着盘晃，
        // 反而像盘在抖。这里只跟容器，盘转它不转。
        Box(
            Modifier
                .size(disc * 1.16f)
                .align(Alignment.Center)
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val cc = Offset(size.width / 2f, size.height / 2f)
                val R = size.minDimension / 2f
                // 环境影：大、淡、往右下压一点，给出「悬在纸上」的层次
                val ambientCenter = Offset(cc.x + R * 0.012f, cc.y + R * 0.030f)
                drawCircle(
                    brush = Brush.radialGradient(
                        0.00f to Color.Black.copy(alpha = 0.16f),
                        0.55f to Color.Black.copy(alpha = 0.12f),
                        0.78f to Color.Black.copy(alpha = 0.05f),
                        1.00f to Color.Transparent,
                        center = ambientCenter,
                        radius = R
                    ),
                    radius = R,
                    center = ambientCenter
                )
                // 接触影：贴着盘缘的一圈更深的窄影，让盘「落」在背景上。
                // 必须用渐变而不是描边——描边在浅色底上是一条均匀灰环，看着像给盘加了个边框。
                drawCircle(
                    brush = Brush.radialGradient(
                        0.00f to Color.Transparent,
                        0.855f to Color.Transparent,
                        0.905f to Color.Black.copy(alpha = 0.11f),
                        0.960f to Color.Black.copy(alpha = 0.05f),
                        1.00f to Color.Transparent,
                        center = Offset(cc.x, cc.y + R * 0.012f),
                        radius = R
                    ),
                    radius = R,
                    center = Offset(cc.x, cc.y + R * 0.012f)
                )
            }
        }

        // ── 盘面 ─────────────────────────────────────────────
        Box(
            Modifier
                .size(disc)
                .align(Alignment.Center)
                .graphicsLayer { rotationZ = angle }
        ) {
            // 黑胶底色：中心偏亮、边缘压黑，和真唱片打光一致
            Canvas(Modifier.fillMaxSize()) {
                val r = size.minDimension / 2f
                val center = Offset(size.width / 2f, size.height / 2f)
                drawCircle(
                    brush = Brush.radialGradient(
                        // 略带蓝调的黑：藏蓝主题下纯中性黑会发褐，和背景脱层
                        colors = listOf(Color(0xFF1E2533), Color(0xFF111722), Color(0xFF080B12)),
                        center = center,
                        radius = r
                    ),
                    radius = r,
                    center = center
                )
                // 沟槽：等距细环，靠透明度做出密纹感，不画满（太多会变成纯灰）
                var rr = r * 0.34f
                var i = 0
                while (rr < r * 0.985f) {
                    drawCircle(
                        color = Color.White.copy(alpha = if (i % 3 == 0) 0.055f else 0.03f),
                        radius = rr,
                        center = center,
                        style = Stroke(width = 0.6.dp.toPx())
                    )
                    rr += r * 0.022f
                    i++
                }
                // 外缘高光：左上到右下一道，让圆盘有厚度
                drawArc(
                    brush = Brush.sweepGradient(
                        listOf(
                            Color.White.copy(alpha = 0.30f), Color.Transparent,
                            Color.Transparent, Color.White.copy(alpha = 0.10f)
                        )
                    ),
                    startAngle = 200f, sweepAngle = 140f, useCenter = false,
                    topLeft = Offset(center.x - r, center.y - r),
                    size = Size(2 * r, 2 * r),
                    style = Stroke(width = 1.2.dp.toPx())
                )
            }

            // 中间圆形封面（62%）：跟着盘一起转
            Box(
                Modifier
                    .fillMaxSize(0.62f)
                    .align(Alignment.Center)
                    .clip(CircleShape)
            ) {
                CoverArt(
                    coverUrl = coverUrl,
                    modifier = Modifier.fillMaxSize(),
                    cornerRadius = coverCornerRadius
                )
                // 封面内侧压一圈暗边：看起来是贴在盘上而不是浮着
                Canvas(Modifier.fillMaxSize()) {
                    drawCircle(
                        color = Color.Black.copy(alpha = 0.28f),
                        radius = size.minDimension / 2f - 0.5.dp.toPx(),
                        style = Stroke(width = 3.dp.toPx())
                    )
                }
            }

            // 中心孔 + 轴心
            Box(
                Modifier
                    .size(disc * 0.045f)
                    .align(Alignment.Center)
                    .clip(CircleShape)
                    .background(if (c.isDark) Color(0xFF0B0B0E) else Color(0xFF141418))
            )
            Box(
                Modifier
                    .size(disc * 0.016f)
                    .align(Alignment.Center)
                    .clip(CircleShape)
                    .background(Color(0xFF8E8F9A).copy(alpha = 0.9f))
            )
        }

        // ── 唱针 ─────────────────────────────────────────────
        if (withArm) {
            Tonearm(
                playing = isPlaying,
                // 必须传实际盘径比例：写死 0.86 会让臂按更大的盘算几何，支点直接被推到画面外
                discDiameterRatio = disc / side,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/**
 * 唱针：按参考图 1:1 复刻的细金属臂。
 *
 * 几何全部从参考图量出来，以盘半径 R 为单位（盘心为原点、屏幕 y 向下）：
 *   - 支点 P = (0, -1.58R)：正上方，不是斜插进画面；
 *   - 臂杆 P → K，K = (0.35R, -0.78R)，即 65° 下斜；
 *   - K 处拐 22° 的 offset，唱头 K → T，T = (0.53R, -0.61R)（约 1 点半，落在 0.80R 沟槽）；
 *   - 臂宽 ≈ 0.05R（参考图里 11px / 半径 217px），唱头略粗。
 * 抬臂/落臂绕 P 转 5°，线性 420ms，不回弹。
 */
@Composable
private fun Tonearm(
    playing: Boolean,
    discDiameterRatio: Float,
    modifier: Modifier = Modifier
) {
    var arm by remember { mutableFloatStateOf(if (playing) 1f else 0f) }
    LaunchedEffect(playing) {
        animate(
            initialValue = arm,
            targetValue = if (playing) 1f else 0f,
            animationSpec = tween(420, easing = LinearEasing)
        ) { v, _ -> arm = v }
    }

    Canvas(modifier) {
        val s = size.minDimension
        val c = Offset(size.width / 2f, size.height / 2f)
        val r = s * discDiameterRatio / 2f

        // 支点：盘心正上方 1.22R（方案 A；1.58R 会配出 0.62 的小盘）
        val pivot = Offset(c.x, c.y - r * 1.22f)
        val knee0 = Offset(c.x + r * 0.35f, c.y - r * 0.78f)
        val tip0 = Offset(c.x + r * 0.53f, c.y - r * 0.61f)

        // 抬臂 = 绕支点往盘外转 5°（屏幕坐标下逆时针）
        val lift = (1f - arm) * 5f
        fun swing(p: Offset): Offset {
            if (lift == 0f) return p
            val rad = Math.toRadians(lift.toDouble())
            val cs = cos(rad).toFloat(); val sn = sin(rad).toFloat()
            val dx = p.x - pivot.x; val dy = p.y - pivot.y
            return Offset(pivot.x + (dx * cs + dy * sn), pivot.y + (-dx * sn + dy * cs))
        }
        val knee = swing(knee0)
        val tip = swing(tip0)

        val armPath = Path().apply {
            moveTo(pivot.x, pivot.y)
            lineTo(knee.x, knee.y)
        }
        val headPath = Path().apply {
            moveTo(knee.x, knee.y)
            lineTo(tip.x, tip.y)
        }
        val headDir = run {
            val dx = tip.x - knee.x; val dy = tip.y - knee.y
            val len = kotlin.math.hypot(dx, dy).coerceAtLeast(1f)
            Offset(dx / len, dy / len)
        }

        // 线宽全部以 R 为单位：参考图里臂宽 11px / R=217 → 0.05R
        // 阴影：整根臂一起投
        translate(r * 0.020f, r * 0.026f) {
            drawPath(armPath, Color.Black.copy(alpha = 0.10f), style = Stroke(r * 0.105f, cap = StrokeCap.Round))
            drawPath(headPath, Color.Black.copy(alpha = 0.10f), style = Stroke(r * 0.130f, cap = StrokeCap.Round))
            drawPath(armPath, Color.Black.copy(alpha = 0.14f), style = Stroke(r * 0.052f, cap = StrokeCap.Round))
        }
        // 针尖接触影
        drawCircle(Color.Black.copy(alpha = 0.12f), radius = r * 0.040f, center = Offset(tip.x + r * 0.012f, tip.y + r * 0.016f))

        // 臂杆：深底 → 银面 → 高光
        drawPath(armPath, Color(0xFF5A5F6B), style = Stroke(r * 0.058f, cap = StrokeCap.Round))
        drawPath(armPath, Color(0xFFE2E6EE), style = Stroke(r * 0.042f, cap = StrokeCap.Round))
        drawPath(armPath, Color.White.copy(alpha = 0.55f), style = Stroke(r * 0.011f, cap = StrokeCap.Round))

        // 唱头：略粗的深色壳体 + 银边 + 针尖
        drawPath(headPath, Color(0xFF2E3239), style = Stroke(r * 0.088f, cap = StrokeCap.Round))
        drawPath(headPath, Color(0xFF474C57), style = Stroke(r * 0.072f, cap = StrokeCap.Round))
        drawPath(headPath, Color.White.copy(alpha = 0.18f), style = Stroke(r * 0.016f, cap = StrokeCap.Round))
        val needle = Offset(tip.x + headDir.x * r * 0.026f, tip.y + headDir.y * r * 0.026f)
        drawLine(Color(0xFFC9CFDA), start = tip, end = needle, strokeWidth = r * 0.014f, cap = StrokeCap.Round)
        drawCircle(Color(0xFFF4F6FB), radius = r * 0.010f, center = needle)

        // 支点：横向配重 + 小圆底座
        val back = Offset(pivot.x - r * 0.105f, pivot.y - r * 0.018f)
        drawLine(Color(0xFF3A3E47), start = pivot, end = back, strokeWidth = r * 0.075f, cap = StrokeCap.Round)
        drawLine(Color(0xFFB9BFCC), start = pivot, end = back, strokeWidth = r * 0.055f, cap = StrokeCap.Round)
        drawCircle(Color(0xFF3A3E47), radius = r * 0.048f, center = back)
        drawCircle(Color(0xFFD6DAE4), radius = r * 0.036f, center = back, style = Stroke(r * 0.010f))

        drawCircle(Color(0xFF23272E), radius = r * 0.062f, center = pivot)
        drawCircle(Color(0xFFB4BAC7), radius = r * 0.050f, center = pivot)
        drawCircle(Color(0xFF6C727F), radius = r * 0.028f, center = pivot)
        drawCircle(Color.White.copy(alpha = 0.85f), radius = r * 0.010f, center = Offset(pivot.x - r * 0.014f, pivot.y - r * 0.016f))
    }
}
