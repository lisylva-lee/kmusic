package com.kmusic.apkhd.ui.theme

import android.os.Build
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 底部安全区。
 *
 * 为什么需要：比亚迪 DiLink 那类车机把「返回/主页」画成自己的悬浮条，
 * 不上报系统 inset —— navigationBarsPadding() 拿到的是 0，页面底部就被整排盖住。
 * 这里给一个手动值兜底：取 max(系统导航栏, 手动值)，不会双倍留白。
 */
val LocalSafeBottom = staticCompositionLocalOf { 0.dp }

/** 车机自动判定：比亚迪 DiLink（Android 10，density 160）默认留 56dp */
fun autoSafeBottomDp(): Int {
    val brand = Build.BRAND.orEmpty().uppercase()
    val maker = Build.MANUFACTURER.orEmpty().uppercase()
    val model = Build.MODEL.orEmpty().uppercase()
    val byd = brand.contains("BYD") || maker.contains("BYD") || model.contains("DILINK")
    return if (byd) 56 else 0
}

/** -1 = 自动；>=0 用用户设定 */
fun effectiveSafeBottom(setting: Int): Int = if (setting >= 0) setting else autoSafeBottomDp()

/**
 * 底部避让：系统导航栏照旧让，手动安全区更大时再补差值。
 * 用差值而不是相加，手势条 + 手动值同时存在时不会多出一截空白。
 */
@Composable
fun Modifier.safeBottomPadding(): Modifier {
    val nav: Dp = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val manual = LocalSafeBottom.current
    val extra = (manual - nav).coerceAtLeast(0.dp)
    return this.navigationBarsPadding().padding(bottom = extra)
}

/**
 * 底部弹层该让开多少。
 *
 * 为什么弹层不能直接用 [safeBottomPadding]：
 * 弹层有的在「已经避让过系统栏」的容器里（媒体库/搜索里的长按菜单），
 * 有的在容器外（播放页是覆盖层，自己管 inset）。前者里 navigationBars 已经被消费成 0，
 * 再加一次 safeBottomPadding 的手动差值就会算两遍。
 *
 * 取 max(系统导航栏, 手动安全区) 当**纯内边距数**，两种情况都对：
 * - 容器里：nav 已被消费成 0 → 只剩手动值（手机上 0，车机上 56）
 * - 容器外：nav 还是真实值 → 取两者较大的那个
 *
 * 车机上系统不上报 inset（见文件头），nav=0，靠手动值 56 兜住 —— 这正是
 * 「播放模式」只露三项、「随机播放」被底栏压住的原因：弹层从来没读过这个值。
 */
@Composable
fun sheetBottomInset(): Dp {
    val nav: Dp = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val manual = LocalSafeBottom.current
    return maxOf(nav, manual)
}

/**
 * 弹层面板的底部让位：**加在背景之后**，面板底色照样铺到屏幕最底边，
 * 只有里面的内容上移。加在背景之前会在面板下方露出一条隔着遮罩的缝，
 * 看起来像面板没贴到底。
 *
 * [bottomPanel] = false 时什么都不做：宽屏右侧抽屉是全高的，它不需要给底栏让位，
 * 加了反而在抽屉底部拉出一截空白。
 */
@Composable
fun Modifier.sheetBottomPadding(bottomPanel: Boolean = true): Modifier =
    if (bottomPanel) this.padding(bottom = sheetBottomInset()) else this
