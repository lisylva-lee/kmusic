package com.kmusic.apkhd.ui

import kotlin.math.roundToInt

/**
 * 歌词排版参数 —— VM（调档）和屏幕（渲染）共用同一份，避免两边各写一个常量后飘掉。
 */

const val LYRIC_SCALE_MIN = 0.8f
const val LYRIC_SCALE_MAX = 1.8f
const val LYRIC_SCALE_STEP = 0.1f

/** 正在唱的那行 */
private const val LYRIC_CURRENT_SP = 15f

/** 其余行（相邻行只改颜色，不改字号） */
private const val LYRIC_OTHER_SP = 12f

fun coerceLyricScale(v: Float): Float = v.coerceIn(LYRIC_SCALE_MIN, LYRIC_SCALE_MAX)

/**
 * 歌词每行的字号（sp）。
 *
 * ⚠️ 倍率必须乘在**整个 if/else 外面**。
 *
 * 写成 `if (on) 13f else 10f * scale` 时，Kotlin 把倍率只并进了 else 分支，
 * 于是当前行恒为 13sp、其余行 = 10 × 倍率：调到 1.3 倍时两边一样大，
 * 调到 1.8 倍时**其余行反而比正在唱的那行更大**。用户的原话是
 * 「不是到哪一句哪一句变大吗？怎么还缩小」—— 就是这个括号位置造成的。
 */
fun lyricLineSp(on: Boolean, scale: Float): Int =
    ((if (on) LYRIC_CURRENT_SP else LYRIC_OTHER_SP) * scale).roundToInt()
