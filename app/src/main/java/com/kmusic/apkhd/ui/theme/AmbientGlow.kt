package com.kmusic.apkhd.ui.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max

/**
 * 顶部氛围光 —— 从屏幕顶端往下洇开一层强调色，画在背景之上、内容之下。
 *
 * 老写法是「固定高度 Box + 半径按宽度算」（`340.wdp` 高的 Box，半径 `width * 0.95`）。
 * 高度是定值、半径却跟宽度走：屏越宽半径越大，光到盒子底边时还没衰减完，
 * 就被 `drawBehind` 按 Box 边界硬裁掉 —— 底边留下一条第 1~2px 宽的、上亮下暗的直线接缝。
 * 实测台阶（每通道）：手机竖屏 Δ≈2/255、手机横屏 Δ≈6/255、车机 1280dp 宽 Δ≈14/255，
 * 宽屏上肉眼非常明显。
 *
 * 现在改成**椭圆渐变**：
 * - 横向半轴按宽度铺开（保留「中间浓、两边淡」的观感，不会缩成中间一团光）；
 * - 纵向半轴压在光带高度之内，光带最后一行已经是 0；
 * - 末端色直接用 [bg]（不是 Transparent）—— 万一某个体积比例下还差一丝没收敛，
 *   画出来的也是「背景色 → 背景色」，看不出边。
 *
 * 宽屏（车机/平板）光带按 `max(baseHeight, 宽 × 0.30)` 拉长：1280dp 宽的屏上
 * 只给 340dp 高的光会显得很局促，拉长后更像「顶灯往下打」的环境光。
 *
 * @param tint 强调色（通常 `c.accent`）
 * @param bg 背景色（必须与所在页面底色一致，否则末端会显出另一种颜色）
 * @param alpha 顶端中心的最大不透明度；<=0 时直接不画（浅色主题多数页面就是 0）
 * @param baseHeight 光带基准高度（传 `wdp` 值以便跟随缩放）
 */
fun Modifier.ambientGlow(
    tint: Color,
    bg: Color,
    alpha: Float,
    baseHeight: Dp = 340.dp
): Modifier = drawBehind {
    if (alpha <= 0f || size.width <= 0f || size.height <= 0f) return@drawBehind

    val h = max(baseHeight.toPx(), size.width * 0.30f).coerceAtMost(size.height)
    val rx = size.width * 0.95f
    val ry = h * 0.98f
    val k = ry / rx
    if (rx <= 0f || k <= 0f) return@drawBehind

    // 把画布纵向压 k 倍，圆就成了横长竖短的椭圆：
    // 横向仍按 rx 铺满（≈ 0.95 屏宽），纵向在光带底部（ry = 0.98h）收敛到 0。
    withTransform({ scale(1f, k, pivot = Offset(size.width * 0.5f, 0f)) }) {
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(tint.copy(alpha = alpha), bg),
                center = Offset(size.width * 0.5f, 0f),
                radius = rx
            ),
            // 压扁后要覆盖到 y = h，局部坐标就得画到 h / k
            size = Size(size.width, h / k)
        )
    }
}
