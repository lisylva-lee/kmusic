package com.kmusic.apkhd.ui.theme

import android.graphics.Color as AndroidColor
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat

/**
 * 让系统状态栏 / 导航栏跟随 **App 内**主题，而不是系统夜间模式。
 *
 * 之前只在 onCreate 调了一次 `enableEdgeToEdge()`，默认 `SystemBarStyle.auto`
 * 按系统夜间模式决定图标黑白 —— App 内切深色后，导航栏还是浅色图标，
 * 看起来就是「没跟随」。
 *
 * 规则：
 * - 图标明暗由 [dark] 决定（App 实际主题）
 * - 导航栏底色：Android 10（车机常见）用主题底色实心，避免透明栏压出黑线；
 *   Android 11+ 透明交给系统对比度兜底
 */
@Composable
fun SyncSystemBars(dark: Boolean, background: Color) {
    val ctx = LocalContext.current
    val activity = ctx as? ComponentActivity ?: return
    LaunchedEffect(dark, background) {
        val bg = background.toArgb()
        // 所有版本都用主题底色：三键导航条在 Android 15 会忽略 navigationBarColor，
        // 但它会读窗口背景 —— 两处都设，才能保证「导航栏 == 页面底色」。
        val navScrim = bg
        // Android 10+ 默认会给导航栏叠一层对比度遮罩 —— 在深色主题上就是
        // 「栏比页面更深」的另一半原因，关掉，让颜色严格等于主题底色。
        if (Build.VERSION.SDK_INT >= 29) {
            activity.window.isNavigationBarContrastEnforced = false
            activity.window.isStatusBarContrastEnforced = false
        }
        activity.enableEdgeToEdge(
            statusBarStyle = if (dark) {
                SystemBarStyle.dark(AndroidColor.TRANSPARENT)
            } else {
                SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
            },
            navigationBarStyle = if (dark) {
                SystemBarStyle.dark(navScrim)
            } else {
                SystemBarStyle.light(navScrim, navScrim)
            }
        )
        @Suppress("DEPRECATION")
        activity.window.navigationBarColor = bg
        activity.window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(bg))
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
}
