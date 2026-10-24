package com.kmusic.apkhd.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit

/**
 * 窗口尺寸档位 —— 按屏幕宽度（dp）划分。
 * 手机竖屏 < 600，折叠屏/小平板 600-840，大屏 > 840。
 */
enum class WindowSize { COMPACT, MEDIUM, EXPANDED }

data class WindowInfo(
    val size: WindowSize,
    /** 屏幕宽高（dp） */
    val widthDp: Int,
    val heightDp: Int,
    /**
     * 尺寸缩放系数：以 360dp 宽（主流手机）为基准 1.0。
     * 小屏 320dp → 0.89，大屏 412dp → 1.14。
     * 只缩放间距/图标/封面，字号另走字阶（见 scaleText）。
     */
    val scale: Float,
    /** 字阶：1=小屏，2=主流，3=大屏/平板 */
    val textStep: Int,
    /** 是否为宽屏（平板/折叠屏展开） */
    val isWide: Boolean
) {
    /** 间距/图标/组件尺寸缩放 */
    val d: Float get() = scale

    /** 内容水平边距 */
    val gutter: Dp get() = when (size) {
        WindowSize.COMPACT -> (16 * scale).dp
        WindowSize.MEDIUM -> (24 * scale).dp
        WindowSize.EXPANDED -> 32.dp
    }

    /** 首页推荐卡宽度 */
    val cardWidth: Dp get() = when (size) {
        WindowSize.COMPACT -> (126 * scale).dp
        WindowSize.MEDIUM -> (150 * scale).dp
        WindowSize.EXPANDED -> 176.dp
    }

    /** 列表封面尺寸 */
    val listArt: Dp get() = when (size) {
        WindowSize.COMPACT -> (48 * scale).dp
        WindowSize.MEDIUM -> (56 * scale).dp
        WindowSize.EXPANDED -> 64.dp
    }

    /** 播放页封面最大边长（屏宽 - 2*gutter） */
    fun playerArtMax(widthDp: Int): Dp = (widthDp - gutter.value.toInt() * 2).coerceAtMost(420).dp

    /** Tab 栏高度 */
    val tabBarHeight: Dp get() = when (size) {
        WindowSize.COMPACT -> (64 * scale).dp
        else -> 72.dp
    }

    /** 迷你播放条高度 */
    val miniPlayerHeight: Dp get() = when (size) {
        WindowSize.COMPACT -> (56 * scale).dp
        else -> 64.dp
    }
}

val LocalWindowInfo: ProvidableCompositionLocal<WindowInfo> = staticCompositionLocalOf {
    WindowInfo(WindowSize.COMPACT, 360, 800, 1f, 2, false)
}

/**
 * 宽屏（平板 / 折叠屏展开）侧边导航栏宽度。
 * MainActivity 用它给内容区让位，MoyinSideRail 用它定宽 —— 必须同源，否则会错位。
 */
val SideRailWidth: Dp = 92.dp

/** 便捷访问 */
val window: WindowInfo
    @Composable get() = LocalWindowInfo.current

/**
 * 尺寸缩放：间距、图标、组件尺寸统一走这里。
 * 例：16.wdp → 360dp 屏 = 16dp，320dp 屏 = 14dp，412dp 屏 = 18dp
 */
val Int.wdp: Dp
    @Composable get() = (this * window.scale).dp

/**
 * 字阶缩放：三档微调，**不是等比放大**。
 * 设计稿里的值是 212px 容器内的渲染值，直接拿来用会过小；
 * 但按 212→390 的比例放大又会过大。这里以真机标准字阶为准。
 * 基准档 = 代码里写的值本身（已是 360dp 真机标准值）。
 * 小屏 -6%，大屏 +8%，保证可读性同时不变形。
 */
val Int.wsp: TextUnit
    @Composable get() = when (window.textStep) {
        1 -> (this * 0.94f).sp
        2 -> this.sp
        4 -> (this * 1.25f).sp   // 车机：观看距离 60-80cm，字号放大
        else -> (this * 1.08f).sp
    }

/** 字距缩放：允许小数，同 wsp 三档 */
val Double.wspSp: TextUnit
    @Composable get() = when (window.textStep) {
        1 -> this.sp
        2 -> (this * 1.1).sp
        else -> (this * 1.2).sp
    }

/** 测量当前窗口并注入 */
@Composable
fun ProvideWindowInfo(content: @Composable () -> Unit) {
    val cfg = LocalConfiguration.current
    val widthDp = cfg.screenWidthDp
    val heightDp = cfg.screenHeightDp
    val size = when {
        widthDp < 600 -> WindowSize.COMPACT
        widthDp < 840 -> WindowSize.MEDIUM
        else -> WindowSize.EXPANDED
    }
    // 以 380dp（主流手机实际渲染宽度）为基准。
    // 只做 ±10% 微调：小屏略缩防溢出，大屏略放防空旷。
    // 注意：绝不做等比放大，否则大屏上所有元素都会异常巨大。
    // 车机/超大 dp 屏（如比亚迪海鸥 1280x720 @160dpi = 1280x720dp）：
    // 这类屏 dp 稠密但观看距离远，字号要放大、间距略放大
    val carMode = widthDp >= 1200 && heightDp <= 760
    val scale = when {
        carMode -> 1.15f
        else -> (widthDp / 380f).coerceIn(0.90f, 1.10f)
    }
    val textStep = when {
        carMode -> 4
        widthDp < 350 -> 1
        widthDp > 420 -> 3
        else -> 2
    }
    val info = WindowInfo(size, widthDp, heightDp, scale, textStep, widthDp >= 600)
    CompositionLocalProvider(LocalWindowInfo provides info, content = content)
}
