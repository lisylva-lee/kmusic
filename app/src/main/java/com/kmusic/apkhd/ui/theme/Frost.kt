package com.kmusic.apkhd.ui.theme

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape

/**
 * 毛玻璃 / 纤维质感。
 *
 * 为什么不用真模糊：Android 10（车机就是 10）上 Modifier.blur 对内容无效，
 * RenderEffect 又要 API 31。所以走"半透明面 + 纤维纹理 + 亮暗细边"的模拟路线：
 *   - 半透明：透出下层颜色，形成玻璃感；
 *   - 纤维：一张 96px 的程序化贴图（白噪点 + 斜丝）平铺，深色用白噪、浅色用黑噪；
 *   - 边：顶部 1px 亮边、底部 1px 暗边，做出厚度。
 * 贴图只生成一次并缓存，平铺用 ImageShader，不是每帧画几百个点。
 */
enum class FrostLevel(val label: String) {
    OFF("关"),
    LIGHT("轻"),
    STRONG("强");

    companion object {
        fun of(name: String?): FrostLevel = entries.firstOrNull { it.name == name } ?: LIGHT
    }
}

/** 由 MainActivity 注入；组件不用逐层传参 */
val LocalFrostLevel = staticCompositionLocalOf { FrostLevel.LIGHT }

private fun fiberBitmap(isDark: Boolean, level: FrostLevel): ImageBitmap {
    val size = 96
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    val paint = android.graphics.Paint().apply { isAntiAlias = true }
    val rnd = java.util.Random(20261021L)
    val tone = if (isDark) 255 else 0
    val strong = level == FrostLevel.STRONG

    // 噪点：像玻璃里的细颗粒
    val specks = if (strong) 300 else 170
    val maxAlpha = if (strong) 24 else 13
    repeat(specks) {
        paint.color = android.graphics.Color.argb(
            (1 + rnd.nextInt(maxAlpha)).coerceAtMost(255), tone, tone, tone
        )
        canvas.drawCircle(
            rnd.nextFloat() * size,
            rnd.nextFloat() * size,
            0.3f + rnd.nextFloat() * 0.8f,
            paint
        )
    }

    // 斜丝：玻璃纤维的方向感，透明度压到几乎只有侧看才可见
    val strands = if (strong) 16 else 9
    paint.strokeWidth = 0.7f
    repeat(strands) {
        paint.color = android.graphics.Color.argb(if (isDark) 9 else 7, tone, tone, tone)
        val y = rnd.nextFloat() * size
        canvas.drawLine(-size * 0.2f, y, size * 1.2f, y - size * 0.55f, paint)
    }

    return bmp.asImageBitmap()
}

/**
 * 玻璃面板。shape 决定圆角；alpha 是面板本身的不透明度（越透越像玻璃，也越考验文字对比度）。
 * 只该用在"结构面"（底栏 / 侧栏 / 搜索框 / 迷你条 / 弹层），不要套在整页内容上。
 */
@Composable
fun Modifier.frosted(
    shape: Shape = RoundedCornerShape(0.dp),
    level: FrostLevel = LocalFrostLevel.current,
    alpha: Float = if (level == FrostLevel.STRONG) 0.76f else 0.88f,
    /** 面板底色。null = 主题 s1；导航栏传 bg 可做到与页面严格同色 */
    base: Color? = null
): Modifier {
    if (level == FrostLevel.OFF) return this
    val c = moyinColors()
    val tex = remember(level, c.isDark) { fiberBitmap(c.isDark, level) }
    // ⚠️ 以前写死 #12141A（纯黑主题的 s1）：换成藏蓝后，侧栏/底栏仍是灰黑，
    // 比页面底色更深更冷，看起来「导航栏没跟上主题」。必须用当前主题的 s1。
    val panel = base ?: c.s1
    return this
        .clip(shape)
        .background(panel.copy(alpha = alpha), shape)
        .drawWithCache {
            val brush = ShaderBrush(
                ImageShader(tex, TileMode.Repeated, TileMode.Repeated)
            )
            onDrawBehind { drawRect(brush) }
        }
        .border(
            width = 1.dp,
            brush = Brush.verticalGradient(
                listOf(
                    Color.White.copy(alpha = if (c.isDark) 0.10f else 0.55f),
                    Color.Transparent,
                    Color.Black.copy(alpha = if (c.isDark) 0.22f else 0.05f)
                )
            ),
            shape = shape
        )
}
