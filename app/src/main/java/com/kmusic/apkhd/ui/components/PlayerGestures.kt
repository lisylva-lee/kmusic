package com.kmusic.apkhd.ui.components

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * 播放页手势（B2）。
 *
 * **只挂在封面/歌词那块 Box 上，绝不挂整页**：整页有进度条横拖、歌词竖滚、
 * 面板内滚动，挂整页就是主动制造「拖歌词被抢」的同类事故（v2.3.x 已经踩过一次）。
 *
 * 规则：
 *  - 起手后先用 touchSlop 判方向：横向为主 → 切封面/歌词；纵向为主 → 下滑收起播放页；
 *  - 位移阈值 60dp，避免「手指微抖就翻页」；
 *  - 判定为纵向时**不消费事件**，交给内部的歌词滚动。
 */
fun Modifier.playerSwipeGestures(
    onSwipeToLyrics: (Boolean) -> Unit,
    onSwipeDown: () -> Unit,
    /**
     * 宽屏布局里封面和歌词是并排的，没有「横滑切过去」这个对象。
     * 关掉横向判定后，横滑会直接落回下层 clickable（宽屏=打开音质面板），
     * 而不是被这里吞掉再什么也不做 —— 吞掉的那种最糟：用户滑了没反应也没提示。
     */
    horizontalEnabled: Boolean = true
): Modifier = this.then(
    Modifier.pointerInput(Unit) {
        val threshold = 60.dp.toPx()
        val slop = viewConfiguration.touchSlop
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var dx = 0f
            var dy = 0f
            // 方向判定阶段：只观察，不消费
            while (abs(dx) <= slop && abs(dy) <= slop) {
                val ev = awaitPointerEvent()
                val ch = ev.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                if (!ch.pressed) return@awaitEachGesture   // 只是点了一下，交给 clickable
                dx = ch.position.x - down.position.x
                dy = ch.position.y - down.position.y
            }
            if (!horizontalEnabled && abs(dx) > abs(dy)) return@awaitEachGesture
            val horizontal = abs(dx) > abs(dy)
            if (!horizontal && dy < 0) return@awaitEachGesture   // 上滑：不处理（留给歌词滚动）
            var totalX = dx
            var totalY = dy
            while (true) {
                val ev = awaitPointerEvent()
                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                if (!ch.pressed) break
                totalX = ch.position.x - down.position.x
                totalY = ch.position.y - down.position.y
                if (horizontal) {
                    if (abs(totalX) > threshold) {
                        ch.consume()
                        onSwipeToLyrics(totalX < 0)   // 左滑 → 歌词，右滑 → 封面
                        return@awaitEachGesture
                    }
                } else {
                    if (totalY > threshold) {
                        ch.consume()
                        onSwipeDown()
                        return@awaitEachGesture
                    }
                }
            }
        }
    }
)

/**
 * 播放页沉浸模式（B3）。
 *
 * 进入 [idleDelayMs] 后隐藏状态栏与导航栏；用户触摸（由 [interactionTick]
 * 递增传入）时立刻恢复，并**重新开始计时**。
 *
 * 为什么用「计数 + 重启计时」而不是回调恢复：隐藏后如果没有重新计时，
 * 用户点一下栏回来、下一帧又被藏掉 —— 表现是「怎么点都没有状态栏」。
 *
 * 车机（1280×720 常驻横屏）上状态栏本来就是噪声，收益比手机大。
 * 离开播放页必须显式 SHOW 回去（DisposableEffect），否则回列表页就没状态栏。
 */
@Composable
fun PlayerImmersiveMode(
    enabled: Boolean,
    interactionTick: Int = 0,
    idleDelayMs: Long = 3000L
) {
    val context = LocalContext.current
    val activity = context as? Activity ?: return

    fun controller(): WindowInsetsControllerCompat =
        WindowCompat.getInsetsController(activity.window, activity.window.decorView)

    LaunchedEffect(enabled, interactionTick) {
        if (!enabled) {
            runCatching { controller().show(WindowInsetsCompat.Type.systemBars()) }
            return@LaunchedEffect
        }
        // 有交互 → 先还回来
        if (interactionTick > 0) {
            runCatching { controller().show(WindowInsetsCompat.Type.systemBars()) }
        }
        delay(idleDelayMs)
        runCatching {
            controller().apply {
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // 组件离场（返回列表 / 打开别的页）必须还原
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            runCatching { controller().show(WindowInsetsCompat.Type.systemBars()) }
        }
    }
}

/**
 * 触摸即恢复系统栏。
 *
 * 单独一个 Modifier 是为了挂在根节点最外层：用 Initial pass 观察，不消费事件 ——
 * 恢复系统栏的同时，这一下点击该落到哪个按钮还是落到哪个按钮。
 *
 * ⚠️ 必须做**节流**：Initial pass 每帧都会收到 MOVE 事件，
 * 不加判断就是每次滑动几十次 show(…) 调用。
 */
fun Modifier.restoreBarsOnTouch(onTouch: () -> Unit): Modifier = this.then(
    Modifier.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val ev = awaitPointerEvent(
                    androidx.compose.ui.input.pointer.PointerEventPass.Initial
                )
                // 只在「按下」这一刻触发，滑动/抬起不重复调用
                if (ev.changes.any { it.pressed && it.previousPressed.not() }) {
                    onTouch()
                }
            }
        }
    }
)

/** 供手势模块内部使用：判定一次事件是否「几乎没动」 */
internal fun isTap(start: Offset, end: Offset, slop: Float): Boolean {
    val dx = end.x - start.x
    val dy = end.y - start.y
    return dx * dx + dy * dy <= slop * slop
}
